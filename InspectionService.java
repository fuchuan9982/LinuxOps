package com.linuxops.inspection;

import com.linuxops.model.Host;
import com.linuxops.security.CredentialSnapshot;
import com.linuxops.task.TaskManager;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Connects the structured inspection engine to SSHJ and the bounded batch executor. */
public final class InspectionService {
    private final TaskManager tasks;

    public InspectionService(TaskManager tasks) {
        this.tasks = tasks;
    }

    public TaskManager.BatchHandle inspect(List<Host> hosts, char[] password,
                                           TaskManager.Listener listener,
                                           java.util.function.Consumer<InspectionReport> reportConsumer) {
        return tasks.submitBatch(hosts, password, (host, credential, token) -> {
            token.throwIfCancelled();
            String stdout = "";
            String error = null;
            Integer exit = null;
            char[] pwd = credential.copyPassword();
            try (SSHClient ssh = new SSHClient()) {
                ssh.loadKnownHosts();
                ssh.setConnectTimeout(10_000);
                ssh.setTimeout(30_000);
                ssh.connect(host.host(), host.port());
                if (pwd.length > 0) ssh.authPassword(host.user(), new String(pwd));
                else ssh.authPublickey(host.user());
                token.throwIfCancelled();
                try (Session session = ssh.startSession()) {
                    Session.Command command = session.exec(InspectionCommand.build());
                    command.join(60, java.util.concurrent.TimeUnit.SECONDS);
                    stdout = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    String stderr = new String(command.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    exit = command.getExitStatus();
                    if (exit != null && exit != 0) error = "远端巡检命令退出码：" + exit + (stderr.isBlank() ? "" : "；" + stderr.trim());
                }
            } catch (Exception ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            } finally {
                java.util.Arrays.fill(pwd, '\0');
            }
            InspectionReport report = InspectionParser.parse(host.id(), host.name(), stdout, error);
            reportConsumer.accept(report);
            return error == null ? 0 : 1;
        }, listener);
    }
}
