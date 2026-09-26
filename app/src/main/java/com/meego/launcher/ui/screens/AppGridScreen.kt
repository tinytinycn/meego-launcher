package com.meego.launcher.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.meego.launcher.model.AppItem
import com.meego.launcher.model.LaunchOrigin
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.ui.components.DefaultSquircleShape
import com.meego.launcher.ui.components.rememberMeeGoBounceController
import com.meego.launcher.ui.components.rememberMeeGoFlingBehavior
import com.meego.launcher.ui.theme.MeeGoCardBg
import com.meego.launcher.ui.theme.MeeGoCyan
import com.meego.launcher.ui.theme.MeeGoTextDim
import com.meego.launcher.ui.theme.MeeGoTextWhite
import com.meego.launcher.util.ScreenCaptureHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppGridScreen(
    apps: List<AppItem>,
    columns: Int,
    onOpenSettings: () -> Unit,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val iconSize = if (columns == 4) 62.dp else 52.dp
    val fontSize = if (columns == 4) 12.sp else 11.sp
    val bounceController = rememberMeeGoBounceController()
    val gridState = rememberLazyGridState()
    val flingBehavior = rememberMeeGoFlingBehavior()
    val coroutineScope = rememberCoroutineScope()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var flingJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            flingJob?.cancel()
            flingJob = null
            bounceController.reset()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // 顶部微间距与 MeeGo 极简标题
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Applications",
                color = MeeGoTextWhite,
                fontSize = 22.sp
            )
            Text(
                text = "${columns} 列",
                color = MeeGoCyan,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(DefaultSquircleShape)
                    .background(MeeGoCardBg)
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        CompositionLocalProvider(
            LocalOverscrollConfiguration provides null
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        translationY = bounceController.overscrollOffset
                    }
                    // 自研纵向滚动手势驱动。
                    // 背景：在 HorizontalPager 多页共存（beyondBoundsPageCount = 1）时，切到
                    // Feed 滚动后再切回本页，LazyVerticalGrid 内建 scrollable 的拖拽位移派发会失效
                    // （LazyGridState.onScroll 中待处理位移被丢弃且未 remeasure），表现为
                    // “列表不跟手、只有抬起手指后才滚动”。此处在 Initial pass 抢先接管纵向拖拽，
                    // 直接用 gridState.dispatchRawDelta 驱动滚动，保证手指 1:1 跟手，
                    // 并自行接管惯性滑行与触顶/触底橡皮筋回弹。
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            flingJob?.cancel()
                            flingJob = null
                            var lastY = down.position.y
                            var totalDy = 0f
                            var dragging = false
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val dy = change.position.y - lastY
                                lastY = change.position.y
                                totalDy += dy
                                if (!dragging && abs(totalDy) > touchSlop) dragging = true
                                if (dragging) {
                                    // 在 Initial pass 消费位移，阻止内建 scrollable 重复接管造成双倍滚动
                                    change.consume()
                                    // 手指位移 -> 列表位移方向换算：
                                    // 手指下移(dy>0)时列表应向“回退”方向滚（内容跟随手指下移），
                                    // 而 gridState.dispatchRawDelta 正值代表向前（内容上移），故取反。
                                    val forListFinger = bounceController.consumeOverscrollForDrag(dy)
                                    val listDelta = -forListFinger
                                    val consumed = gridState.dispatchRawDelta(listDelta)
                                    // 未消费部分换算回“手指方向”供橡皮筋拉伸使用
                                    val leftoverFinger = -(listDelta - consumed)
                                    if (abs(leftoverFinger) > 0.01f) {
                                        bounceController.pullOverscroll(leftoverFinger)
                                    }
                                }
                            }
                            if (dragging) {
                                val releaseVelocity = velocityTracker.calculateVelocity().y
                                if (abs(releaseVelocity) > 20f) {
                                    // 手指速度换算为列表速度方向（取反），复用统一的自适应增益 Fling 引擎
                                    val flingVelocity = -releaseVelocity
                                    flingJob = coroutineScope.launch {
                                        val sink = object : ScrollScope {
                                            override fun scrollBy(pixels: Float): Float {
                                                val consumed = gridState.dispatchRawDelta(pixels)
                                                val leftoverFinger = -(pixels - consumed)
                                                if (abs(leftoverFinger) > 0.5f) {
                                                    bounceController.pullOverscroll(leftoverFinger)
                                                }
                                                return consumed
                                            }
                                        }
                                        val leftVelocity = with(flingBehavior) { sink.performFling(flingVelocity) }
                                        bounceController.releaseOverscroll(-leftVelocity)
                                    }
                                } else {
                                    bounceController.releaseOverscroll(releaseVelocity)
                                }
                            }
                        }
                    }
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = apps,
                        key = { it.packageName + it.activityName },
                        contentType = { "app_item" }
                    ) { app ->
                        AppGridItem(
                            app = app,
                            iconSize = iconSize,
                            fontSize = fontSize,
                            onClick = {
                                if (app.isMeeGoSettings) {
                                    onOpenSettings()
                                } else {
                                    launchApp(context, app)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppGridItem(
    app: AppItem,
    iconSize: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(DefaultSquircleShape)
                .background(MeeGoCardBg),
            contentAlignment = Alignment.Center
        ) {
            val iconBitmap = app.iconBitmap
            if (iconBitmap != null) {
                // 优先直接绘制异步预渲染的 ImageBitmap，主线程零内存分配，绝对满帧流畅
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (app.icon != null) {
                val fallbackBitmap = remember(app.icon) {
                    try {
                        app.icon.toBitmap(128, 128)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (fallbackBitmap != null) {
                    Image(
                        bitmap = fallbackBitmap.asImageBitmap(),
                        contentDescription = app.label,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = app.label,
            color = MeeGoTextWhite,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

private fun launchApp(context: Context, app: AppItem) {
    LaunchOriginTracker.lastOrigin = LaunchOrigin.GRID
    LaunchOriginTracker.currentPackageName = app.packageName
    LaunchOriginTracker.currentTaskId = null // 关键：清空旧应用的 TaskId，绝不在新应用中串扰使用
    (context as? Activity)?.let { activity ->
        ScreenCaptureHelper.captureLauncherScreen(activity, LaunchOrigin.GRID)
    }
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
