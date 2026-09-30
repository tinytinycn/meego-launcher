package com.meego.launcher.service

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import com.meego.launcher.MainActivity
import com.meego.launcher.data.LauncherPreferences
import com.meego.launcher.model.LaunchOrigin
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.model.LauncherPreviews
import com.meego.launcher.model.TaskSnapshotCache
import com.meego.launcher.util.AppIconRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

class EdgeSwipeGestureService : AccessibilityService() {
    companion object {
        private const val TAG = "EdgeSwipeService"
        const val ACTION_START = "com.meego.launcher.START_EDGE_SWIPE"
        const val ACTION_STOP = "com.meego.launcher.STOP_EDGE_SWIPE"
        const val ACTION_ENABLE = "com.meego.launcher.ACTION_ENABLE"
        const val ACTION_DISABLE = "com.meego.launcher.ACTION_DISABLE"
        const val EXTRA_ENABLED = "extra_enabled"

        /**
         * Application Grid 层级微缩放的**起始**比例（划出刚起手时的桌面观感）。
         * 随划出进度线性回落到 1.0，形成「从 0.95 倍长回原位」的纵深。
         *
         * 仅对 [com.meego.launcher.model.LaunchOrigin.GRID] 生效；
         * Multitasking View 与 Events Feed 恒为 1.0，不参与缩放。
         */
        private const val GRID_DEPTH_START = 0.95f

        /**
         * 边缘侧滑「滑出应用界面」的判定比例（占屏幕宽度）。
         *
         * 手指横向位移达到 `screenWidth * 1/3` 即越过阈值：
         * 越过的那一刻立刻给出触觉震动（不等抬手），
         * 抬手时若位移仍在阈值之上则顺势飞出屏幕，否则原路弹回。
         */
        private const val SWIPE_OUT_THRESHOLD_RATIO = 1f / 3f

        /**
         * 需要禁用手势的**系统浮层窗口类名**特征串（`AccessibilityEvent.className`）。
         *
         * ① [SHADE_VIEW_CLASS] 通知栏 / 快捷设置下拉面板的窗口
         *    （SystemUI `NotificationShadeWindowView`）
         * ② [RECENTS_VIEW_CLASS] 系统后台卡片（Quickstep / Recents）
         *
         * 【为何按「类名」而非「包名」判定 —— 一条真实的误伤】
         * SystemUI 进程**同时**托管下拉面板与 **IME 输入法窗口**。用户点输入框
         * 弹键盘时，焦点同样会切到 SystemUI（`InputMethodManager` 的
         * `InputMethodWindow`），此时若只按包名判定，**打字时边缘划回会被误禁用**
         * —— 而打字恰恰是最需要边缘划回去「换个界面」的场景。故必须用类名区分。
         *
         * 【为何不能只按类名 —— 系统 Quickstep 宿主包是可配的】
         * Quickstep 宿主包由 `config_recentsComponentName` 决定（LineageOS 默认
         * `com.android.launcher3`），不同 ROM / 用户桌面可能不同。类名
         * `RecentsActivity` / `QuickstepActivity` 才是跨宿主稳定的特征，
         * 故类名判据优先、宿主包仅作补充。
         *
         * 【判据为何只在窗口状态**变化**时求值，而非轮询窗口存在性】
         * 下拉面板与 IME 窗口**都是常驻存在**的（收起/收键盘后窗口仍在），
         * 用「列表里有没有它」当判据会**永久禁用**手势。故只在收到
         * [android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED]
         * ——即窗口**真的**获得了输入焦点——时求值。
         */
        private const val SHADE_VIEW_CLASS = "NotificationShadeWindowView"
        private const val RECENTS_VIEW_CLASS = "RecentsActivity"
        private const val RECENTS_VIEW_CLASS_ALT = "QuickstepActivity"

        /** 类名不可得时的补充判据用宿主包（见 [isSystemOverlayWindow] 的说明）。 */
        private const val SYSTEM_UI_PKG = "com.android.systemui"
        private const val LAUNCHER_PKG = "com.android.launcher3"

        @Volatile
        private var instance: EdgeSwipeGestureService? = null
        @Volatile
        var isAccessibilityConnected = false

        fun dismissOverlay() {
            instance?.postDismissOverlay()
        }

        fun setInLauncher(inLauncher: Boolean) {
            instance?.updateInLauncher(inLauncher)
        }

        fun setGestureEnabled(context: Context, enabled: Boolean) {
            Log.d(TAG, "setGestureEnabled: $enabled")
            instance?.applyGestureEnabled(enabled)
            try {
                val intent = Intent(context, EdgeSwipeGestureService::class.java).apply {
                    action = if (enabled) ACTION_ENABLE else ACTION_DISABLE
                    putExtra(EXTRA_ENABLED, enabled)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to startService with enable/disable action", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // v1.8.45：任何窗口状态变化都触发一次**现查**式重算。
            //
            // 【关键：事件只作「何时去查」，不作「是什么」】
            // 绝不能把「收到 SystemUI 事件」直接等同于「通知栏正在显示」——
            // 真机实测收起动画期间 SystemUI 会补发自己的窗口事件，
            // 那样做会把标记留在错误的 true 上（详见 [refreshSystemOverlayState]）。
            // 无论事件来自谁，一律现查当前窗口状态，由 [querySystemOverlayShowing]
            // 给出唯一结论。
            refreshSystemOverlayState()
            if (pkg != packageName) {
                // 进入了第三方应用（例如系统设置 com.android.settings），确保手势触发区处于激活状态
                updateInLauncher(false)
                if (LaunchOriginTracker.currentPackageName != pkg) {
                    LaunchOriginTracker.currentPackageName = pkg
                    // 关键：包名发生变更，之前的 taskId 必然失效！立即清空，杜绝跨应用残留！
                    LaunchOriginTracker.currentTaskId = null
                    updateCurrentTaskIdForPackage(pkg)
                }
            } else if (event.className?.toString()?.contains("MainActivity") == true) {
                // 仅当真正回到桌面 MainActivity 且不在手势拖拽中时才隐藏触发区
                if (!isDraggingCard) {
                    updateInLauncher(true)
                    LaunchOriginTracker.reset()
                }
            }
        }
    }

    private fun updateCurrentTaskIdForPackage(pkg: String) {
        try {
            val jsonFile = File("/data/local/tmp/recent_tasks.json")
            if (jsonFile.exists() && jsonFile.canRead()) {
                val array = JSONArray(jsonFile.readText().trim())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("packageName") == pkg) {
                        val tid = obj.optInt("taskId", -1)
                        if (tid != -1) {
                            LaunchOriginTracker.currentTaskId = tid
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        isAccessibilityConnected = true
        Log.d(TAG, "EdgeSwipeGestureService onServiceConnected via AccessibilityService")
        // v1.8.45：置位 FLAG_RETRIEVE_INTERACTIVE_WINDOWS。
        //
        // 【为何必须显式置位】本服务 `canRetrieveWindowContent="false"`
        // （见 res/xml/accessibility_service_config.xml），而 AOSP 规定
        // `getWindowsOnAllDisplays()` 必须**同时**具备 `canRetrieveWindowContent`
        // **与** 本标志位，否则**恒返回空列表**。不置位则 [querySystemOverlayShowing]
        // 永远查不到任何窗口，浮层判据会**静默失效**（编译通过、运行不报错，
        // 但手势再也不会被禁用）—— 正是最需要避免的「安静地坏掉」。
        //
        // 【安全性】该标志位只允许服务读取**窗口的元数据与节点结构**，
        // 本服务不取窗口内容、不做界面解析，仅用类名判断窗口身份，隐私影响可忽略。
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        mainHandler.post {
            removeViews()
            createTriggerViews()
            // 底部导航条遮罩已移除：MainActivity 现通过
            // WindowInsetsControllerCompat 真正隐藏系统状态栏与导航条，
            // 无需再用浮层去「盖住」导航条或对抗性屏蔽其手势。
            createCardOverlay()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val safetyDismissRunnable = Runnable {
        postDismissOverlay()
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isGestureEnabledByUser = true
    private var isInLauncher = true

    /**
     * 上一次「系统浮层（通知栏下拉面板 / 系统后台卡片）是否正在显示」的结论。
     *
     * 它是**触发区显隐的第 3 个必要条件**（见 [applyTriggerVisibility]），
     * 唯一由 [setSystemOverlayShowing] 改写，改写来源是无障碍窗口状态变化事件
     * （见 [onAccessibilityEvent]）。之所以缓存成字段而非每次现查：
     * AOSP 的 [AccessibilityService.getWindows] 文档明确要求
     * `canRetrieveWindowContent` + `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 双重声明，
     * 否则**返回空列表**；本服务 `canRetrieveWindowContent="false"`（见
     * `res/xml/accessibility_service_config.xml`），现查永远查不到东西。
     * 故只能走事件驱动，这也是本字段存在的原因。
     */
    private var lastSystemOverlayShowing = false

    fun updateInLauncher(inLauncher: Boolean) {
        isInLauncher = inLauncher
        if (inLauncher) {
            LaunchOriginTracker.reset()
        }
        mainHandler.post {
            applyTriggerVisibility()
        }
    }

    /**
     * 边缘触发区的统一可见性判据（v1.8.45）。
     *
     * 触发区**仅在「用户身处某个应用、且没有系统浮层盖在上面」时**才可见：
     * 1. 不在启动器内（`!isInLauncher`）—— 桌面内本来由 [HomeScreen] 自己处理切屏手势；
     * 2. 用户开关已打开（`isGestureEnabledByUser`）；
     * 3. **通知栏下拉面板与系统 Quickstep 后台卡片**都没有显示
     *    （见 [isSystemOverlayShowing]）—— v1.8.45 新增的第 3 条。
     *
     * 把三态收敛到**唯一**的可见性出口是本次改动的关键：v1.8.44 之前
     * 触发区的显隐散落在 [updateInLauncher] 与 [applyGestureEnabled] 两处各自
     * 计算 `visibility`，而新加的「系统浮层」判据只在其中一处被更新时才会重算
     * —— 即**通知栏收起后触发区不会自己回来**。收敛后，
     * 任何一次系统窗口变化都会经由同一出口重算，不存在「某条路径忘了恢复」。
     */
    private fun applyTriggerVisibility() {
        val visible = !isInLauncher && isGestureEnabledByUser && !lastSystemOverlayShowing
        val visibility = if (visible) View.VISIBLE else View.GONE
        leftTriggerView?.visibility = visibility
        rightTriggerView?.visibility = visibility
        Log.d(
            TAG,
            "applyTriggerVisibility: inLauncher=$isInLauncher, " +
                "isGestureEnabledByUser=$isGestureEnabledByUser, " +
                "systemOverlay=$lastSystemOverlayShowing, trigger visibility=$visibility"
        )
    }

    /**
     * 判定某个窗口是否属于「不该被划出手势介入」的系统浮层。
     *
     * 只看**类名**（见 [SHADE_VIEW_CLASS] / [RECENTS_VIEW_CLASS] 的取舍说明），
     * **不看包名** —— 否则 SystemUI 的输入法窗口会连累打字时的边缘划回。
     *
     * 【为何不看窗口可见性】
     * 可见性由 [querySystemOverlayShowing] 统一把关；本方法只回答「这个窗口
     * **是不是**系统浮层」这一**身份**问题，保持单一职责。
     */
    private fun isSystemOverlayWindow(pkg: String, cls: String?): Boolean {
        if (cls == null) return false
        if (cls.contains(SHADE_VIEW_CLASS)) return true
        if (cls.contains(RECENTS_VIEW_CLASS) || cls.contains(RECENTS_VIEW_CLASS_ALT)) return true
        // 补充判据：类名不可得（部分 ROM 裁剪）时退回宿主包，但必须**排除输入法**，
        // 否则打字时会被误禁用。输入法窗口类名含 InputMethod。
        if (cls.contains("InputMethod", ignoreCase = true)) return false
        return pkg == SYSTEM_UI_PKG || pkg == LAUNCHER_PKG
    }

    /**
     * 依据**当前**窗口状态重算浮层标记，并同步触发区显隐（v1.8.45 真机修正）。
     *
     * 【为何不能只靠事件推导 —— 真机实测出的真实缺陷】
     * 首版把 `lastSystemOverlayShowing` 直接绑在 `TYPE_WINDOW_STATE_CHANGED`
     * 的先后顺序上。真机（Redmi K40 / LineageOS 23.2 / Android 16）实测
     * 「下拉 → 收起」这一条路径，日志为：
     * ```
     * system overlay showing: false      ← 面板交还焦点，标记清零（正确）
     * system overlay showing: true       ← 378ms 后又被置回 true（错误）
     * ```
     * 根因：收起动画期间 SystemUI 会**再补发一次自己的**窗口状态事件，
     * 而首版把「收到 SystemUI 事件」直接等同于「面板正在显示」。
     * 二者并不等价 —— 面板收起后其窗口**仍然存在**（只是不可见），
     * 故「事件到达」≠「面板展开」。于是标记被永久留在错误的 true 上，
     * 表现为「收起通知栏后边缘划回依旧失灵，且再也不自己恢复」。
     *
     * 【修法：事件只作「何时去查」，绝不作为「是什么」的结论】
     * 事件仍用于触发重算，但结论一律由 [querySystemOverlayShowing]
     * **现查当前窗口**得出。收起动画结束时面板窗口 `isVisible` 已转 false，
     * 现查即得 false；那个迟到的 SystemUI 事件再触发一次重算，也只会
     * **再次得到 false**，标记因此无法被留在错误的 true 上。
     */
    private fun refreshSystemOverlayState() {
        setSystemOverlayShowing(querySystemOverlayShowing())
    }

    /**
     * 现查「此刻是否有系统浮层正在显示」。
     *
     * 判据是「**当前持有输入焦点的窗口是不是系统浮层**」。
     *
     * 【为何用焦点而非可见性】`AccessibilityWindowInfo` 的**公开** API 只有
     * `isActive` / `isFocused` / `title` / `type` / `root`，**没有** `isVisible`
     * 与 `className`（`WindowInfo` 里那两个字段是 system 私有，SDK 不可见）。
     * 而焦点恰是更可靠的信号：面板展开时 SystemUI
     * `applyFocusableFlag()` 会移除 `FLAG_NOT_FOCUSABLE`（**夺焦点**），
     * 收起时焦点必然交还应用（已在本机日志中验证该事件确实到达）。
     *
     * 【为何只看焦点窗口】`getWindowsOnAllDisplays()` 的 javadoc 明确说明它
     * 只返回「用户能与之交互」的窗口：被模态面板盖住的窗口**不会**被返回，
     * 焦点窗口则**一定**在其中。故焦点窗口即当前真正的顶层界面，无误判空间。
     *
     * 查不到任何窗口时**保守返回 false**（不误禁手势）：
     * 宁可漏禁一次，也好过把核心功能永久关掉。
     */
    private fun querySystemOverlayShowing(): Boolean {
        val windows = try {
            windowsOnAllDisplays
        } catch (e: Exception) {
            Log.w(TAG, "windowsOnAllDisplays failed, assume no overlay: ${e.message}")
            return false
        } ?: return false

        // SparseArray 无 iterator()，须按下标遍历
        for (i in 0 until windows.size()) {
            val list = windows.valueAt(i) ?: continue
            for (w in list) {
                if (w == null || !w.isActive) continue
                val rootPkg = try {
                    w.root?.packageName?.toString()
                } catch (e: Exception) {
                    null
                }
                val isOverlay = isSystemOverlayWindow(rootPkg.orEmpty(), w.classNameOrNull())
                // 焦点窗口 = 用户此刻真正面对的界面；非焦点窗口不参与判定
                if (isOverlay && w.isFocused) {
                    Log.d(TAG, "system overlay focused: cls=${w.classNameOrNull()} pkg=$rootPkg")
                    return true
                }
            }
        }
        return false
    }

    /**
     * 取窗口类名。
     *
     * `AccessibilityWindowInfo` 公开 API 无 `getClassName()`，只能从
     * `getRoot()`（即窗口的 View 树根节点）取 `className` —— 展开的
     * 下拉面板根节点即 `NotificationShadeWindowView`。
     *
     * `getRoot()` 需要 `canRetrieveWindowContent`（见
     * `res/xml/accessibility_service_config.xml`），取不到时**返回 null**
     * 而非抛异常，保证无权限时优雅降级到 [isSystemOverlayWindow] 的包名补充判据。
     */
    private fun AccessibilityWindowInfo.classNameOrNull(): String? = try {
        root?.className?.toString()
    } catch (e: Exception) {
        null
    }

    /**
     * 系统浮层显隐的唯一状态出口（v1.8.45）。
     *
     * 只在**状态真的翻转**时才重算触发区显隐，避免无障碍事件风暴
     * （面板动画期间 SystemUI 会连发大量窗口事件）引发高频重算。
     */
    private fun setSystemOverlayShowing(showing: Boolean) {
        if (lastSystemOverlayShowing == showing) return
        Log.d(TAG, "system overlay showing: $showing")
        lastSystemOverlayShowing = showing
        mainHandler.post { applyTriggerVisibility() }
    }

    fun applyGestureEnabled(enabled: Boolean) {
        Log.d(TAG, "applyGestureEnabled: enabled=$enabled (was=$isGestureEnabledByUser)")
        isGestureEnabledByUser = enabled
        mainHandler.post {
            if (!enabled) {
                postDismissOverlay()
                // 关闭开关：清空系统手势排除区，彻底释放边缘触控
                leftTriggerView?.visibility = View.GONE
                rightTriggerView?.visibility = View.GONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    leftTriggerView?.systemGestureExclusionRects = emptyList()
                    rightTriggerView?.systemGestureExclusionRects = emptyList()
                }
            } else {
                if (leftTriggerView == null || rightTriggerView == null) {
                    createTriggerViews()
                }
                if (cardOverlayLayout == null) {
                    createCardOverlay()
                }
                // 统一走唯一出口，使「重新开启」也能正确尊重系统浮层判据
                applyTriggerVisibility()
                if (leftTriggerView?.visibility == View.VISIBLE &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ) {
                    leftTriggerView?.let { v ->
                        v.post {
                            if (v.width > 0 && v.height > 0) {
                                v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
                            }
                        }
                    }
                    rightTriggerView?.let { v ->
                        v.post {
                            if (v.width > 0 && v.height > 0) {
                                v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
                            }
                        }
                    }
                }
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var leftTriggerView: View? = null
    private var rightTriggerView: View? = null
    private var leftParams: WindowManager.LayoutParams? = null
    private var rightParams: WindowManager.LayoutParams? = null
    private var edgeWidth: Int = 0

    // 注：原「底部导航条纯黑遮罩与手势屏蔽层」(bottomMaskView/bottomMaskParams) 已整体移除。
    // MainActivity 现通过 WindowInsetsControllerCompat 真正隐藏系统状态栏与导航条，
    // 导航条及其手势区不再显示，无需浮层遮盖或对抗性屏蔽。

    // 全局划出卡片跟随 Overlay
    private var cardOverlayLayout: FrameLayout? = null
    private var desktopBackgroundImageView: ImageView? = null
    private var cardContainer: FrameLayout? = null
    private var cardImageView: ImageView? = null

    private var screenWidth = 1080
    private var screenHeight = 2400

    private var isDraggingCard = false
    private var isCardAnimatingOut = false
    private var pendingDismiss = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        createTriggerViews()
        createCardOverlay()

        serviceScope.launch {
            try {
                LauncherPreferences(this@EdgeSwipeGestureService).edgeSwipeFlow.collect { enabled ->
                    applyGestureEnabled(enabled)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting edgeSwipeFlow in EdgeSwipeGestureService", e)
            }
        }
    }

    fun scheduleSafetyDismiss() {
        mainHandler.removeCallbacks(safetyDismissRunnable)
        mainHandler.postDelayed(safetyDismissRunnable, 400)
    }

    fun cancelSafetyDismiss() {
        mainHandler.removeCallbacks(safetyDismissRunnable)
    }

    fun postDismissOverlay() {
        mainHandler.post {
            if (isCardAnimatingOut) {
                pendingDismiss = true
            } else {
                executeSmoothDismiss()
            }
        }
    }

    private fun executeSmoothDismiss() {
        cancelSafetyDismiss()
        pendingDismiss = false
        isCardAnimatingOut = false
        setGestureActive(false)

        val overlay = cardOverlayLayout ?: return
        if (overlay.visibility != View.VISIBLE) return

        // 真实桌面首帧已在底层就绪，上层快照以 60ms 极速平滑淡出，两层画面无缝交融，杜绝任何黑屏与闪烁
        overlay.animate()
            ?.alpha(0f)
            ?.setDuration(60)
            ?.setInterpolator(DecelerateInterpolator())
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.visibility = View.GONE
                    // 注意：绝不在此处重置 overlay.alpha = 1.0f 或 cardContainer 状态！
                    // 避免 SurfaceFlinger 在窗口隐藏过程中的异步帧被重置状态污染而产生短暂闪黑/闪烁。
                    // 状态复位统一移至下一次手势触发 startSwipeOutAnimation() 时进行。
                }
            })
            ?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, ACTION_DISABLE -> {
                applyGestureEnabled(false)
            }
            ACTION_START, ACTION_ENABLE -> {
                applyGestureEnabled(true)
            }
        }
        if (intent?.hasExtra(EXTRA_ENABLED) == true) {
            val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
            applyGestureEnabled(enabled)
        }
        return START_STICKY
    }

    private fun getOverlayType(): Int {
        return if (isAccessibilityConnected) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun setGestureActive(active: Boolean) {
        try {
            val flagFile = File("/data/local/tmp/gesture_active")
            if (active) {
                if (!flagFile.exists()) {
                    flagFile.createNewFile()
                    flagFile.setReadable(true, false)
                    flagFile.setWritable(true, false)
                }
            } else {
                if (flagFile.exists()) {
                    flagFile.delete()
                }
            }
        } catch (e: Exception) {
            // Ignore flag file error
        }
    }

    private fun createTriggerViews() {
        if (windowManager == null || leftTriggerView != null) return

        // 覆盖屏幕左右两侧完整边缘，宽度 28dp
        edgeWidth = (28 * resources.displayMetrics.density).toInt()

        leftParams = WindowManager.LayoutParams(
            edgeWidth,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        leftTriggerView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = if (isInLauncher) View.GONE else View.VISIBLE
            setOnTouchListener(createEdgeTouchListener(isLeft = true))
            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
                }
            }
        }

        rightParams = WindowManager.LayoutParams(
            edgeWidth,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 0
            alpha = 1.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        rightTriggerView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = if (isInLauncher) View.GONE else View.VISIBLE
            setOnTouchListener(createEdgeTouchListener(isLeft = false))
            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
                }
            }
        }

        try {
            windowManager?.addView(leftTriggerView, leftParams)
            windowManager?.addView(rightTriggerView, rightParams)
            Log.d(TAG, "MeeGo edge triggers created successfully with exclusion rects (initial inLauncher=$isInLauncher, type=${leftParams?.type})")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding trigger views", e)
        }
    }

    private fun createCardOverlay() {
        if (windowManager == null || cardOverlayLayout != null) return

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            alpha = 1.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        cardOverlayLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }

        // 真实底层桌面背景层 (Application Grid 或 Multitasking View 快照)
        //
        // 关键：这里**不能**用 MATCH_PARENT + FIT_XY 铺满整个 Overlay。
        // 桌面快照的位图尺寸 == 桌面窗口尺寸（挖孔机型上被挖孔 inset 顶掉，如
        // Redmi K40 窗口 [0,95][1080,2400] = 1080x2305），而本 Overlay 是带
        // FLAG_LAYOUT_NO_LIMITS 的全屏 1080x2400。直接 FIT_XY 铺满会把画面纵向
        // 拉伸 1.0412 倍并把顶部 95px 留白撑没 —— 即「划出过程中 grid 顶到屏幕
        // 最上沿铺满全屏」的根因。
        //
        // 正确做法：背景层始终与 Overlay 等尺寸（保证不透明底色铺满、无透光），
        // 但把 ImageView 自身按**快照真实屏幕矩形**定位并缩放，图层始终以 FIT_XY
        // 绘制 —— 组合结果即「快照 1:1 贴回它在物理屏幕上的原始位置」。
        desktopBackgroundImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 1.0f
        }
        cardOverlayLayout?.addView(desktopBackgroundImageView)

        // 3. 镜像应用界面卡片 (具有 MeeGo 圆角 squircle 轮廓与阴影)
        cardContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val cornerRadius = 28 * resources.displayMetrics.density
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
            elevation = 20 * resources.displayMetrics.density
        }

        cardImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_XY
        }

        cardContainer?.addView(cardImageView)
        cardOverlayLayout?.addView(cardContainer)

        try {
            windowManager?.addView(cardOverlayLayout, overlayParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding cardOverlayLayout", e)
        }
    }

    private fun createEdgeTouchListener(isLeft: Boolean): View.OnTouchListener {
        var startRawX = 0f
        var isExpanded = false
        // 本次手势是否已经越过滑出阈值并震动过：用于保证阈值只震一次（且不在抬手时重复震）
        var hasVibratedThisGesture = false

        return View.OnTouchListener { v, event ->
            if (isInLauncher || !isGestureEnabledByUser) {
                return@OnTouchListener false
            }
            // v1.8.45 第二道防线：浮层刚展开、触发区 GONE 尚未生效的那一帧，
            // 落指时也当场复核一次状态，保证「通知栏/后台卡片打开期间侧滑」在任何时序下
            // 都不会划出卡片。
            if (event.actionMasked == MotionEvent.ACTION_DOWN && lastSystemOverlayShowing) {
                Log.d(TAG, "Edge touch ignored: system overlay (shade/recents) is showing")
                return@OnTouchListener false
            }
            val params = if (isLeft) leftParams else rightParams

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    isDraggingCard = false
                    hasVibratedThisGesture = false
                    Log.d(TAG, "Touch ACTION_DOWN at rawX=$startRawX, isLeft=$isLeft")

                    if (params != null && !isExpanded) {
                        params.width = WindowManager.LayoutParams.MATCH_PARENT
                        params.height = WindowManager.LayoutParams.MATCH_PARENT
                        windowManager?.updateViewLayout(v, params)
                        isExpanded = true
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = if (isLeft) (event.rawX - startRawX) else (startRawX - event.rawX)
                    if (deltaX > 25 && !isDraggingCard) {
                        isDraggingCard = true
                        startSwipeOutAnimation(isLeft)
                        Log.d(TAG, "Edge drag triggered card animation! isLeft=$isLeft, deltaX=$deltaX")
                    }

                    if (isDraggingCard) {
                        updateCardTransform(isLeft, deltaX)
                        // 手指「滑过」阈值即刻震动，不等抬手 —— 触觉与手指动作实时同步。
                        // 单调性：deltaX 只增不减，故自动锁定为仅首次越过时震一次。
                        if (!hasVibratedThisGesture && deltaX >= swipeOutThreshold()) {
                            hasVibratedThisGesture = true
                            vibrate()
                            Log.d(TAG, "Swipe crossed threshold in-flight: deltaX=$deltaX, threshold=${swipeOutThreshold()}")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingCard) {
                        val deltaX = if (isLeft) (event.rawX - startRawX) else (startRawX - event.rawX)
                        Log.d(TAG, "Swipe release: deltaX=$deltaX, threshold=${swipeOutThreshold()}")
                        finishSwipeOut(isLeft, deltaX)
                        isDraggingCard = false
                    }
                    hasVibratedThisGesture = false

                    if (params != null && isExpanded) {
                        params.width = edgeWidth
                        params.height = WindowManager.LayoutParams.MATCH_PARENT
                        windowManager?.updateViewLayout(v, params)
                        isExpanded = false
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun getCurrentTaskId(): Int? {
        val curPkg = LaunchOriginTracker.currentPackageName

        // 1. 优先读取守护进程实时探测记录的前台 Task ID（真实运行中应用的任务栈 ID）
        val fileTaskId = readTaskIdFromFile()
        val filePkg = readCurrentPkgFromFile()

        if (fileTaskId != null) {
            // 若守护进程记录了包名且与当前包名匹配，或当前包名为 null，强力优先采用
            if (curPkg == null || filePkg == null || filePkg == curPkg) {
                LaunchOriginTracker.currentTaskId = fileTaskId
                if (filePkg != null && LaunchOriginTracker.currentPackageName == null) {
                    LaunchOriginTracker.currentPackageName = filePkg
                }
                return fileTaskId
            }
        }

        // 2. 如果是从多任务卡片明确点击唤起的任务，使用 trackerTaskId
        val trackerTaskId = LaunchOriginTracker.currentTaskId
        if (trackerTaskId != null) {
            return trackerTaskId
        }

        // 3. 从 recent_tasks.json 按当前包名匹配
        if (curPkg != null) {
            val foundId = findTaskIdForPackage(curPkg)
            if (foundId != null) {
                LaunchOriginTracker.currentTaskId = foundId
                return foundId
            }
        }

        return fileTaskId
    }

    private fun readTaskIdFromFile(): Int? {
        return readIntFromFile(File(filesDir, "current_task_id.txt"))
            ?: readIntFromFile(File("/data/local/tmp/current_task_id.txt"))
    }

    private fun readCurrentPkgFromFile(): String? {
        return readTextFromFile(File(filesDir, "current_app_pkg.txt"))
            ?: readTextFromFile(File("/data/local/tmp/current_app_pkg.txt"))
    }

    private fun readIntFromFile(file: File): Int? {
        return try {
            if (file.exists() && file.canRead()) {
                file.readText().trim().toIntOrNull()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun readTextFromFile(file: File): String? {
        return try {
            if (file.exists() && file.canRead()) {
                val t = file.readText().trim()
                if (t.isNotEmpty()) t else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun findTaskIdForPackage(pkg: String): Int? {
        return try {
            val candidateFiles = listOf(
                File(filesDir, "recent_tasks.json"),
                File("/data/local/tmp/recent_tasks.json")
            )
            for (f in candidateFiles) {
                if (f.exists() && f.canRead()) {
                    val text = f.readText().trim()
                    if (text.isNotEmpty() && text != "[]") {
                        val array = JSONArray(text)
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            if (obj.optString("packageName") == pkg) {
                                val tid = obj.optInt("taskId", -1)
                                if (tid != -1) return tid
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun startSwipeOutAnimation(isLeft: Boolean) {
        setGestureActive(true)
        cancelSafetyDismiss()
        pendingDismiss = false
        isCardAnimatingOut = false
        cardOverlayLayout?.animate()?.cancel()
        cardOverlayLayout?.alpha = 1.0f

        // 1. 设置当前运行应用的卡片画面
        val snapBitmap = loadLatestSnapshot()
        val curTaskId = getCurrentTaskId()
        val curPkg = LaunchOriginTracker.currentPackageName ?: readCurrentPkgFromFile()

        if (snapBitmap != null) {
            cardImageView?.setImageBitmap(snapBitmap)
            cardImageView?.scaleType = ImageView.ScaleType.FIT_XY
            cardImageView?.setBackgroundColor(Color.TRANSPARENT)
            // 滑出瞬间的画面本质是过渡态：显式标记 isSettled=false，
            // 并用帧文件的真实 mtime 作为抓取时刻，避免把过渡帧伪装成"最新且稳定"的帧
            val srcTime = snapshotCandidates(curTaskId ?: -1, curPkg)
                .filter { it.exists() }
                .maxOfOrNull { it.lastModified() } ?: System.currentTimeMillis()
            if (curTaskId != null) {
                TaskSnapshotCache.put(curTaskId, snapBitmap, curPkg, srcTime, isSettled = false)
            }
            // 经 recordSwipe 统一写入并盖上时刻戳，滑出保序锁才会被正确计时
            TaskSnapshotCache.recordSwipe(curTaskId, curPkg, snapBitmap)
        } else {
            // 没有快照时，优雅显示当前应用的图标与深色背景，绝不串扰其他应用
            val icon = if (curPkg != null) {
                try {
                    packageManager.getApplicationIcon(curPkg)
                } catch (e: Exception) {
                    null
                }
            } else null

            if (icon != null) {
                // 无快照时的兜底图标同样剥离系统圆形 mask，与桌面网格形状口径一致
                val iconBmp = AppIconRenderer.toBitmap(icon, 192)
                cardImageView?.setImageBitmap(iconBmp)
                cardImageView?.scaleType = ImageView.ScaleType.CENTER
                cardImageView?.setBackgroundColor(Color.parseColor("#1C1C1E"))
            } else {
                cardImageView?.setImageBitmap(null)
                cardImageView?.setBackgroundColor(Color.parseColor("#1C1C1E"))
            }
        }

        // 2. 加载来源桌面 (Application Grid 或 Multitasking View) 快照作为底层揭示内容
        val desktopBitmap = loadOriginDesktopSnapshot()
        if (desktopBitmap != null) {
            desktopBackgroundImageView?.setImageBitmap(desktopBitmap)
            desktopBackgroundImageView?.visibility = View.VISIBLE
            // 关键：Overlay 底色**必须保持不透明黑**。
            //
            // 快照只覆盖桌面窗口那块矩形（挖孔机型上顶部 95px 留白不在窗口内），
            // 若底色设为 TRANSPARENT，这 95px 就会透出下层正在运行的三方应用 ——
            // 实测表现为划出过程中顶部露出一条 Via 浏览器的白色页面带。
            // 真实桌面首帧在该区域同样是纯黑，故不透明黑底才是与真实画面一致的还原。
            cardOverlayLayout?.setBackgroundColor(Color.BLACK)
            // 按快照真实屏幕矩形定位（顶部保留挖孔留白、1:1 不拉伸）
            desktopBackgroundImageView?.post {
                applyDesktopBackgroundGeometry(desktopBitmap)
            }
            // Overlay 首帧可能尚未完成布局：若已有宽高则立即定位一次，杜绝首帧拉伸闪烁
            applyDesktopBackgroundGeometry(desktopBitmap)
            // 复位层级微缩放：每次手势都从 0.95 重新起步，杜绝上一次手势的残留缩放
            desktopBackgroundImageView?.animate()?.cancel()
            desktopBackgroundImageView?.scaleX = 1f
            desktopBackgroundImageView?.scaleY = 1f
            desktopBackgroundImageView?.translationX = 0f
            desktopBackgroundImageView?.translationY = 0f
        } else {
            desktopBackgroundImageView?.visibility = View.GONE
            cardOverlayLayout?.setBackgroundColor(Color.BLACK)
        }
        desktopBackgroundImageView?.alpha = 1.0f

        cardContainer?.visibility = View.VISIBLE
        cardContainer?.translationX = 0f
        cardContainer?.scaleX = 1f
        cardContainer?.scaleY = 1f
        cardOverlayLayout?.visibility = View.VISIBLE
    }

    private fun updateCardTransform(isLeft: Boolean, deltaX: Float) {
        val progress = (deltaX / screenWidth).coerceIn(0f, 1f)
        val translateX = if (isLeft) deltaX else -deltaX
        val scale = 1.0f - (progress * 0.12f) // 随着拖拽轻微缩放至 0.88

        cardContainer?.translationX = translateX
        cardContainer?.scaleX = scale
        cardContainer?.scaleY = scale

        // 底层桌面保持 100% 不透明；其 1:1 贴回定位由 [applyDesktopBackgroundGeometry] 负责。
        //
        // 此处**只**叠加 GRID 专属的层级微缩放（见 [applyGridDepthScale]）：
        // 缩放锚点已由 [applyDesktopScalePivot] 钉在物理屏幕中心，故画面表现为
        // 从 0.95 倍「长」回 1.0 倍、四边对称内收，不会朝任何角落漂移。
        // Multitasking / Events Feed 恒为 1.0，不受影响。
        desktopBackgroundImageView?.alpha = 1.0f
        applyGridDepthScale(progress)
    }

    /**
     * Application Grid 专属的层级微缩放：0.95 → 1.0 随划出进度推进。
     *
     * **仅对 Application Grid 生效** —— 判据是
     * [LaunchOriginTracker.currentScreenIndex]（用户此刻所在页序 == 1），
     * 而**不是** [LaunchOriginTracker.lastOrigin]（应用从哪屏唤起）：
     * Events Feed 从不写入 `lastOrigin`，停留在 Feed 时它仍停留在上一次的
     * GRID/RECENTS，用它判断会给 Feed 错误地加上缩放动画。
     * 故此判据同时精确排除了 **Multitasking View**（页序 2）与
     * **Events Feed**（页序 0），两者恒为 1.0。
     *
     * 缩放锚点取**物理屏幕中心**：见 [applyDesktopScalePivot]。
     *
     * 此处**只**改 `scaleX/scaleY`、且 `translation` 恒为 0：
     * Android 的 `View.scaleX/scaleY` 本就绕 `pivot` 缩放（AOSP View#setPivotX
     * 原文："By default, the pivot point is centered on the object"），
     * 故当 pivot 被 [applyDesktopScalePivot] 钉在屏幕中心时，
     * 改 scale 即自动满足「屏幕中心不动、四边对称内收」，无需任何平移补偿。
     *
     * 此前版本额外叠加了 `translation = center * (1 - s)`，那是**重复补偿**：
     * 中心早已被 pivot 钉住，再加一次平移就把画面整体推向右下 —— 即用户报的
     * 「缩放动画偏右下」。平移在此已彻底移除。
     *
     * @param progress 划出进度 0..1（0 = 刚起手，1 = 划过 1/3 屏）
     */
    private fun applyGridDepthScale(progress: Float) {
        val iv = desktopBackgroundImageView ?: return
        if (LaunchOriginTracker.currentScreenIndex != LaunchOrigin.GRID.pageIndex) {
            // 非 Grid 页（Events Feed / Multitasking View）：确保复位，
            // 避免上一次 Grid 手势的残留缩放串到别屏
            if (iv.scaleX != 1f || iv.scaleY != 1f ||
                iv.translationX != 0f || iv.translationY != 0f
            ) {
                iv.scaleX = 1f
                iv.scaleY = 1f
                iv.translationX = 0f
                iv.translationY = 0f
            }
            return
        }

        val s = GRID_DEPTH_START + (1f - GRID_DEPTH_START) * progress
        iv.scaleX = s
        iv.scaleY = s
        iv.translationX = 0f
        iv.translationY = 0f
    }

    /**
     * 把桌面快照的缩放锚点（pivot）钉在**物理屏幕中心**。
     *
     * 为什么不直接用 Android 的默认锚点：默认 pivot 是 View **自身**的中心。
     * 而本图层按 [applyDesktopBackgroundGeometry] 只覆盖桌面窗口那块矩形
     * （挖孔机型上是 `[0,95][1080,2400]`，顶掉了 95px 挖孔），
     * 其自身中心比屏幕中心低约 47px；绕它缩放，画面会整体朝下漂一点。
     * 要真正「以屏幕为中心」，必须显式把 pivot 设到屏幕中心。
     *
     * 坐标系推导（View 本地坐标 → 物理屏幕）：
     * `screen = view.left + scale * (p - pivot) + pivot`，
     * 令屏幕中心 `C = overlayW/2` 为缩放不动点（对任意 scale 恒成立）得
     * `pivotX = C - view.left`、`pivotY = C' - view.top`。
     *
     * 注：显式设置 pivot 会**永久关闭** Android 的自动居中行为
     * （`mRenderNode.isPivotExplicitlySet()`），故本方法必须覆盖所有布局路径
     * —— 由 [applyDesktopBackgroundGeometry] 在布局生效后统一调用，
     * 含全屏兜底分支。
     */
    private fun applyDesktopScalePivot() {
        val iv = desktopBackgroundImageView ?: return
        val overlayW = cardOverlayLayout?.width ?: 0
        val overlayH = cardOverlayLayout?.height ?: 0
        if (overlayW <= 0 || overlayH <= 0 || iv.width <= 0 || iv.height <= 0) return

        iv.pivotX = overlayW * 0.5f - iv.left
        iv.pivotY = overlayH * 0.5f - iv.top
    }

    /** 滑出阈值：屏幕宽度的 1/3（见 [SWIPE_OUT_THRESHOLD_RATIO]）。 */
    private fun swipeOutThreshold(): Float = screenWidth * SWIPE_OUT_THRESHOLD_RATIO

    private fun finishSwipeOut(isLeft: Boolean, deltaX: Float) {
        val threshold = swipeOutThreshold()
        if (deltaX >= threshold) {
            // 达到 1/3 屏幕: 顺势飞出屏幕 (Slide-out)
            val targetX = if (isLeft) screenWidth.toFloat() else -screenWidth.toFloat()
            isCardAnimatingOut = true
            pendingDismiss = false

            // 确保将当前滑出界面的真实快照存入 TaskSnapshotCache，供 Multitasking View 立即渲染
            val currentTaskId = getCurrentTaskId()
            val curPkg = LaunchOriginTracker.currentPackageName ?: readCurrentPkgFromFile()
            val snapBitmap = (cardImageView?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (snapBitmap != null && cardImageView?.scaleType == ImageView.ScaleType.FIT_XY) {
                val srcTime = snapshotCandidates(currentTaskId ?: -1, curPkg)
                    .filter { it.exists() }
                    .maxOfOrNull { it.lastModified() } ?: System.currentTimeMillis()
                if (currentTaskId != null) {
                    TaskSnapshotCache.put(currentTaskId, snapBitmap, curPkg, srcTime, isSettled = false)
                }
                // 经 recordSwipe 统一写入并盖上时刻戳，滑出保序锁才会被正确计时
                TaskSnapshotCache.recordSwipe(currentTaskId, curPkg, snapBitmap)
            }

            // 1. 立即以无动画模式唤起桌面，让系统在后台并行装载并渲染 MainActivity
            triggerReturnToLauncher()

            cardContainer?.animate()
                ?.translationX(targetX)
                ?.scaleX(0.85f)
                ?.scaleY(0.85f)
                ?.setDuration(200)
                ?.setInterpolator(DecelerateInterpolator())
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isCardAnimatingOut = false
                        cardContainer?.visibility = View.GONE
                        if (pendingDismiss) {
                            executeSmoothDismiss()
                        } else {
                            scheduleSafetyDismiss()
                        }
                    }
                })
                ?.start()
        } else {
            // 未达到 1/3 屏幕: 原路弹回全屏 (Spring back)
            cardContainer?.animate()
                ?.translationX(0f)
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(250)
                ?.setInterpolator(OvershootInterpolator(1.2f))
                ?.start()

            // 底层桌面：透明度收尾 + 复位层级微缩放。
            //
            // 1:1 贴回定位由 [applyDesktopBackgroundGeometry] 用 layoutParams 设定，
            // **不能**对其 scale 做「回到 1.0」的补间动画 —— 那会在收尾期间
            // 与贴回定位打架，表现为交接瞬间一帧跳动。这里直接一次性归位：
            // Overlay 随即被 GONE 隐藏，复位过程对用户不可见；
            // 且 [startSwipeOutAnimation] 每次也会复位，绝无跨手势残留。
            desktopBackgroundImageView?.animate()
                ?.alpha(1.0f)
                ?.setDuration(250)
                ?.setInterpolator(OvershootInterpolator(1.2f))
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        desktopBackgroundImageView?.scaleX = 1f
                        desktopBackgroundImageView?.scaleY = 1f
                        desktopBackgroundImageView?.translationX = 0f
                        desktopBackgroundImageView?.translationY = 0f
                        cardOverlayLayout?.visibility = View.GONE
                        setGestureActive(false)
                    }
                })
                ?.start()
        }
    }

    private fun triggerReturnToLauncher() {
        val targetPage = LaunchOriginTracker.lastOrigin.pageIndex
        val intent = Intent().apply {
            setClassName(packageName, "com.meego.launcher.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra(MainActivity.EXTRA_TARGET_PAGE, targetPage)
            putExtra("ts", System.currentTimeMillis())
        }
        val options = ActivityOptions.makeCustomAnimation(this, 0, 0)
        startActivity(intent, options.toBundle())
    }

    private fun loadOriginDesktopSnapshot(): Bitmap? {
        val origin = LaunchOriginTracker.lastOrigin
        val bmp = when (origin) {
            LaunchOrigin.GRID -> {
                LauncherPreviews.gridBitmap
                    ?: loadBitmapFromFile(File(filesDir, "app_grid_preview.png").absolutePath)
                    ?: loadBitmapFromFile("/data/local/tmp/app_grid_preview.png")
            }
            LaunchOrigin.RECENTS -> {
                LauncherPreviews.recentsBitmap
                    ?: loadBitmapFromFile(File(filesDir, "recents_preview.png").absolutePath)
                    ?: loadBitmapFromFile("/data/local/tmp/recents_preview.png")
            }
        }
        if (bmp != null) return bmp

        // Fallback: 若对应快照暂未就绪，尝试读取任意已有快照
        return LauncherPreviews.gridBitmap
            ?: loadBitmapFromFile(File(filesDir, "app_grid_preview.png").absolutePath)
            ?: loadBitmapFromFile("/data/local/tmp/app_grid_preview.png")
            ?: LauncherPreviews.recentsBitmap
            ?: loadBitmapFromFile(File(filesDir, "recents_preview.png").absolutePath)
            ?: loadBitmapFromFile("/data/local/tmp/recents_preview.png")
    }

    /**
     * 取当前来源桌面的**真实屏幕矩形**（快照位图左上角在物理屏幕上的位置）。
     *
     * 内存路径带 [LauncherPreviews] 记录的 rect；从文件回读（进程重启、内存位图被清）
     * 时 rect 丢失，此时按「屏幕高 − 快照高 = 顶部挖孔 inset」复原。
     * 无挖孔机型上快照高 == 屏幕高，该式自然退化为 0，无需特判。
     */
    private fun originDesktopScreenRect(bitmap: Bitmap): Rect {
        val remembered = when (LaunchOriginTracker.lastOrigin) {
            LaunchOrigin.GRID -> LauncherPreviews.gridWindowRect
            LaunchOrigin.RECENTS -> LauncherPreviews.recentsWindowRect
        }
        if (remembered != null && remembered.width() > 0 && remembered.height() > 0) {
            return Rect(remembered)
        }
        val top = (screenHeight - bitmap.height).coerceAtLeast(0)
        return Rect(0, top, bitmap.width, top + bitmap.height)
    }

    /**
     * 把桌面背景层按快照真实矩形定位。
     *
     * 本 Overlay 带 `FLAG_LAYOUT_NO_LIMITS` 且为 MATCH_PARENT，坐标系**就是物理屏幕**
     * （真机实测 frame=[0,0][1080,2400]），故窗口矩形可直接用作布局边距，
     * 快照得以 1:1 贴回原始位置：顶部 95px 挖孔留白保留，画面不再被纵向拉伸。
     *
     * 未覆盖的挖孔区由 `cardOverlayLayout` 的不透明黑底兜底 ——
     * 这与真实桌面首帧在该区域的呈现完全一致，交接瞬间零差异。
     */
    private fun applyDesktopBackgroundGeometry(bitmap: Bitmap) {
        val iv = desktopBackgroundImageView ?: return
        val overlayW = cardOverlayLayout?.width ?: 0
        val overlayH = cardOverlayLayout?.height ?: 0
        if (overlayW <= 0 || overlayH <= 0) return

        val rect = originDesktopScreenRect(bitmap)
        // 矩形必须完整落在 Overlay 内；否则说明坐标系假设失效，退回铺满（保持旧行为，绝不露出异常画面）
        if (rect.width() <= 0 || rect.height() <= 0 ||
            rect.left < 0 || rect.top < 0 ||
            rect.right > overlayW || rect.bottom > overlayH
        ) {
            iv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            iv.translationX = 0f
            iv.translationY = 0f
            // 兜底分支同样要钉 pivot：全屏时 View 中心 == 屏幕中心，
            // 漏设会让 pivot 保持上一次手势的旧值而缩放跑偏
            iv.post { applyDesktopScalePivot() }
            Log.w(TAG, "desktop bg rect $rect does not fit overlay ${overlayW}x$overlayH; fallback to full-bleed")
            return
        }

        iv.layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }
        iv.translationX = 0f
        iv.translationY = 0f
        // layoutParams 变更要到下一次 layout 才反映到 iv.left/iv.top，
        // 故先 post 一次在布局后重算 pivot，再兜底补一次防首帧缺失
        iv.post { applyDesktopScalePivot() }
        applyDesktopScalePivot()
        Log.d(TAG, "desktop bg placed 1:1 at $rect (overlay ${overlayW}x$overlayH, bitmap ${bitmap.width}x${bitmap.height})")
    }

    private fun loadBitmapFromFile(path: String): Bitmap? {
        return try {
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun loadLatestSnapshot(): Bitmap? {
        return try {
            val currentTaskId = getCurrentTaskId() ?: return null
            val curPkg = LaunchOriginTracker.currentPackageName ?: readCurrentPkgFromFile()

            // a. 内存快路径：滑出动画需要即时性。若内存帧已确认稳定，且不早于任何文件候选
            //    的最新 mtime，则直接采用，避免解码开销。
            val memEntry = TaskSnapshotCache.getEntry(currentTaskId)
            if (memEntry != null && memEntry.isSettled) {
                val newestFileTime = snapshotCandidates(currentTaskId, curPkg)
                    .maxOfOrNull { f -> if (f.exists()) f.lastModified() else 0L } ?: 0L
                if (memEntry.captureTimeMs >= newestFileTime) {
                    Log.d(TAG, "Using settled in-memory snapshot for task $currentTaskId ($curPkg)")
                    return memEntry.bitmap
                }
            }

            // b. 文件路径：唯一来源 ${tid}_sys.jpg（常驻服务产物）。
            //
            // 【为何不再需要「稳定池 / provisional 帧」两级仲裁】
            // 那是为 screencap 整屏帧设计的：启动动画期抓到的帧带
            // _live.provisional 标记，需与系统快照分开仲裁。而常驻服务对
            // **可见**任务走 takeTaskSnapshot（11~42ms），应用还在启动动画时
            // 就能取到当前这一帧；**后台**任务走 getTaskSnapshot，拿到的就是
            // 系统在「任务变不可见」那一刻抓下的最终态。两种来源都是「真实」帧，
            // 不存在「半渲染过渡帧」这一档，故 provisional 机制整体失去存在意义。
            //
            // 【为何取不到时直接返回 null，而不是找替代品】
            // 唯一候选不存在 ⇒ 此刻确实没有任何画面。返回一个图标占位，比返回
            // 一张混入状态栏/导航栏的整屏截图更诚实（产品已确认该行为）。
            val chosen = snapshotCandidates(currentTaskId, curPkg)
                .firstOrNull { it.exists() && it.canRead() && it.length() > 0 }

            if (chosen != null) {
                val bmp = loadBitmapFromFile(chosen.absolutePath)
                if (bmp != null) {
                    TaskSnapshotCache.put(
                        currentTaskId, bmp, curPkg,
                        captureTimeMs = chosen.lastModified(),
                        isSettled = true
                    )
                    Log.d(TAG, "Picked task snapshot ${chosen.name} for task $currentTaskId ($curPkg)")
                    return bmp
                }
            }

            // c. 该任务确实一个可用文件都没有（守护进程尚未抓到第一帧）。
            //    此时返回 null，由调用方渲染应用图标 + 名称 —— 这才是图标唯一该出场
            //    的场景：手上一帧都没有，而不是「有帧但嫌它不够稳」。
            Log.w(TAG, "No usable snapshot for task $currentTaskId ($curPkg)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot", e)
            null
        }
    }

    /**
     * 划出卡片的画面来源（v1.8.44 收敛为唯一一项）。
     *
     * 【本方法此前存在一处真实缺陷，本次一并修掉】
     * 它列的是 `_live.png` / `_reduced.jpg` / `.jpg`（filesDir 与
     * /data/local/tmp 两套），**唯独漏了 `_sys.jpg`** —— 也就是 v1.8.40
     * 引入的、唯一「只含任务窗口、无系统栏污染」的那一档。于是：
     *   · `RootTaskProvider`（多任务卡片）认 `_sys.jpg`；
     *   · `EdgeSwipeGestureService`（划出卡片）**不认**。
     * 同一次采集的结果在两处 UI 上表现不一致 —— 划出时反而退回混入
     * 状态栏/导航栏的整屏帧。v1.8.44 既然把采集收敛到 `_sys.jpg`，
     * 此处只保留它，两处 UI 的口径从此严格一致。
     *
     * 保留内存快路径（`loadLatestSnapshot` 的分支 a）：它不经过本列表，
     * 直接用 `TaskSnapshotCache` 里的帧，可省去一次解码。
     */
    private fun snapshotCandidates(taskId: Int, @Suppress("UNUSED_PARAMETER") curPkg: String?): List<File> {
        return listOf(File(filesDir, "snapshots/${taskId}_sys.jpg"))
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(35)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeViews() {
        try {
            if (leftTriggerView != null) windowManager?.removeView(leftTriggerView)
            if (rightTriggerView != null) windowManager?.removeView(rightTriggerView)
            if (cardOverlayLayout != null) windowManager?.removeView(cardOverlayLayout)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            leftTriggerView = null
            rightTriggerView = null
            cardOverlayLayout = null
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        setGestureActive(false)
        cancelSafetyDismiss()
        instance = null
        removeViews()
        super.onDestroy()
    }
}
