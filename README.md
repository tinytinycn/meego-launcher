# MeeGo Launcher (Harmattan for Android)

> 在现代 Android (Jetpack Compose) 上 1:1 像素级复刻经典的诺基亚 **Nokia N9 MeeGo 1.2 Harmattan** 操作系统体验。包含三大主屏无限循环穿梭、实时无级缩放多任务卡片、全屏边缘滑动切回、超椭圆设计语言及深黑极简质感。

---

## 🛠️ 跨平台开发环境（Ubuntu / macOS 双套支持）

本项目完全支持在 **macOS** 与 **Ubuntu Linux** 下进行开发、构建与物理真机调试。内置跨平台环境自适应探针（[`scripts/env_detect.sh`](file:///Users/tinytinycn/dsh-projects/test-meego-launcher/scripts/env_detect.sh)），无需手动修改脚本即可无缝运行。

### 1. 核心依赖规范

项目统一采用 **SDKMAN** 进行工具链版本管理，根目录下配有 [`.sdkmanrc`](file:///Users/tinytinycn/dsh-projects/test-meego-launcher/.sdkmanrc)：
- **Java**: OpenJDK 17.0.1 (`17.0.1-open`)
- **Gradle**: 8.5
- **Android Target**: Android 14 (`platforms;android-34`)，Build-Tools `34.0.0`
- **Gradle Plugin**: AGP 8.3.2 + Kotlin 1.9.22 + Compose 1.5.8

---

### 2. 快速上手步骤

#### 第 1 步：克隆项目与配置网络代理（可选）
在国内或内网环境下，可通过终端代理加速依赖拉取：
```bash
export http_proxy=http://127.0.0.1:1087
export https_proxy=http://127.0.0.1:1087
export ALL_PROXY=socks5://127.0.0.1:1086
```

#### 第 2 步：通过 SDKMAN 准备 JDK 与 Gradle（Ubuntu / macOS 通用）
```bash
# 1. 安装 SDKMAN (若本机尚未安装)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 2. 自动安装并切换到项目指定版本
sdk env install
sdk env
```

#### 第 3 步：运行环境初始化与检查
```bash
bash scripts/setup_env.sh
```
该脚本将自动：
- 识别当前操作系统（macOS Darwin 或 Ubuntu Linux）；
- 探查并绑定 JDK 17 与 Android SDK 路径；
- 自动写入/更新 `local.properties`（无需手动配置）；
- 预先接受 Android SDK 协议 License；
- 检测 ADB 与真机连接状态。

---

### 3. 两套开发环境配置细节

#### 🍎 macOS 开发环境
- **Android SDK 默认路径**：`~/Library/Android/sdk`
- **JDK 备选方式**：支持 SDKMAN、`/usr/libexec/java_home -v 17` 或 Homebrew (`brew install openjdk@17`)
- **ADB 路径**：支持 `/usr/local/bin/adb`、`/opt/homebrew/bin/adb` 或 SDK `platform-tools`
- **真机连接**：macOS 原生支持 USB 调试直连，即插即用

#### 🐧 Ubuntu / Linux 开发环境
- **Android SDK 默认路径**：`~/Android/Sdk` 或预编译目录 `/home/tiny/android-sdk`
- **JDK 备选方式**：支持 SDKMAN、`apt install openjdk-17-jdk` 或系统预编译 JDK 17
- **USB 权限配置（udev 规则）**：若 `adb devices` 提示 `no permissions`，执行一次：
  ```bash
  sudo sh -c 'echo "SUBSYSTEM==\"usb\", ATTR{idVendor}==\"18d1\", MODE=\"0666\", GROUP=\"plugdev\"" > /etc/udev/rules.d/51-android.rules'
  sudo udevadm control --reload-rules && sudo udevadm trigger
  adb kill-server
  ```

---

### 4. 自动化脚本命令索引

| 脚本 | 说明 | 示例命令 |
| :--- | :--- | :--- |
| [`scripts/setup_env.sh`](file:///Users/tinytinycn/dsh-projects/test-meego-launcher/scripts/setup_env.sh) | 跨平台环境智能检查与初始化 | `bash scripts/setup_env.sh` |
| [`scripts/run_build.sh`](file:///Users/tinytinycn/dsh-projects/test-meego-launcher/scripts/run_build.sh) | 跨平台编译 Debug APK（输出至 `app/build/...`） | `bash scripts/run_build.sh` |
| [`scripts/check_device.sh`](file:///Users/tinytinycn/dsh-projects/test-meego-launcher/scripts/check_device.sh) | 诊断物理机连接、型号、屏幕与 Root 状态 | `bash scripts/check_device.sh` |
| [`scripts/deploy.sh`](file:///Users/tinytinycn/dsh-projects/test-meego-launcher/scripts/deploy.sh) | 编译、推送安装、配置特权守护脚本并拉起 Launcher | `bash scripts/deploy.sh` |
| [`scripts/screenshot.sh`](file:///Users/tinytinycn/dsh-projects/test-meego-launcher/scripts/screenshot.sh) | 捕获物理机屏幕快照至 `screenshots/` 目录 | `bash scripts/screenshot.sh` |

---

### 5. 物理机与无线调试 (ADB over Wi-Fi)

手机与电脑连接同一局域网 Wi-Fi 后，开启【开发者选项 -> 无线调试】：
```bash
adb pair <手机IP>:<配对端口>
adb connect <手机IP>:<连接端口>
bash scripts/check_device.sh
```

---

### 6. 技术规格文档

完整的设计理念、架构图、手势冲突解决机制、特权伴侣实现细节请参阅：
👉 [**MEEGO_LAUNCHER_TECH_SPEC.md**](file:///Users/tinytinycn/dsh-projects/test-meego-launcher/MEEGO_LAUNCHER_TECH_SPEC.md)
