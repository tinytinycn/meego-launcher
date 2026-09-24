package com.meego.launcher.service

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
                    updateCurrentTaskIdForPackage(pkg)
                }
            } else if (event.className?.toString()?.contains("MainActivity") == true) {
                // 仅当真正回到桌面 MainActivity 且不在手势拖拽中时才隐藏触发区
                if (!isDraggingCard) {
                    updateInLauncher(true)
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

    // 全局划出卡片跟随 Overlay
    private var cardOverlayLayout: FrameLayout? = null
    private var desktopBackgroundImageView: ImageView? = null
    private var cardContainer: FrameLayout? = null
    private var cardImageView: ImageView? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var isDraggingCard = false

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
        mainHandler.postDelayed(safetyDismissRunnable, 500)
    }

    fun cancelSafetyDismiss() {
        mainHandler.removeCallbacks(safetyDismissRunnable)
    }

    fun postDismissOverlay() {
        cancelSafetyDismiss()
        setGestureActive(false)
        mainHandler.post {
            cardOverlayLayout?.visibility = View.GONE
            cardContainer?.visibility = View.VISIBLE
            cardContainer?.translationX = 0f
            cardContainer?.scaleX = 1f
            cardContainer?.scaleY = 1f
        }
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
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        // 1. 底层纯黑保底垫层：完全覆盖全屏，从根源杜绝底层正在运行的第三方应用透光或露角
        val solidBackdrop = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        cardOverlayLayout?.addView(solidBackdrop)

        // 2. 真实底层桌面背景层 (Application Grid 或 Multitasking View 快照，100% 不透明)
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
        val trackerTaskId = LaunchOriginTracker.currentTaskId

        // 1. 如果 LaunchOriginTracker 已经记录了有效的前台任务 ID，优先使用
        if (trackerTaskId != null && curPkg != null) {
            return trackerTaskId
        }

        // 2. 从守护进程的 current_task_id.txt 读取
        return readTaskIdFromFile() ?: trackerTaskId
    }

    private fun readTaskIdFromFile(): Int? {
        return try {
            val taskIdFile = File("/data/local/tmp/current_task_id.txt")
            if (taskIdFile.exists() && taskIdFile.canRead()) {
                taskIdFile.readText().trim().toIntOrNull()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun startSwipeOutAnimation(isLeft: Boolean) {
        setGestureActive(true)

        // 1. 设置当前运行应用的卡片画面
        val snapBitmap = loadLatestSnapshot()
        val curTaskId = getCurrentTaskId()
        val curPkg = LaunchOriginTracker.currentPackageName

        if (snapBitmap != null) {
            cardImageView?.setImageBitmap(snapBitmap)
            cardImageView?.scaleType = ImageView.ScaleType.FIT_XY
            cardImageView?.setBackgroundColor(Color.TRANSPARENT)
            if (curTaskId != null) {
                TaskSnapshotCache.put(curTaskId, snapBitmap)
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
        } else {
            desktopBackgroundImageView?.visibility = View.GONE
        }
        desktopBackgroundImageView?.scaleX = 1.0f
        desktopBackgroundImageView?.scaleY = 1.0f
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

        // 保持底层桌面 100% 不透明与全屏 1:1 贴合，杜绝透明度造成的三方应用内容透光
        desktopBackgroundImageView?.scaleX = 1.0f
        desktopBackgroundImageView?.scaleY = 1.0f
        desktopBackgroundImageView?.alpha = 1.0f
    }

    private fun finishSwipeOut(isLeft: Boolean, deltaX: Float) {
        val threshold = screenWidth / 2f
        if (deltaX >= threshold) {
            // 达到 1/2 屏幕: 顺势飞出屏幕 (Slide-out)
            val targetX = if (isLeft) screenWidth.toFloat() else -screenWidth.toFloat()

            // 确保将当前滑出界面的真实快照存入 TaskSnapshotCache，供 Multitasking View 立即渲染
            val currentTaskId = getCurrentTaskId()
            val snapBitmap = (cardImageView?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (snapBitmap != null && cardImageView?.scaleType == ImageView.ScaleType.FIT_XY) {
                if (currentTaskId != null) {
                    TaskSnapshotCache.put(currentTaskId, snapBitmap)
                    TaskSnapshotCache.lastSwipedTaskId = currentTaskId
                }
                if (LaunchOriginTracker.currentPackageName != null) {
                    TaskSnapshotCache.lastSwipedPackageName = LaunchOriginTracker.currentPackageName
                }
                TaskSnapshotCache.lastSwipedBitmap = snapBitmap
            }

            // 1. 立即以无动画模式唤起桌面，让系统在后台并行装载 MainActivity
            triggerReturnToLauncher()

            cardContainer?.animate()
                ?.translationX(targetX)
                ?.scaleX(0.85f)
                ?.scaleY(0.85f)
                ?.setDuration(220)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()

            desktopBackgroundImageView?.animate()
                ?.scaleX(1.0f)
                ?.scaleY(1.0f)
                ?.alpha(1.0f)
                ?.setDuration(220)
                ?.setInterpolator(DecelerateInterpolator())
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // 飞出动画完成：隐藏滑出的卡片
                        cardContainer?.visibility = View.GONE
                        // 注意：保持 desktopBackgroundImageView 遮罩显示，绝不提前隐藏！
                        // 覆盖在最上层的 desktopBackgroundImageView 继续平滑盖住旧应用，直到 MainActivity.onResume() 的首帧就绪通知 dismissOverlay()
                        scheduleSafetyDismiss()
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

            desktopBackgroundImageView?.animate()
                ?.scaleX(1.0f)
                ?.scaleY(1.0f)
                ?.alpha(1.0f)
                ?.setDuration(250)
                ?.setInterpolator(OvershootInterpolator(1.2f))
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
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
        startActivity(intent)
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

            // 1. 优先读取该任务专属的 live 实时截图
            val liveFile = File("/data/local/tmp/snapshots/${currentTaskId}_live.png")
            if (liveFile.exists() && liveFile.length() > 0) {
                val bmp = loadBitmapFromFile(liveFile.absolutePath)
                if (bmp != null) {
                    Log.d(TAG, "Loaded live snapshot for task $currentTaskId from ${liveFile.name}")
                    return bmp
                }
            }

            // 2. 检查全局 current_app.png，但【严格校验】所有权：current_app_tid.txt 必须精确等于 currentTaskId！
            val tidFile = File("/data/local/tmp/current_app_tid.txt")
            if (tidFile.exists() && tidFile.readText().trim() == currentTaskId.toString()) {
                val currentAppFile = File("/data/local/tmp/current_app.png")
                if (currentAppFile.exists() && currentAppFile.length() > 0) {
                    val bmp = loadBitmapFromFile(currentAppFile.absolutePath)
                    if (bmp != null) {
                        Log.d(TAG, "Loaded verified live screen snapshot from current_app.png for task $currentTaskId")
                        return bmp
                    }
                }
            }

            // 3. 读取系统生成的该任务专属快照文件 (绝对专属当前 taskId，杜绝跨任务混淆)
            val taskSnapReduced = File("/data/local/tmp/snapshots/${currentTaskId}_reduced.jpg")
            if (taskSnapReduced.exists() && taskSnapReduced.length() > 0) {
                val bmp = loadBitmapFromFile(taskSnapReduced.absolutePath)
                if (bmp != null) {
                    Log.d(TAG, "Loaded snapshot for task $currentTaskId (reduced)")
                    return bmp
                }
            }
            val taskSnapFull = File("/data/local/tmp/snapshots/$currentTaskId.jpg")
            if (taskSnapFull.exists() && taskSnapFull.length() > 0) {
                val bmp = loadBitmapFromFile(taskSnapFull.absolutePath)
                if (bmp != null) {
                    Log.d(TAG, "Loaded snapshot for task $currentTaskId (full)")
                    return bmp
                }
            }

            // 4. 读取当前任务专属内存缓存
            val cached = TaskSnapshotCache.get(currentTaskId)
            if (cached != null) {
                Log.d(TAG, "Loaded snapshot from TaskSnapshotCache for task $currentTaskId")
                return cached
            }

            // 5. 严格隔离：若该任务尚无快照，坚决不读取任何其他任务的画面！
            Log.w(TAG, "No snapshot available for task $currentTaskId")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot", e)
            null
        }
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
