package com.meego.launcher.ui.screens

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.meego.launcher.ui.components.meeGoTopSafeArea
import com.meego.launcher.ui.components.rememberMeeGoBounceController
import com.meego.launcher.ui.components.rememberMeeGoFlingBehavior
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.meego.launcher.data.RootTaskProvider
import com.meego.launcher.model.LaunchOrigin
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.model.RecentTask
import com.meego.launcher.model.TaskSnapshotCache
import com.meego.launcher.ui.components.SquircleShape
import com.meego.launcher.ui.theme.MeeGoCardBg
import com.meego.launcher.ui.theme.MeeGoCyan
import com.meego.launcher.ui.theme.MeeGoDarkGrey
import com.meego.launcher.ui.theme.MeeGoRed
import com.meego.launcher.ui.theme.MeeGoTextDim
import com.meego.launcher.ui.theme.MeeGoTextWhite
import com.meego.launcher.util.ScreenCaptureHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultitaskingScreen(
    tasks: List<RecentTask>,
    taskProvider: RootTaskProvider,
    onTaskRemoved: (Int) -> Unit,
    onClearAll: () -> Unit,
    columns: Int = 2,
    onColumnsChanged: (Int) -> Unit = {},
    isVisible: Boolean = true,
    showTitle: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isEditMode by remember { mutableStateOf(false) }

    // 双指缩放进度: 1.0f 为 2x2 视图, 0.0f 为 3x3 视图
    val initialProgress = if (columns == 3) 0.0f else 1.0f
    val animProgress = remember { Animatable(initialProgress) }

    LaunchedEffect(columns) {
        val target = if (columns == 3) 0.0f else 1.0f
        if (animProgress.targetValue != target) {
            animProgress.animateTo(target, spring(dampingRatio = 0.96f, stiffness = 450f))
        }
    }

    LaunchedEffect(tasks.isEmpty()) {
        if (tasks.isEmpty()) {
            isEditMode = false
        }
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val horizontalPadding = 16.dp
    val availableWidth = screenWidthDp - horizontalPadding * 2

    // 卡片高宽比 = 屏幕真实长宽比（物理屏幕 高/宽），使后台应用卡片与手机屏幕保持同一比例。
    // 卡片内放的是整屏应用快照，同比例后 ContentScale.Crop 可完整无裁切地呈现。
    //
    // 刻意不使用 LocalConfiguration.screenHeightDp / screenWidthDp：该值取自「应用窗口」
    // 而非物理屏幕，会随系统栏/挖孔 inset 变化。Redmi K40 实测同一台机器上
    // 全屏态为 w393dp/h873dp（比值 2.2214 = 2400/1080），而带挖孔与导航栏 inset 时
    // 变为 w393dp/h811dp（比值 2.0636）—— 同一台机器卡片比例会随窗口状态漂移，
    // 且与整屏快照比例对不上。故改取 WindowManager 的真实显示尺寸：
    // API 30+ 用 maximumWindowMetrics，更低版本回退 defaultDisplay.getRealMetrics。
    // 该值只取决于屏幕本身，跨机型、跨横竖屏均稳定正确。
    val screenAspect = remember(context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val (widthPx: Int, heightPx: Int) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager?.maximumWindowMetrics?.bounds
            (bounds?.width() ?: 0) to (bounds?.height() ?: 0)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        // 取不到真实尺寸时退化为常见 20:9，宁可略有偏差也不让高度算成 0
        if (widthPx > 0 && heightPx > 0) heightPx.toFloat() / widthPx else 2.2222f
    }

    // 2 列模式几何参数 (大卡片)
    val spacingX2 = 12.dp
    val spacingY2 = 16.dp
    val cardWidth2 = (availableWidth - spacingX2) / 2
    val cardHeight2 = cardWidth2 * screenAspect

    // 3 列模式几何参数 (紧凑卡片)
    val spacingX3 = 10.dp
    val spacingY3 = 12.dp
    val cardWidth3 = (availableWidth - spacingX3 * 2) / 3
    val cardHeight3 = cardWidth3 * screenAspect

    val p = animProgress.value.coerceIn(0.0f, 1.0f)
    // 堆叠权重: 当 p = 0.5 (过渡中间态) 时达到峰值 1.0，两端 p=0 或 p=1 时归零
    val stackWeight = sin(p * PI).toFloat()

    // 基础尺寸线性插值
    val baseW = cardWidth3 + (cardWidth2 - cardWidth3) * p
    val baseH = cardHeight3 + (cardHeight2 - cardHeight3) * p

    val scrollState = rememberScrollState()
    val flingBehavior = rememberMeeGoFlingBehavior()
    val bounceController = rememberMeeGoBounceController()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var flingJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            flingJob?.cancel()
            flingJob = null
            bounceController.reset()
        }
    }
    val scrollYDp = with(density) { scrollState.value.toDp() }
    val stackStartY = maxOf(16.dp, scrollYDp + 16.dp)

    // 容器高度动态计算
    val rows2 = (tasks.size + 1) / 2
    val height2 = if (rows2 > 0) (cardHeight2 + spacingY2) * rows2 + 120.dp else 200.dp
    val rows3 = (tasks.size + 2) / 3
    val height3 = if (rows3 > 0) (cardHeight3 + spacingY3) * rows3 + 120.dp else 200.dp
    val heightStack = stackStartY + 38.dp * tasks.size + cardHeight2 + 120.dp
    val baseContainerH = height3 + (height2 - height3) * p
    val containerHeight = maxOf(baseContainerH, baseContainerH + (heightStack - baseContainerH) * stackWeight)

    val currentColumns by rememberUpdatedState(columns)
    val currentOnColumnsChanged by rememberUpdatedState(onColumnsChanged)
    var isPinching by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .meeGoTopSafeArea()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var startDistance = 0f
                    var startProgress = 1.0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.size >= 2) {
                            val p0 = pressed[0].position
                            val p1 = pressed[1].position
                            val currentDistance = (p0 - p1).getDistance()

                            if (!isPinching) {
                                isPinching = true
                                startDistance = currentDistance
                                startProgress = animProgress.value
                            } else if (startDistance > 0f) {
                                val deltaDistance = currentDistance - startDistance
                                // 双指间距变化由原 260px 扩大至 680px，降低缩放倍率，使得手指滑动距离更长，卡片缩放及堆叠过渡更平缓细致
                                val newProgress = (startProgress + deltaDistance / 680f).coerceIn(0.0f, 1.0f)
                                coroutineScope.launch {
                                    animProgress.snapTo(newProgress)
                                }
                            }
                            pressed.forEach { it.consume() }
                        } else {
                            if (isPinching) {
                                isPinching = false
                                startDistance = 0f
                                val currentVal = animProgress.value
                                val targetP = if (currentVal < 0.5f) 0.0f else 1.0f
                                val targetCols = if (targetP == 0.0f) 3 else 2
                                val initialCols = if (startProgress < 0.5f) 3 else 2
                                val willChange = targetCols != initialCols || targetCols != currentColumns

                                coroutineScope.launch {
                                    animProgress.animateTo(
                                        targetP,
                                        spring(dampingRatio = 0.96f, stiffness = 450f)
                                    )
                                    if (willChange) {
                                        currentOnColumnsChanged(targetCols)
                                        vibrate(context)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (isEditMode) isEditMode = false
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // 沉浸式 UI：隐藏顶部标题栏时，仅保留紧凑安全间距，让卡片铺满全屏
            Spacer(modifier = Modifier.height(if (showTitle) 16.dp else 8.dp))

            // 顶部栏（沉浸式 UI 开启时隐藏）
            if (showTitle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Open Applications",
                        color = MeeGoTextWhite,
                        fontSize = 22.sp
                    )
                    Text(
                        text = if (p > 0.5f) "2×2 视图" else "3×3 视图",
                        color = MeeGoCyan,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(SquircleShape())
                            .background(MeeGoCardBg)
                            .clickable {
                                val newTarget = if (p > 0.5f) 0.0f else 1.0f
                                val newCols = if (newTarget == 0.0f) 3 else 2
                                coroutineScope.launch {
                                    animProgress.animateTo(
                                        newTarget,
                                        spring(dampingRatio = 0.96f, stiffness = 450f)
                                    )
                                    onColumnsChanged(newCols)
                                    vibrate(context)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (tasks.isEmpty()) {
                Box(
                    // 空态居中留白由 96dp 压缩至 16dp，配合窗口全屏铺满，
                    // 避免空屏时底部出现大片无意义留白。
                    modifier = Modifier.fillMaxSize().padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "无正在运行的应用",
                        color = MeeGoTextDim,
                        fontSize = 15.sp
                    )
                }
            } else {
                CompositionLocalProvider(
                    LocalOverscrollConfiguration provides null
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            // 越界裁剪（必须在 graphicsLayer 之前，理由同 AppGridScreen）：
                            // 触底/触顶时整层卡片被 translationY 拖出自身边界，Compose 默认不裁剪，
                            // 本 Box 作为 Column 中靠后的兄弟节点会直接盖住上方的 "Open Applications" 标题。
                            // clipToBounds 走 RenderNode clip 变换，开销近乎为零。
                            .clipToBounds()
                            .graphicsLayer {
                                translationY = bounceController.overscrollOffset
                            }
                            // 自研纵向滚动手势驱动（与 AppGridScreen 同源方案）。
                            // 背景：HorizontalPager 多页共存时，相邻页滚动过后内建 scrollable 的拖拽
                            // 位移派发会失效（事件被消费但 scrollState.value 不推进），表现为
                            // “列表不跟手、只有抬起手指后才滚动”。此处以 Initial pass 接管单指纵向拖拽，
                            // 直接用 scrollState.dispatchRawDelta 驱动滚动；一旦出现第二根手指立即让位，
                            // 保证双指无级缩放（Pinch）零干扰。
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
                                        // 双指按下：立刻放弃单指滚动，完整让位给外层 Pinch 手势
                                        if (event.changes.count { it.pressed } >= 2) {
                                            dragging = false
                                            break
                                        }
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        val dy = change.position.y - lastY
                                        lastY = change.position.y
                                        totalDy += dy
                                        if (!dragging && abs(totalDy) > touchSlop) dragging = true
                                        if (dragging) {
                                            // 在 Initial pass 消费位移，阻止内建 scrollable 重复接管造成双倍滚动
                                            change.consume()
                                            // 手指位移 -> 列表位移方向换算（dispatchRawDelta 正值 = 内容上移）
                                            val forListFinger = bounceController.consumeOverscrollForDrag(dy)
                                            val listDelta = -forListFinger
                                            val consumed = scrollState.dispatchRawDelta(listDelta)
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
                                                        val consumed = scrollState.dispatchRawDelta(pixels)
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
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (isEditMode) {
                                        isEditMode = false
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(containerHeight)
                            ) {
                        tasks.forEachIndexed { i, task ->
                            // 2 列网格目标位置
                            val col2 = i % 2
                            val row2 = i / 2
                            val x2 = (cardWidth2 + spacingX2) * col2
                            val y2 = (cardHeight2 + spacingY2) * row2

                            // 3 列网格目标位置
                            val col3 = i % 3
                            val row3 = i / 3
                            val x3 = (cardWidth3 + spacingX3) * col3
                            val y3 = (cardHeight3 + spacingY3) * row3

                            // 基础线性插值坐标
                            val baseX = x3 + (x2 - x3) * p
                            val baseY = y3 + (y2 - y3) * p

                            // 堆叠中心位置 (卡片上下汇聚叠放，并带有轻微左右错落与倾斜)
                            val centerX = (availableWidth - baseW) / 2
                            val staggerX = if (i % 2 == 0) (-6).dp else 6.dp
                            val stackX = centerX + staggerX
                            val stackY = stackStartY + 38.dp * i

                            // 随堆叠权重融合的最终坐标与形变
                            val currentX = baseX + (stackX - baseX) * stackWeight
                            val currentY = baseY + (stackY - baseY) * stackWeight
                            val currentScale = 1.0f - 0.08f * stackWeight
                            val currentRot = if (i % 2 == 0) (-1.8f) * stackWeight else 1.8f * stackWeight

                            key(task.taskId) {
                                // 卡片坐标平滑位移动画：当后台任务重新排序时，C卡片由后方顺滑移动至第一位，AB卡片向后顺位移位
                                // 适当降低刚度（stiffness = 140f）并搭配临界轻阻尼（dampingRatio = 0.92f），使卡片在 C A B 顺序重排换位过程中动画时长自然延伸至约 400~450ms，视觉呈现优雅丝滑、无晃动感
                                val animX by animateDpAsState(
                                    targetValue = currentX,
                                    animationSpec = spring(
                                        dampingRatio = 0.92f,
                                        stiffness = 140f
                                    ),
                                    label = "card_x_${task.taskId}"
                                )
                                val animY by animateDpAsState(
                                    targetValue = currentY,
                                    animationSpec = spring(
                                        dampingRatio = 0.92f,
                                        stiffness = 140f
                                    ),
                                    label = "card_y_${task.taskId}"
                                )

                                // 双指主动 Pinch 缩放过程中直接跟随手指零延迟响应；任务重排时使用弹簧动画
                                val renderX = if (isPinching) currentX else animX
                                val renderY = if (isPinching) currentY else animY

                                // 切换至最前面的卡片享有更高层级，确保优雅浮于其他卡片上方飞过
                                val isTopMoving = (i == 0 && (task.taskId == LaunchOriginTracker.currentTaskId || task.taskId == TaskSnapshotCache.lastSwipedTaskId))
                                val zIndexVal = if (isTopMoving) 25f else (tasks.size - i).toFloat()

                                Box(
                                    modifier = Modifier
                                        .size(baseW, baseH)
                                        .graphicsLayer {
                                            translationX = renderX.toPx()
                                            translationY = renderY.toPx()
                                            scaleX = currentScale
                                            scaleY = currentScale
                                            rotationZ = currentRot
                                            shadowElevation = (6.dp * (1f + stackWeight * 0.8f)).toPx()
                                        }
                                        .zIndex(zIndexVal)
                                ) {
                                    RecentTaskCard(
                                        task = task,
                                        isEditMode = isEditMode,
                                        isCompact = (p < 0.5f),
                                        onLongClick = {
                                            isEditMode = true
                                            vibrate(context)
                                        },
                                        onClick = {
                                            if (isEditMode) {
                                                isEditMode = false
                                            } else {
                                                LaunchOriginTracker.lastOrigin = LaunchOrigin.RECENTS
                                                LaunchOriginTracker.currentTaskId = task.taskId
                                                LaunchOriginTracker.currentPackageName = task.packageName
                                                (context as? Activity)?.let { activity ->
                                                    ScreenCaptureHelper.captureLauncherScreen(activity, LaunchOrigin.RECENTS)
                                                }
                                                taskProvider.switchToTask(task.taskId, task.packageName)
                                            }
                                        },
                                        onDelete = {
                                            coroutineScope.launch {
                                                taskProvider.removeTask(task.taskId, task.packageName)
                                                onTaskRemoved(task.taskId)
                                                vibrate(context)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

        // 底部一键清理所有任务按钮 (MeeGo 原生交互：只有长按卡片进入编辑模式后才展示)
        AnimatedVisibility(
            visible = isEditMode && tasks.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
        ) {
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        isEditMode = false
                        taskProvider.removeAllTasks(tasks)
                        onClearAll()
                        vibrate(context)
                    }
                },
                modifier = Modifier
                    .size(52.dp)
                    .clip(SquircleShape(2.732f))
                    .background(MeeGoCardBg)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close all",
                    tint = MeeGoTextWhite,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentTaskCard(
    task: RecentTask,
    isEditMode: Boolean,
    isCompact: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(SquircleShape(2.732f))
            .background(MeeGoDarkGrey)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // 卡片内容: 快照预览或图标占位
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (task.snapshot != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        bitmap = task.snapshot.asImageBitmap(),
                        contentDescription = task.label,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // 顶部半透明应用名称与图标超椭圆胶囊
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(SquircleShape(cornerRadius = 10.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (task.icon != null) {
                            val iconBmp = remember(task.icon) {
                                try {
                                    task.icon.toBitmap(32, 32)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (iconBmp != null) {
                                Image(
                                    bitmap = iconBmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                        }
                        Text(
                            text = task.label,
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                // 优雅的卡片占位
                if (task.icon != null) {
                    val bitmap = remember(task.icon) {
                        try {
                            task.icon.toBitmap(96, 96)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = task.label,
                            modifier = Modifier.size(if (isCompact) 36.dp else 48.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = task.label,
                    color = MeeGoTextWhite,
                    fontSize = if (isCompact) 11.sp else 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 右上角删除角标 (长按触发编辑模式后浮现)
        AnimatedVisibility(
            visible = isEditMode,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(10f)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDelete
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(SquircleShape())
                        .background(MeeGoRed),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Delete Task",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private fun vibrate(context: Context) {
    try {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
