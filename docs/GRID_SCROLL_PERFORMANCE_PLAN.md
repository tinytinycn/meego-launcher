# Application Grid 滑动流畅度提升方案：对比 Trebuchet（LineageOS 23 / Android 16）应用抽屉

> 状态：**方案（plan），不写功能代码**。待批准后在后续实现轮次逐步落地。
> 涉及文件均位于 `/Users/tinytinycn/dsh-projects/test-meego-launcher/`（下称“本项目”）。

---

## 1. 目标与成功标准

**目标**：让本项目 Application Grid（`AppGridScreen.kt` 的 `LazyVerticalGrid`）在 120Hz 真机（Redmi K40、Android 16）上的纵向滑动流畅度达到/接近 LineageOS 23.2（Android 16）Trebuchet 应用抽屉（`AllAppsRecyclerView` + `GridLayoutManager`）的水准，同时**保留**本项目已实现的 MeeGo 橡皮筋回弹、自适应增益 Fling 等特色物理手感。

**成功标准（可测量）**：
1. 真机 `dumpsys gfxinfo <pkg> reset` 后快速往返滚动（Recorded frames），**帧时长 P95 ≤ 12ms、P99 ≤ 16ms、jank 比例 ≤ 3%**（Trebuchet 同机同法作对照基准）。
2. `LazyVerticalGrid` **组合同步成本**（first composition）控制在主线程单帧预算内：预测量 ≤ 40ms，且滚动过程中 `Compose:onSubmitNodeChanges` / `measure` 不出现成簇掉帧。
3. 覆盖以下回归场景：4/5 列切换、Feed→Grid 往返滚动、Grid 触顶/触底橡皮筋、快速 fling 穿屏、120Hz 下大行程滑动，均不掉帧。
4. 特性不变：MeeGo 回弹、Fling 增益、Squircle 外观、长按/点击交互语义。

**非目标（本方案不承诺）**：图标加载网络拉取、App 标签异步分布截图、横屏适配。全部为纵向 20:9 竖屏场景。

---

## 2. 结论先行：为什么本项目滑动"看起来"不如 Trebuchet

通过静态分析与源码对照（Trebuchet 侧读取 LineageOS `lineage-23.2` 分支真实源码，本项目侧逐文件审读），本项目与 Trebuchet 在**滚动流畅度**上的差距不是某一个 bug，而是**五类系统性的架构差异**叠加造成的：

| # | 维度 | Trebuchet（Launcher3 / LineageOS 23） | 本项目（Compose MeeGo Launcher） | 影响方向 |
|---|------|--------------------------------------|----------------------------------|----------|
| 1 | **渲染管线** | 原生 View 体系，RecyclerView 视图可直接绘制图标位图纹理（`FastBitmapDrawable`），几乎没有重组概念 | Compose UI + **每次滚动手势都触发外层 CompositionLocal / Box 重组**；`AppGridItem` 虽做了 `@Immutable` 优化，但列表参数仍是被 `remember` 的 `List` | **主线程重组成本**是 Compose 独占的开销，Trebuchet 没有 |
| 2 | **图标解码/绘制模型** | 图标加载走 `IconCache`，`FastBitmapDrawable` 由 GPU 直接采样位图，绘制过程不分配对象、不遍历 drawable tree | 图标已在 `Dispatchers.IO` 预解码为 `ImageBitmap`（好），但 `AppGridItem` 中 **Column 内多子节点 + Squircle clip + Text 合成**，每项仍要逐次 clip/measure/text layout | 每帧 GPU 绘制指令数与合成开销高于 Trebuchet |
| 3 | **列表布局成本** | `GridLayoutManager` 精确定位、`incrementTotalHeight` 缓存行高，滚动只做增量 layout/draw | `LazyVerticalGrid` 在 30+ 应用时每帧重建可见项，`SquircleShape.createOutline` 虽缓存了特定尺寸，但**遍历 72 点 Path** 仍在首次测量发生 | 滚动时偶发 **measure/layout 峰值** |
| 4 | **预取与帧调度** | RecyclerView `GapWorker` 预取下一屏可见项，配合 `RecyclerView` 内置 prefetch 管线 | `LazyVerticalGrid` 默认 prefetch offset 小（Compose 1.6 默认），且本项目在 `HorizontalPager(beyondBoundsPageCount=1)` 多页共存场景下，快速 fling 时可能出现**预取不足 → 边界抖动** | 快速滑动的**火焰式抖动/丢帧** |
| 5 | **手势/物理引擎** | RecyclerView 默认 fling 与 `OverScroller`，阻尼模型成熟 | 本项目为达成 MeeGo 手感，`dispatchRawDelta` + 自定义 Fling 增益（1.35x~1.85x）+ 阻尼回弹，**大幅延长滑行行程、降低减速**，行程变长 = 同样滑行时间更长、更易暴露中间任何一帧的掉帧 | 感知上的 "不够跟手/不够顺" 部分源于**过长的惯性行程**放大了掉帧 |

**关键结论**：本项目并非"卡死"，而是**惯性滑行更长（自研高增益 Fling）** + **Compose 重组与绘制开销** + **预取不足**共同作用，在 120Hz 上少数掉帧被放大。Trebuchet 的"丝滑"来自其**无重组开销的 View 架构 + 更短默认行程 + 原生预取**。

---

## 3. 技术方案总览（按子系统分档）

### 3.1 【渲染/重组】Application Grid 重组成本压缩（核心）

**现状**：
- `AppGridScreen` 接收 `apps: List<AppItem>`，`AppItem` 已 `@Immutable`，含 `iconBitmap: ImageBitmap`。这已避免"图标每帧重绘"，是好基础。
- 但 `AppGridScreen` 里仍有几个会在滚动时反复触发的重组源：`LaunchedEffect(isVisible)`、`bounceController.overscrollOffset`（`graphicsLayer.translationY`）、以及 `AppGridItem` 内 `Column{Box{Image}+Text}` 的多层布局。

**方案（均为低风险、无外部依赖）**：
1. **强制列表整体跳过重组**：在 `LazyVerticalGrid` 的 `items(...) { AppGridItem(...) }` 外层不引入其他状态；把 `apps`、`iconSize`、`fontSize`、`onClick` 用 `remember/apps` 稳定引用，确保滚动时 `apps` 引用不变 → Compose 会跳过整棵 item 重组（它们的 lambda 已带正确的 state 捕获）。
   - 具体实现：把 `iconSize`/`fontSize` 计算从每次组合中依赖外部 `columns` 的地方收敛进 `remember(columns)`；将 `AppGridItem` 内 `remember(app)` 的 fallback 位图逻辑改为只对 `icon` 依赖（`icon==null` 时不用）。
2. **`AppGridItem` 拆分**：将图标绘制层（`Image(bitmap)`)与文字层（`Text`）拆成两个独立 composable（`GridIcon` / `GridLabel`），使文字重排时图标层不重绘。或更直接：保留单 composable，但在其中用 `derivedStateOf` 只订阅真正会变化的量。
3. **`statusBarsPadding` / `weight(1f)` 产生的 measure 周期**：确认 `Box(weight(1f))` 与其 `LazyVerticalGrid.fillMaxSize()` 不产生额外 remeasure（当前已是标准写法，仅做确认）。

**收益**：显著降低每帧 Compose 主线程成本；是"从 View 级贴到 Compose 可接受"的关键一步。

### 3.2 【图标绘制】GPU 直绘与绘制指令精简（对齐 Trebuchet 图标管线）

**现状**：`AppGridItem.kt` 用 `clip(DefaultSquircleShape).background(MeeGoCardBg)` + `Image(bitmap)`。每项：
- 一次 `clip`（Squircle Outline 产生 path）→ 每项每帧进 GPU 一次 clip+draw。
- `background` 一层 + Image 一层，共 2 层绘制/项。

**方案（对齐 Trebuchet `FastBitmapDrawable` 直绘思想）**：
1. **小幅优化绘制层数**：`MeeGoCardBg` 为纯色 `#1E1E1E`，可移除 `background`，让 Squircle clip 只作用在 Image 上（或保留背景但用 `Modifier.drawBehind` 一次性绘制底色+裁剪为一次 `draw()`），把"每项 2 次 draw"降到"1 次 draw"。
2. **Squircle clip 成本**：当前 `clip(DefaultSquircleShape)` 每项每次布局都查 Outline 缓存。项目已实现 `(width,height,density)` 维度缓存（`SquircleShape.createOutline` 缓存命中路径），滚动时图标尺寸不变 → 该项应已命中缓存。**验证并确保缓存被真正命中**——若未命中，将局部缓存提升为**全局共享 Outline 缓存**（`ConcurrentHashMap` keyed by (n, cornerRadius, widthPx, heightPx, density)），进一步降低首次测量成本。
3. **不做**：不引入第三方图标库、不改成 Canvas 级，保持现有 `ImageBitmap` 直绘（这已与 Trebuchet 的位图纹理直绘同源）。

**收益**：每帧绘制指令数与 clip/measure 峰值下降，与 Trebuchet 的"单 drawable 单 draw"路径更接近。

### 3.3 【列表预取】滚动预取与边界稳定性（对齐 RecyclerView GapWorker）

**现状**：
- `LazyVerticalGrid` 默认预取尺寸来自 Compose 1.6 默认参数（约 1 屏）。在 `HorizontalPager(beyondBoundsPageCount=1)` + 相邻 Feed 曾滚动的场景，快速 fling 时可能出现预取不足，触达可滚动范围边缘时被迫同步 measure → 抖动。
- 本项目又借 `dispatchRawDelta` 自接管手势，绕过了内建 scrollable 的部分协调逻辑，预取调度没有随手势协调（内建 scrollable 会调 `onPreScroll`/`onPostScroll` 联动 prefetch）。

**方案**：
1. **提高预取范围**：给 `LazyVerticalGrid` 的 `state` 设置 `prefetchStrategy`（Compose 1.6+ 支持 `LazyGridPrefetchStrategy`），或通过 `LazyGridState` 的 `beyondBoundsItemCount`/`prefetchItemCount`（Compose 1.7）显式增加滚动方向的预取行数（建议 3~5 行）。Compose BOM 2024.02.01 → foundation 1.6.x，此方案需确认可用 API 并可能小幅升级 foundation 到 1.7.x（见 §4.3 兼容性）。
2. **fling 期间主动预取**：在自研 fling 协程（`MeeGoScrollPhysics.performFling`）的 `scrollBy` 内，通过 `gridState` 滚动方向估算可见边界，主动触发 `gridState.requestScrollToItem` 前预取（或仅在 `onScroll` 回调中依赖框架即可 —— 优先确认默认行为，避免重复造轮子）。
3. **事件驱动校正**：确保 `isVisible` 从 false 变 true（切回 Grid 页）时，立即以 `snapshotFlow` 触发一次 `gridState.scrollToItem` 校正，避免"切回后首次滑动奇偶错位"。

**收益**：消除快速 fling 的边界抖帧；这是对齐 Trebuchet `GapWorker` 的关键。

### 3.4 【手势/物理】保留 MeeGo 手感的参数化调优

**现状**：本项目 `MeeGoSmoothFlingBehavior` 增益 1.35x/1.70x/1.85x 显著长于原生行程；`MeeGoBounceScrollController` 回弹用弹簧。Trebuchet 默认行程约 0.3~0.5 屏（默认摩擦），滑行更"快收"。

**方案（不改手感模型，只调参数 + 加开关）**：
1. **参数下放到可配置**：把增益系数、摩擦（decay）、回弹阻尼/刚度抽到 `LauncherPreferences` 或 `MeeGoScrollPhysics` 顶部常量（少量），**默认保持现有手感**，但允许 A/B 验证"降低增益 → 更接近 Trebuchet 的短行程"的观感对比。
2. **避免过度行程淹没掉帧**：为 120Hz 场景提供"增益=1.0x + 原生摩擦"的对照档（开发用常量或隐藏项），用于判断"滑不动/阻滞感"到底来自摩擦还是来自掉帧。
3. **确认 `performFling` 的 `cancelAnimation` 边界逻辑无帧级泄漏**：当 `scrollBy` 消费不足时 `cancelAnimation()` 退出，`velocityLeft` 交给 `releaseOverscroll`。当前逻辑正确，仅做验证与注释加固；不改变行为。

> 注意：这一档与 §3.5 的性能预算正交，不应与"卡顿"诊断混淆 —— 延长行程只是暴露掉帧，不是制造掉帧的根因（根因是 §3.1~3.3）。

### 3.5 【测量/数据】建立可复现的流畅度基准

**方案**：
1. **真机基准脚本**（新增 `scripts/perf_grid.sh`，可执行，纯工具，不写业务），封装：
   - `adb shell settings put global animator_duration_scale 1`（保持系统动画，不关）
   - `adb shell dumpsys gfxinfo com.meego.launcher reset` → 记录 baseline
   - 用 `adb shell input swipe` 驱动 N 次往返滚动（同一设备、同一手势参数），`dumpsys gfxinfo` 输出 `Total frames / Janky frames / 90th / 95th / 99th percentiles`
   - 对 Trebuchet（`com.android.launcher3` 或 LineageOS 的 Trebuchet 包名）跑**同一套命令**作对照基线。
   - 输出 markdown 表格：帧数、jank、P90/P95/P99、frame stats。
2. **Compose 侧诊断**：滚动中抓 `logcat` 里 `Compose` 的 `onSubmitNodeChanges` / `measureCost` / `recomposition` 计时（如有埋点），或直接读 `Choreographer` 帧统计；作为掉帧来源的旁证。
3. **截图/录屏**：`scripts/screenshot.sh` 已存在，滚动中抓图做人工肉眼 ABC 对比（与 Trebuchet 同场景截屏摆一起看）。

> 需要真机在线（当前 `adb devices` 显示离线），故测量阶段把设备接上即可执行；不接设备也能先完成代码侧优化。

---

## 4. 实施步骤（按依赖顺序）

| 步骤 | 改动 | 涉及文件 | 验收 |
|---|---|---|---|
| 1 | **建立基准**：`scripts/perf_grid.sh`（Trebuchet 对照 + 本项目）| 新增 | 能输出可比 jank/P95/P99 |
| 2 | **§3.1 重组压缩**：稳定 `apps`/尺寸参数引用、拆分 `GridIcon`/`GridLabel`、移除冗余 `background` | `AppGridScreen.kt` | 主线程组合成本下降、滚动帧平滑 |
| 3 | **§3.2 绘制精简**：`drawBehind` 一次绘制、全局 Outline 缓存 | `AppGridScreen.kt`、`SquircleShape.kt` | 每项 draw 次数 2→1，clip 峰值下降 |
| 4 | **§3.3 预取增强**：`prefetch` 行数、切页校正 | `AppGridScreen.kt` | 快速 fling 边界无抖帧 |
| 5 | **§3.4 物理参数化**：增益/摩擦/回弹参数常量化 + A/B 对照档 | `MeeGoScrollPhysics.kt`、`LauncherPreferences.kt`、`SettingsScreen.kt`（可选隐藏项）| 手感不变；A/B 对比可执行 |
| 6 | **测量验证**：真机跑 `perf_grid.sh`，分数达标；回归 4/5 列、Feed↔Grid、橡皮筋、120Hz 大行程 | — | §1 成功标准全达标 |
| 7 | **文档同步**：`MEEGO_LAUNCHER_TECH_SPEC.md` 追加本方案与实测数据 | `MEEGO_LAUNCHER_TECH_SPEC.md` | 文档与实机一致 |

> 步骤 2/3 可合并为一个实现轮次；步骤 4/5 各自独立。全部可独立提交。

---

## 5. 公共 API / 数据结构 / 数据流变更

- **无外部 API 变更**：均为模块内私有实现；不新增第三方依赖（Compose BOM/foundation 升级除外，见 §4.3）。
- **`LauncherPreferences`**：新增可选的滚动物理偏好键（`scroll_fling_gain` 等，默认保持现状值；若仅做 A/B 诊断可用常量替代，则**不引入**用户可见设置项，避免改动设置 UI）。
- **`MeeGoScrollPhysics.kt`**：新增 `ScrollPhysicsConfig`/常量集，供 A/B 对照；行为默认不变。
- **数据流**：无新增异步加载；图标仍走 `AppRepository` 的 `Dispatchers.IO` 预解码 → `ImageBitmap`。

---

## 6. 边界情况与失败模式

1. **`prefetch` 增强与自接管手势冲突**：若 `dispatchRawDelta` 路径绕过 prefetch，预取增强无效 → 需在 fling `scrollBy` 中显式触发，或改用 Compose 1.7 的 `requestScrollToItem` + prefetch 联动。若升级 foundation 有风险，回退为"只提升 `beyondBoundsItemCount`"的低风险档。
2. **A/B 对照档不应默认生效**：避免用户主观"变短行程"误解为退化；对照档以常量/隐藏标志隔离，默认关。
3. **Squircle 全局缓存内存上限**：需定上限（如 key 数 ≤ 128）防泄漏；不同 `n`/`cornerRadius`/尺寸组合极少，风险低，仍加保护。
4. **真机离线导致无法测基准**：测量步骤依赖线上设备；可先完成代码优化，在下一轮接上设备再验。
5. **Compose BOM 升级恐慌**：若 foundation 1.7 API 需升级且影响编译，优先保 BOM 2024.02.01（1.6.x），用 `beyondBounds` 方案即可。
6. **回归**：4/5 列切换、Feed↔Grid 往返、橡皮筋触底、120Hz fling 全部回归；若翻滚时出现新的抖动，优先回退 §3.3/§3.5 的不稳定项。

---

## 7. 明确的假设与限制

- **基线假设**：目标设备 Redmi K40 / Android 16 支持 120Hz 且已开启高刷（建议实测确认）；列表数据量为 30~60 个应用（含 20 个测试应用），无网络加载。
- **范围限制**：不做横屏、不做图标长按拖拽、不做 Trebuchet 特有的字母快滑/搜索框（与本项目 MeeGo 体验无关）。
- **Trebuchet 参照**：为 LineageOS 23.2 分支真机源码（本文已抓取 `AllAppsRecyclerView`/`AllAppsGridAdapter` 印证技术方案）；其包名在真机上确认后再写入基准脚本。
- **"更流畅"的判定**：以 §1 的帧时长指标为准，不单凭主观手感；手感应与 Trebuchet 同屏对照评估。

---

*（以上为方案，未改动任何业务代码；批准的下一轮起按 §4 执行。）*