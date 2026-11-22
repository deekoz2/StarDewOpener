# StarDewOpener · 星露谷开服器

**ValleyServer-arm64 的 Android 前端 APP**：在手机上检测 Termux / ZeroTermux、验证连通性、一键部署星露谷物语无头专用服务器。

配套服务器仓库：[deekoz2/ValleyServer-arm64](https://github.com/deekoz2/ValleyServer-arm64)（ValleyServer v3.0.3 linux-arm64 DSH 修复版，上游 [Lixeer/ValleyServer](https://github.com/Lixeer/ValleyServer)）。

## 功能

- 🔍 **检测安装状态**：通过 PackageManager 检测 `com.termux` / `com.zerotermux` 是否安装（含版本号）
- 📶 **连通性测试**：通过 Termux `RUN_COMMAND` intent 下发测试命令，用 PendingIntent 回执验证链路（按官方 v0.118.0 result-bundle 协议解析：`err=-1` 成功，失败显示 errmsg 并给出对应指引）
- ⚡ **一键安装**：调起 Termux 执行服务器仓库的 `install.sh`（tmoe proot 容器 + ValleyServer）
- 📋 **放行助手**：「复制放行命令」+「打开 Termux」，快速完成 `allow-external-apps=true` 放行

## 安装

从 [Releases](https://github.com/deekoz2/StarDewOpener/releases) 下载 `valley-launcher.apk` 安装。

或自行构建（无需 Android Studio，支持在手机 Termux/Ubuntu 上构建）：

```sh
apt install openjdk-17-jdk-headless aapt dalvik-exchange apksigner zip
curl -L -o /tmp/android.jar \
  https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar
cd app && SDK_JAR=/tmp/android.jar bash build.sh
# 产物: app/build/valley-launcher.apk
```

## 前置条件（Termux 侧）

1. 已安装 Termux（或 ZeroTermux），打开过一次；
2. 放行外部应用（APP 里有「复制放行命令」按钮，或手动执行）：

   ```sh
   mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   ```

   然后完全退出并重开 Termux；
3. 保持 Termux 可后台运行（关闭电池优化 / 后台清理白名单）。

## 使用流程

1. 打开 APP → 确认 Termux 状态为「已安装」；
2. 点「检测能否连接」→ 看到「✓ 连接成功」；
   - 若失败，按日志提示放行（APP 按钮一键复制命令）后重测；
3. 点「一键安装开服环境」→ 跟随 tmoe 菜单选 `Debian → arm64 → proot`，等待部署完成；
4. Termux 里 `valley` 启动服务器（端口 24642/udp）。

## 实现说明

- minSdk 24 / targetSdk 30，单 Activity，程序化 UI，零第三方依赖（APK 约 17KB）；
- 清单声明 `com.termux.permission.RUN_COMMAND`（否则调起 Termux 服务会 SecurityException）；
- 回执解析遵循官方协议：广播携带 `result` Bundle（`err` / `exitCode` / `stdout` / `stderr` / `errmsg`），
  兼容不回传 stdout 的 fork 版本（`err=-1` 即视为连通）；
- 兼容 Termux 与 ZeroTermux（fork 包名不同但保持同一 service/action）。

## 许可

Apache-2.0（服务器本体 [Lixeer/ValleyServer](https://github.com/Lixeer/ValleyServer) 同为 Apache-2.0）。如果你喜欢星露谷物语，请支持正版。
