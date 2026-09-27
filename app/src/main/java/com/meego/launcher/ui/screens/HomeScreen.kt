package com.meego.launcher.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import com.meego.launcher.ui.components.SquircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.meego.launcher.data.RootTaskProvider
import com.meego.launcher.model.AppItem
import com.meego.launcher.model.LaunchOrigin
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.model.RecentTask
import com.meego.launcher.ui.theme.MeeGoBlack
import com.meego.launcher.ui.theme.MeeGoCyan
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val SCREEN_COUNT = 3
private const val LOOP_COUNT = 10000
private const val TOTAL_PAGES = SCREEN_COUNT * LOOP_COUNT
private const val INITIAL_PAGE = (LOOP_COUNT / 2) * SCREEN_COUNT + 1 // 默认居中停留在 Page 1 (Application Grid)

// ---------------------------------------------------------------------------
// 三屏切换物理参数（无回弹 / 顺滑干练）
//
// 设计目标：切换"更顺畅、更干练"，**不要回弹效果**。
// 原实现三处切屏动画全部使用**欠阻尼**弹簧
// （边缘松手 spring(dampingRatio = 0.82f, stiffness = 380f)，指示点用框架默认
//  spring(0.9f / 400f) 与 tween(500ms)），阻尼比 < 1.0 会冲过目标页并来回振荡，
// 正是肉眼可见的"回弹"。此外原实现**完全丢弃松手速度**，画面脱离手指后从 0
// 重新加速，表现为一次可见的顿挫。本次统一改为：
//
//   1) **临界阻尼**（dampingRatio = 1.0f）：单调逼近目标，**无过冲、无振荡**，
//      从根本上消除回弹与晃动感；
//   2) **注入松手初速度**：动画从手指离开那一刻的速度无缝衔接，而非从 0 重启，
//      消除"松手后画面脱手 / 顿一下"的迟滞感；
//   3) **行程自适应刚度**：剩余行程越短，刚度越高、收尾越快，短距离"滑回去"
//      干脆利落；接近整屏时刚度回落，保持自然的行程感 —— 这正是"干练"的来源。
//
// 关于"临界阻尼注入初速度会不会反而过冲"：临界阻尼系统仅当归一化初速度
// v0/剩余行程 > ω=√stiffness 时才产生过冲。本实现初速度上限为 6000px/s，
// 而整屏行程通常 800~1100px，6000/800 = 7.5 远小于 √stiffness(≈20~25)，
// 因此实际手势下**单调收敛，严格无过冲**。
// ---------------------------------------------------------------------------

/** 临界阻尼：单调收敛，零过冲 —— 消除"回弹"的关键。 */
private const val SNAP_DAMPING_RATIO = 1.0f

/** 临界阻尼达到 ~95% 位移所需的 ωt 系数（5/ω 为收敛时间常数）。 */
private const val CRITICAL_SETTLE_FACTOR = 5.0f

/** 行程自适应刚度的收敛时长下界（秒）——短距离回弹，干脆利落。 */
private const val MIN_SNAP_DURATION_SEC = 0.15f

/** 行程自适应刚度的收敛时长上界（秒）——整屏切换的自然行程感。 */
private const val MAX_SNAP_DURATION_SEC = 0.26f

/** 指示点跳转的时长缩放（无松手速度，纯命令式跳转，略快一档更利落）。 */
private const val DOT_TAP_SPEED_SCALE = 0.85f

/**
 * 边缘划动判定为"切屏"的距离阈值（屏幕宽度比例）。
 *
 * 由 0.18 下调到 0.12：更小的距离即可完成切换，让手势更果断、减少"滑了半天又弹回去"的挫败感。
 */
private const val SWITCH_DISTANCE_FRACTION = 0.12f

/**
 * 边缘划动判定为"切屏"的速度阈值（px/s）。
 *
 * 由 600 下调到 400：轻快的一划即可切屏，轻扫手感更跟手。
 */
private const val SWITCH_VELOCITY = 400f

/**
 * 注入吸附弹簧的初速度上限（px/s），防止极端甩动把画面"射"过整屏。
 */
private const val MAX_SNAP_INITIAL_VELOCITY = 6000f

/**
 * 按**剩余行程**求解吸附刚度。
 *
 * 临界阻尼弹簧的收敛时间 ≈ [CRITICAL_SETTLE_FACTOR] / √stiffness，
 * 反解即 stiffness = (factor / duration)²。行程越短 → duration 越短 → 刚度越高，
 * 使"几乎没滑就松手"以极短时间干脆吸附回去，杜绝拖泥带水的长距离回弹。
 *
 * @param distanceFraction 剩余行程 / 单页宽度，取值 0..1
 * @param durationScale 时长缩放系数（<1 更快）
 */
private fun snapStiffnessForDistance(distanceFraction: Float, durationScale: Float = 1f): Float {
    val t = distanceFraction.coerceIn(0f, 1f)
    val durationSec = (
        MIN_SNAP_DURATION_SEC +
            (MAX_SNAP_DURATION_SEC - MIN_SNAP_DURATION_SEC) * t
        ) * durationScale
    val omega = CRITICAL_SETTLE_FACTOR / durationSec
    return omega * omega
}

/**
 * 把 Pager 平滑吸附到 [targetPage]，全程**零回弹**。
 *
 * 为什么不用 `animateScrollToPage`：本项目锁定 Compose foundation 1.6.2，
 * 其 `animateScrollToPage(page, offset, animationSpec)` **不接受 initialVelocity**，
 * 手指松手瞬间的速度被丢弃，画面会脱离手指重新从 0 加速，表现为一次可见的顿挫。
 * 因此这里改用更低层的 [PagerState.scroll] + [Animatable]，显式注入松手速度。
 * 目标位移算法与框架 1.6.2 `animateScrollToPage` 内部完全一致：
 * `(targetPage - currentPage - currentPageOffsetFraction) * pageSize`。
 *
 * @param targetPage 目标页
 * @param initialVelocity 松手速度（px/s，正方向 = 屏幕内容向左，即翻向下一页）
 * @param durationScale 时长缩放系数（<1 更快）
 */
@OptIn(ExperimentalFoundationApi::class)
private suspend fun PagerState.snapToPageWithoutBounce(
    targetPage: Int,
    initialVelocity: Float = 0f,
    durationScale: Float = 1f
) {
    val currentPage = currentPage
    val currentOffset = currentPageOffsetFraction
    val targetPageOffset = targetPage - currentPage - currentOffset

    val clampedVelocity = initialVelocity.coerceIn(
        -MAX_SNAP_INITIAL_VELOCITY,
        MAX_SNAP_INITIAL_VELOCITY
    )

    // 已在目标页且没有残余位移时无需动画，避免无谓的一次重绘
    if (abs(targetPageOffset) < 0.0001f && abs(clampedVelocity) < 0.01f) {
        return
    }

    val pageSizePx = layoutInfo.pageSize.toFloat()
    if (pageSizePx <= 0f) return

    // 目标位移换算为像素：正 = 向前（内容向左，翻向下一页）
    val targetPx = targetPageOffset * pageSizePx
    val stiffness = snapStiffnessForDistance(
        distanceFraction = (abs(targetPageOffset) / pageSizePx).coerceIn(0f, 1f),
        durationScale = durationScale
    )

    scroll {
        val animatable = Animatable(0f)
        animatable.snapTo(0f)
        var applied = 0f
        animatable.animateTo(
            targetValue = targetPx,
            animationSpec = spring(
                dampingRatio = SNAP_DAMPING_RATIO,
                stiffness = stiffness
            ),
            // 从手指离开的速度无缝衔接，动画与手势连续，不再"脱手重启"
            initialVelocity = clampedVelocity
        ) {
            val delta = value - applied
            applied = value
            // 沿 ScrollScope 协议逐帧消费位移（正向 = 向前翻页）
            scrollBy(delta)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    apps: List<AppItem>,
    recentTasks: List<RecentTask>,
    gridColumns: Int,
    onSelectColumns: (Int) -> Unit,
    recentsColumns: Int = 2,
    onSelectRecentsColumns: (Int) -> Unit = {},
    edgeSwipeEnabled: Boolean,
    onToggleEdgeSwipe: (Boolean) -> Unit,
    immersiveUiEnabled: Boolean,
    onToggleImmersiveUi: (Boolean) -> Unit,
    taskProvider: RootTaskProvider,
    onTaskRemoved: (Int) -> Unit,
    onClearAllTasks: () -> Unit,
    targetPage: Int? = null,
    targetPageEvent: Long = 0L,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    // 三屏无限循环水平滑动 Pager (Events Feed <-> Application Grid <-> Multitasking View)
    val pagerState = rememberPagerState(initialPage = INITIAL_PAGE, pageCount = { TOTAL_PAGES })
    var showSettings by remember { mutableStateOf(false) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val edgeWidthPx = with(density) { 36.dp.toPx() }
    val touchSlop = viewConfig.touchSlop

    val currentScreen = Math.floorMod(pagerState.currentPage, SCREEN_COUNT)

    // 实时同步当前页面来源 (Grid / Recents)
    LaunchedEffect(currentScreen) {
        if (currentScreen == 1) {
            LaunchOriginTracker.lastOrigin = LaunchOrigin.GRID
        } else if (currentScreen == 2) {
            LaunchOriginTracker.lastOrigin = LaunchOrigin.RECENTS
        }
    }

    // 独立记录「用户此刻所在页序」，供划出手势的动画分支判断。
    //
    // 不能复用 [LaunchOriginTracker.lastOrigin]：它是「应用从哪一屏唤起」，
    // 停留在 Events Feed 时它仍是上一次的 GRID/RECENTS，会导致 Feed 被误判。
    SideEffect {
        LaunchOriginTracker.currentScreenIndex = currentScreen
    }

    // 响应外部传入的定向回溯目标页面 (例如边缘划回)，通过最短距离无缝瞬时定位
    LaunchedEffect(targetPageEvent) {
        if (targetPage != null && targetPage in 0..2) {
            val curr = Math.floorMod(pagerState.currentPage, SCREEN_COUNT)
            var diff = targetPage - curr
            if (diff == 2) diff = -1
            if (diff == -2) diff = 1
            pagerState.scrollToPage(pagerState.currentPage + diff)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeeGoBlack)
            .pointerInput(showSettings) {
                if (showSettings) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val startX = down.position.x
                    val isLeftEdge = startX <= edgeWidthPx
                    val isRightEdge = startX >= size.width - edgeWidthPx

                    // 核心准则：仅当触控起始点严格位于左右屏幕边缘（36dp 区域内）时才启动三屏边缘切屏手势探测；
                    // 屏幕中央区域（如 Application Grid）一律完全放行，零阻断、零冲突
                    if (!isLeftEdge && !isRightEdge) {
                        return@awaitEachGesture
                    }

                    val startPage = pagerState.currentPage
                    var isEdgeHorizontalDrag: Boolean? = null
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            if (isEdgeHorizontalDrag == true) {
                                val velocity = velocityTracker.calculateVelocity().x
                                val totalDx = change.position.x - startX
                                val threshold = size.width * SWITCH_DISTANCE_FRACTION

                                // 目标页判定：位移过阈值 **或** 甩动速度过阈值 即完成切换，
                                // 两者取"或"让手势更果断、减少滑回原页的挫败感。
                                val destinationPage = if (isRightEdge) {
                                    // 从右边缘向左划入，目标为下一屏 (startPage + 1)
                                    if (totalDx < -threshold || velocity < -SWITCH_VELOCITY) {
                                        startPage + 1
                                    } else {
                                        startPage
                                    }
                                } else {
                                    // 从左边缘向右划入，目标为上一屏 (startPage - 1)
                                    if (totalDx > threshold || velocity > SWITCH_VELOCITY) {
                                        startPage - 1
                                    } else {
                                        startPage
                                    }
                                }

                                // Pager 的滚动正方向（内容向左）对应手指向左滑动。
                                // 手指 x 速度为负 = 向左划 = 翻向下一页，故取反后作为 Pager 初速度。
                                val pagerVelocity = -velocity

                                scrollJob?.cancel()
                                scrollJob = coroutineScope.launch {
                                    pagerState.snapToPageWithoutBounce(
                                        targetPage = destinationPage,
                                        initialVelocity = pagerVelocity
                                    )
                                }
                            }
                            break
                        }

                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        val dx = change.position.x - startX
                        val dy = change.position.y - down.position.y

                        if (isEdgeHorizontalDrag == null) {
                            val absDx = abs(dx)
                            val absDy = abs(dy)
                            if (absDx > touchSlop || absDy > touchSlop) {
                                if (absDx > absDy) {
                                    // 水平位移严格大于垂直位移，且方向必须是从边缘向屏幕内部滑入
                                    if ((isLeftEdge && dx > 0) || (isRightEdge && dx < 0)) {
                                        isEdgeHorizontalDrag = true
                                        scrollJob?.cancel()
                                        change.consume()
                                    } else {
                                        isEdgeHorizontalDrag = false
                                        break
                                    }
                                } else {
                                    // 垂直位移大于水平位移，说明用户是在边缘附近上下滑动网格或列表，坚决不拦截
                                    isEdgeHorizontalDrag = false
                                    break
                                }
                            }
                        } else if (isEdgeHorizontalDrag == true) {
                            val deltaX = change.position.x - change.previousPosition.x
                            change.consume()
                            pagerState.dispatchRawDelta(-deltaX)
                        }
                    }
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalOverscrollConfiguration provides null
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    beyondBoundsPageCount = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    val actualPage = Math.floorMod(page, SCREEN_COUNT)
                    when (actualPage) {
                        0 -> FeedScreen()
                        1 -> AppGridScreen(
                            apps = apps,
                            columns = gridColumns,
                            onOpenSettings = { showSettings = true },
                            isVisible = (currentScreen == 1),
                            showTitle = !immersiveUiEnabled
                        )
                        2 -> MultitaskingScreen(
                            tasks = recentTasks,
                            taskProvider = taskProvider,
                            onTaskRemoved = onTaskRemoved,
                            onClearAll = onClearAllTasks,
                            columns = recentsColumns,
                            onColumnsChanged = onSelectRecentsColumns,
                            isVisible = (currentScreen == 2),
                            showTitle = !immersiveUiEnabled
                        )
                    }
                }
            }

            // 底部经典 Nokia N9 三屏循环导航指示点
            // 沉浸式 UI 开启时整行隐藏：让三屏内容真正铺满至屏幕底部（含导航条区）
            if (!immersiveUiEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 30.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(SCREEN_COUNT) { index ->
                        val isSelected = currentScreen == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .clickable {
                                    coroutineScope.launch {
                                        val curr = Math.floorMod(pagerState.currentPage, SCREEN_COUNT)
                                        var diff = index - curr
                                        if (diff == 2) diff = -1
                                        if (diff == -2) diff = 1
                                        // 指示点跳转同样使用临界阻尼吸附：无回弹、干脆到位
                                        pagerState.snapToPageWithoutBounce(
                                            targetPage = pagerState.currentPage + diff,
                                            durationScale = DOT_TAP_SPEED_SCALE
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 8.dp else 5.dp)
                                    .clip(SquircleShape())
                                    .background(if (isSelected) MeeGoCyan else Color(0xFF444444))
                            )
                        }
                    }
                }
            }
        }

        // 拦截系统返回键
        androidx.activity.compose.BackHandler(enabled = showSettings) {
            showSettings = false
        }

        // Settings 面板覆盖层 (支持边缘手势划出返回与物理按键返回)
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.fillMaxSize()
        ) {
            var dragOffset by remember { mutableFloatStateOf(0f) }
            val animOffset by androidx.compose.animation.core.animateFloatAsState(
                targetValue = dragOffset,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 400f),
                label = "settings_swipe"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = animOffset
                        if (animOffset != 0f) {
                            val progress = (abs(animOffset) / 1000f).coerceIn(0f, 1f)
                            scaleX = 1f - progress * 0.08f
                            scaleY = 1f - progress * 0.08f
                            shadowElevation = 20.dp.toPx()
                        }
                    }
                    .clip(SquircleShape(cornerRadius = if (animOffset != 0f) 28.dp else 0.dp))
                    .background(MeeGoBlack)
                    .pointerInput(Unit) {
                        var isEdgeDrag = false
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val edgeWidthPx = 36.dp.toPx()
                                isEdgeDrag = (offset.x <= edgeWidthPx || offset.x >= (size.width.toFloat() - edgeWidthPx))
                            },
                            onDragEnd = {
                                if (isEdgeDrag) {
                                    if (abs(dragOffset) > size.width.toFloat() / 3.5f) {
                                        showSettings = false
                                        dragOffset = 0f
                                    } else {
                                        dragOffset = 0f
                                    }
                                    isEdgeDrag = false
                                }
                            },
                            onDragCancel = {
                                dragOffset = 0f
                                isEdgeDrag = false
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (isEdgeDrag) {
                                    change.consume()
                                    dragOffset += dragAmount
                                }
                            }
                        )
                    }
            ) {
                SettingsScreen(
                    currentColumns = gridColumns,
                    onSelectColumns = {
                        onSelectColumns(it)
                    },
                    edgeSwipeEnabled = edgeSwipeEnabled,
                    onToggleEdgeSwipe = onToggleEdgeSwipe,
                    immersiveUiEnabled = immersiveUiEnabled,
                    onToggleImmersiveUi = onToggleImmersiveUi,
                    onBack = { showSettings = false }
                )
            }
        }
    }
}
