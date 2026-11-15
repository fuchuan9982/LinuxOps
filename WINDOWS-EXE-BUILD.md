# Windows EXE 打包说明

## 当前交付形式

GitHub Actions 在 Windows x64 runner 上执行 Maven 构建，再用 JDK 21 自带的 `jpackage` 生成 app-image。最终 artifact 名称为 `LinuxOps-Windows-x64`，其中包含 `LinuxOps.exe` 和配套运行时文件。

这属于免安装便携包。请解压后运行 `LinuxOps.exe`，不要把 EXE 单独拎出来。

## 发布步骤

1. 将本项目目录内的文件提交到 GitHub 仓库 `main` 分支根目录（`pom.xml`、`src/`、`.github/` 等必须在仓库根目录）。
2. 在 GitHub 仓库打开 Actions。
3. 选择 `Build LinuxOps Windows EXE`，点击 `Run workflow`。
4. 等待任务变为绿色成功。
5. 打开该次运行页面，在 Artifacts 下载 `LinuxOps-Windows-x64`。
6. 解压并运行 `LinuxOps.exe`。

## 注意

- 必须由 Windows runner 生成 Windows 应用；Linux 本机的 `jpackage` 不能产出 Windows EXE。
- 当前工作流未代表已经构建成功；只有 GitHub Actions 实际运行成功后才算生成。
- 运行时需要连接 Linux 主机的 SSH 网络可达，并正确配置 SSH 主机指纹。
- 批量重启、安装软件和 systemd 变更请先在测试服务器验证。
