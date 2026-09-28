# MeeGo Launcher 五个核心文件技术摘要

> 依据源码逐行核对，覆盖 MainActivity / HomeScreen / AppGridScreen / FeedScreen / SettingsScreen，
> 并交叉验证 data、model、service、ui.components、ui.theme 依赖。**所有常量名与取值均逐字引自源码。**
>
> 文中标注 `⛔` 的条目为「提问中假设存在、但源码中确实不存在」，已全项目 grep 验证。

---

## 0. 全局主题与形状常量（`ui/theme/Theme.kt`、`ui/components/SquircleShape.kt`）

| 名称 | 取值 | 说明 |
|---|---|---|
| `MeeGoBlack` | `Color(0xFF000000)` | 纯黑背景，OLED 降功耗 |
| `MeeGoDarkGrey` | `Color(0xFF141414)` | surface |
| `MeeGoCardBg` | `Color(0xFF1E1E1E)` | 卡片/图标底板 |
| `MeeGoCyan` | `Color(0xFF00A2E8)` | 强调色 |
| `MeeGoRed` | `Color(0xFFE81123)` | 警示/第二强调 |
| `MeeGoTextWhite` | `Color(0xFFFFFFFF)` | 主文本 |
| `MeeGoTextDim` | `Color(0xFFAAAAAA)` | 次级文本 |
| `SquircleShape(n, cornerRadius)` | `n` 默认 `2.732f`（= 1+√3），`cornerRadius` 默认 `null` | MeeGo Harmattan 黄金超椭圆 |
| `DefaultSquircleShape` | `SquircleShape(2.732f)` | 全局单例，图标/卡片统一裁形 |

---

## 1. MainActivity.kt（431 行）

### 1.1 结构

`class MainActivity : ComponentActivity()`

| 成员 | 签名 | 职责 |
|---|---|---|
| companion | `const val EXTRA_TARGET_PAGE = "extra_target_page"` | 外部定向回溯目标页的 Intent extra key |
| 字段 | `appRepo: AppRepository` / `taskProvider: RootTaskProvider` / `preferences: LauncherPreferences` | 三个 `lateinit` 单例 |
| 状态 | `apps = mutableStateListOf<AppItem>()` | 全部应用 |
| 状态 | `recentTasks = mutableStateListOf<RecentTask>()` | 多任务卡片 |
| 状态 | `gridColumns = mutableIntStateOf(4)` | 桌面列数初值 **4** |
| 状态 | `recentsColumns = mutableIntStateOf(2)` | 多任务列数初值 **2** |
| 状态 | `edgeSwipeEnabled = mutableStateOf(true)` | 边缘手势开关初值 **true** |
| 状态 | `immersiveUiEnabled = mutableStateOf(false)` | 沉浸式 UI 初值 **false** |
| 状态 | `targetPage = mutableStateOf<Int?>(null)` | 定向页 |
| 状态 | `targetPageEvent = mutableLongStateOf(0L)` | 定向事件戳，充当 `LaunchedEffect` key |
| override | `onCreate(savedInstanceState: Bundle?)` | 窗口/沉浸式/异步初始化/装配 UI |
| override | `onNewIntent(intent: Intent?)` | 关闭转场 + 处理定向页 + 置顶滑出任务 |
| override | `onResume()` | 重新隐藏系统栏、刷新任务、首帧通知、抓帧 |
| override | `onPause()` | 交还手势服务 + 抓帧 |
| override | `onWindowFocusChanged(hasFocus: Boolean)` | 获焦即重新隐藏系统栏 |
| private | `notifyFrameReadyForDismiss()` | 帧提交后解散手势遮罩 |
| private | `promoteLastSwipedTaskToTop()` | 把刚滑出的任务提到首位并 reset 追踪器 |
| private | `handleTargetPage(intent: Intent?)` | 解析 `EXTRA_TARGET_PAGE` |
| private | `loadApps()` | `appRepo.getInstalledApps()` 后 `clear()+addAll()` |
| private | `updateRecentTasks(newTasks: List<RecentTask>)` | 增量保序合并（见 §1.6） |
| private | `loadTasks()` | `taskProvider.getRecentTasks()` → `updateRecentTasks` |
| private | `isAccessibilityServiceEnabled(): Boolean` | 解析 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` |
| private | `startEdgeServiceIfNeeded()` | 通过 FIFO `/data/local/tmp/meego_cmd` 下发 `ENABLE_A11Y` + `startService` |
| private | `hideSystemBars()` | `WindowInsetsControllerCompat` 隐藏系统栏 |
| private | `setupSystemGestureExclusion()` | 左右各 28dp 排除原生侧滑返回 |
| 顶层 | `private fun <T> mutableStateListOf(): SnapshotStateList<T>` | 文件尾辅助函数（第 429–431 行） |

### 1.2 窗口设置（immersive / cutout）

- `WindowCompat.setDecorFitsSystemWindows(window, false)` —— edge-to-edge 铺满。
- `hideSystemBars()` 内部：
  - `controller.hide(WindowInsetsCompat.Type.systemBars())` —— 同时隐藏**状态栏 + 导航条**。
  - `controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`。
- **cutout mode：源码中未出现 `layoutInDisplayCutoutMode`**（全文件无该字符串），因此采用主题/默认策略，**未显式设置** `LAYOUT_IN_DISPLAY_CUTOUT_MODE_*`。
- 设计原则（源码注释 L56–77）：**隐藏系统栏，而非屏蔽/拦截系统手势**。此前用 `systemGestureExclusionRects` / `FLAG_NOT_TOUCH_MODAL` / 底部黑色遮罩反复压制，均不可靠；改为向系统申请隐藏两条系统栏后，其手势区一并消失。
- 三处调用点：`onCreate`（L79）、`onResume`（L176）、`onWindowFocusChanged`（L395）。

### 1.3 边缘手势排除区（`setupSystemGestureExclusion`）

- 仅在 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` 生效，`rootView.post { }` 中读取 `height` / `width`。
- `val edgeW = (28 * density).toInt()` —— **左右各 28dp**（注释即「全屏左右边缘各 28dp 排除 Android 原生手势冲突」）。
- `leftRect = Rect(0, 0, edgeW, height)`，`rightRect = Rect(width - edgeW, 0, width, height)`。
- `ViewCompat.setSystemGestureExclusionRects(rootView, listOf(leftRect, rightRect))`。
- **底部排除区已刻意移除**（L412–417 注释）：系统栏已隐藏，底部上滑无目标可唤出，无需对抗性屏蔽。

### 1.4 最近任务刷新时序（`onResume`）

| 时刻 | 动作 |
|---|---|
| 同步 | `hideSystemBars()`；转场 `overrideActivityTransition(OPEN/CLOSE, 0, 0)`（API 34+），否则 `overridePendingTransition(0, 0)` |
| 同步 | `promoteLastSwipedTaskToTop()` |
| 同步 | `loadTasks()` —— 第一次 |
| **+600ms** | `window.decorView.postDelayed({ loadTasks() }, 600)` |
| **+1500ms** | `window.decorView.postDelayed({ loadTasks() }, 1500)` |
| **+2500ms** | 条件刷新：仅当 `recentTasks.firstOrNull()?.snapshot == null` 才 `loadTasks()` |
| 同步 | `startEdgeServiceIfNeeded()` |
| 同步 | `EdgeSwipeGestureService.setInLauncher(true)` |
| 同步 | `notifyFrameReadyForDismiss()` |
| **+400ms** | `ScreenCaptureHelper.captureLauncherScreen(this, LaunchOriginTracker.lastOrigin)` |

关于 2500ms 的兜底，源码注释（L188–190）说明动机：慢设备上系统快照落盘可能晚于 1500ms；**仅在首位卡片仍无快照时**才追加一次，避免无条件并发加载 —— v1.8.13 曾因重复并发 `loadTasks` 引入「卡片位移动画播放两次」的竞态。

### 1.5 `promoteLastSwipedTaskToTop()`

```
val topTaskId = LaunchOriginTracker.currentTaskId ?: TaskSnapshotCache.activeSwipe()?.taskId
if (topTaskId != null) { idx = indexOfFirst{...}; if (idx > 0) { removeAt(idx); add(0, item) } }
LaunchOriginTracker.reset()
```

- 滑出记录已过期（用户已唤起新应用）时 `activeSwipe()` 返回 null，此时以系统 recents 顺序为权威，旧锁不得干涉。
- **重排后必调 `LaunchOriginTracker.reset()`**（清 `currentTaskId` 与 `currentPackageName`），杜绝历史残留影响后续打开其他应用。
- 调用点：`onNewIntent`、`onResume` 各一次。

### 1.6 `updateRecentTasks()` —— 增量保序合并

1. **空列表直接 return**：避免守护进程轮询空窗期把已有卡片误清空（手动清空由 `onClearAllTasks` 负责）。
2. **强保序锁**：`TaskSnapshotCache.activeSwipe()?.taskId` 若存在且 `idx > 0`，在**新列表副本**中把它移到首位。
3. `recentTasks.isEmpty()` → 直接 `addAll`。
4. 否则按 item key 同步：`removeAll { it.taskId !in newIds }`，再 `forEachIndexed` 对齐目标下标：
   - 不存在 → `add(targetIndex.coerceAtMost(size), task)`
   - 存在但下标不符 → 移动并 `copy(snapshot = task.snapshot ?: item.snapshot, label = task.label, icon = task.icon ?: item.icon)`
   - 下标相同 → 仅当 `snapshot` 或 `label` 变化才替换（`snapshot = task.snapshot ?: existing.snapshot`）
   - 设计意图（L283）：**保留 item key** 以触发流畅的重排与顺移位移动画。

### 1.7 生命周期与 LaunchOriginTracker 交互汇总

| 时机 | Tracker / Cache 交互 |
|---|---|
| `onCreate` | `TaskSnapshotCache.clear()`（同时 `clearSwipeRecord()`） |
| `HomeScreen` `LaunchedEffect(currentScreen)` | `currentScreen==1 → lastOrigin=GRID`；`==2 → lastOrigin=RECENTS`（Feed 不写，见 §2.4） |
| `HomeScreen` `SideEffect` | `currentScreenIndex = currentScreen`（0/1/2） |
| `AppGridScreen.launchApp` | `lastOrigin=GRID`、`currentPackageName=`、`currentTaskId=null`、`TaskSnapshotCache.clearSwipeRecord()`、抓帧 |
| `onResume` | `promoteLastSwipedTaskToTop()` → `reset()`；+400ms 抓帧 |
| `onPause` | `setInLauncher(false)` + 立即抓帧 |

### 1.8 返回键处理

- **MainActivity 本身不处理返回键**；`onBackPressed` 未重写。
- 唯一的返回拦截在 `HomeScreen`：`BackHandler(enabled = showSettings) { showSettings = false }` —— 仅当设置面板打开时消费，其余情况交回系统（桌面通常无上一 Activity）。
- `SettingsScreen` 内另有 `IconButton` + `onBack = { showSettings = false }`。

### 1.9 边缘手势服务的启停（`startEdgeServiceIfNeeded`）

- 仅当 `edgeSwipeEnabled.value` 为 true 时执行。
- 若 `!isAccessibilityServiceEnabled()`：在 `Dispatchers.IO` 下检查 FIFO `/data/local/tmp/meego_cmd` 是否存在，若存在则 `FileOutputStream(fifo).use { it.write("ENABLE_A11Y\n".toByteArgs()); it.flush() }`。
- 随后 `startService(Intent(this, EdgeSwipeGestureService::class.java).apply { action = ACTION_ENABLE; putExtra(EXTRA_ENABLED, true) })`。
- Service 侧常量（`service/EdgeSwipeGestureService.kt`）：`ACTION_ENABLE = "com.meego.launcher.ACTION_ENABLE"`，`EXTRA_ENABLED = "extra_enabled"`，`dismissOverlay()` / `setInLauncher(Boolean)` / `setGestureEnabled(Context, Boolean)` 均为 `companion object` 静态方法。

### 1.10 日志字符串（全文件唯一一条）

```kotlin
Log.e("MainActivity", "Failed to send ENABLE_A11Y command", e)
```

- TAG 为**硬编码字面量 `"MainActivity"`**（无 `TAG` 常量），位于 L349，写 FIFO 失败时触发。
- `startEdgeServiceIfNeeded` 外层 `catch` 用 `e.printStackTrace()`，非 Log。
- **本文件无其他 `Log.*`、无 `println`**。

---

## 2. HomeScreen.kt（510 行）

### 2.1 命名常量（逐字引用）

| 常量 | 取值 | 用途 |
|---|---|---|
| `SCREEN_COUNT` | `3` | 三屏数 |
| `LOOP_COUNT` | `10000` | 循环圈数 |
| `TOTAL_PAGES` | `SCREEN_COUNT * LOOP_COUNT` = `30000` | Pager 总页数 |
| `INITIAL_PAGE` | `(LOOP_COUNT / 2) * SCREEN_COUNT + 1` = `15001` | 居中落在 Page 1（Application Grid） |
| `SNAP_DAMPING_RATIO` | `1.0f` | **临界阻尼**，零过冲 |
| `CRITICAL_SETTLE_FACTOR` | `5.0f` | 达到 ~95% 位移的 ωt 系数 |
| `MIN_SNAP_DURATION_SEC` | `0.15f` | 吸附收敛时长下界（秒） |
| `MAX_SNAP_DURATION_SEC` | `0.26f` | 吸附收敛时长上界（秒） |
| `DOT_TAP_SPEED_SCALE` | `0.85f` | 指示点跳转的时长缩放（略快一档） |
| `SWITCH_DISTANCE_FRACTION` | `0.12f` | 切屏距离阈值（屏宽比例；注释：由 0.18 下调） |
| `SWITCH_VELOCITY` | `400f` | 切屏速度阈值 px/s（注释：由 600 下调） |
| `MAX_SNAP_INITIAL_VELOCITY` | `6000f` | 注入吸附弹簧的初速度上限 px/s |

另有 Settings 覆盖层的两个内联字面量常量：弹簧 `spring(dampingRatio = 0.8f, stiffness = 400f)`（label `"settings_swipe"`）、收起阈值 `size.width / 3.5f`。

### 2.2 结构

| 成员 | 签名 | 职责 |
|---|---|---|
| private fun | `snapStiffnessForDistance(distanceFraction: Float, durationScale: Float = 1f): Float` | **行程自适应刚度**：`durationSec = (0.15f + (0.26f - 0.15f) * t) * durationScale`，`omega = 5.0f / durationSec`，返回 `omega * omega` |
| private suspend fun | `PagerState.snapToPageWithoutBounce(targetPage: Int, initialVelocity: Float = 0f, durationScale: Float = 1f)` | 零回弹吸附，见 §2.3 |
| @Composable | `HomeScreen(...)` | 根容器 |

`HomeScreen` 完整签名：

```kotlin
@Composable fun HomeScreen(
    apps: List<AppItem>, recentTasks: List<RecentTask>,
    gridColumns: Int, onSelectColumns: (Int) -> Unit,
    recentsColumns: Int = 2, onSelectRecentsColumns: (Int) -> Unit = {},
    edgeSwipeEnabled: Boolean, onToggleEdgeSwipe: (Boolean) -> Unit,
    immersiveUiEnabled: Boolean, onToggleImmersiveUi: (Boolean) -> Unit,
    taskProvider: RootTaskProvider,
    onTaskRemoved: (Int) -> Unit, onClearAllTasks: () -> Unit,
    targetPage: Int? = null, targetPageEvent: Long = 0L,
    modifier: Modifier = Modifier
)
```

内部状态：`coroutineScope`、`pagerState = rememberPagerState(initialPage = INITIAL_PAGE, pageCount = { TOTAL_PAGES })`、`showSettings`（`mutableStateOf`）、`scrollJob`（`mutableStateOf<Job?>`）、`edgeWidthPx = with(density) { 36.dp.toPx() }`、`touchSlop = viewConfig.touchSlop`、`currentScreen = Math.floorMod(pagerState.currentPage, SCREEN_COUNT)`。

### 2.3 三屏无限循环轮播机制

**① 无限循环的构造**
- 不做真正的 modulo 折叠，而是用**超长 Pager + 居中起点**制造「无限」观感：`TOTAL_PAGES = 30000` 页，起点 `INITIAL_PAGE = 15001`。`Math.floorMod(page, SCREEN_COUNT)` 把物理页号映射为 0/1/2 逻辑屏，因此永不触及边界。
- `HorizontalPager(state = pagerState, userScrollEnabled = false, beyondBoundsPageCount = 1, modifier = Modifier.fillMaxWidth().weight(1f))` —— **`userScrollEnabled = false`**，滚动完全由自研手势驱动。

**② 页面合成**
```
actualPage = Math.floorMod(page, SCREEN_COUNT)
0 -> FeedScreen()
1 -> AppGridScreen(apps, columns = gridColumns, onOpenSettings = { showSettings = true },
                   isVisible = (currentScreen == 1), showTitle = !immersiveUiEnabled)
2 -> MultitaskingScreen(tasks = recentTasks, taskProvider, onTaskRemoved,
                       onClearAll, columns = recentsColumns,
                       onColumnsChanged = onSelectRecentsColumns,
                       isVisible = (currentScreen == 2), showTitle = !immersiveUiEnabled)
```
外层 `CompositionLocalProvider(LocalOverscrollConfiguration provides null)` 关闭系统越界效果。

**③ 切屏手势（自研，非 Pager 内建）**
- `pointerInput(showSettings)`：**`showSettings` 为 true 时直接 `return@pointerInput`，设置面板打开期间完全禁用**。
- `awaitEachGesture` + `awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)`。
- 边缘判定：`isLeftEdge = startX <= edgeWidthPx`（**36dp**）、`isRightEdge = startX >= size.width - edgeWidthPx`；两者皆非则 `return@awaitEachGesture` —— **屏幕中央零阻断、零冲突**。
- 方向锁：位移超过 `touchSlop` 后，若 `absDx > absDy` **且**方向为由边缘向内（左边缘 `dx > 0` / 右边缘 `dx < 0`）→ `isEdgeHorizontalDrag = true` 并 `change.consume()`；否则置 `false` 并 `break`（垂直优先时坚决不拦截）。
- 拖拽阶段每帧：`pagerState.dispatchRawDelta(-deltaX)`。
- 抬手判定：`destinationPage` = 右边缘时 `totalDx < -threshold || velocity < -SWITCH_VELOCITY → startPage + 1`；左边缘时 `totalDx > threshold || velocity > SWITCH_VELOCITY → startPage - 1`；**距离与速度取「或」**。`threshold = size.width * SWITCH_DISTANCE_FRACTION`。
- 速度换算：`val pagerVelocity = -velocity`（Pager 正方向 = 内容向左 = 手指向左）。
- 每次手势先 `scrollJob?.cancel()`，抬手后 `coroutineScope.launch { pagerState.snapToPageWithoutBounce(targetPage = destinationPage, initialVelocity = pagerVelocity) }`。

**④ `snapToPageWithoutBounce`（切屏物理）**
- 目标位移：`targetPageOffset = targetPage - currentPage - currentPageOffsetFraction`（**与框架 1.6.2 `animateScrollToPage` 内部算法完全一致**），像素化 `targetPx = targetPageOffset * pageSizePx`。
- 初速度限幅：`initialVelocity.coerceIn(-MAX_SNAP_INITIAL_VELOCITY, MAX_SNAP_INITIAL_VELOCITY)`。
- 空转守卫：`abs(targetPageOffset) < 0.0001f && abs(clampedVelocity) < 0.01f` → 直接 return；`pageSizePx <= 0f` → return。
- 刚度：`snapStiffnessForDistance(distanceFraction = (abs(targetPageOffset) / pageSizePx).coerceIn(0f,1f), durationScale)`。
- 执行：`scroll { Animatable(0f) ... animateTo(targetPx, spring(dampingRatio = SNAP_DAMPING_RATIO, stiffness = stiffness), initialVelocity = clampedVelocity) { scrollBy(value - applied) } }`。
- **为何不用 `animateScrollToPage`**（L128–136 注释）：项目锁定 Compose foundation **1.6.2**，其 `animateScrollToPage(page, offset, animationSpec)` **不接受 `initialVelocity`**，松手瞬间速度被丢弃，画面脱离手指从 0 重启，表现为可见顿挫。
- **无过冲的数学依据**（L67–71 注释）：临界阻尼仅当归一化初速度 `v0/剩余行程 > ω=√stiffness` 时过冲；初速度上限 6000px/s、整屏行程 800~1100px，`6000/800 = 7.5 ≪ √stiffness(≈20~25)`，故**单调收敛、严格无过冲**。
- 三处切屏动画（边缘松手、指示点跳转、旧实现回退）**已统一**为该临界阻尼方案；旧实现三处均用欠阻尼（边缘 `spring(0.82f / 380f)`、指示点框架默认 `spring(0.9f / 400f)` 与 `tween(500ms)`），阻尼比 < 1.0 会冲过目标页并振荡，即肉眼可见的「回弹」。

**⑤ 指示点（底部 Nokia N9 经典三屏导航点）**
- 外层条件：**`if (!immersiveUiEnabled)`** —— 沉浸式开启时整行隐藏，让三屏内容真正铺满至屏幕底部（含导航条区）。
- 容器：`Row(modifier = Modifier.fillMaxWidth().padding(bottom = 30.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically)`。
- 每点：`Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clickable { ... }, contentAlignment = Center)`。
- 视觉：选中 `8.dp`，未选中 `5.dp`；`.clip(SquircleShape())`；背景选中 `MeeGoCyan`、未选中 `Color(0xFF444444)`。
- 点击跳转：同样走最短路 `diff`（`diff == 2 → -1`，`diff == -2 → 1`），但 `durationScale = DOT_TAP_SPEED_SCALE`（`0.85f`）、`initialVelocity` 默认 `0f`（纯命令式跳转）。

**⑥ `currentScreen` 同步到 EdgeSwipeGestureService（`SideEffect`）**
- `LaunchedEffect(currentScreen)`：**只写 `lastOrigin`**，且仅在 1（GRID）与 2（RECENTS）时写；**Feed（0）不写**。
- `SideEffect { LaunchOriginTracker.currentScreenIndex = currentScreen }` —— 每次重组提交后同步「用户此刻所在页序」。用 `SideEffect` 而非 `LaunchedEffect` 是因为它必须与本次提交同步、且不需异步等待。
- 语义区分（`model/Models.kt` L19–31 注释）：`lastOrigin` = 「应用从哪一屏被唤起」→ 决定**回溯到哪一页**；`currentScreenIndex` = 「用户此刻停在哪一屏」→ 决定**划出动画如何表现**。因 Feed 从不写 `lastOrigin`，若复用会在 Feed 上误判为 Grid 而错误加上层级缩放。
- 消费点（`service/EdgeSwipeGestureService.kt` L821）：`if (LaunchOriginTracker.currentScreenIndex != LaunchOrigin.GRID.pageIndex) { ... }` —— 仅当页序 == 1 时执行 `applyGridDepthScale()`，精确排除 Multitasking（2）与 Feed（0）。
- `LaunchOrigin` 枚举（`Models.kt`）：`GRID(1)`、`RECENTS(2)`，字段名 `pageIndex`。

**⑦ 外部定向回溯（`LaunchedEffect(targetPageEvent)`）**
- `if (targetPage != null && targetPage in 0..2)` 时算最短路 `diff` 并 `pagerState.scrollToPage(pagerState.currentPage + diff)` —— **瞬时定位、无动画**。
- 该状态由 `MainActivity.handleTargetPage` 写入：`targetPage.value = page`（默认 `1`）+ `targetPageEvent.longValue = System.currentTimeMillis()`，以时间戳充当重组 key 强制重触发。

**⑧ Settings 覆盖层**
- `AnimatedVisibility(visible = showSettings, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }, modifier = Modifier.fillMaxSize())`。
- 内部 `dragOffset`（`mutableFloatStateOf`）→ `animateFloatAsState(targetValue = dragOffset, spring(dampingRatio = 0.8f, stiffness = 400f), label = "settings_swipe")`。
- `graphicsLayer { translationX = animOffset; if (animOffset != 0f) { progress = (abs(animOffset)/1000f).coerceIn(0f,1f); scaleX = 1f - progress*0.08f; scaleY = 1f - progress*0.08f; shadowElevation = 20.dp.toPx() } }` —— **缩到 0.92 倍**，阴影 20dp。
- `.clip(SquircleShape(cornerRadius = if (animOffset != 0f) 28.dp else 0.dp))` —— 拖动中 28dp 圆角。
- 划出返回：仅当起点在左右 36dp 内（`onDragStart` 判定）才响应；`abs(dragOffset) > size.width / 3.5f` 关闭，否则归零；`onDragCancel` 直接归零。

### 2.4 ⛔ Quick Launch Bar —— 不存在

- HomeScreen 的 `Column` 只有两个子项：`HorizontalPager`（`weight(1f)`）与可选的指示点 `Row`。**没有 dock / 常驻底栏 / 快捷启动栏**。
- 全项目 `grep -rni "quicklaunch|quick launch|快捷"` 仅命中 FeedScreen 一条**文案**（`"截图快捷方式已放置到多任务屏常用区域。"`），无任何实现。
- HomeScreen **无 `@Composable` 内部子函数**，三屏编排完全靠 `when` + Pager。
- 结论：**应用入口仅存在于第 1 屏的 `LazyVerticalGrid` 内**（MeeGo Settings 置顶 + 全部已安装应用 + 20 个测试占位 App），不存在独立的「快捷启动栏」组件。

### 2.5 顶部安全区与沉浸式

- 沉浸式的「隐藏系统栏」在 **MainActivity**（`hideSystemBars`），HomeScreen 不直接控制 Window。
- 顶部安全间距由各屏的 `Modifier.meeGoTopSafeArea()` 承担（`ui/components/TopSafeArea.kt`）：`fun Modifier.meeGoTopSafeArea(minTop: Dp = 4.dp)` = `displayCutoutPadding().then(Modifier.padding(top = minTop))`。
- 理由（TopSafeArea 注释）：状态栏已真正隐藏，**真正需避让的只剩挖孔**；此前用 `statusBarsPadding()` 按 105px 避让，把首行图标推到 `y=117`（105px + Spacer 8dp=22px），顶部大片空白。Redmi K40 居中挖孔 `y=0..95`。
- 沉浸式 UI（`immersiveUiEnabled`）是**另一层**，只影响 Compose 内部：向 `AppGridScreen` / `MultitaskingScreen` 传 `showTitle = !immersiveUiEnabled`，并决定指示点整行是否渲染。

---

## 3. AppGridScreen.kt（380 行）

### 3.1 结构

| 成员 | 签名 | 职责 |
|---|---|---|
| @Composable | `AppGridScreen(apps: List<AppItem>, columns: Int, onOpenSettings: () -> Unit, isVisible: Boolean = true, showTitle: Boolean = true, modifier: Modifier = Modifier)` | 桌面主屏 |
| private class | `JobHolder` | 非快照 Job 持有器；`set(Job)` / `cancelAndClear()`。刻意不用 `mutableStateOf<Job?>`，避免每次抬手/落下写快照触发无效重组 |
| private data class | `GridMetrics(iconSize: Dp, fontSize: TextUnit)` | 列数 → 尺寸映射，`companion object { fun from(columns: Int) }` |
| @Composable private | `AppGridItem(app: AppItem, iconSize: Dp, fontSize: TextUnit, onClick: () -> Unit)` | 单个图标项 |
| private fun | `launchApp(context: Context, app: AppItem)` | 启动路径，见 §3.5 |

### 3.2 命名常量与全部字面量

| 位置 | 值 |
|---|---|
| `GridMetrics.from`：`4 ->` | `GridMetrics(62.dp, 12.sp)` |
| `GridMetrics.from`：`5 ->` | `GridMetrics(52.dp, 11.sp)` |
| `GridMetrics.from`：`else ->` | `GridMetrics(56.dp, 12.sp)` |
| `horizontalArrangement` | `Arrangement.spacedBy(12.dp)` |
| `verticalArrangement` | `Arrangement.spacedBy(18.dp)` |
| `contentPadding` | `PaddingValues(bottom = 8.dp)` —— **由 96dp 压缩至此** |
| Column `padding(horizontal = ...)` | `16.dp` |
| 标题行 `Spacer(height = ...)` | `16.dp`（顶部、底部各一） |
| 标题行 `padding(horizontal = ...)` | `8.dp` |
| 标题字号 / 列数字号 | `22.sp` / `13.sp` |
| 列数徽标 `padding(horizontal, vertical)` | `10.dp, 4.dp` |
| 标签 `Spacer(height = ...)` | `6.dp` |
| 图标回退渲染边长 | `AppIconRenderer.toImageBitmap(app.icon, 128)` —— **128** |
| 释放速度判定 | `abs(releaseVelocity) > 20f` 才起 fling |
| 拖拽中橡皮筋残余阈值 | `abs(leftoverFinger) > 0.01f` |
| fling 中橡皮筋残余阈值 | `abs(leftoverFinger) > 0.5f` |
| `key` / `contentType` | `it.gridKey` / `"app_item"` |

### 3.3 4/5 列切换

- 列数由 `columns: Int` 直通 `LazyVerticalGrid(columns = GridCells.Fixed(columns))` —— **切换即时生效，无动画过渡**。
- 尺寸映射收敛到 `val metrics = remember(columns) { GridMetrics.from(columns) }`，避免在每个 item 的组合中重复分支与装箱。
- 文案联动：标题行右侧徽标 `"${columns} 列"`（青色 `MeeGoCyan`，13sp，`DefaultSquircleShape` 裁形 + `MeeGoCardBg` 底），**点击即 `onOpenSettings()`** —— 这是设置页的两个入口之一。
- 持久化路径：`onSelectColumns` → `HomeScreen` → `MainActivity.onSelectColumns` → 立即更新 `gridColumns.intValue` + `lifecycleScope.launch { preferences.setGridColumns(cols) }`（DataStore）。**状态更新与持久化分离，UI 先行、落盘异步**。
- 预取说明（L84–88）：Compose 1.6.2 中 `LazyGridState.prefetchingEnabled` 为 `internal` 不可访问，反编译确认默认 true；`beyondBoundsItemCount` / `prefetchItemCount` 属 1.7+ API，本项目不适用。

### 3.4 MeeGo Settings 入口

- 注入点（`data/AppRepository.kt` L30–42）：`getInstalledApps()` **第一个**元素即
  `AppItem(packageName = ownPackageName, activityName = "MeeGoSettings", label = "MeeGo Settings", icon = R.mipmap.ic_launcher, iconBitmap = AppIconRenderer.toImageBitmap(...), isMeeGoSettings = true)`，
  故**永远位于网格首位**。
- 其余 App：`resolveInfos` 过滤掉自身包名，`loadIcon` + `AppIconRenderer.toImageBitmap` 预解码，`sortedBy { it.label.lowercase() }`。
- 末尾追加 **20 个** MeeGo 经典配色测试占位 App（`com.meego.launcher.test.app$numStr`，`isMeeGoSettings = true`）。
- 点击分派（`AppGridItem` 的 `onClick`）：
  ```
  if (app.isMeeGoSettings) onOpenSettings() else launchApp(context, app)
  ```
  即 **`isMeeGoSettings` 同时覆盖真实 MeeGo Settings 入口与 20 个测试占位**，全部走设置页。
- 真实设置为 `HomeScreen` 的 `showSettings = true`，再由 `AnimatedVisibility` 呈现 `SettingsScreen`。

### 3.5 `launchApp(context, app)` —— 启动路径

按顺序执行 5 件事：

1. `LaunchOriginTracker.lastOrigin = LaunchOrigin.GRID`
2. `LaunchOriginTracker.currentPackageName = app.packageName`
3. `LaunchOriginTracker.currentTaskId = null` —— 注释「关键：清空旧应用的 TaskId，绝不在新应用中串扰使用」
4. `TaskSnapshotCache.clearSwipeRecord()` —— 唤起新应用即刻作废上一次手势滑出的保序锁。注释解释：否则会复现「打开 D 再退出后顺序为 A D B C 而非 D A B C」
5. `(context as? Activity)?.let { ScreenCaptureHelper.captureLauncherScreen(activity, LaunchOrigin.GRID) }`
6. `context.packageManager.getLaunchIntentForPackage(app.packageName)`；非 null 时 `launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)` 并 `context.startActivity(launchIntent)`；`catch (e: Exception) { e.printStackTrace() }`

### 3.6 ⛔ 长按编辑模式 —— 不存在

- `AppGridItem` 的点击使用 `Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)` —— **无 `combinedClickable`、无 `onLongClick`、无 `onLongPress`**。
- 全项目 `grep "combinedClickable|longClickable|onLongClick|editMode|isEditing"` 仅命中 **`MultitaskingScreen.kt`**（L646 / L730 / L739-741），即长按编辑是**多任务屏**的能力，桌面网格没有。
- 补充：`SAMPLE_FEED_ITEMS` 中有一条文案「图标列数已更新为 4 列，长按桌面进入编辑模式。」属**演示用假数据**，与实际实现无关（长按桌面确实无此功能）。

### 3.7 图标渲染（`AppIconRenderer` 集成）

- **双路径**：`val iconBitmap = app.iconBitmap ?: if (app.icon != null) { remember(app.icon) { AppIconRenderer.toImageBitmap(app.icon, 128) } } else null`
  - 主路径：`AppRepository` 已预解码的 `ImageBitmap`，主线程零分配直绘。
  - 回退：Drawable 经 `AppIconRenderer` 剥离自适应图标 mask，以 128px 光栅化；`remember(app.icon)` 缓存。注释强调回退路径「同样经 AppIconRenderer 剥离系统 mask，与主路径保持同一形状来源，杜绝两条路径形状分裂」。
- 裁形：`Box(modifier = Modifier.size(iconSize).clip(DefaultSquircleShape).background(MeeGoCardBg), contentAlignment = Center)`，内部 `Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.fillMaxSize())`。
- **性能结论（L320–326，引用 docs/perf/perf_grid_after-opt_*.md）**：坚持 `Modifier.clip`（走 RenderNode clip 变换，近乎免费），**不用** `drawBehind { clipPath() }`。后者每项每帧产生真实 canvas clip，「Slow issue draw commands」由 **5 飙到 46**，jank **15.7% → 21.5%**。
- 标签：`Text(app.label, MeeGoTextWhite, fontSize, maxLines = 1, overflow = Ellipsis, textAlign = Center)`。
- `AppIconRenderer` 常量（`util/AppIconRenderer.kt`）：`const val DEFAULT_SIZE = 144`；`private const val ADAPTIVE_LAYER_SCALE = 1.5f`（对齐 AOSP `EXTRA_INSET_PERCENTAGE = 0.25` → 162/108 = 1.5，作用于**背景层与前景层整体**）。`toBitmap` 内 `try/catch` 兜底，单个图标异常返回 null 不拖垮整轮装载。

### 3.8 滚动（自研纵向手势）

**根因（L157–163 注释）**：`HorizontalPager` 多页共存（`beyondBoundsPageCount = 1`）时，切到 Feed 滚动后再切回本页，`LazyVerticalGrid` 内建 scrollable 的拖拽位移派发会失效（`LazyGridState.onScroll` 中待处理位移被丢弃且未 remeasure），表现为「列表不跟手、只有抬起手指后才滚动」。故在 `PointerEventPass.Initial` 抢先接管。

链路：
1. `awaitFirstDown(requireUnconsumed = false, pass = Initial)` → `flingJob.cancelAndClear()`。
2. 逐帧 `abs(totalDy) > touchSlop` 判定 `dragging`；`change.consume()` 后：
   - `forListFinger = bounceController.consumeOverscrollForDrag(dy)`（先消化已有越界拉伸）
   - `listDelta = -forListFinger`（**取反**：手指下移 dy>0 时 `dispatchRawDelta` 正值代表内容上移）
   - `consumed = gridState.dispatchRawDelta(listDelta)`
   - `leftoverFinger = -(listDelta - consumed)`；`abs > 0.01f` → `bounceController.pullOverscroll(leftoverFinger)`
3. 抬手：`releaseVelocity = velocityTracker.calculateVelocity().y`；`abs > 20f` 时 `flingVelocity = -releaseVelocity`，在 `flingJob` 中建匿名 `ScrollScope`，其 `scrollBy` 内同样把未消费部分（`abs > 0.5f`）交给 `pullOverscroll`；`val leftVelocity = with(flingBehavior) { sink.performFling(flingVelocity) }` 后 `bounceController.releaseOverscroll(-leftVelocity)`。否则直接 `releaseOverscroll(releaseVelocity)`。
4. **越界裁剪顺序不可调换**（L144–152）：`.clipToBounds()` **必须在** `.graphicsLayer { translationY = bounceController.overscrollOffset }` **外层**。原因：`graphicsLayer` 在外会把 clip 一起平移等于没裁剪。实测（1080x2400 / 440dpi）静止时首行图标顶边 `y=281`、标题带 `y=149..236`，留白仅 45px；触底上拉越界整层上移可达 **190px**，无裁剪则直接盖住 "Applications" 标题整行。
5. `LaunchedEffect(isVisible) { if (!isVisible) { flingJob.cancelAndClear(); bounceController.reset() } }` —— 切走时复位。

滚动物理参数（`ui/components/MeeGoScrollPhysics.kt` 的 `ScrollPhysicsConfig`，**默认值与优化前逐位一致**）：

| 参数 | 默认值 |
|---|---|
| `lightFlingGain` | `1.35f` |
| `mediumFlingGain` | `1.70f` |
| `highFlingGain` | `1.85f` |
| `lightFlingVelocityThreshold` | `1000f` |
| `mediumFlingVelocityThreshold` | `3500f` |
| `minFlingVelocity` | `1f` |
| `flingBoundaryEpsilon` | `0.5f` |
| `maxOverscrollDp` | `90.dp` |
| `overscrollDamping` | `0.42f` |
| `overscrollDampingMin` | `0.12f` |
| `overscrollDampingFalloff` | `0.65f` |
| `impactVelocityThreshold` | `120f` |
| `maxReboundInitialVelocity` | `8000f` |
| `reboundDampingRatio` | `0.78f` |
| `reboundStiffness` | `380f` |
| `postFlingReboundStiffness` | `360f` |

`MeeGoSmoothFlingBehavior.performFling`：按 `|v|` 阶梯取增益（`<1000 → 1.35f`，`<3500 → 1.70f`，否则 `1.85f`），再 `animateDecay(splineDecay)`；`abs(delta - consumed) > flingBoundaryEpsilon` 即 `cancelAnimation()` 并把剩余 `velocityLeft` 交回调用方驱动回弹。阻尼 `damping = (0.42f * (1f - ratio*0.65f)).coerceIn(0.12f, 0.42f)`。

---

## 4. FeedScreen.kt（396 行）

### 4.1 结构

| 成员 | 签名 | 职责 |
|---|---|---|
| @Composable | `FeedScreen(modifier: Modifier = Modifier)` | 第 0 屏，**无参数、无回调** |
| private class | `FeedJobHolder` | 同 `JobHolder`，非快照 Job 持有器 |
| @Composable private | `SectionHeader(text: String)` | 分节标题 |
| @Composable private | `FeedCard(title: String, body: String, time: String, accent: Color = MeeGoCyan)` | 通知卡片 |
| private data class | `FeedSample(title, body, time, accent = MeeGoCyan)` | 样例条目 |
| private val | `SAMPLE_FEED_ITEMS: List<FeedSample>` | **12 条** |
| private val | `SAMPLE_FEED_ARCHIVE: List<FeedSample>` | **4 条** |

### 4.2 数据内容（问题 6 的直接回答）

- **时间：真实且每秒刷新**。`LaunchedEffect(Unit)` 内 `while (true) { currentTime = timeFormat.format(Date()); currentDate = dateFormat.format(Date()); delay(1000) }`
  - `timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())`
  - `dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH)`
  - 占位符：`currentTime.ifEmpty { "19:00" }`、`currentDate.ifEmpty { "Tuesday, September 22" }`
- **通知/Feed：全部为硬编码 MOCK 数据，无任何真实通知源**。源码 L209 明示：「以下为滚动测试用样例数据（MOCK），接入真实通知源后可整段删除」。
  - 分区：小标题 `"NOTIFICATIONS & FEEDS"` → 2 张置顶硬编码卡 → `SectionHeader("TODAY")` + `SAMPLE_FEED_ITEMS`（12 条）→ `SectionHeader("EARLIER")` + `SAMPLE_FEED_ARCHIVE`（4 条）。
  - 两张置顶卡：① `"MeeGo Launcher v1.3.0"` / `"Nokia N9 体验已点亮。支持 4/5 列排版与双指 2x2/3x3 缩放。"` / `"Just now"` / `MeeGoCyan`；② `"Root Privileges Active"` / `"后台卡片管理通道已建立，支持实时秒切与清理。"` / `"System"` / `MeeGoRed`。
  - 样例条目涵盖天气（`"Weather Alert: Rain expected tonight"`，78% 降水概率）、日历（`"Calendar: Design Review"`）、电池、存储、同步、帧预算（`"Frame Budget: 8.26ms"`，P50 7.17ms / P95 8.09ms）等，**均为演示文案**。
- **天气：没有真实天气数据**，仅有一条静态 MOCK 条目。

### 4.3 布局与尺寸常量

| 项 | 值 |
|---|---|
| 大字时钟 | `fontSize = 58.sp`, `letterSpacing = (-1).sp`, `MeeGoTextWhite` |
| 日期 | `fontSize = 17.sp`, `MeeGoCyan` |
| `"NOTIFICATIONS & FEEDS"` | `12.sp`, `MeeGoCyan`, `padding(bottom = 12.dp)` |
| 内容横向内边距 | `20.dp` |
| 顶部 Spacer | `28.dp` |
| 时钟↔日期↔列表 | `Spacer(36.dp)` |
| 置顶卡间距 | `Spacer(12.dp)`，第二张后 `Spacer(20.dp)` |
| 列表末 | `Spacer(20.dp)`，页尾 `Spacer(36.dp)` |
| 卡片 | `.clip(SquircleShape(cornerRadius = 16.dp)).background(MeeGoCardBg).padding(16.dp)` |
| 卡片内 | 圆点 `8.dp` + `SquircleShape(cornerRadius = 4.dp)`；`Spacer(width = 8.dp)`；标题 `15.sp` + `weight(1f)`；时间 `12.sp`；正文前 `Spacer(6.dp)`，正文 `13.sp` |
| `SectionHeader` | `MeeGoTextDim`, `12.sp`, `letterSpacing = 2.sp` |

### 4.4 滚动物理（与 AppGridScreen 同源，逐行等价）

- `rememberScrollState()` + `rememberMeeGoFlingBehavior()` + `rememberMeeGoBounceController()` + `FeedJobHolder`。
- 外层 `Box(Modifier.fillMaxSize().meeGoTopSafeArea())`，内层 `Box(Modifier.fillMaxSize().padding(horizontal = 20.dp).clipToBounds().graphicsLayer { translationY = bounceController.overscrollOffset }.pointerInput(Unit) { ... })` —— **`.clipToBounds()` 同样在 `graphicsLayer` 外层**。
- 手势实现与 AppGridScreen 完全同构，仅 `gridState` 换成 `scrollState`：`consumeOverscrollForDrag(dy)` → `listDelta = -forListFinger` → `scrollState.dispatchRawDelta(listDelta)` → `leftoverFinger = -(listDelta - consumed)`（阈值 `0.01f` 拖拽 / `0.5f` fling）。
- 释放：`abs(releaseVelocity) > 20f` 才起 fling，`flingVelocity = -releaseVelocity`，`performFling` 后 `releaseOverscroll(-leftVelocity)`。
- **根因注释（L93–99）**：本屏此前用框架内建 `verticalScroll(scrollState)`，在 `beyondBoundsPageCount = 1` 场景下事件被消费但 `scrollState.value` 不推进，表现为「列表不跟手、只有抬起手指后才滚动」，且完全没有触顶/触底回弹。
- 物理参数全部复用 `ScrollPhysicsConfig.Default`（见 §3.8 表）。
- `Column(Modifier.fillMaxSize().verticalScroll(scrollState))` 内部内容；`CompositionLocalProvider(LocalOverscrollConfiguration provides null)` 关闭越界效果。
- **注意**：内部 `Column` 仍保留 `verticalScroll(scrollState)` 以提供可测量内容高度，实际位移由自研手势驱动。
- FeedScreen **不消费** `isVisible`（无 `LaunchedEffect(isVisible)`），与 AppGridScreen 的复位逻辑不同。

---

## 5. SettingsScreen.kt（249 行）

### 5.1 结构

| 成员 | 签名 |
|---|---|
| @Composable | `SettingsScreen(currentColumns: Int, onSelectColumns: (Int) -> Unit, edgeSwipeEnabled: Boolean, onToggleEdgeSwipe: (Boolean) -> Unit, immersiveUiEnabled: Boolean, onToggleImmersiveUi: (Boolean) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier)` |
| @Composable private | `SettingRadioItem(title: String, description: String, isSelected: Boolean, onClick: () -> Unit)` |
| @Composable private | `SettingSwitchItem(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit)` |
| @Composable private | `InfoRow(key: String, value: String)` |

**纯受控组件（fully controlled）**：本文件不读写任何持久化层，所有状态由 `MainActivity` 的 `mutableStateOf`/`mutableIntStateOf` 持有，回调上抛后由 `MainActivity` 落 DataStore。

### 5.2 四个分组

| # | 分组标题（`MeeGoCyan`, 13sp） | 内容 |
|---|---|---|
| 1 | `"APPLICATION GRID 图标列数"` | 两个 `SettingRadioItem`（4 列 / 5 列），中间 `Divider(color = Color(0xFF2C2C2C), thickness = 0.5.dp)` |
| 2 | `"DISPLAY 显示与沉浸式"` | `SettingSwitchItem`「沉浸式 UI (隐藏标题与翻页点)」 |
| 3 | `"ORIGIN-AWARE SWIPE 边缘手势"` | `SettingSwitchItem`「全屏边缘侧滑切回」 |
| 4 | `"SYSTEM & HARDWARE"` | 4 行 `InfoRow`（硬编码，不可改） |

- **网格列数设置**：`isSelected = currentColumns == 4` / `== 5`；点击 → `onSelectColumns(4)` / `onSelectColumns(5)`；选中时右侧显示 `Icons.Default.Check`（`contentDescription = "Selected"`，`MeeGoCyan`，`size(20.dp)`）。
  - 文案：`"4 列图标 (经典 Nokia N9 比例)"` / `"舒展大气呼吸感，适合经典视效体验"`；`"5 列图标 (现代全面屏高密紧凑)"` / `"充分利用 20:9 物理长屏纵深，单屏展示更多应用"`。
  - **只有 4 / 5 两个选项**；`GridMetrics.from` 的 `else -> GridMetrics(56.dp, 12.sp)` 仅为防御性兜底，无 UI 入口。
- **沉浸式 UI 开关**：`title = "沉浸式 UI (隐藏标题与翻页点)"`，`description = "隐藏应用网格 / 多任务视图的顶部标题栏，以及底部三屏翻页指示点，让内容铺满全屏"`。
- **边缘划回开关**：`title = "全屏边缘侧滑切回"`，`description = "从任意 App 屏幕左右边缘划入 1/2 松手，切回启动源页面"`。
- **系统信息**：`("设备机型", "Redmi K40 / POCO F3 (alioth)")`、`("操作系统", "LineageOS (Android 16)")`、`("ADB 状态", "Root Privileges (Active)")`、`("安全模式", "独立组件调试（未破坏原生桌面）")`。
- 顶部栏：`IconButton` + `Icons.Default.ArrowBack`（`contentDescription = "Back"`, `MeeGoTextWhite`）+ `Spacer(8.dp)` + `"MeeGo Settings"`（`22.sp`）。
- 布局：`Column(Modifier.fillMaxSize().meeGoTopSafeArea().padding(16.dp).verticalScroll(rememberScrollState()))`。
- 分组间距：首组后 `Spacer(28.dp)`，组内 `Spacer(8.dp)`，InfoRow 间 `Spacer(8.dp)`。
- 卡片容器：`.clip(SquircleShape(cornerRadius = 16.dp)).background(MeeGoCardBg)`，含 Switch/InfoRow 的组再加 `.padding(16.dp)`。
- 字号：`SettingRadioItem` / `SettingSwitchItem` 主文本 `16.sp`、描述分别为 `12.sp` / `13.sp`；`InfoRow` 两侧均 `14.sp`。
- Switch 配色：`SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MeeGoCyan)`。
- **SettingsScreen 内部不拦截返回键**，返回有三条路径：① 顶部 `IconButton` → `onBack`；② `HomeScreen` 的 `BackHandler(enabled = showSettings)`；③ 覆盖层的边缘横滑（`> size.width / 3.5f`）。

### 5.3 持久化（`data/LauncherPreferences.kt`）

**DataStore Preferences**，文件 `name = "meego_settings"`（`val Context.dataStore by preferencesDataStore(name = "meego_settings")`）。

| Key 常量 | 类型 | 字符串名 | 默认值 | Flow 属性 | setter |
|---|---|---|---|---|---|
| `KEY_GRID_COLUMNS` | `intPreferencesKey` | `"grid_columns"` | **`4`** | `gridColumnsFlow` | `setGridColumns(columns: Int)` |
| `KEY_RECENTS_COLUMNS` | `intPreferencesKey` | `"recents_columns"` | **`2`** | `recentsColumnsFlow` | `setRecentsColumns(columns: Int)` |
| `KEY_EDGE_SWIPE` | `booleanPreferencesKey` | `"edge_swipe"` | **`true`** | `edgeSwipeFlow` | `setEdgeSwipe(enabled: Boolean)` |
| `KEY_IMMERSIVE_UI` | `booleanPreferencesKey` | `"immersive_ui"` | **`false`** | `immersiveUiFlow` | `setImmersiveUi(enabled: Boolean)` |

- 每个 setter 均为 `suspend fun`，内部 `context.dataStore.edit { preferences -> preferences[KEY] = value }`。
- `onCreate` 中用 `.first()` 各取一次填充状态（**非持续订阅**，进程内改动靠 Compose state 驱动）。
- `recentsColumns` 有持久化与 `MultitaskingScreen` 的 `onColumnsChanged` 通路，但 **`SettingsScreen` 未暴露该选项**（当前无 UI 入口，仅多任务屏自身可改）。
- `immersiveUiFlow` 注释说明默认 `false` 的理由：「避免升级后用户界面被意外改变」。

---

## 6. 全局补充（跨文件事实）

### 6.1 Manifest（`app/src/main/AndroidManifest.xml`）

- `MainActivity`：`android:launchMode="singleTask"`（**解释 `onNewIntent` 复用**）、`stateNotNeeded="true"`、`windowSoftInputMode="adjustNothing"`、`exported="true"`。
- 双 intent-filter：标准 `MAIN`+`LAUNCHER`（可从原桌面点起调试）+ `MAIN`+`HOME`+`DEFAULT`（注册为桌面）。
- 权限：`QUERY_ALL_PACKAGES`、`VIBRATE`、`SYSTEM_ALERT_WINDOW`、`KILL_BACKGROUND_PROCESSES`、`REORDER_TASKS`、`PACKAGE_USAGE_STATS`、`RECEIVE_BOOT_COMPLETED`、`DUMP`。
- 服务 `EdgeSwipeGestureService`（`BIND_ACCESSIBILITY_SERVICE`）、接收器 `BootReceiver`（`BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`）。

### 6.2 `TaskSnapshotCache` 保序锁（`model/Models.kt`）

- `private const val SWIPE_PIN_VALIDITY_MS = 5_000L` —— 滑出保序锁有效期 **5s**（守护进程 `meego_daemon.sh` 主循环 `sleep 2.0`，即 2s 轮询 + 落盘开销，故取 5s）。
- 三个 `@Synchronized` 出口：`recordSwipe(taskId, packageName, bitmap)`（`taskId` 非正则置 null、不产生置顶锁）、`activeSwipe()`（`elapsed in 0 until SWIPE_PIN_VALIDITY_MS`，**时钟回拨 `elapsed < 0` 保守判失效**）、`clearSwipeRecord()`。
- 另有 `put` / `get` / `getByPackage` / `getEntry` / `remove`（`remove` 时若命中 `lastSwipe` 顺带 `clearSwipeRecord()`）/ `clear`。

### 6.3 本次覆盖的日志字符串清单

五个文件中**仅一条** `Log.*`：

```kotlin
Log.e("MainActivity", "Failed to send ENABLE_A11Y command", e)   // MainActivity.kt:349
```

其余错误处理均为 `e.printStackTrace()`（`MainActivity.startEdgeServiceIfNeeded` 外层 catch、`AppGridScreen.launchApp` catch）。`FeedScreen.kt` 与 `SettingsScreen.kt` **无任何日志**。相邻 Service 侧（非本次范围）另有 `Log.d(TAG, "setGestureEnabled: $enabled")` 与 `Log.w(TAG, "Failed to startService with enable/disable action", e)`。
