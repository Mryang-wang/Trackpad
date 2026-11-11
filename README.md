# Trackpad

把闲置安卓手机变成 Mac 的蓝牙触控板和应用控制台。

手机通过 BLE 连接 Mac，支持全屏横向触控、应用图标网格、模糊搜索和应用专属操作。Mac 接收端在菜单栏后台运行，可配置为登录后自动启动。

当前控制台版本为 Android **0.3.7**、macOS **0.4.2**。

## 功能

- 单指移动与轻点，双指滚动与右键，三指拖移，四指切换桌面。
- 指针默认使用原速度的 1.8 倍。右上角「触控板设置」可调整为 0.5–3.0 倍，自动保存，并可恢复原速度；单指移动与三指拖移共用此设置，双指滚动速度保持原值。
- 单击应用图标打开或切换，长按选择打开／切换、退出应用、应用详情和返回。
- 运行中的应用优先显示并带有淡青色光晕，各组按名称首字母排序，中文使用拼音排序。
- 搜索支持大小写混输、中文、拼音及首字母，例如 `AQY` 查找爱奇艺、`bdwp` 查找百度网盘。
- 应用列表和图标保存在手机中。首次完整同步后，再次进入先显示缓存，再更新运行状态。
- ChatGPT / Codex 详情页提供开始／结束听写、发送草稿、停止回复和新建对话。听写使用 Mac 当前的麦克风。
- Mac 接收端使用固定的本机签名身份，便于后续更新沿用当前权限。

## 目录

| 路径 | 内容 |
| --- | --- |
| `Console/BluetoothTrackpad` | 当前控制台的 Android、macOS 源码及构建安装脚本 |
| `Experiments/BluetoothTrackpad` | 早期触控板实验源码 |
| `Stable` | Android 0.1.5、Mac 0.1.9 的回退包、源码归档和 SHA-256 校验值 |

稳定版文件可在该目录中执行 `shasum -a 256 -c SHA256SUMS` 校验。早期实验使用独立的应用身份，当前控制台的使用说明位于 [Console/BluetoothTrackpad/README.md](Console/BluetoothTrackpad/README.md)。

## 构建当前控制台

需要 macOS 13 或更新版本、Xcode Command Line Tools、JDK 11 或更新版本，以及 Android SDK Platform 35 和 Build-Tools 35。以下 SDK 路径按 Android Studio 的默认安装位置举例，请按本机实际位置调整。

```sh
export ANDROID_PLATFORM_JAR="$HOME/Library/Android/sdk/platforms/android-35/android.jar"
export ANDROID_BUILD_TOOLS="$HOME/Library/Android/sdk/build-tools/35.0.0"

cd Console/BluetoothTrackpad
./setup-signing.sh
./build.sh
./install-host.sh
./enable-login.sh
```

`setup-signing.sh` 首次运行会在本机登录钥匙串中创建专用代码签名身份，之后复用同一身份。公开仓库保存源码与脚本，本机证书指纹、签名私钥、Android 签名密钥和构建缓存由 `.gitignore` 排除。

生成的 Android 安装包位于 `Console/Build/Trackpad-Console-0.3.7.apk`。连接已开启 USB 调试的安卓手机后，可用 `adb install -r` 安装。

Mac 接收端安装到 `~/Applications/Trackpad 控制台接收端.app`。首次使用允许蓝牙访问，并在系统设置的「隐私与安全性 → 辅助功能」中启用接收端。菜单栏中的「显示连接窗口」可查看六位连接码，手机首次连接时输入该码。

只构建 Mac 接收端可运行 `./build.sh --mac-only`，只构建 Android 可运行 `./build.sh --android-only`。

## 使用范围

项目目前用于已登录的 Mac 桌面，已在小米 11 Lite、Android 12 / MIUI 13 上进行过实机测试。多指手势可能需要先关闭小米系统的三指截屏快捷手势。Codex 控制依赖其当前窗口的辅助功能界面，操作时先把 Codex 对话切到 Mac 前台。
