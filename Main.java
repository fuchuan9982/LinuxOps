package com.linuxops;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

public class Main {
    private static final Color BG = new Color(18, 24, 33), PANEL = new Color(27, 36, 48),
            FIELD = new Color(13, 19, 27), TEXT = new Color(225, 233, 242),
            MUTED = new Color(145, 160, 178), ACCENT = new Color(35, 145, 220);
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"选择", "分组", "名称", "主机/IP", "端口", "用户名", "认证"}, 0) {
        public boolean isCellEditable(int r, int c) { return c == 0; }
        public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
    };
    private final JTable table = new JTable(model);
    private final JTextArea output = new JTextArea();
    private final JTextField command = new JTextField("uptime && free -h && df -h");
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(8, 30, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000), new ThreadPoolExecutor.CallerRunsPolicy());
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private ScheduledFuture<?> monitorTask;
    private final List<Host> hosts = new ArrayList<>();
    private final JLabel status = new JLabel("就绪 | 支持批量 SSH / SFTP");
    private final JPasswordField password = new JPasswordField(14);

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().show());
    }

    private void show() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        JFrame frame = new JFrame("LinuxOps · 批量 SSH 运维平台");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1080, 720));
        frame.setSize(1280, 820);
        frame.setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(BG); root.setBorder(new EmptyBorder(16, 16, 12, 16));
        root.add(header(), BorderLayout.NORTH);
        root.add(center(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setVisible(true);
        append("LinuxOps 已启动。请先导入服务器 CSV，或手动添加服务器。");
    }

    private JPanel header() {
        JPanel p = new JPanel(new BorderLayout()); p.setOpaque(false);
        JPanel title = new JPanel(new GridLayout(2,1)); title.setOpaque(false);
        JLabel h = new JLabel("LINUXOPS  /  批量服务器运维"); h.setForeground(TEXT); h.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 23));
        JLabel sub = new JLabel("集中连接 · 批量执行 · 文件传输 · 巡检"); sub.setForeground(MUTED);
        title.add(h); title.add(sub); p.add(title, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4)); actions.setOpaque(false);
        actions.add(button("导入 CSV", this::importCsv)); actions.add(button("添加服务器", this::addHost));
        actions.add(button("删除选中", this::deleteSelected)); p.add(actions, BorderLayout.EAST);
        return p;
    }

    private JSplitPane center() {
        JPanel left = panel("服务器列表", new BorderLayout(8,8));
        table.setRowHeight(30); table.setBackground(PANEL); table.setForeground(TEXT);
        table.setGridColor(new Color(48,60,74)); table.setSelectionBackground(new Color(35,75,105));
        table.getTableHeader().setBackground(new Color(35,48,63)); table.getTableHeader().setForeground(TEXT);
        left.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel credential = new JPanel(new FlowLayout(FlowLayout.LEFT)); credential.setOpaque(false);
        credential.add(label("SSH 密码（本次操作使用）")); styleField(password); credential.add(password);
        left.add(credential, BorderLayout.SOUTH);

        JPanel right = panel("命令执行 / 操作输出", new BorderLayout(8,8));
        JPanel cmd = new JPanel(new BorderLayout(8,0)); cmd.setOpaque(false);
        styleField(command); cmd.add(command, BorderLayout.CENTER);
        cmd.add(button("对选中服务器执行", this::runCommand), BorderLayout.EAST);
        right.add(cmd, BorderLayout.NORTH);
        output.setEditable(false); output.setBackground(FIELD); output.setForeground(new Color(195,218,232));
        output.setCaretColor(TEXT); output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        output.setBorder(new EmptyBorder(10,10,10,10));
        right.add(new JScrollPane(output), BorderLayout.CENTER);
        JPanel ops = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); ops.setOpaque(false);
        ops.add(button("批量巡检", this::inspect)); ops.add(button("实时监控：启动/停止", this::toggleMonitor));
        ops.add(button("导出巡检报告", this::exportReport)); ops.add(button("APT/DNF 安装", this::installPackages));
        ops.add(button("服务管理", this::manageService)); ops.add(button("上传文件", this::upload));
        ops.add(button("下载文件", this::download)); ops.add(button("批量重启", this::reboot));
        right.add(ops, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(.43); split.setBorder(null); split.setOpaque(false);
        return split;
    }

    private JPanel footer() {
        JPanel p = new JPanel(new BorderLayout()); p.setOpaque(false);
        status.setForeground(MUTED); p.add(status, BorderLayout.WEST);
        JLabel note = new JLabel("安全提示：首次连接请核验服务器主机密钥；生产环境建议使用 SSH 密钥认证。");
        note.setForeground(MUTED); p.add(note, BorderLayout.EAST); return p;
    }

    private JPanel panel(String title, LayoutManager layout) {
        JPanel p = new JPanel(layout); p.setBackground(PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(48,60,74)),
                BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(8,8,8,8), title,
                        0, 0, new Font("Microsoft YaHei UI", Font.BOLD, 14), TEXT)));
        return p;
    }
    private JLabel label(String s) { JLabel l = new JLabel(s); l.setForeground(MUTED); return l; }
    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text); b.setFocusPainted(false); b.setForeground(Color.WHITE);
        b.setBackground(ACCENT); b.setBorder(new EmptyBorder(8,14,8,14));
        b.addActionListener(e -> action.run()); return b;
    }
    private void styleField(JTextField f) { f.setBackground(FIELD); f.setForeground(TEXT); f.setCaretColor(TEXT); f.setBorder(new EmptyBorder(8,8,8,8)); }

    private void addHost() {
        JTextField group = new JTextField("默认", 10), name = new JTextField(12), host = new JTextField(16),
                port = new JTextField("22",5), user = new JTextField("root",10);
        JPanel form = new JPanel(new GridLayout(0,2,6,6));
        for (JComponent c : new JComponent[]{new JLabel("分组"),group,new JLabel("名称"),name,new JLabel("主机/IP"),host,
                new JLabel("端口"),port,new JLabel("用户名"),user}) form.add(c);
        if (JOptionPane.showConfirmDialog(null, form, "添加服务器", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            Host h = new Host(group.getText(), name.getText(), host.getText(), Integer.parseInt(port.getText()), user.getText());
            hosts.add(h); model.addRow(new Object[]{true,h.group,h.name,h.host,h.port,h.user,"密码/密钥"});
        } catch (Exception ex) { error("端口格式不正确"); }
    }

    private void importCsv() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
        try (BufferedReader br = Files.newBufferedReader(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8)) {
            String line; int count=0;
            while ((line=br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#") || line.toLowerCase().startsWith("group,")) continue;
                String[] a=line.split(",",-1); if (a.length < 5) continue;
                Host h=new Host(a[0].trim(),a[1].trim(),a[2].trim(),Integer.parseInt(a[3].trim()),a[4].trim());
                hosts.add(h); model.addRow(new Object[]{true,h.group,h.name,h.host,h.port,h.user,"密码/密钥"}); count++;
            }
            append("CSV 导入完成：" + count + " 台服务器");
        } catch(Exception ex) { error("导入失败：" + ex.getMessage()); }
    }

    private void deleteSelected() {
        for(int i=model.getRowCount()-1;i>=0;i--) if(Boolean.TRUE.equals(model.getValueAt(i,0))) {
            String host=(String)model.getValueAt(i,3);
            hosts.removeIf(h->h.host.equals(host)); model.removeRow(i);
        }
    }

    private List<Host> selected() {
        List<Host> list=new ArrayList<>();
        for(int i=0;i<model.getRowCount();i++) if(Boolean.TRUE.equals(model.getValueAt(i,0))) {
            String host=(String)model.getValueAt(i,3);
            hosts.stream().filter(h->h.host.equals(host)).findFirst().ifPresent(list::add);
        }
        return list;
    }

    private SSHClient connect(Host h, char[] pwd) throws Exception {
        SSHClient ssh=new SSHClient();
        // 使用用户本机 ~/.ssh/known_hosts 校验主机密钥；未知主机将拒绝连接。
        ssh.loadKnownHosts();
        ssh.setConnectTimeout(10000);
        ssh.setTimeout(30000);
        ssh.connect(h.host,h.port);
        if(pwd.length>0) ssh.authPassword(h.user,new String(pwd));
        else ssh.authPublickey(h.user);
        return ssh;
    }

    private void runCommand() {
        List<Host> targets=selected(); if(targets.isEmpty()){error("请先勾选服务器");return;}
        String cmd=command.getText().trim(); if(cmd.isEmpty()) return;
        char[] pwd=password.getPassword(); setStatus("正在执行：" + targets.size() + " 台服务器");
        for(Host h:targets) pool.submit(()->{
            try(SSHClient ssh=connect(h,pwd); Session session=ssh.startSession()) {
                Session.Command c=session.exec(cmd);
                c.join(60,TimeUnit.SECONDS);
                String result=new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                String err=new String(c.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);
                append("\n[" + h.name + " / " + h.host + "]\n" + result + (err.isBlank()?"":"\n[stderr]\n"+err) + "\n退出码：" + c.getExitStatus());
                audit("COMMAND", h, "exit=" + c.getExitStatus() + "; command=" + cmd);
            } catch(Exception ex){append("\n["+h.host+"] 失败："+ex.getMessage());}
        });
    }

    private void inspect() {
        command.setText("echo '=== HOST ==='; hostname; echo '=== OS ==='; cat /etc/os-release | head -5; echo '=== UPTIME ==='; uptime; echo '=== CPU ==='; nproc; echo '=== MEMORY ==='; free -h; echo '=== DISK ==='; df -hT; echo '=== LOAD ==='; cat /proc/loadavg; echo '=== FAILED SERVICES ==='; systemctl --failed --no-pager; echo '=== PACKAGE MANAGER ==='; (command -v apt-get || command -v dnf || command -v yum || command -v zypper || true); echo '=== FIREWALL ==='; (command -v ufw >/dev/null && ufw status || command -v firewall-cmd >/dev/null && firewall-cmd --state || true)");
        runCommand();
    }

    private void toggleMonitor() {
        if (monitoring.compareAndSet(false, true)) {
            List<Host> targets = selected();
            if (targets.isEmpty()) { monitoring.set(false); error("请先勾选服务器"); return; }
            monitorTask = monitor.scheduleAtFixedRate(() -> {
                for (Host h : targets) pool.submit(() -> {
                    char[] pwd = password.getPassword();
                    try (SSHClient ssh = connect(h, pwd); Session session = ssh.startSession()) {
                        Session.Command c = session.exec("printf 'HOST='; hostname; printf ' LOAD='; cut -d' ' -f1-3 /proc/loadavg; printf ' MEM='; free -m | awk '/Mem:/ {print $3 "/" $2}'; printf ' ROOTFS='; df -P / | awk 'NR==2 {print $5}'");
                        c.join(20, TimeUnit.SECONDS);
                        String result = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                        append("[监控 " + h.host + "] " + result);
                    } catch (Exception ex) { append("[监控异常 " + h.host + "] " + ex.getMessage()); }
                    finally { Arrays.fill(pwd, '\0'); }
                });
            }, 0, 30, TimeUnit.SECONDS);
            setStatus("实时监控运行中 | 每 30 秒采集一次");
        } else {
            monitoring.set(false);
            if (monitorTask != null) monitorTask.cancel(false);
            setStatus("实时监控已停止");
        }
    }

    private void exportReport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("LinuxOps巡检报告_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".html"));
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;
        String content = output.getText();
        String escaped = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String html = "<!doctype html><html lang='zh-CN'><meta charset='UTF-8'><title>LinuxOps 巡检报告</title>" +
                "<style>body{font:14px Arial;background:#101820;color:#e5edf5;padding:28px}h1{color:#54b8ff}pre{white-space:pre-wrap;background:#1b2836;padding:18px;border-radius:8px;line-height:1.5}</style>" +
                "<h1>LinuxOps 巡检 / 运维记录</h1><p>生成时间：" + LocalDateTime.now() + "</p><pre>" + escaped + "</pre></html>";
        try { Files.writeString(chooser.getSelectedFile().toPath(), html, StandardCharsets.UTF_8); append("报告已导出：" + chooser.getSelectedFile()); }
        catch (IOException ex) { error("报告导出失败：" + ex.getMessage()); }
    }

    private void installPackages() {
        List<Host> targets = selected();
        if (targets.isEmpty()) { error("请先勾选服务器"); return; }
        String packages = JOptionPane.showInputDialog(null, "输入软件包名称，多个包用空格分隔（不支持 shell 参数）", "安装软件包", JOptionPane.QUESTION_MESSAGE);
        if (packages == null || packages.isBlank()) return;
        if (!packages.matches("[A-Za-z0-9][A-Za-z0-9+_.:-]*(\\s+[A-Za-z0-9][A-Za-z0-9+_.:-]*)*")) {
            error("软件包名称格式不正确"); return;
        }
        if (JOptionPane.showConfirmDialog(null, "将在 " + targets.size() + " 台服务器上尝试使用 apt-get 或 dnf 安装：\n" + packages + "\n需要远端账户具备 sudo 权限。", "确认批量安装", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        String remote = "if command -v apt-get >/dev/null 2>&1; then sudo -n apt-get update && sudo -n apt-get install -y -- " + packages + "; elif command -v dnf >/dev/null 2>&1; then sudo -n dnf install -y -- " + packages + "; else echo '不支持的包管理器' >&2; exit 127; fi";
        executeForHosts(targets, remote, "PACKAGE_INSTALL");
    }

    private void manageService() {
        List<Host> targets = selected();
        if (targets.isEmpty()) { error("请先勾选服务器"); return; }
        String service = JOptionPane.showInputDialog(null, "systemd 服务名称（例如 ssh 或 nginx）", "服务管理", JOptionPane.QUESTION_MESSAGE);
        if (service == null || service.isBlank()) return;
        if (!service.matches("[A-Za-z0-9_.@:-]+")) { error("服务名称格式不正确"); return; }
        String[] actions = {"查看状态", "启动", "停止", "重启"};
        int choice = JOptionPane.showOptionDialog(null, "选择服务操作：" + service, "服务管理", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, actions, actions[0]);
        if (choice < 0) return;
        String verb = new String[]{"status", "start", "stop", "restart"}[choice];
        if (choice > 0 && JOptionPane.showConfirmDialog(null, "确认对 " + targets.size() + " 台服务器执行 systemctl " + verb + " " + service + "？", "确认服务变更", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        executeForHosts(targets, "sudo -n systemctl " + verb + " " + service + " --no-pager", "SERVICE_" + verb.toUpperCase(Locale.ROOT));
    }

    private void executeForHosts(List<Host> targets, String remoteCommand, String action) {
        char[] pwd = password.getPassword();
        setStatus(action + " 执行中 | " + targets.size() + " 台服务器");
        for (Host h : targets) {
            char[] taskPwd = pwd.clone();
            pool.submit(() -> {
            try (SSHClient ssh = connect(h, taskPwd); Session session = ssh.startSession()) {
                Session.Command c = session.exec(remoteCommand);
                c.join(120, TimeUnit.SECONDS);
                String out = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String err = new String(c.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                append("[" + action + " " + h.host + "]\n" + out + (err.isBlank() ? "" : "\n[stderr]\n" + err) + "\n退出码：" + c.getExitStatus());
                audit(action, h, "exit=" + c.getExitStatus());
            } catch (Exception ex) { append("[" + action + " " + h.host + "] 失败：" + ex.getMessage()); audit(action + "_FAILED", h, ex.toString()); }
            finally { Arrays.fill(taskPwd, '\0'); }
            });
        }
        Arrays.fill(pwd, '\0');
    }

    private void audit(String action, Host h, String detail) {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".linuxops");
            Files.createDirectories(dir);
            String line = LocalDateTime.now() + "\t" + action + "\t" + h.host + "\t" + detail.replace('\n', ' ') + System.lineSeparator();
            Files.writeString(dir.resolve("audit.log"), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) { append("[审计日志写入失败] " + ex.getMessage()); }
    }

    private void upload() {
        List<Host> targets=selected(); if(targets.isEmpty()){error("请先勾选服务器");return;}
        JFileChooser fc=new JFileChooser(); if(fc.showOpenDialog(null)!=JFileChooser.APPROVE_OPTION)return;
        String remote=JOptionPane.showInputDialog("远程目标路径", "/tmp/"+fc.getSelectedFile().getName()); if(remote==null)return;
        char[] pwd=password.getPassword(); Path local=fc.getSelectedFile().toPath();
        for(Host h:targets) pool.submit(()->{try(SSHClient ssh=connect(h,pwd);SFTPClient sftp=ssh.newSFTPClient()){
            sftp.put(local.toString(),remote); append("上传成功："+h.host+" -> "+remote);
        }catch(Exception e){append("上传失败："+h.host+"："+e.getMessage());}});
    }

    private void download() {
        List<Host> targets=selected(); if(targets.isEmpty()){error("请先勾选服务器");return;}
        String remote=JOptionPane.showInputDialog("远程文件路径"); if(remote==null||remote.isBlank())return;
        JFileChooser fc=new JFileChooser(); fc.setSelectedFile(new File(Path.of(remote).getFileName().toString()));
        if(fc.showSaveDialog(null)!=JFileChooser.APPROVE_OPTION)return;
        char[] pwd=password.getPassword(); Path local=fc.getSelectedFile().toPath();
        // 多台下载时自动追加主机名，避免覆盖同名文件。
        for(Host h:targets) pool.submit(()->{Path dest=targets.size()==1?local:local.resolveSibling(stripExt(local.getFileName().toString())+"_"+h.host+ext(local.getFileName().toString()));
            try(SSHClient ssh=connect(h,pwd);SFTPClient sftp=ssh.newSFTPClient()){sftp.get(remote,dest.toString());append("下载成功："+h.host+" -> "+dest);}
            catch(Exception e){append("下载失败："+h.host+"："+e.getMessage());}});
    }

    private void reboot() {
        if(JOptionPane.showConfirmDialog(null,"确定对所有已勾选服务器执行 reboot？请确认业务影响。","高风险操作确认",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        command.setText("sudo -n reboot");
        List<Host> targets = selected();
        audit("REBOOT_REQUESTED", new Host("-", "batch", "multiple", 22, "-"), "targets=" + targets.size());
        runCommand();
    }
    private String ext(String s){int i=s.lastIndexOf('.');return i<0?"":s.substring(i);}
    private String stripExt(String s){int i=s.lastIndexOf('.');return i<0?s:s.substring(0,i);}
    private void append(String s){SwingUtilities.invokeLater(()->{output.append(s+"\n");output.setCaretPosition(output.getDocument().getLength());});}
    private void setStatus(String s){SwingUtilities.invokeLater(()->status.setText(s));}
    private void error(String s){JOptionPane.showMessageDialog(null,s,"提示",JOptionPane.WARNING_MESSAGE);}
    private record Host(String group,String name,String host,int port,String user){}
}
