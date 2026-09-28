# MeeGo Launcher (Harmattan for Android)

> 在现代 Android (Jetpack Compose) 上 1:1 像素级复刻经典的诺基亚 **Nokia N9 MeeGo 1.2 Harmattan** 操作系统体验。包含三大主屏无限循环穿梭、实时无级缩放多任务卡片、全屏边缘滑动切回、超椭圆设计语言及深黑极简质感。

---

## 📦 安装教程

### 1. 前置条件

| 项目 | 要求 | 说明 |
| :--- | :--- | :--- |
| **手机系统** | Android 9.0+（`minSdk 28`） | 已在 **LineageOS 23.2 / Android 16** 实机验证（Redmi K40 / 1080×2400） |
| **Root 权限** | ⚠️ **必需**，`adb root` 或 Magisk | **本项目核心能力全部依赖 Root**，详见下方说明 |
| **电脑环境** | JDK 17 + Android SDK + Gradle 8.5 | 由 [`.sdkmanrc`](.sdkmanrc) 统一固定版本，见 §开发环境 |
| **连接方式** | USB 数据线，或 ADB over Wi-Fi | 无线调试步骤见 §5 |

#### ⚠️ 关于 Root：这不是可选项

本项目与普通启动器的根本差异在于**多任务卡片的真实画面**。普通启动器只能显示应用图标；本项目通过特权链路直读系统的 `TaskSnapshot`，拿到**只含该应用窗口**的真实像素（不含状态栏、导航栏、挖孔留白）。

该能力所需的系统权限为：

```
allOf = { MANAGE_ACTIVITY_TASKS, READ_FRAME_BUFFER }    // 二者均为 signature 级
```

普通应用（`untrusted_app`）调用会被直接拒绝：

```
SecurityException: Access denied, requires:
  allOf={android.permission.MANAGE_ACTIVITY_TASKS, android.permission.READ_FRAME_BUFFER}
```

实测对比（同一份代码，仅 uid 不同）：

| 调用身份 | 结果 |
| :--- | :--- |
| `uid=10209`（启动器自身） | ❌ `SecurityException` |
| `uid=0`（adbd root / Magisk su） | ✅ 通过 |

**没有 Root 也能装，但会静默降级**：卡片退化为整屏截图（含系统栏），部分场景退化为纯图标。`deploy.sh` 会在部署前**显式检查**并拒绝继续（见 §6 故障排查）。

**如何获得 `adb root`**：LineageOS 的 `userdebug` / `eng` 构建默认支持，直接执行 `adb root` 即可。若你的构建不支持（`user` 构建），需先安装 Magisk —— 注意当前 `deploy.sh` 的部署链路要求 `adb shell` 本身即 root，仅 Magisk 的场景尚需改造。

---

### 2. 一键安装

```bash
# ① 克隆项目
git clone <repo-url> && cd test-meego-launcher

# ② 环境自检（自动识别 macOS / Ubuntu，绑定 JDK 17 与 Android SDK）
bash scripts/setup_env.sh

# ③ 切换 adbd 到 root（关键一步，userdebug 构建支持）
adb root

# ④ 一键部署：编译 → 安装 → 部署特权链路 → 自检
bash scripts/deploy.sh
```

`deploy.sh` 会依次完成 7 个步骤，成功时输出形如：

```
=== 前置检查：设备连接与 Root 权限 ===
  ✅ 已连接设备 1 台
  ✅ adb shell 为 root（uid=0），特权守护链具备所需权限
=== [3/8] 编译并部署常驻 Task Snapshot 服务 + 直读桥 ===
  ✅ 常驻 TaskSnapService 已部署 (/data/local/tmp/meego_svc.dex)
  ✅ 一次性 TaskSnapBridge 已部署（自检/排障兜底）
=== [4/8] 部署特权守护脚本（不改动 /system 分区）===
  ✅ 已安装 Magisk 开机自启钩子（/data/adb/service.d/meego_boot.sh）
✅ 特权守护进程已就绪（主进程 PID=9081、watchdog PID=9065）
=== [6/8] 验证进程存活与前台状态 ===
✅ MeeGo Launcher 启动成功！PID: 9391
✅ 特权守护链就绪：主进程 PID=9081、watchdog PID=9065（同脚本进程共 7 个）
=== [7/8] 验证守护进程自愈（杀主进程后应自动恢复）===
✅ 自愈成功：watchdog 已在 ~1s 内拉起新主进程 PID=9171
=== [8/8] 验证常驻 Task Snapshot 服务（画面来源）===
  ✅ 常驻快照服务存活（PID=606）
  ✅ 服务 IPC 正常响应：{"ok":true,"listening":false,"lastEvent":"","binderPoolError":"","threads":12}
```

> **本部署流程不写 `/system` 任何文件。** 手机的 `/system` 通常受 dm-verity 校验保护，改写会导致校验失败甚至无法开机，故所有脚本一律落在 `/data` 分区（`/data/local/tmp` 与 `/data/adb`）。

---

### 3. 安装内容清单

| 组件 | 位置 | 职责 |
| :--- | :--- | :--- |
| **启动器 APK** | `com.meego.launcher` | 三屏界面、手势、卡片渲染 |
| **特权守护脚本** | `/data/local/tmp/meego_daemon.sh` | 任务列表维护、驱动取帧、任务切换/销毁、无障碍与手势自愈 |
| **常驻 Task Snapshot 服务** | `/data/local/tmp/meego_svc.dex` | **本版唯一画面来源**：常驻 root 进程，对齐 Quickstep 直取 WMS 真实任务快照（仅任务窗口，无系统栏污染），单帧 11~42ms |
| **一次性快照桥** | `/data/local/tmp/meego_daemon_bridge.dex` | 部署自检与排障兜底（不再是主链路） |
| **拉起器** | `/data/local/tmp/meego_boot.sh` | 按「watchdog 先、主进程后」顺序拉起守护链（幂等） |
| **开机自启钩子** | `/data/adb/service.d/meego_boot.sh` | 开机自动拉起守护链（Magisk 官方钩子目录） |
| **运行时数据** | 应用私有目录 `files/` | `snapshots/`（快照帧）、`cmd_queue/`（指令队列与心跳） |

**开机自启**依赖 Magisk 的 `service.d` 目录。若手机未安装 Magisk，部署会提示跳过，其余功能不受影响，但**每次重启后需手动重跑一次 `deploy.sh`**。

---

### 4. 安装后：设为默认桌面

安装完成**必须**把 MeeGo Launcher 设为默认桌面，否则按 Home 键仍会回到系统桌面：

**方式一：图形界面**
> 设置 → 应用 → 默认应用 → 主屏幕应用 → 选择 **MeeGo Launcher**

**方式二：命令行**
```bash
adb shell cmd package set-home-activity com.meego.launcher/.MainActivity
```

以下两项**无需手动操作**，特权守护脚本会自动配置并在运行期持续自愈：

- **无障碍服务**（`EdgeSwipeGestureService`）—— 边缘划回手势依赖 `TYPE_ACCESSIBILITY_OVERLAY` 穿透系统设置页的悬浮窗屏蔽
- **系统侧滑返回手势清零**（`back_gesture_inset_scale_left/right = 0`）—— 消除原生侧滑与本项目边缘手势的冲突

---

### 5. 验证安装

```bash
bash scripts/check_device.sh
```

全部正常时输出：

```
=== 快照链路诊断 (Task Snapshot) ===
  布局: 每用户 UUID 子目录 ✅ (Android 16)
        /data/system_ce/0/snapshots/8930c050-f26d-495b-9e37-8bfd7d2c5871
  系统快照张数: 12  ✅
  ✅ 快照同步正常（App 侧不落后系统侧）
  ✅ 服务进程存活（PID=30219）
  ✅ IPC 正常响应（Binder 线程数=12）
  最新帧: 758_sys.jpg（1s 前）  ✅
  ✅ 守护进程存活（心跳 2s 前）

=== 开机自启诊断 ===
  ✅ Magisk 开机钩子已安装（/data/adb/service.d/meego_boot.sh）
  最近一次拉起日志（/data/local/tmp/meego_boot.log）：
    09-28 15:16:35 watchdog spawned
    09-28 15:16:38 main started (pid=23813, 1s)
```

**目视验收**：打开几个应用 → 回到桌面 → 滑到多任务屏。卡片应显示**应用真实界面**，且**顶部没有状态栏、底部没有导航栏**——这是 Task Snapshot 直读管线生效的标志。

---

### 6. 故障排查

| 现象 | 根因 | 处理 |
| :--- | :--- | :--- |
| `deploy.sh` 报 `adb shell 当前 uid=... 不是 root` | 未执行 `adb root` | 执行 `adb root` 后重试 |
| 卡片**只有应用图标**（无画面） | 常驻 Task Snapshot 服务未运行 | `check_device.sh` 看「常驻快照服务诊断」；确认 root，并查 `/data/local/tmp/meego_snap_out` |
| 卡片只显示**应用图标** | 守护链已死，或应用刚启动尚无快照 | `check_device.sh` 看心跳；冷启动应用后等 2~3 秒 |
| 删除卡片后**后台进程仍在跑** | 守护链已死（命令队列无人消费） | `check_device.sh` 看心跳；重跑 `deploy.sh` |
| **重启后**所有特权功能失效 | 开机钩子未安装（无 Magisk） | `check_device.sh` 看「开机自启诊断」 |
| 按 Home 键回到系统桌面 | 未设为默认桌面 | 见 §4 |
| 边缘划回手势无反应 | 无障碍服务被系统回收 | 守护脚本在主循环中约每 5 秒自愈一次；也可重跑 `deploy.sh` |

**查看守护链日志**：

```bash
adb shell cat /data/local/tmp/meego_boot.log    # 拉起过程
adb shell cat /data/local/tmp/daemon.log        # 守护进程运行日志
```

**一键重启守护链**（不重新编译安装）：

```bash
adb shell /data/local/tmp/meego_boot.sh
```

该脚本**幂等** —— 守护链健在时会直接跳过，可安全反复执行。

---

### 7. 卸载

```bash
# ① 停止守护链
adb shell 'for p in $(pgrep -f meego_daemon.sh); do kill -9 $p; done'

# ② 移除开机自启钩子（需 root 写 /data/adb）
adb shell "su -c 'rm -f /data/adb/service.d/meego_boot.sh'"

# ③ 移除特权脚本与运行时数据
adb shell 'rm -f /data/local/tmp/meego_daemon.sh \
                  /data/local/tmp/meego_svc.dex \
                  /data/local/tmp/meego_daemon_bridge.dex \
                  /data/local/tmp/meego_boot.sh \
                  /data/local/tmp/meego_daemon.pid \
                  /data/local/tmp/meego_svc.pid \
                  /data/local/tmp/meego_snap_cmd \
                  /data/local/tmp/meego_snap_out \
                  /data/local/tmp/meego_boot.log \
                  /data/local/tmp/daemon.log \
                  /data/local/tmp/recent_tasks.json'
# 另需结束常驻快照服务（它不随守护脚本退出）
adb shell 'pkill -f "app_process / TaskSnapService"'

# ④ 卸载应用
adb uninstall com.meego.launcher
```

若曾把本启动器设为默认桌面，卸载前建议先切回系统桌面，避免卸载后无桌面可用：
```bash
adb shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher
```

---

## 🛠️ 开发环境（Ubuntu / macOS 双套支持）

本项目完全支持在 **macOS** 与 **Ubuntu Linux** 下进行开发、构建与物理真机调试。内置跨平台环境自适应探针（[`scripts/env_detect.sh`](scripts/env_detect.sh)），无需手动修改脚本即可无缝运行。

### 1. 核心依赖规范

项目统一采用 **SDKMAN** 进行工具链版本管理，根目录下配有 [`.sdkmanrc`](.sdkmanrc)：
- **Java**: OpenJDK 17.0.1 (`17.0.1-open`)
- **Gradle**: 8.5
- **Android Target**: `compileSdk 35` / `targetSdk 35` / `minSdk 28`
- **Gradle Plugin**: AGP 8.3.2 + Kotlin 1.9.22 + Compose 1.5.8

### 2. 环境准备

```bash
# 1. 安装 SDKMAN（若本机尚未安装）
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 2. 自动安装并切换到项目指定版本
sdk env install
sdk env

# 3. 环境初始化与检查
bash scripts/setup_env.sh
```

`setup_env.sh` 会自动：识别操作系统（macOS Darwin / Ubuntu Linux）、探查并绑定 JDK 17 与 Android SDK、写入 `local.properties`、预接受 SDK License、检测 ADB 与真机连接。

> 国内或内网环境可通过代理加速依赖拉取：
> ```bash
> export http_proxy=http://127.0.0.1:1087
> export https_proxy=http://127.0.0.1:1087
> export ALL_PROXY=socks5://127.0.0.1:1086
> ```

### 3. 两套平台的差异细节

#### 🍎 macOS
- **Android SDK 默认路径**：`~/Library/Android/sdk`
- **JDK 备选**：SDKMAN、`/usr/libexec/java_home -v 17` 或 `brew install openjdk@17`
- **ADB 路径**：`/usr/local/bin/adb`、`/opt/homebrew/bin/adb` 或 SDK `platform-tools`
- **真机连接**：原生支持 USB 直连，即插即用
- ⚠️ **bash 3.2 陷阱**：macOS 自带 bash 为 **3.2.57**（2007 年，Apple 因 GPLv3 不再更新），存在多字节插值缺陷 —— `$VAR` 后紧跟中文时变量会被吞掉并输出乱码 `��`。**本项目 shell 脚本硬性规范：凡变量后紧跟中文一律写 `${VAR}`。** 最小复现：
  ```bash
  V=11474
  echo "PID=$V，x"      # → PID=\xbc\x8cx           变量被吞
  echo "PID=${V}，x"    # → PID=11474\xef\xbc\x8cx   正确
  ```

#### 🐧 Ubuntu / Linux
- **Android SDK 默认路径**：`~/Android/Sdk` 或 `/home/tiny/android-sdk`
- **JDK 备选**：SDKMAN 或 `apt install openjdk-17-jdk`
- **USB 权限（udev 规则）**：若 `adb devices` 提示 `no permissions`，执行一次：
  ```bash
  sudo sh -c 'echo "SUBSYSTEM==\"usb\", ATTR{idVendor}==\"18d1\", MODE=\"0666\", GROUP=\"plugdev\"" > /etc/udev/rules.d/51-android.rules'
  sudo udevadm control --reload-rules && sudo udevadm trigger
  adb kill-server
  ```

---

## 📜 自动化脚本索引

| 脚本 | 说明 | 示例命令 |
| :--- | :--- | :--- |
| [`scripts/setup_env.sh`](scripts/setup_env.sh) | 跨平台环境智能检查与初始化 | `bash scripts/setup_env.sh` |
| [`scripts/run_build.sh`](scripts/run_build.sh) | 跨平台编译 Debug APK（输出至 `app/build/...`） | `bash scripts/run_build.sh` |
| [`scripts/check_device.sh`](scripts/check_device.sh) | 设备诊断：型号/屏幕/Root + **快照链路** + **开机自启** | `bash scripts/check_device.sh` |
| [`scripts/deploy.sh`](scripts/deploy.sh) | **一键安装**：编译、安装、部署特权链路、自检与自愈验证 | `bash scripts/deploy.sh` |
| [`scripts/meego_boot.sh`](scripts/meego_boot.sh) | 守护链拉起器（幂等；开机钩子亦复用同一份） | `adb shell /data/local/tmp/meego_boot.sh` |
| [`scripts/meego_daemon.sh`](scripts/meego_daemon.sh) | 特权守护主脚本（watchdog + 主进程 + 3 个 monitor 子 shell），并守护常驻快照服务 | 由 `deploy.sh` 自动部署，勿手工执行 |
| [`scripts/TaskSnapService.java`](scripts/TaskSnapService.java) | **常驻 Task Snapshot 服务**（本版唯一画面来源，对齐 Quickstep 取帧） | 由 `deploy.sh` 现编为 `meego_svc.dex`，经 stdin/stdout JSON 驱动 |
| [`scripts/perf_grid.sh`](scripts/perf_grid.sh) | 滑动性能对照基准（MeeGo Grid vs Trebuchet） | `bash scripts/perf_grid.sh` |
| [`scripts/analyze_framestats.py`](scripts/analyze_framestats.py) | 解析 `framestats` 逐帧分段耗时 | `python3 scripts/analyze_framestats.py <dump>` |
| [`scripts/screenshot.sh`](scripts/screenshot.sh) | 捕获物理机屏幕快照至 `screenshots/` | `bash scripts/screenshot.sh` |

> `scripts/deploy.sh` 会现编译两个 dex：`scripts/TaskSnapService.java` → `/data/local/tmp/meego_svc.dex`（**常驻服务，本版唯一画面来源**），以及 `scripts/TaskSnapBridge.java` → `/data/local/tmp/meego_daemon_bridge.dex`（一次性桥，仅自检/排障兜底）。**不提交预编译 dex** —— dex 与设备 ROM 强绑定（ART 版本、hidden API 名单都会变），现编现用才永远跟随当次 SDK。

---

## 📡 附：物理机与无线调试 (ADB over Wi-Fi)

手机与电脑连接同一局域网 Wi-Fi 后，开启【开发者选项 → 无线调试】：

```bash
adb pair <手机IP>:<配对端口>
adb connect <手机IP>:<连接端口>
adb root                    # 仍需切换到 root
bash scripts/check_device.sh
```

> ⚠️ **无线调试的注意点**：设备重启后无线调试通道会断开，且端口通常需要在手机上重新配对。**若你打算验证「重启后守护链是否自动恢复」，请改用 USB 连接** —— 否则重启后将无法回连取证。

---

## 🧭 当前实现状态（v1.8.44 实测，2026-09-28）

> 本节由**逐行通读全部 20 个 Kotlin 源文件**核对得出。README 与技术规格文档中若出现与本节冲突的表述，**以本节与代码为准**。

### ✅ 已完整落地

| 能力 | 实现位置 | 关键取值 |
| :--- | :--- | :--- |
| 三屏无限循环穿梭 | `HomeScreen.kt` | 30000 页 Pager、起点 15001、`floorMod(page,3)`；边缘 36.dp 起手，跨屏判定「距离 12% **或** 速度 400f」 |
| 应用网格 4/5 列动态切换 | `AppGridScreen.kt` + DataStore | 图标 `62.dp`(4列) / `52.dp`(5列) |
| MeeGo 设置中心 | `SettingsScreen.kt` | `grid_columns` / `edge_swipe` / `immersive_ui` 三项（`recents_columns` 有存储通路但未暴露 UI） |
| 多任务真实快照卡片 | `RootTaskProvider.kt` + `EdgeSwipeGestureService.kt` | 四级任务源降级 + **单一快照来源 `_sys.jpg`** |
| Task Snapshot 常驻取帧 | `scripts/TaskSnapService.java` | 对齐 Quickstep：`TaskSnapshotManager.takeTaskSnapshot` / `getTaskSnapshot`；**只含该任务窗口**，无状态栏/导航栏/挖孔留白；单帧 **11~42ms**（旧一次性桥 ~1.35s） |
| 双指无级缩放 2×2 ⇄ 3×3 | `MultitaskingScreen.kt` | 阈值 `0.5f`、弹簧 `spring(0.96f, 450f)`、捏合灵敏度 `680f` |
| 长按编辑 + 删除角标 + 一键清空 | `MultitaskingScreen.kt` | 消失 150ms → 补位 `spring(0.95f, 300f)` |
| 全局边缘划回（含双层揭示） | `EdgeSwipeGestureService.kt` | 触发区 28.dp、**滑出阈值 1/3 屏**、震动越线即刻触发（35ms） |
| 来源感知回溯 | `model/Models.kt` | `lastOrigin`（回哪一页）**与** `currentScreenIndex`（动画如何表现）刻意分离 |
| 桌面缩放中心锚定 | `EdgeSwipeGestureService.kt` | pivot 钉物理屏幕中心，漂移由 (+51,+156)px 降到 (+0.0,+45.3)px |
| 图标形状统一（超椭圆） | `util/AppIconRenderer.kt` | 绕开 `AdaptiveIconDrawable.draw()` 的系统圆形 mask；图层整体 `scale(1.5f)` 对齐 AOSP |
| 特权守护链 + watchdog 自愈 | `scripts/meego_daemon.sh` | watchdog 每 5s 校验、连续 3 次确认死亡才重启（15s 宽限） |
| 开机自启 | `scripts/meego_boot.sh` | Magisk `service.d` 钩子，**不写 `/system`**；开机路径自脱离防拖死 |
| MeeGo 滚动物理 | `ui/components/MeeGoScrollPhysics.kt` | 17 项参数显式化；回弹为**单段弹簧 + 初速度注入**（1302ms → ~490ms） |

### ⚠️ 规划中但**未实现**（请勿在文档中当作现状）

- **Quick Launch Bar**（底部上划呼出电话/短信/相机/浏览器）—— `HomeScreen` 无底栏，应用入口只存在于第 1 屏网格内。
- **应用网格长按编辑**（图标抖动 / 快速卸载）—— `AppGridScreen` 只有普通 `clickable`，无长按。全项目 `onLongClick` 仅存在于多任务屏。
- **Events Feed 的真实数据** —— 除**时间**（每秒刷新）外，天气与通知均为 **MOCK 假数据**；`NotificationListenerService` 未实现。
- **底部系统横条遮罩** —— 已改为 `WindowInsetsControllerCompat` 真正隐藏系统栏，无障碍遮罩视图从代码移除。

> 💡 `FeedScreen` 的 MOCK 列表里有「长按桌面进入编辑模式」「快捷…」等文案，**那是演示假数据，不是功能说明**。

---

---

## 📖 技术规格文档

完整的设计理念、架构决策、权限链分析、踩坑留档与真机实测数据请参阅：
👉 [**MEEGO_LAUNCHER_TECH_SPEC.md**](MEEGO_LAUNCHER_TECH_SPEC.md)
