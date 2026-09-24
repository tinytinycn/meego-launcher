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
    private var targetPage = androidx.compose.runtime.mutableStateOf<Int?>(null)
    private var targetPageEvent = androidx.compose.runtime.mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式透明状态栏和导航栏
        WindowCompat.setDecorFitsSystemWindows(window, false)

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
            loadApps()
            loadTasks()
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
        handleTargetPage(intent)
        promoteLastSwipedTaskToTop()
        loadTasks()
        window.decorView.postDelayed({ loadTasks() }, 600)
        window.decorView.postDelayed({ loadTasks() }, 1500)
    }

    override fun onResume() {
        super.onResume()
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
        startEdgeServiceIfNeeded()
        // 处于启动器内部时，彻底禁用边缘手势触发区，保证三屏循环滑动不被任何边缘悬浮层拦截
        EdgeSwipeGestureService.setInLauncher(true)
        // 桌面首帧绘制就绪后，通知手势服务撤除底层遮罩，彻底杜绝切回桌面时的界面闪烁
        window.decorView.post {
            EdgeSwipeGestureService.dismissOverlay()
        }
        window.decorView.postDelayed({
            ScreenCaptureHelper.captureLauncherScreen(this, LaunchOriginTracker.lastOrigin)
        }, 400)
    }

    private fun promoteLastSwipedTaskToTop() {
        val topTaskId = LaunchOriginTracker.currentTaskId ?: TaskSnapshotCache.lastSwipedTaskId
        if (topTaskId != null) {
            val idx = recentTasks.indexOfFirst { it.taskId == topTaskId }
            if (idx > 0) {
                val item = recentTasks.removeAt(idx)
                recentTasks.add(0, item)
            }
        }
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
        if (recentTasks.isEmpty()) {
            recentTasks.addAll(newTasks)
            return
        }
        // 同步更新现有列表以保留 item key，触发流畅的重排与顺移位移动画
        val newIds = newTasks.map { it.taskId }.toSet()
        recentTasks.removeAll { it.taskId !in newIds }
        newTasks.forEachIndexed { targetIndex, task ->
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
                ViewCompat.setSystemGestureExclusionRects(rootView, listOf(leftRect, rightRect))
            }
        }
    }
}

// 辅助 stateListOf
private fun <T> mutableStateListOf(): androidx.compose.runtime.snapshots.SnapshotStateList<T> {
    return androidx.compose.runtime.mutableStateListOf()
}
