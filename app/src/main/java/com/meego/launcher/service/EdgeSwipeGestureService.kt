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
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import com.meego.launcher.MainActivity
import com.meego.launcher.data.LauncherPreferences
import com.meego.launcher.model.LaunchOrigin
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.model.LauncherPreviews
import com.meego.launcher.model.TaskSnapshotCache
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

    fun updateInLauncher(inLauncher: Boolean) {
        isInLauncher = inLauncher
        if (inLauncher) {
            LaunchOriginTracker.reset()
        }
        mainHandler.post {
            val visibility = if (inLauncher || !isGestureEnabledByUser) View.GONE else View.VISIBLE
            leftTriggerView?.visibility = visibility
            rightTriggerView?.visibility = visibility
            Log.d(TAG, "updateInLauncher: inLauncher=$inLauncher, isGestureEnabledByUser=$isGestureEnabledByUser, trigger visibility=$visibility")
        }
    }

    fun applyGestureEnabled(enabled: Boolean) {
        Log.d(TAG, "applyGestureEnabled: enabled=$enabled (was=$isGestureEnabledByUser)")
        isGestureEnabledByUser = enabled
        mainHandler.post {
            if (!enabled) {
                postDismissOverlay()
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
                val visibility = if (isInLauncher) View.GONE else View.VISIBLE
                leftTriggerView?.visibility = visibility
                rightTriggerView?.visibility = visibility
                if (visibility == View.VISIBLE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

        return View.OnTouchListener { v, event ->
            if (isInLauncher || !isGestureEnabledByUser) {
                return@OnTouchListener false
            }
            val params = if (isLeft) leftParams else rightParams

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    isDraggingCard = false
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
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingCard) {
                        val deltaX = if (isLeft) (event.rawX - startRawX) else (startRawX - event.rawX)
                        Log.d(TAG, "Swipe release: deltaX=$deltaX, threshold=${screenWidth / 2f}")
                        finishSwipeOut(isLeft, deltaX)
                        isDraggingCard = false
                    }

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
                TaskSnapshotCache.lastSwipedTaskId = curTaskId
            }
            if (curPkg != null) {
                TaskSnapshotCache.lastSwipedPackageName = curPkg
            }
            TaskSnapshotCache.lastSwipedBitmap = snapBitmap
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
                val iconBmp = try {
                    icon.toBitmap(192, 192)
                } catch (e: Exception) {
                    null
                }
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
        // 缩放绕内容中心进行，故通过「先按缩放系数反推左上角偏移」来保持
        // 快照中心在屏幕上不动 —— 表现为画面从 0.95 倍「长」回 1.0 倍，
        // 而非整体朝某个角落漂移。Multitasking / Events Feed 恒为 1.0，不受影响。
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
     * 缩放锚点取快照矩形的**中心**：View 默认以左上角缩放，若直接改 scale，
     * 画面会朝左上角漂移。故按 `s` 反推平移量 `t = center * (1 - s)`，
     * 使 `t + s * center == center` 恒成立 —— 中心不动、四边对称内收。
     *
     * @param progress 划出进度 0..1（0 = 刚起手，1 = 划过半屏）
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
        val cx = iv.width * 0.5f
        val cy = iv.height * 0.5f
        iv.scaleX = s
        iv.scaleY = s
        iv.translationX = cx * (1f - s)
        iv.translationY = cy * (1f - s)
    }

    private fun finishSwipeOut(isLeft: Boolean, deltaX: Float) {
        val threshold = screenWidth / 2f
        if (deltaX >= threshold) {
            // 达到 1/2 屏幕: 顺势飞出屏幕 (Slide-out)
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
                    TaskSnapshotCache.lastSwipedTaskId = currentTaskId
                }
                if (curPkg != null) {
                    TaskSnapshotCache.lastSwipedPackageName = curPkg
                }
                TaskSnapshotCache.lastSwipedBitmap = snapBitmap
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

            vibrate()
        } else {
            // 未达到 1/2 屏幕: 原路弹回全屏 (Spring back)
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
            Log.w(TAG, "desktop bg rect $rect does not fit overlay ${overlayW}x$overlayH; fallback to full-bleed")
            return
        }

        iv.layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }
        iv.translationX = 0f
        iv.translationY = 0f
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

    /**
     * 判定某个 taskId 的 live 帧是否仍处于启动动画期（provisional）。
     *
     * 守护进程在应用刚进入前台时抓到的第一帧必然是 splash / 过渡帧，
     * 它在 `settle_window` 结束前一直带此标记；渲染层遇到 provisional 帧应降级为图标占位，
     * 绝不展示误导性的半渲染画面。
     */
    private fun isProvisional(taskId: Int): Boolean {
        return try {
            File(filesDir, "snapshots/${taskId}_live.provisional").exists()
        } catch (e: Exception) {
            false
        }
    }

    private data class SnapshotCandidate(
        val file: File,
        val isProvisional: Boolean
    )

    /**
     * 按**新鲜度**仲裁快照来源。
     *
     * 此前实现按固定优先级取 `${tid}_live.png` 并直接返回，导致一张陈旧的过渡帧
     * 仅凭优先级就能压过系统刚刚写好的新鲜快照（实测：任务 192 的 live 帧老化 3725s，
     * 而同任务的系统 `_reduced.jpg` 仅 0s）。现在改为：同池候选中取 mtime 最大者，
     * 且 provisional 帧被排除在仲裁之外。
     */
    private fun pickFreshestFile(candidates: List<File>, taskId: Int): Pair<File, Boolean>? {
        val provisional = isProvisional(taskId)
        var best: File? = null
        var bestTime = Long.MIN_VALUE
        for (f in candidates) {
            try {
                if (!f.exists() || !f.canRead() || f.length() <= 0) continue
                val t = f.lastModified()
                if (t > bestTime) {
                    bestTime = t
                    best = f
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        if (best == null) return null

        // 标记只针对 live 类文件；系统快照（_reduced.jpg / .jpg）视为稳定帧
        val isLive = best.name.endsWith("_live.png")
        return Pair(best, isLive && provisional)
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

            // b. 文件仲裁：同池取 mtime 最大者，跳过 provisional 帧
            val files = snapshotCandidates(currentTaskId, curPkg)
            val nonProvisional = files.filter { !isProvisionalLive(it, currentTaskId) }
            val pool = if (nonProvisional.isNotEmpty()) nonProvisional else emptyList()

            val chosen = if (pool.isNotEmpty()) pickFreshestFile(pool, currentTaskId) else null
            if (chosen != null) {
                val (file, prov) = chosen
                val bmp = loadBitmapFromFile(file.absolutePath)
                if (bmp != null) {
                    TaskSnapshotCache.put(
                        currentTaskId, bmp, curPkg,
                        captureTimeMs = file.lastModified(),
                        isSettled = !prov
                    )
                    Log.d(TAG, "Picked freshest snapshot ${file.name} for task $currentTaskId ($curPkg) settled=${!prov}")
                    return bmp
                }
            }

            // c. 全部文件候选均为 provisional（应用仍处于启动动画期）：
            //    此时任何截图都是误导性的半渲染画面，按产品决策降级为图标占位，
            //    即返回 null，由调用方渲染应用图标 + 名称。
            if (files.isNotEmpty() && pool.isEmpty()) {
                Log.d(TAG, "All snapshot candidates for task $currentTaskId ($curPkg) are provisional; degrade to icon")
            }

            // d. 严格隔离：若该任务尚无任何快照，坚决不读取其他任务的画面
            Log.w(TAG, "No usable snapshot for task $currentTaskId ($curPkg)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot", e)
            null
        }
    }

    private fun snapshotCandidates(taskId: Int, curPkg: String?): List<File> {
        val list = mutableListOf<File>()
        // App 私有目录优先：Android 16 SELinux 严格隔离 /data/local/tmp/snapshots 软链
        list.add(File(filesDir, "snapshots/${taskId}_live.png"))
        list.add(File(filesDir, "snapshots/${taskId}_reduced.jpg"))
        list.add(File(filesDir, "snapshots/${taskId}.jpg"))
        list.add(File("/data/local/tmp/snapshots/${taskId}_live.png"))
        list.add(File("/data/local/tmp/snapshots/${taskId}_reduced.jpg"))
        list.add(File("/data/local/tmp/snapshots/${taskId}.jpg"))

        // 全局 current_app.png 需严格校验所有权，防止跨应用串扰
        val tidFromTidFile = readIntFromFile(File(filesDir, "current_app_tid.txt"))
            ?: readIntFromFile(File("/data/local/tmp/current_app_tid.txt"))
        val pkgFromPkgFile = readTextFromFile(File(filesDir, "current_app_pkg.txt"))
            ?: readTextFromFile(File("/data/local/tmp/current_app_pkg.txt"))
        val ownershipVerified = (tidFromTidFile == taskId) ||
                (curPkg != null && pkgFromPkgFile == curPkg)
        if (ownershipVerified) {
            list.add(File(filesDir, "current_app.png"))
            list.add(File("/data/local/tmp/current_app.png"))
        }
        return list
    }

    private fun isProvisionalLive(file: File, taskId: Int): Boolean {
        return file.name.endsWith("_live.png") && isProvisional(taskId)
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
