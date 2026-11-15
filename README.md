# LinuxOps Professional（统一源码交付包）

本包将此前阶段 1–5 的重复源码合并为一个标准 Maven 项目，保留一份主程序和一套模块源码。

## 环境要求

- Windows 10/11（桌面运行目标）
- JDK 21
- Maven 3.9+
- 目标 Linux 主机启用 SSH；首次连接前须核验并配置 Windows 用户目录下的 `~/.ssh/known_hosts`

## 构建

在本目录打开 PowerShell 或终端：

```powershell
mvn -B clean package
```

成功后生成：`target/linuxops-1.0.0.jar`

运行：

```powershell
java -jar target/linuxops-1.0.0.jar
```

Windows 便携应用由 GitHub Actions 的 `.github/workflows/windows-build.yml` 在 Windows runner 上通过 `jpackage --type app-image` 构建。当前工作流产物是 app-image 目录，下载后运行其中的 `LinuxOps.exe`；不是传统安装向导。

## 当前源码结构

```text
src/main/java/com/linuxops/
├── Main.java                    # Swing 主界面（服务器管理、巡检、SSH 命令、安装、systemd、SFTP、重启和报告）
├── model/Host.java              # 服务器模型及唯一标识
├── security/CredentialSnapshot.java
├── task/                        # 有界批量任务、状态、结果和取消
├── inspection/                  # 巡检命令、解析、分级、SSH 采集服务
├── report/ReportExporter.java   # HTML / CSV 报告
└── ssh/SshOperations.java      # SSH 命令、软件安装、systemd、SFTP 服务层
```

## 已纳入的能力

- 服务器手动添加、CSV 导入、选择、删除；唯一标识包含主机、端口和用户名
- 有界并发任务调度、分批执行、进度回调和取消令牌
- SSH `known_hosts` 校验、连接超时、密码副本清理
- 结构化巡检与 NORMAL / WARNING / CRITICAL 分级
- HTML、CSV 巡检报告
- 主界面已接入批量 SSH 命令执行（含超时设置和 stdout/stderr 输出）
- 主界面已接入 APT/DNF/YUM 软件安装入口
- 主界面已接入 systemd status/start/stop/restart
- 主界面已接入 SFTP 上传、下载
- 主界面已接入带二次确认的批量重启
- SSH 命令 stdout/stderr 改为并行消费，降低大输出时通道互相阻塞风险
- Windows GitHub Actions app-image 构建工作流

## 尚未完成 / 使用前须知

**这是开发源码包，不是已完成生产验收的最终产品。** 实时监控界面、服务器分组持久化、集中审计、完整任务列表、凭据保险库仍需开发。CSV 导入当前仍不支持带引号的逗号字段。

本次环境没有安装 Maven，无法执行真实 Maven + SSHJ 依赖构建；仅使用临时 SSHJ API stub 对 Java 源码做了 Java 21 语法/类型结构编译检查，该检查不等于真实依赖构建。Windows app-image、Ubuntu/Rocky/Debian 真实主机测试和 100–500 台压力测试仍未完成。请先在测试环境构建和验证，勿直接执行生产变更。

## CSV 格式

见 `hosts_example.csv`，列顺序为 `group,name,host,port,user`。当前导入器按逗号简单拆分，不支持带引号的逗号字段。

## 安全建议

- 首次连接前通过可信渠道核验 SSH 主机指纹；不要关闭主机密钥校验。
- 优先使用 SSH 密钥和最小权限账户。
- 批量安装、服务变更、重启前必须由操作者确认，并配置受控的免交互 sudo 权限。
- 不要把密码、私钥或生产主机敏感信息提交到 Git 仓库。
