package com.meego.launcher.ui.screens

import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.meego.launcher.ui.components.SquircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meego.launcher.ui.components.meeGoTopSafeArea
import com.meego.launcher.ui.components.rememberMeeGoBounceController
import com.meego.launcher.ui.components.rememberMeeGoFlingBehavior
import com.meego.launcher.ui.theme.MeeGoCardBg
import com.meego.launcher.ui.theme.MeeGoCyan
import com.meego.launcher.ui.theme.MeeGoRed
import com.meego.launcher.ui.theme.MeeGoTextDim
import com.meego.launcher.ui.theme.MeeGoTextWhite
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH)
        while (true) {
            val now = Date()
            currentTime = timeFormat.format(now)
            currentDate = dateFormat.format(now)
            delay(1000)
        }
    }

    // 与 AppGridScreen / MultitaskingScreen 同源的滚动物理：
    // 自适应增益 FlingBehavior + 触顶/触底对数阻尼橡皮筋回弹。
    val scrollState = rememberScrollState()
    val flingBehavior = rememberMeeGoFlingBehavior()
    val bounceController = rememberMeeGoBounceController()
    val coroutineScope = rememberCoroutineScope()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val flingJob = remember { FeedJobHolder() }

    CompositionLocalProvider(
        LocalOverscrollConfiguration provides null
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .meeGoTopSafeArea()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    // 越界裁剪（必须在 graphicsLayer 之前，理由同 AppGridScreen）：
                    // 触顶/触底时整层内容被 translationY 拖出自身边界，Compose 默认不裁剪，
                    // 会直接盖住状态栏区域。clipToBounds 走 RenderNode clip 变换，开销近乎为零。
                    .clipToBounds()
                    .graphicsLayer {
                        translationY = bounceController.overscrollOffset
                    }
                    // 自研纵向滚动手势驱动（与 AppGridScreen / MultitaskingScreen 同源）。
                    // 背景：本屏此前使用框架内建 verticalScroll(scrollState)，
                    // 在 HorizontalPager 多页共存（beyondBoundsPageCount = 1）场景下，
                    // 其拖拽位移派发会失效（事件被消费但 scrollState.value 不推进），
                    // 表现为“列表不跟手、只有抬起手指后才滚动”，且完全没有触顶/触底回弹。
                    // 此处在 Initial pass 抢先接管纵向拖拽，直接用 scrollState.dispatchRawDelta
                    // 驱动滚动，保证手指 1:1 跟手，并自行接管惯性滑行与橡皮筋回弹。
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
                                    // 而 scrollState.dispatchRawDelta 正值代表向前（内容上移），故取反。
                                    val forListFinger = bounceController.consumeOverscrollForDrag(dy)
                                    val listDelta = -forListFinger
                                    val consumed = scrollState.dispatchRawDelta(listDelta)
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
                                    })
                                } else {
                                    bounceController.releaseOverscroll(releaseVelocity)
                                }
                            }
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
            Spacer(modifier = Modifier.height(28.dp))

            // MeeGo 经典大字时钟
            Text(
                text = currentTime.ifEmpty { "19:00" },
                color = MeeGoTextWhite,
                fontSize = 58.sp,
                letterSpacing = (-1).sp
            )
            Text(
                text = currentDate.ifEmpty { "Tuesday, September 22" },
                color = MeeGoCyan,
                fontSize = 17.sp
            )

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = "NOTIFICATIONS & FEEDS",
                color = MeeGoCyan,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // 置顶两张硬编码卡片保持原有语义，其余条目全部来自下方样例数据，
            // 便于在真机上反复滑动测试列表布局与回弹表现。
            FeedCard(
                title = "MeeGo Launcher v1.3.0",
                body = "Nokia N9 体验已点亮。支持 4/5 列排版与双指 2x2/3x3 缩放。",
                time = "Just now",
                accent = MeeGoCyan
            )

            Spacer(modifier = Modifier.height(12.dp))

            FeedCard(
                title = "Root Privileges Active",
                body = "后台卡片管理通道已建立，支持实时秒切与清理。",
                time = "System",
                accent = MeeGoRed
            )

            Spacer(modifier = Modifier.height(20.dp))

            // —— 以下为滚动测试用样例数据（MOCK），接入真实通知源后可整段删除 ——
            SectionHeader(text = "TODAY")

            SAMPLE_FEED_ITEMS.forEach { item ->
                Spacer(modifier = Modifier.height(12.dp))
                FeedCard(
                    title = item.title,
                    body = item.body,
                    time = item.time,
                    accent = item.accent
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionHeader(text = "EARLIER")
            SAMPLE_FEED_ARCHIVE.forEach { item ->
                Spacer(modifier = Modifier.height(12.dp))
                FeedCard(
                    title = item.title,
                    body = item.body,
                    time = item.time,
                    accent = item.accent
                )
            }

            Spacer(modifier = Modifier.height(36.dp))
                }
            }
        }
    }
}


/**
 * 非快照的 Job 持有器（理由同 AppGridScreen.JobHolder）：
 * 避免 `mutableStateOf<Job?>` 在每次手指抬起/落下时写入快照状态、触发无效重组。
 */
private class FeedJobHolder {
    var job: Job? = null
    fun set(value: Job) { job = value }
    fun cancelAndClear() {
        job?.cancel()
        job = null
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = MeeGoTextDim,
        fontSize = 12.sp,
        letterSpacing = 2.sp
    )
}

@Composable
private fun FeedCard(
    title: String,
    body: String,
    time: String,
    accent: Color = MeeGoCyan
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SquircleShape(cornerRadius = 16.dp))
            .background(MeeGoCardBg)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(SquircleShape(cornerRadius = 4.dp))
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = MeeGoTextWhite,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = time, color = MeeGoTextDim, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = body, color = MeeGoTextDim, fontSize = 13.sp)
    }
}

private data class FeedSample(
    val title: String,
    val body: String,
    val time: String,
    val accent: Color = MeeGoCyan
)

private val SAMPLE_FEED_ITEMS = listOf(
    FeedSample(
        title = "Weather Alert: Rain expected tonight",
        body = "降水概率 78%，预计 21:00 起持续到次日清晨，出门记得带伞。气温将从当前 24°C 降至 17°C。",
        time = "12m"
    ),
    FeedSample(
        title = "Calendar: Design Review",
        body = "10:30 – 11:15 · 三号会议室，参与者：产品、设计、前端。议题为 MeeGo 桌面三屏循环手势验收。",
        time = "1h",
        accent = MeeGoRed
    ),
    FeedSample(
        title = "MeeGo Settings",
        body = "图标列数已更新为 4 列，长按桌面进入编辑模式。",
        time = "2h"
    ),
    FeedSample(
        title = "Multitasking: 5 cards alive",
        body = "后台常驻 5 张卡片，双指捏合可在 2×2 与 3×3 之间无级缩放。",
        time = "3h"
    ),
    FeedSample(
        title = "Battery: 78% · 4h 12m left",
        body = "省电模式未开启，功耗正常。已优化 Feed 列表重组开销。",
        time = "5h"
    ),
    FeedSample(
        title = "Nokia N9 Pull-to-refresh",
        body = "下拉刷新已恢复 MeeGo 经典边缘阻尼手感，松手以弹簧动画回弹。",
        time = "6h"
    ),
    FeedSample(
        title = "Storage: 12.4 GB free",
        body = "已清理 3.2 GB 缩略图缓存，应用图标改为按需解码加载。",
        time = "8h"
    ),
    FeedSample(
        title = "Sync Complete",
        body = "联系人、日历与账户设置已全部同步完成。",
        time = "Yesterday"
    ),
    FeedSample(
        title = "Shortcut Added: Screenshot",
        body = "截图快捷方式已放置到多任务屏常用区域。",
        time = "Yesterday"
    ),
    FeedSample(
        title = "Frame Budget: 8.26ms",
        body = "120Hz 下端到端帧时长 P50 7.17ms / P95 8.09ms，采样帧恰好落在一个预算内。",
        time = "Yesterday"
    ),
    FeedSample(
        title = "Accessibility Service Active",
        body = "边缘划回手势服务已授权，桌面可见时悬浮层自动隐藏。",
        time = "2d"
    ),
    FeedSample(
        title = "12 Updates Read",
        body = "系统更新已全部确认，无待处理的安全补丁。",
        time = "2d"
    )
)

private val SAMPLE_FEED_ARCHIVE = listOf(
    FeedSample(
        title = "Squircle Shape Rollout",
        body = "全局超椭圆（n = 2.732）设计语言落地：卡片、按钮、指示点统一为 SquircleShape。",
        time = "3d"
    ),
    FeedSample(
        title = "Boot Receiver Installed",
        body = "开机自启已注册，开机后自动拉起 MeeGo Launcher。",
        time = "4d"
    ),
    FeedSample(
        title = "Recents: Memory Snapshot",
        body = "后台卡片基于任务栈快照渲染，滑动过程零重绘抖动。",
        time = "5d"
    ),
    FeedSample(
        title = "Theme: Pure Black",
        body = "深色主题使用纯黑背景 0xFF000000，降低 OLED 像素点亮带来的功耗。",
        time = "6d"
    )
)
