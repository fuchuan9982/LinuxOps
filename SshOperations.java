package com.linuxops.ssh;

import com.linuxops.model.Host;
import com.linuxops.security.CredentialSnapshot;
import com.linuxops.task.TaskManager;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** SSH command and SFTP operations sharing the bounded batch executor. */
public final class SshOperations {
    public record CommandResult(String hostId, String hostName, String stdout, String stderr, Integer exitCode) { }
    private final TaskManager tasks;

    public SshOperations(TaskManager tasks) { this.tasks = tasks; }

    public TaskManager.BatchHandle execute(List<Host> hosts, char[] password, String command,
                                           long timeoutSeconds, TaskManager.Listener listener,
                                           Consumer<CommandResult> results) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("命令不能为空");
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) throw new IllegalArgumentException("超时时间须为 1..3600 秒");
        return tasks.submitBatch(hosts, password, (host, credential, token) -> {
            token.throwIfCancelled();
            CommandResult result = run(host, credential, command, timeoutSeconds, token);
            results.accept(result);
            return result.exitCode() == null ? 1 : result.exitCode();
        }, listener);
    }

    public TaskManager.BatchHandle installPackages(List<Host> hosts, char[] password, String packages,
                                                    TaskManager.Listener listener, Consumer<CommandResult> results) {
        if (packages == null || !packages.matches("[A-Za-z0-9][A-Za-z0-9+_.:-]*(\\s+[A-Za-z0-9][A-Za-z0-9+_.:-]*)*"))
            throw new IllegalArgumentException("软件包名称格式不正确");
        String cmd = "if command -v apt-get >/dev/null 2>&1; then sudo -n apt-get update && sudo -n apt-get install -y -- " + packages
                + "; elif command -v dnf >/dev/null 2>&1; then sudo -n dnf install -y -- " + packages
                + "; elif command -v yum >/dev/null 2>&1; then sudo -n yum install -y -- " + packages
                + "; else echo 'unsupported package manager' >&2; exit 127; fi";
        return execute(hosts, password, cmd, 1800, listener, results);
    }

    public TaskManager.BatchHandle manageSystemd(List<Host> hosts, char[] password, String service,
                                                  String action, TaskManager.Listener listener,
                                                  Consumer<CommandResult> results) {
        if (service == null || !service.matches("[A-Za-z0-9_.@:-]+")) throw new IllegalArgumentException("服务名称格式不正确");
        if (!List.of("status", "start", "stop", "restart").contains(action)) throw new IllegalArgumentException("不支持的服务操作");
        return execute(hosts, password, "sudo -n systemctl " + action + " " + service + " --no-pager", 120, listener, results);
    }

    public TaskManager.BatchHandle reboot(List<Host> hosts, char[] password, TaskManager.Listener listener,
                                          Consumer<CommandResult> results) {
        return execute(hosts, password, "sudo -n systemctl reboot", 30, listener, results);
    }

    public TaskManager.BatchHandle upload(List<Host> hosts, char[] password, Path local, String remote,
                                          TaskManager.Listener listener, Consumer<String> messages) {
        if (local == null || !Files.isRegularFile(local) || !Files.isReadable(local)) throw new IllegalArgumentException("本地文件不存在或不可读");
        validateRemotePath(remote);
        return tasks.submitBatch(hosts, password, (host, credential, token) -> {
            token.throwIfCancelled();
            withClient(host, credential, ssh -> {
                try (SFTPClient sftp = ssh.newSFTPClient()) { sftp.put(local.toString(), remote); }
            });
            messages.accept("上传成功 " + host.id() + " -> " + remote);
            return 0;
        }, listener);
    }

    public TaskManager.BatchHandle download(List<Host> hosts, char[] password, String remote, Path localBase,
                                            TaskManager.Listener listener, Consumer<String> messages) {
        validateRemotePath(remote);
        if (localBase == null) throw new IllegalArgumentException("本地目标路径不能为空");
        return tasks.submitBatch(hosts, password, (host, credential, token) -> {
            token.throwIfCancelled();
            Path destination = hosts.size() == 1 ? localBase : localBase.resolveSibling(
                    stripExtension(localBase.getFileName().toString()) + "_" + safeFilePart(host.host()) + extension(localBase.getFileName().toString()));
            withClient(host, credential, ssh -> {
                try (SFTPClient sftp = ssh.newSFTPClient()) { sftp.get(remote, destination.toString()); }
            });
            messages.accept("下载成功 " + host.id() + " -> " + destination);
            return 0;
        }, listener);
    }

    private CommandResult run(Host host, CredentialSnapshot credential, String command, long timeout,
                              TaskManager.CancellationToken token) throws Exception {
        final String[] stdout = {""}, stderr = {""}; final Integer[] exit = {null};
        withClient(host, credential, ssh -> {
            token.throwIfCancelled();
            try (Session session = ssh.startSession()) {
                Session.Command c = session.exec(command);
                // Drain both channels concurrently: sequential readAllBytes can deadlock
                // when the remote process fills the other SSH channel's window.
                CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readUtf8(c.getInputStream()));
                CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readUtf8(c.getErrorStream()));
                c.join(timeout, TimeUnit.SECONDS);
                try {
                    stdout[0] = out.get(5, TimeUnit.SECONDS);
                    stderr[0] = err.get(5, TimeUnit.SECONDS);
                } catch (Exception readFailure) {
                    out.cancel(true); err.cancel(true);
                    throw new java.io.IOException("读取远程命令输出失败或超时", readFailure);
                }
                exit[0] = c.getExitStatus();
            }
        });
        return new CommandResult(host.id(), host.name(), stdout[0], stderr[0], exit[0]);
    }

    private static String readUtf8(java.io.InputStream input) {
        try (input) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        catch (java.io.IOException ex) { throw new java.util.concurrent.CompletionException(ex); }
    }

    @FunctionalInterface private interface SshAction { void accept(SSHClient ssh) throws Exception; }
    private static void withClient(Host host, CredentialSnapshot credential, SshAction action) throws Exception {
        char[] pwd = credential.copyPassword();
        try (SSHClient ssh = new SSHClient()) {
            ssh.loadKnownHosts();
            ssh.setConnectTimeout(10_000); ssh.setTimeout(30_000);
            ssh.connect(host.host(), host.port());
            if (pwd.length > 0) ssh.authPassword(host.user(), new String(pwd)); else ssh.authPublickey(host.user());
            action.accept(ssh);
        } finally { Arrays.fill(pwd, '\0'); }
    }

    private static void validateRemotePath(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0)
            throw new IllegalArgumentException("远程路径无效");
    }
    private static String safeFilePart(String s) { return s.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String extension(String s) { int i=s.lastIndexOf('.'); return i < 0 ? "" : s.substring(i); }
    private static String stripExtension(String s) { int i=s.lastIndexOf('.'); return i < 0 ? s : s.substring(0,i); }
}
