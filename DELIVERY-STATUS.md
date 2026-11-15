# LinuxOps Professional 交付状态

本次基于用户上传的 `LinuxOps_Professional_Source.zip` 修改。

| 项目 | 状态 |
|---|---|
| 统一 Maven 源码结构 | 已整理 |
| 服务器清单、CSV 导入、批量巡检、报告导出 | 已有源码入口 |
| SSH 命令执行界面入口 | 本次已接入 |
| APT/DNF/YUM 软件安装界面入口 | 本次已接入 |
| systemd 服务管理界面入口 | 本次已接入 |
| SFTP 上传/下载界面入口 | 本次已接入 |
| 批量重启（二次确认） | 本次已接入 |
| stdout/stderr 并行读取 | 本次已调整 |
| Java 21 源码静态编译检查 | 通过（使用临时 SSHJ API stub；非真实依赖构建） |
| Maven + SSHJ 完整构建 | 未验证（当前环境无 Maven） |
| Windows jpackage app-image | 未验证 |
| Linux 发行版真实主机测试 | 未验证 |
| 100–500 台压力测试 | 未验证 |
| GitHub 仓库更新 | 未执行 |

## 重要限制

- 执行 SSH 命令可运行任意远程 Shell 指令，请只对可信目标使用。
- 软件安装、systemd 和重启使用 `sudo -n`，目标机需预先配置受控的免交互 sudo 权限。
- SFTP 下载到多台服务器时会按主机名生成不同文件名，避免覆盖。
- 仍缺实时监控 UI、分组持久化、集中审计、凭据保险库和 CSV 引号字段解析。
- 交付包是开发源码，不代表 Windows EXE 已构建或生产验收通过。
