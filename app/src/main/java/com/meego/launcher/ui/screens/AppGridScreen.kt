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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.meego.launcher.model.AppItem
import com.meego.launcher.model.LaunchOrigin
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.model.TaskSnapshotCache
import com.meego.launcher.ui.components.DefaultSquircleShape
import com.meego.launcher.ui.components.meeGoTopSafeArea
import com.meego.launcher.ui.components.rememberMeeGoBounceController
import com.meego.launcher.ui.components.rememberMeeGoFlingBehavior
import com.meego.launcher.ui.theme.MeeGoCardBg
import com.meego.launcher.ui.theme.MeeGoCyan
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
    showTitle: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bounceController = rememberMeeGoBounceController()
    val gridState = rememberLazyGridState()
    val flingBehavior = rememberMeeGoFlingBehavior()
    val coroutineScope = rememberCoroutineScope()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    // 用非快照 Holder 保存 fling Job：Job 的启停/赋值不会触发任何重组，
    // 原实现用 mutableStateOf<Job?>，每次起手/抬手都会写一次快照状态。
    val flingJob = remember { JobHolder() }

    // 注意：apps 是 SnapshotStateList，主流程用 clear()+addAll() 更新，
    // 其“实例引用”永不变化，只有内容变化。若对它做 remember(apps) 缓存，
    // 会永久锁死首次（空列表）快照。因此这里直接透传 apps：
    // items() 读取快照状态本身已是正确且廉价的做法，真正的滑动期开销在 key 拼接，已由 gridKey 解决。

    // 列数 → 图标尺寸/字号 收敛到 remember(columns)。
    // 避免在每个 item 的组合中重复做条件判断与装箱分配。
    val metrics = remember(columns) { GridMetrics.from(columns) }

    // 预取（§3.3）：Compose foundation 1.6.2 中 `LazyGridState.prefetchingEnabled` 为 internal，
    // 编译期不可访问；实测反编译确认其默认即为 true，且预取由 onScroll 驱动 ——
    // 本项目自研手势走 dispatchRawDelta -> scrollableState.dispatchRawDelta -> onScroll 链路，
    // 因此框架预取在本项目手势路径下同样生效，无需（也无法）在此显式开启。
    // beyondBoundsItemCount / prefetchItemCount 属 Compose 1.7+ API，本项目锁定 1.6.2 不适用。

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            flingJob.cancelAndClear()
            bounceController.reset()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 顶部安全间距：状态栏已被真正隐藏，此处只避让挖孔，
            // 不再按 105px 系统栏留白（否则图标被推到 y=117，顶部大片空白）。
            .meeGoTopSafeArea()
            .padding(horizontal = 16.dp)
    ) {
        // 沉浸式 UI：隐藏顶部标题栏时，仅保留一个紧凑的安全间距，让图标真正铺满全屏
        if (showTitle) {
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
        } else {
            // 沉浸式：顶部间距已由 meeGoTopSafeArea 统一处理（挖孔避让 + 最小呼吸空间），
            // 此处不再额外叠加 Spacer，避免顶部出现冗余空白。
        }

        CompositionLocalProvider(
            LocalOverscrollConfiguration provides null
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 越界裁剪（必须在 graphicsLayer 之前）。
                    // 触底/触顶时整层网格被 translationY 拖出自身边界，而 Compose 默认不做裁剪，
                    // 位于 Column 中靠后的本 Box 会直接盖住上方的 "Applications" 标题。
                    // 实测（1080x2400 / 440dpi）：静止时首行图标顶边 y=281，标题带 y=149..236，
                    // 留白仅 45px；触底上拉越界时整层上移可达 190px，图标直接覆盖标题整行。
                    // clipToBounds 走 RenderNode 的 clip 变换（近乎免费，不下发 canvas 绘制指令），
                    // 与本屏图标绘制保持同一 "用 Modifier.clip 而非 drawBehind{clipPath}" 的性能结论一致。
                    // 顺序不可调换：graphicsLayer 在外会把 clip 一起平移，等于没有裁剪；
                    // 置于其外层才能在"未平移"的父坐标系中裁掉越界内容。
                    .clipToBounds()
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
                            flingJob.cancelAndClear()
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
                                    flingJob.set(coroutineScope.launch {
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
                                    })
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
                    // 底部留白由 96dp 大幅压缩至 8dp。
                    //
                    // 真机实测（Redmi K40 / 440dpi / 1080x2400）：窗口与 ComposeView
                    // 本身已铺满 [0,0][1080,2400]，并不存在系统级导航条留白；
                    // 此前 96dp(=264px) 的 contentPadding 是底部黑缝的真正来源，
                    // 使最后一行图标下方出现大片空白，破坏沉浸感。
                    // 现压缩至 8dp，仅够容纳标签呼吸空间，内容一路铺至物理屏幕底边。
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = apps,
                        // 使用 AppItem 构造期预计算好的稳定 key，避免每次 measure 都做字符串拼接
                        key = { it.gridKey },
                        contentType = { "app_item" }
                    ) { app ->
                        AppGridItem(
                            app = app,
                            iconSize = metrics.iconSize,
                            fontSize = metrics.fontSize,
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

/**
 * 非快照的 Job 持有器。
 * 避免使用 `mutableStateOf<Job?>`：那会在每次手指抬起/落下的赋值时写入快照状态，
 * 触发一次无效重组。Fling Job 的生命周期只需要跟随手势，不需要驱动 UI。
 */
private class JobHolder {
    var job: Job? = null
    fun set(value: Job) { job = value }
    fun cancelAndClear() {
        job?.cancel()
        job = null
    }
}

/** 列数 → 图标尺寸/字号的收敛映射，避免在每个 item 中重复分支。 */
private data class GridMetrics(
    val iconSize: Dp,
    val fontSize: TextUnit
) {
    companion object {
        fun from(columns: Int): GridMetrics = when (columns) {
            4 -> GridMetrics(62.dp, 12.sp)
            5 -> GridMetrics(52.dp, 11.sp)
            else -> GridMetrics(56.dp, 12.sp)
        }
    }
}

@Composable
private fun AppGridItem(
    app: AppItem,
    iconSize: Dp,
    fontSize: TextUnit,
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
        // 预解码的 ImageBitmap 走主线程零分配直绘；仅当缺失时才准备 Drawable 回退位图。
        val iconBitmap = app.iconBitmap ?: if (app.icon != null) {
            remember(app.icon) {
                try {
                    app.icon.toBitmap(128, 128).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        } else {
            null
        }

        // 图标：走框架级 clip + background + Image 直绘。
        //
        // 实测结论（docs/perf/perf_grid_after-opt_*.md）：改为在 drawBehind 内用
        // clipPath() 手工裁剪虽少一层布局节点，却让每项每帧产生一次真实 canvas clip，
        // 「Slow issue draw commands」由 5 飙到 46，jank 15.7%→21.5%，明显劣化。
        // 原因是 Modifier.clip 走 RenderNode 的 clip 变换（近乎免费），
        // 而 drawBehind 里的 clipPath 必须下发一条绘制指令。故保留框架路径。
        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(DefaultSquircleShape)
                .background(MeeGoCardBg),
            contentAlignment = Alignment.Center
        ) {
            if (iconBitmap != null) {
                // 优先直接绘制异步预渲染的 ImageBitmap，主线程零内存分配
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
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
    // 关键：唤起新应用即刻作废上一次手势滑出的保序锁。
    //
    // 该锁只用于补偿「滑出后守护进程尚未把新顺序落盘」的短暂空窗期；而用户主动
    // 唤起新应用时，系统 recents 中真正的最近使用任务已经换人，此刻仍在置顶旧任务
    // 会把新任务挤到第二位 —— 即「打开 D 再退出后顺序为 A D B C 而非 D A B C」的根因。
    // 锁只活在进程内存中，清掉不影响任何持久化状态，代价为零。
    TaskSnapshotCache.clearSwipeRecord()
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
