# LinuxOps Professional（开发预览版）

Windows 桌面 Linux 服务器运维工具，基于 Java 21、Swing 和 SSHJ。

## 当前包含
- CSV 导入、手动添加和勾选服务器
- SSH 命令执行、SFTP 上传/下载、批量重启（二次确认）
- 基础巡检：主机、发行版、运行时间、CPU、内存、磁盘、负载、失败服务、包管理器、防火墙
- 实时监控：每 30 秒采集主机名、负载、内存和根分区使用率，可手动停止
- 将当前输出区内容导出为 HTML 运维记录
- 批量软件安装：自动识别 apt-get / dnf，软件包名称做字符白名单校验并二次确认（需要免交互 sudo 权限）
- systemd 服务管理：查看状态、启动、停止、重启；变更操作需二次确认
- 本地审计日志：记录命令、安装、服务变更及失败事件，保存在用户目录 `.linuxops/audit.log`
- SSH 连接超时设置；使用本机 `~/.ssh/known_hosts` 校验主机密钥，未知主机应拒绝连接

## 目标系统适配
1. Ubuntu 20.04 / 22.04 / 24.04 LTS（优先）
2. Rocky Linux 8 / 9
3. Debian 11 / 12（基础命令适配）

巡检命令尽量使用通用 Linux 接口，并检测 `apt-get`、`dnf`、`yum`、`zypper` 和 `ufw` / `firewalld`。目前仍未实现告警通知、结构化指标报表、持久化服务器分组数据库、凭据保险库和完善的任务队列进度界面。

## 构建
需要 JDK 21 和 Maven：

```bash
mvn -B clean package
```

Windows 上运行 `build-windows.bat`，或推送到 GitHub `main` 分支后由 `.github/workflows/windows-build.yml` 自动构建。GitHub Actions 使用 Windows runner 的 `jpackage --type app-image` 将 Java runtime 一起打包，产物为 ZIP。

## CSV 格式

```csv
group,name,host,port,user
Ubuntu生产,web-01,192.0.2.10,22,ubuntu
```

## 安全与限制
- 首次连接前，应通过可信管理渠道核验服务器 SSH 主机密钥并写入 Windows 用户目录下的 `.ssh/known_hosts`；不要关闭主机密钥校验。
- 密码仅用于当前进程的连接认证；生产环境优先使用 SSH 密钥和最小权限账号。批量任务会为每台主机使用独立密码缓冲区并在任务结束后清理。
- 批量安装和服务变更通过 `sudo -n` 执行，不会在远端交互式询问 sudo 密码；请先配置受控的 sudo 权限。
- 审计日志保存在本机用户目录，当前未做签名、防篡改或集中汇总。
- 批量命令具有高风险。尤其 `sudo reboot` 需要账号具备相应 sudo 权限；建议先小批量验证。
- 当前版本是开发预览版，尚未完成真实 Ubuntu/Rocky 环境集成测试、Windows EXE 构建验收或 100–500 台规模压力测试。不要直接用于未经验证的生产变更。
