package com.linuxops;

import com.linuxops.inspection.InspectionReport;
import com.linuxops.inspection.InspectionService;
import com.linuxops.inspection.Severity;
import com.linuxops.model.Host;
import com.linuxops.report.ReportExporter;
import com.linuxops.ssh.SshOperations;
import com.linuxops.task.TaskManager;
import com.linuxops.task.TaskResult;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Swing entry point integrated with structured inspection and bounded batch execution. */
public final class Main {
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"选择", "分组", "名称", "主机/IP", "端口", "用户", "最近巡检"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
    };
    private final JTable table = new JTable(tableModel);
    private final JTextArea log = new JTextArea();
    private final JPasswordField password = new JPasswordField(16);
    private final JLabel status = new JLabel("就绪");
    private final List<Host> hosts = new ArrayList<>();
    private final Map<String, InspectionReport> reports = new ConcurrentHashMap<>();
    private final TaskManager taskManager = new TaskManager(12, 600, 50);
    private final InspectionService inspectionService = new InspectionService(taskManager);
    private final SshOperations sshOperations = new SshOperations(taskManager);
    private TaskManager.BatchHandle activeBatch;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().show());
    }

    private void show() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        JFrame frame = new JFrame("LinuxOps Professional · Linux 批量运维平台");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { taskManager.close(); frame.dispose(); }
        });
        frame.setMinimumSize(new Dimension(1050, 680)); frame.setSize(1240, 800); frame.setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(10, 10)); root.setBorder(new EmptyBorder(14,14,10,14));
        root.add(header(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, hostPanel(), operationPanel());
        split.setResizeWeight(.43); root.add(split, BorderLayout.CENTER);
        JPanel foot = new JPanel(new BorderLayout()); foot.add(status, BorderLayout.WEST);
        foot.add(new JLabel(" SSH known_hosts 校验开启 · 建议使用 SSH 密钥认证"), BorderLayout.EAST); root.add(foot, BorderLayout.SOUTH);
        frame.setContentPane(root); frame.setVisible(true); append("LinuxOps Professional 已启动。请导入或添加服务器。");
    }

    private JPanel header() {
        JPanel p = new JPanel(new BorderLayout());
        JLabel title = new JLabel("LINUXOPS PROFESSIONAL  /  批量 Linux 运维"); title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 22));
        p.add(title, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(new JButton(new AbstractAction("导入 CSV") { public void actionPerformed(java.awt.event.ActionEvent e) { importCsv(); }}));
        actions.add(new JButton(new AbstractAction("添加服务器") { public void actionPerformed(java.awt.event.ActionEvent e) { addHost(); }}));
        actions.add(new JButton(new AbstractAction("删除选中") { public void actionPerformed(java.awt.event.ActionEvent e) { deleteSelected(); }}));
        p.add(actions, BorderLayout.EAST); return p;
    }

    private JPanel hostPanel() {
        JPanel p = new JPanel(new BorderLayout(6,6)); p.setBorder(BorderFactory.createTitledBorder("服务器清单"));
        table.setRowHeight(27); p.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel cred = new JPanel(new FlowLayout(FlowLayout.LEFT)); cred.add(new JLabel("SSH 密码（留空使用密钥）")); cred.add(password); p.add(cred, BorderLayout.SOUTH); return p;
    }

    private JPanel operationPanel() {
        JPanel p = new JPanel(new BorderLayout(8,8)); p.setBorder(BorderFactory.createTitledBorder("任务中心 / 巡检结果"));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        actions.add(button("开始批量巡检", this::inspect));
        actions.add(button("执行 SSH 命令", this::runCommand));
        actions.add(button("批量安装软件", this::installPackages));
        actions.add(button("systemd 服务", this::manageService));
        actions.add(button("上传文件", this::uploadFile));
        actions.add(button("下载文件", this::downloadFile));
        actions.add(button("批量重启", this::rebootHosts));
        actions.add(button("取消当前批次", this::cancelBatch));
        actions.add(button("导出 HTML", () -> export(true))); actions.add(button("导出 CSV", () -> export(false)));
        p.add(new JScrollPane(actions), BorderLayout.NORTH);
        log.setEditable(false); log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); p.add(new JScrollPane(log), BorderLayout.CENTER);
        return p;
    }

    private JButton button(String label, Runnable action) { JButton b = new JButton(label); b.addActionListener(e -> action.run()); return b; }

    private void addHost() {
        JTextField group = new JTextField("默认"), name = new JTextField(), host = new JTextField(), port = new JTextField("22"), user = new JTextField("root");
        JPanel form = new JPanel(new GridLayout(0,2,6,6));
        for (JComponent c : new JComponent[]{new JLabel("分组"),group,new JLabel("名称"),name,new JLabel("主机/IP"),host,new JLabel("端口"),port,new JLabel("用户名"),user}) form.add(c);
        if (JOptionPane.showConfirmDialog(null, form, "添加服务器", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try { addHost(new Host(group.getText(),name.getText(),host.getText(),Integer.parseInt(port.getText()),user.getText())); }
        catch (Exception ex) { warn("服务器信息无效：" + ex.getMessage()); }
    }

    private void addHost(Host h) {
        if (hosts.stream().anyMatch(x -> x.id().equalsIgnoreCase(h.id()))) { warn("服务器已存在：" + h.id()); return; }
        hosts.add(h); tableModel.addRow(new Object[]{true,h.group(),h.name(),h.host(),String.valueOf(h.port()),h.user(),"未巡检"});
    }

    private void importCsv() {
        JFileChooser chooser = new JFileChooser(); if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8)) {
            String line; while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#") || line.toLowerCase(Locale.ROOT).startsWith("group,")) continue;
                String[] a = line.split(",", -1); if (a.length < 5) continue;
                int before = hosts.size(); addHost(new Host(a[0].trim(),a[1].trim(),a[2].trim(),Integer.parseInt(a[3].trim()),a[4].trim()));
                if (hosts.size() > before) count++;
            }
            append("CSV 导入完成：新增 " + count + " 台服务器（格式：group,name,host,port,user；不支持带引号逗号字段）");
        } catch (Exception ex) { warn("CSV 导入失败：" + ex.getMessage()); }
    }

    private void deleteSelected() {
        for (int i = tableModel.getRowCount()-1; i >= 0; i--) if (Boolean.TRUE.equals(tableModel.getValueAt(i,0))) {
            String id = identityAt(i); hosts.removeIf(h -> h.id().equalsIgnoreCase(id)); reports.remove(id); tableModel.removeRow(i);
        }
    }

    private String identityAt(int row) {
        String host = String.valueOf(tableModel.getValueAt(row,3));
        String port = String.valueOf(tableModel.getValueAt(row,4));
        String user = String.valueOf(tableModel.getValueAt(row,5));
        return host + ":" + port + "@" + user;
    }

    private List<Host> selectedHosts() {
        List<Host> selected = new ArrayList<>();
        for (int i=0; i<tableModel.getRowCount(); i++) if (Boolean.TRUE.equals(tableModel.getValueAt(i,0))) {
            String id = identityAt(i); hosts.stream().filter(h -> h.id().equalsIgnoreCase(id)).findFirst().ifPresent(selected::add);
        }
        return selected;
    }


    private TaskManager.Listener sshListener(String operation) {
        return new TaskManager.Listener() {
            @Override public void onUpdate(TaskResult r) {
                append("[" + operation + "/" + r.state() + "] " + r.hostId() + " — " + r.message());
            }
            @Override public void onBatchFinished(int total, int completed, int succeeded, int failed, int cancelled) {
                SwingUtilities.invokeLater(() -> status.setText(operation + "完成：成功 " + succeeded + " / 失败 " + failed + " / 取消 " + cancelled));
            }
        };
    }

    private void startSshOperation(String operation, java.util.function.Function<char[], TaskManager.BatchHandle> submit) {
        List<Host> targets = selectedHosts();
        if (targets.isEmpty()) { warn("请先勾选服务器"); return; }
        char[] pwd = password.getPassword();
        try {
            activeBatch = submit.apply(pwd);
            status.setText(operation + "已提交：" + targets.size() + " 台");
        } catch (Exception ex) { warn(operation + "启动失败：" + ex.getMessage()); }
        finally { Arrays.fill(pwd, '\0'); }
    }

    private void runCommand() {
        JTextArea command = new JTextArea(5, 48); command.setLineWrap(true);
        JTextField timeout = new JTextField("120", 6);
        JPanel form = new JPanel(new BorderLayout(6,6)); form.add(new JLabel("远程 Shell 命令（将以当前 SSH 用户执行）"), BorderLayout.NORTH);
        form.add(new JScrollPane(command), BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT)); south.add(new JLabel("超时秒数")); south.add(timeout); form.add(south, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(null, form, "批量 SSH 命令", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            long seconds = Long.parseLong(timeout.getText().trim());
            startSshOperation("SSH 命令", pwd -> sshOperations.execute(selectedHosts(), pwd, command.getText(), seconds,
                    sshListener("SSH"), result -> appendCommandResult(result)));
        } catch (Exception ex) { warn("参数无效：" + ex.getMessage()); }
    }

    private void appendCommandResult(SshOperations.CommandResult r) {
        append("----- " + r.hostName() + " (" + r.hostId() + ") exit=" + r.exitCode() + " -----");
        if (!r.stdout().isBlank()) append(r.stdout().stripTrailing());
        if (!r.stderr().isBlank()) append("[stderr] " + r.stderr().stripTrailing());
    }

    private void installPackages() {
        JTextField packages = new JTextField("vim curl wget", 32);
        if (JOptionPane.showConfirmDialog(null, packages, "软件包名称（空格分隔；apt/dnf/yum）", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        startSshOperation("软件安装", pwd -> sshOperations.installPackages(selectedHosts(), pwd, packages.getText(), sshListener("安装"), this::appendCommandResult));
    }

    private void manageService() {
        JTextField service = new JTextField("sshd", 18);
        JComboBox<String> action = new JComboBox<>(new String[]{"status", "start", "stop", "restart"});
        JPanel form = new JPanel(new GridLayout(0,2,6,6)); form.add(new JLabel("服务名称")); form.add(service); form.add(new JLabel("操作")); form.add(action);
        if (JOptionPane.showConfirmDialog(null, form, "systemd 服务管理", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        startSshOperation("systemd", pwd -> sshOperations.manageSystemd(selectedHosts(), pwd, service.getText(), String.valueOf(action.getSelectedItem()), sshListener("systemd"), this::appendCommandResult));
    }

    private void rebootHosts() {
        List<Host> targets = selectedHosts(); if (targets.isEmpty()) { warn("请先勾选服务器"); return; }
        if (JOptionPane.showConfirmDialog(null, "确定重启选中的 " + targets.size() + " 台服务器？业务连接可能中断。", "确认批量重启", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        startSshOperation("批量重启", pwd -> sshOperations.reboot(selectedHosts(), pwd, sshListener("重启"), this::appendCommandResult));
    }

    private void uploadFile() {
        JFileChooser chooser = new JFileChooser(); if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
        JTextField remote = new JTextField("/tmp/" + chooser.getSelectedFile().getName(), 32);
        if (JOptionPane.showConfirmDialog(null, remote, "远程目标路径", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        startSshOperation("SFTP 上传", pwd -> sshOperations.upload(selectedHosts(), pwd, chooser.getSelectedFile().toPath(), remote.getText(), sshListener("上传"), this::append));
    }

    private void downloadFile() {
        JTextField remote = new JTextField("/var/log/syslog", 28);
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("downloaded-file"));
        JPanel form = new JPanel(new BorderLayout(6,6)); form.add(new JLabel("远程文件路径"), BorderLayout.NORTH); form.add(remote, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(null, form, "SFTP 下载", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;
        startSshOperation("SFTP 下载", pwd -> sshOperations.download(selectedHosts(), pwd, remote.getText(), chooser.getSelectedFile().toPath(), sshListener("下载"), this::append));
    }

    private void inspect() {
        List<Host> targets = selectedHosts(); if (targets.isEmpty()) { warn("请先勾选服务器"); return; }
        char[] pwd = password.getPassword(); status.setText("巡检提交中：" + targets.size() + " 台");
        try {
            activeBatch = inspectionService.inspect(targets, pwd, new TaskManager.Listener() {
                @Override public void onUpdate(TaskResult r) {
                    SwingUtilities.invokeLater(() -> { append("[" + r.state() + "] " + r.hostId() + " — " + r.message()); status.setText("巡检进度：" + (activeBatch == null ? 0 : activeBatch.completed()) + "/" + targets.size()); });
                }
                @Override public void onBatchFinished(int total,int completed,int succeeded,int failed,int cancelled) {
                    SwingUtilities.invokeLater(() -> status.setText("巡检完成：成功 " + succeeded + " / 失败 " + failed + " / 取消 " + cancelled));
                }
            }, report -> {
                reports.put(report.hostId(), report);
                SwingUtilities.invokeLater(() -> updateHostStatus(report));
            });
        } catch (Exception ex) { warn("无法启动巡检：" + ex.getMessage()); }
        finally { Arrays.fill(pwd, '\0'); }
    }

    private void updateHostStatus(InspectionReport report) {
        for (int i=0;i<tableModel.getRowCount();i++) if (identityAt(i).equalsIgnoreCase(report.hostId()))
            tableModel.setValueAt(report.overallSeverity().name(),i,6);
        append("[巡检汇总] " + report.hostName() + "：" + report.overallSeverity() + "，正常 " + report.count(Severity.NORMAL) + "，警告 " + report.count(Severity.WARNING) + "，严重 " + report.count(Severity.CRITICAL));
        if (report.collectionError()!=null) append("  采集错误：" + report.collectionError());
        report.metrics().forEach((k,v) -> append("  " + k + " = " + v));
    }

    private void cancelBatch() { if (activeBatch != null) { activeBatch.cancel(); append("已请求取消当前批次；正在运行的 SSH 命令会在检查取消点或超时后退出。"); } }

    private void export(boolean html) {
        if (reports.isEmpty()) { warn("目前没有巡检报告可导出"); return; }
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(html ? "LinuxOps巡检报告.html" : "LinuxOps巡检报告.csv"));
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<InspectionReport> snapshot = new ArrayList<>(reports.values());
            if (html) ReportExporter.exportHtml(snapshot, chooser.getSelectedFile().toPath()); else ReportExporter.exportCsv(snapshot, chooser.getSelectedFile().toPath());
            append("报告已导出：" + chooser.getSelectedFile().getAbsolutePath());
        } catch (Exception ex) { warn("报告导出失败：" + ex.getMessage()); }
    }

    private void append(String text) { SwingUtilities.invokeLater(() -> { log.append(text + "\n"); log.setCaretPosition(log.getDocument().getLength()); }); }
    private void warn(String text) { JOptionPane.showMessageDialog(null,text,"LinuxOps 提示",JOptionPane.WARNING_MESSAGE); }
}
