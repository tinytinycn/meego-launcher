package com.meego.launcher

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.meego.launcher.data.AppRepository
import com.meego.launcher.data.LauncherPreferences
import com.meego.launcher.data.RootTaskProvider
import com.meego.launcher.model.AppItem
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.model.RecentTask
import com.meego.launcher.model.TaskSnapshotCache
import com.meego.launcher.service.EdgeSwipeGestureService
import com.meego.launcher.ui.screens.HomeScreen
import com.meego.launcher.ui.theme.MeeGoTheme
import com.meego.launcher.util.DaemonWatchdogClient
import com.meego.launcher.util.ScreenCaptureHelper
import android.content.ComponentName
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TARGET_PAGE = "extra_target_page"
    }

    private lateinit var appRepo: AppRepository
    private lateinit var taskProvider: RootTaskProvider
    private lateinit var preferences: LauncherPreferences

    private var apps = mutableStateListOf<AppItem>()
    private var recentTasks = mutableStateListOf<RecentTask>()
    private var gridColumns = androidx.compose.runtime.mutableIntStateOf(4)
    private var recentsColumns = androidx.compose.runtime.mutableIntStateOf(2)
    private var edgeSwipeEnabled = androidx.compose.runtime.mutableStateOf(true)
    private var immersiveUiEnabled = androidx.compose.runtime.mutableStateOf(false)
    private var targetPage = androidx.compose.runtime.mutableStateOf<Int?>(null)
    private var targetPageEvent = androidx.compose.runtime.mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // =========================================================================
        // 全屏沉浸式：隐藏顶部状态栏与底部导航条
        // =========================================================================
        //
        // 设计原则：**隐藏系统栏，而非屏蔽/拦截系统手势**。
        //
        // 此前方案是在应用层与守护脚本层反复压制系统手势
        // （systemGestureExclusionRects、FLAG_NOT_TOUCH_MODAL、底部黑色遮罩），
        // 实测既无法可靠拦下「底部上滑唤出导航条」，又引入额外浮层与性能开销。
        // 改为直接向系统申请隐藏两条系统栏 —— 这是 Android 官方的标准做法：
        // 系统栏隐藏后，其手势区与导航条一并消失，底部上滑自然无从触发，
        // 无需任何对抗性屏蔽。
        //
        // 关键点（真机 Redmi K40 / LineageOS Android 16 行为）：
        //  1. 必须用 WindowInsetsControllerCompat 隐藏（behavior 设为
        //     BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE），手势导航下即生效；
        //  2. 隐藏后导航条 inset 变为 0，内容可自然铺满至 y=2400；
        //  3. 系统仍保留 mandatorySystemGestures，但因导航条已隐藏，
        //     上滑不会再唤出任何东西，故无需 exclusion rects。
        //
        // 恢复方式：用户在系统栏位置下拉/上滑即可临时唤出（transient），
        // 3 秒后自动隐藏，符合现代 Android 桌面惯例。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        appRepo = AppRepository(this)
        taskProvider = RootTaskProvider(this)
        preferences = LauncherPreferences(this)

        handleTargetPage(intent)
        TaskSnapshotCache.clear()

        lifecycleScope.launch {
            gridColumns.intValue = preferences.gridColumnsFlow.first()
            recentsColumns.intValue = preferences.recentsColumnsFlow.first()
            val isEdgeEnabled = preferences.edgeSwipeFlow.first()
            edgeSwipeEnabled.value = isEdgeEnabled
            immersiveUiEnabled.value = preferences.immersiveUiFlow.first()
            loadApps()
            loadTasks()
            // 守护进程存活守卫：仅当主进程已消失而 watchdog 健在时，投递一条
            // ENSURE_DAEMON 让它跳过 15s 宽限期立即补拉；两者都在时零开销返回。
            DaemonWatchdogClient.ensureDaemonRunning(this@MainActivity)
            if (isEdgeEnabled) {
                startEdgeServiceIfNeeded()
            }
            EdgeSwipeGestureService.setGestureEnabled(this@MainActivity, isEdgeEnabled)
        }

        setupSystemGestureExclusion()

        setContent {
            MeeGoTheme {
                HomeScreen(
                    apps = apps,
                    recentTasks = recentTasks,
                    gridColumns = gridColumns.intValue,
                    onSelectColumns = { cols ->
                        gridColumns.intValue = cols
                        lifecycleScope.launch {
                            preferences.setGridColumns(cols)
                        }
                    },
                    recentsColumns = recentsColumns.intValue,
                    onSelectRecentsColumns = { cols ->
                        recentsColumns.intValue = cols
                        lifecycleScope.launch {
                            preferences.setRecentsColumns(cols)
                        }
                    },
                    edgeSwipeEnabled = edgeSwipeEnabled.value,
                    onToggleEdgeSwipe = { enabled ->
                        edgeSwipeEnabled.value = enabled
                        lifecycleScope.launch {
                            preferences.setEdgeSwipe(enabled)
                            EdgeSwipeGestureService.setGestureEnabled(this@MainActivity, enabled)
                            if (enabled) {
                                startEdgeServiceIfNeeded()
                            }
                        }
                    },
                    immersiveUiEnabled = immersiveUiEnabled.value,
                    onToggleImmersiveUi = { enabled ->
                        immersiveUiEnabled.value = enabled
                        lifecycleScope.launch {
                            preferences.setImmersiveUi(enabled)
                        }
                    },
                    taskProvider = taskProvider,
                    onTaskRemoved = { taskId ->
                        recentTasks.removeAll { it.taskId == taskId }
                    },
                    onClearAllTasks = {
                        recentTasks.clear()
                    },
                    targetPage = targetPage.value,
                    targetPageEvent = targetPageEvent.longValue
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        handleTargetPage(intent)
        promoteLastSwipedTaskToTop()
        notifyFrameReadyForDismiss()
    }

    override fun onResume() {
        super.onResume()
        // 从第三方应用划回启动器时系统栏可能被恢复，需重新隐藏
        hideSystemBars()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        promoteLastSwipedTaskToTop()
        loadTasks()
        window.decorView.postDelayed({ loadTasks() }, 600)
        window.decorView.postDelayed({ loadTasks() }, 1500)
        // 兜底：慢设备上系统快照落盘可能晚于 1500ms。
        // 仅在首位卡片仍无快照时追加一次刷新，避免无条件并发加载
        // （v1.8.13 曾因重复并发 loadTasks 引入过卡片位移动画播放两次的竞态）。
        window.decorView.postDelayed({
            if (recentTasks.firstOrNull()?.snapshot == null) {
                loadTasks()
            }
        }, 2500)
        startEdgeServiceIfNeeded()
        // 处于启动器内部时，彻底禁用边缘手势触发区，保证三屏循环滑动不被任何边缘悬浮层拦截
        EdgeSwipeGestureService.setInLauncher(true)
        // 桌面首帧真实绘制并提交渲染后再通知手势服务解散上层遮罩，彻底杜绝闪黑
        notifyFrameReadyForDismiss()
        window.decorView.postDelayed({
            ScreenCaptureHelper.captureLauncherScreen(this, LaunchOriginTracker.lastOrigin)
        }, 400)
    }

    private fun notifyFrameReadyForDismiss() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.viewTreeObserver.registerFrameCommitCallback {
                window.decorView.post {
                    EdgeSwipeGestureService.dismissOverlay()
                }
            }
            window.decorView.invalidate()
        } else {
            window.decorView.postDelayed({
                EdgeSwipeGestureService.dismissOverlay()
            }, 80)
        }
    }

    private fun promoteLastSwipedTaskToTop() {
        // 滑出记录已过期（即用户已唤起新应用）时返回 null，
        // 此时系统 recents 顺序才是权威来源，旧锁不得再干涉。
        val topTaskId = LaunchOriginTracker.currentTaskId ?: TaskSnapshotCache.activeSwipe()?.taskId
        if (topTaskId != null) {
            val idx = recentTasks.indexOfFirst { it.taskId == topTaskId }
            if (idx > 0) {
                val item = recentTasks.removeAt(idx)
                recentTasks.add(0, item)
            }
        }
        // 关键：重排完成后，清空前台追踪状态，彻底杜绝历史残留影响后续打开其他应用
        LaunchOriginTracker.reset()
    }

    override fun onPause() {
        super.onPause()
        // 离开启动器切入第三方应用时，重新激活边缘划回手势服务
        EdgeSwipeGestureService.setInLauncher(false)
        ScreenCaptureHelper.captureLauncherScreen(this, LaunchOriginTracker.lastOrigin)
    }

    private fun handleTargetPage(intent: Intent?) {
        if (intent != null && intent.hasExtra(EXTRA_TARGET_PAGE)) {
            val page = intent.getIntExtra(EXTRA_TARGET_PAGE, 1)
            targetPage.value = page
            targetPageEvent.longValue = System.currentTimeMillis()
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val list = appRepo.getInstalledApps()
            apps.clear()
            apps.addAll(list)
        }
    }

    private fun updateRecentTasks(newTasks: List<RecentTask>) {
        if (newTasks.isEmpty()) {
            // 避免因守护进程轮询空窗期将已有卡片误清空（手动清空由 onClearAllTasks 执行）
            return
        }

        // 强保序锁：如果有刚刚滑出的任务，确保其始终位于列表首位，
        // 绝对禁止因异步数据滞后导致卡片回弹到后方再切回。
        // 记录过期后一律以守护进程（dumpsys activity recents）顺序为准。
        val swipedId = TaskSnapshotCache.activeSwipe()?.taskId
        val effectiveNewTasks = if (swipedId != null) {
            val idx = newTasks.indexOfFirst { it.taskId == swipedId }
            if (idx > 0) {
                val list = newTasks.toMutableList()
                val item = list.removeAt(idx)
                list.add(0, item)
                list
            } else newTasks
        } else newTasks

        if (recentTasks.isEmpty()) {
            recentTasks.addAll(effectiveNewTasks)
            return
        }
        // 同步更新现有列表以保留 item key，触发流畅的重排与顺移位移动画
        val newIds = effectiveNewTasks.map { it.taskId }.toSet()
        recentTasks.removeAll { it.taskId !in newIds }
        effectiveNewTasks.forEachIndexed { targetIndex, task ->
            val currentIndex = recentTasks.indexOfFirst { it.taskId == task.taskId }
            if (currentIndex == -1) {
                recentTasks.add(targetIndex.coerceAtMost(recentTasks.size), task)
            } else if (currentIndex != targetIndex) {
                val item = recentTasks.removeAt(currentIndex)
                recentTasks.add(targetIndex.coerceAtMost(recentTasks.size), item.copy(
                    snapshot = task.snapshot ?: item.snapshot,
                    label = task.label,
                    icon = task.icon ?: item.icon
                ))
            } else {
                val existing = recentTasks[currentIndex]
                val newSnapshot = task.snapshot ?: existing.snapshot
                if (newSnapshot != existing.snapshot || task.label != existing.label) {
                    recentTasks[currentIndex] = existing.copy(
                        snapshot = newSnapshot,
                        label = task.label,
                        icon = task.icon ?: existing.icon
                    )
                }
            }
        }
    }

    private fun loadTasks() {
        lifecycleScope.launch {
            val tasks = taskProvider.getRecentTasks()
            updateRecentTasks(tasks)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, EdgeSwipeGestureService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun startEdgeServiceIfNeeded() {
        if (edgeSwipeEnabled.value) {
            try {
                if (!isAccessibilityServiceEnabled()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val fifo = java.io.File("/data/local/tmp/meego_cmd")
                            if (fifo.exists()) {
                                java.io.FileOutputStream(fifo).use { fos ->
                                    fos.write("ENABLE_A11Y\n".toByteArray())
                                    fos.flush()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to send ENABLE_A11Y command", e)
                        }
                    }
                }
                val intent = Intent(this, EdgeSwipeGestureService::class.java).apply {
                    action = EdgeSwipeGestureService.ACTION_ENABLE
                    putExtra(EdgeSwipeGestureService.EXTRA_ENABLED, true)
                }
                startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 隐藏顶部状态栏与底部导航条，进入真正全屏沉浸式。
     *
     * 采用 [WindowInsetsControllerCompat] 而非已废弃的 `setSystemUiVisibility`：
     * 前者是 AndroidX 官方现代 API，兼容 API 30+ 的手势导航，
     * 且能正确设置 transient 行为 —— 用户在系统栏位置上下滑可临时唤出，
     * 数秒后自动隐藏，不会破坏系统可访问性。
     *
     * 与「屏蔽系统手势」的本质区别：
     * 隐藏系统栏后，导航条及其手势区**从屏幕上消失**，
     * 底部上滑不再有可唤出的目标，因此无需任何对抗性的手势排除逻辑。
     */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // 隐藏状态栏 + 导航条
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // transient：用户从边缘滑动可临时唤出系统栏，随后自动隐藏
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * 窗口重新获得焦点时重新隐藏系统栏。
     *
     * 必要性：从第三方应用划回启动器、或用户唤出系统栏后，
     * 系统会恢复系统栏显示；此时必须重新隐藏，否则会残留一条导航条。
     * 这是所有全屏桌面/沉浸式应用的标配回调。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun setupSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rootView = window.decorView
            rootView.post {
                val height = rootView.height
                val width = rootView.width
                // 全屏左右边缘各 28dp 排除 Android 原生手势冲突
                val density = resources.displayMetrics.density
                val edgeW = (28 * density).toInt()

                val leftRect = Rect(0, 0, edgeW, height)
                val rightRect = Rect(width - edgeW, 0, width, height)

                // 注意：底部手势排除区已移除。
                // 原因：系统栏（状态栏 + 导航条）现已通过
                // WindowInsetsControllerCompat 真正隐藏，其手势区随之消失，
                // 底部上滑不再会唤出导航条，无需再对抗性屏蔽。
                // 仅保留左右两侧排除区，用于避开 Android 原生侧滑返回手势，
                // 保障 MeeGo 自己的边缘切屏手势（changelog v1.5.0 / v1.8.5）。

                ViewCompat.setSystemGestureExclusionRects(
                    rootView,
                    listOf(leftRect, rightRect)
                )
            }
        }
    }
}

// 辅助 stateListOf
private fun <T> mutableStateListOf(): androidx.compose.runtime.snapshots.SnapshotStateList<T> {
    return androidx.compose.runtime.mutableStateListOf()
}
