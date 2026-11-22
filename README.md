# StarDewOpener · 星露谷开服器

**ValleyServer-arm64 的 Android 前端 APP：内置 proot 容器，完全独立运行，无需 Termux / tmoe / 安装脚本。**

配套服务器仓库：[deekoz2/ValleyServer-arm64](https://github.com/deekoz2/ValleyServer-arm64)（ValleyServer v3.0.3 linux-arm64 DSH 修复版，上游 [Lixeer/ValleyServer](https://github.com/Lixeer/ValleyServer)）。

## v2.0 · 内置容器版

```
下载 rootfs(Ubuntu 26MB) + ValleyServer(448MB, 镜像加速/断点续传)
        │  纯 Java 解包（tar.gz + zip），落盘 APP 私有目录
        ▼
内置 proot（Termux 官方构建）拉起无头服务器，前台服务托管，控制台实时回显
```

- ✅ **不再需要 Termux / ZeroTermux / tmoe**，装 APP 就能开服
- ✅ **取消一键安装指令**：初始化、解压、启动全部在 APP 内完成
- ✅ 下载带镜像回退（TUNA / cdimage / ghfast）+ 字节校验 + 断点续传
- ✅ 服务器以前台服务运行，通知栏常驻，APP 退到后台不中断
- ✅ 反馈问题：一键提交 GitHub Issue（自动附带环境信息与日志）

## 安装与使用

1. 从 [Releases](https://github.com/deekoz2/StarDewOpener/releases) 下载 `valley-launcher.apk` 安装（Android 8.0+，需约 2GB 可用存储）；
2. 打开 APP → 点 **① 初始化容器与服务器**（首次约 500MB 下载，耐心等待）；
3. 点 **② 启动服务器** → 控制台实时输出，通知栏显示运行中；
4. 好友联机：同一 Wi-Fi 填 `你的IP:24642`（APP 内 `valley ip` 已移除，可用路由器后台或 `valley ip` 的 Termux 替代方案查看）；外网联机需 frp / Tailscale 等内网穿透；
5. 停止：点 **③ 停止服务器**。

> 端口修改：初始化后 `config.json` 位于 APP 私有目录
> `/data/data/com.deekoz2.valleyserver/app_valley/rootfs/opt/valleyserver/`，后续版本会提供界面化配置。

## 自己构建（无需 Android Studio）

```sh
apt install openjdk-17-jdk-headless aapt dalvik-exchange apksigner zip
curl -L -o /tmp/android.jar \
  https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar
cd app && SDK_JAR=/tmp/android.jar bash build.sh
# 产物: app/build/valley-launcher.apk
```

内置的 `proot`/`loader`/`libtalloc`/`libandroid-shmem` 来自 Termux 官方软件仓库（GPL-3.0），来源与源码链接见 `app/assets/bin/LICENSE-binaries.txt`。targetSdk 28（与 Termux 相同的原因：允许在应用私有目录执行文件）。

## 技术实现

- **容器管理**：`proot -0 -R rootfs -b /dev,/proc,/sys -w /opt/valleyserver ValleyServer`
- **rootfs**：Ubuntu 22.04.5 base arm64（含 bash / glibc / libstdc++，.NET 运行时所需齐全）
- **解包**：纯 Java 实现 tar.gz（支持 ustar/GNU 长名/pax/符号链接，权限用 `Os.chmod` 还原）+ `java.util.zip` 解 zip
- **进程托管**：前台 Service 持有 proot 进程，stdout/stderr 合流回显到界面

## 许可

- APP：Apache-2.0
- 内置二进制：proot 等遵循 GPL-3.0（来源与源码链接见 `app/assets/bin/LICENSE-binaries.txt`）
- 服务器本体：[Lixeer/ValleyServer](https://github.com/Lixeer/ValleyServer)（Apache-2.0）
- 如果你喜欢星露谷物语，请支持正版。
