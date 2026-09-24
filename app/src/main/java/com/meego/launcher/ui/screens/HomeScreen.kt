package com.meego.launcher.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.meego.launcher.ui.components.SquircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.meego.launcher.data.RootTaskProvider
import com.meego.launcher.model.AppItem
import com.meego.launcher.model.LaunchOrigin
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.model.RecentTask
import com.meego.launcher.ui.theme.MeeGoBlack
import com.meego.launcher.ui.theme.MeeGoCyan
import kotlin.math.abs
import kotlinx.coroutines.launch

private const val SCREEN_COUNT = 3
private const val LOOP_COUNT = 10000
private const val TOTAL_PAGES = SCREEN_COUNT * LOOP_COUNT
private const val INITIAL_PAGE = (LOOP_COUNT / 2) * SCREEN_COUNT + 1 // 默认居中停留在 Page 1 (Application Grid)

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

    val currentScreen = Math.floorMod(pagerState.currentPage, SCREEN_COUNT)

    // 实时同步当前页面来源 (Grid / Recents)
    LaunchedEffect(currentScreen) {
        if (currentScreen == 1) {
            LaunchOriginTracker.lastOrigin = LaunchOrigin.GRID
        } else if (currentScreen == 2) {
            LaunchOriginTracker.lastOrigin = LaunchOrigin.RECENTS
        }
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
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
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
                        onOpenSettings = { showSettings = true }
                    )
                    2 -> MultitaskingScreen(
                        tasks = recentTasks,
                        taskProvider = taskProvider,
                        onTaskRemoved = onTaskRemoved,
                        onClearAll = onClearAllTasks,
                        columns = recentsColumns,
                        onColumnsChanged = onSelectRecentsColumns
                    )
                }
            }

            // 底部经典 Nokia N9 三屏循环导航指示点
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
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
                                    pagerState.animateScrollToPage(pagerState.currentPage + diff)
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
                    onBack = { showSettings = false }
                )
            }
        }
    }
}
