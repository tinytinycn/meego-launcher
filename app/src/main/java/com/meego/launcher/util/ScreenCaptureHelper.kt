package com.meego.launcher.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import com.meego.launcher.model.LaunchOrigin
import com.meego.launcher.model.LauncherPreviews
import java.io.File
import java.io.FileOutputStream

object ScreenCaptureHelper {
    private const val TAG = "ScreenCaptureHelper"

    /**
     * 一帧桌面快照及其在**物理屏幕**上的真实矩形。
     *
     * 快照位图尺寸 == 窗口尺寸。挖孔机型上 MainActivity 窗口被挖孔 inset 顶掉
     * （Redmi K40：窗口 [0,95][1080,2400]，高 2305，位图即 1080x2305），
     * 而手势 Overlay 是全屏 1080x2400。渲染层必须连同 [screenRect] 一起把快照
     * 1:1 贴回原始位置，否则会被拉伸铺满全屏（详见 [LauncherPreviews]）。
     */
    data class CapturedFrame(val bitmap: Bitmap, val screenRect: Rect)

    /**
     * 读取窗口在物理屏幕上的绝对矩形。
     *
     * 窗口本身可能与物理屏幕不等高（挖孔/系统栏 inset 会收缩窗口），
     * 故不能只靠 `decorView` 自身的宽高，必须取 [android.view.Window] 的真实 frame。
     */
    private fun windowScreenRect(activity: Activity): Rect? {
        return try {
            val attrs = activity.window?.attributes ?: return null
            val rect = Rect()
            // 有 bounds/frame 时以系统分配的为准；退化时用 decorView 自身尺寸兜底。
            if (attrs.width > 0 && attrs.height > 0) {
                rect.set(0, 0, attrs.width, attrs.height)
            } else {
                val dv = activity.window?.decorView ?: return null
                if (dv.width <= 0 || dv.height <= 0) return null
                rect.set(0, 0, dv.width, dv.height)
            }
            // getWindowVisibleDisplayFrame 给出窗口原点在屏幕上的真实偏移（挖孔 inset）
            val visible = Rect()
            activity.window.decorView.getWindowVisibleDisplayFrame(visible)
            if (visible.width() > 0 && visible.height() > 0) {
                rect.offsetTo(visible.left, visible.top)
            }
            rect
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve window screen rect", e)
            null
        }
    }

    fun captureWindow(activity: Activity, onCaptured: (CapturedFrame) -> Unit) {
        val window = activity.window ?: return
        val decorView = window.decorView ?: return
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) return

        val screenRect = windowScreenRect(activity)

        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PixelCopy.request(
                    window,
                    bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            onCaptured(CapturedFrame(bitmap, screenRect ?: Rect(0, 0, width, height)))
                        } else {
                            Log.w(TAG, "PixelCopy failed with result $copyResult, falling back to Canvas draw")
                            captureWithCanvas(decorView, screenRect, width, height, onCaptured)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } else {
                captureWithCanvas(decorView, screenRect, width, height, onCaptured)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture window", e)
        }
    }

    private fun captureWithCanvas(
        view: android.view.View,
        screenRect: Rect?,
        width: Int,
        height: Int,
        onCaptured: (CapturedFrame) -> Unit
    ) {
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            onCaptured(CapturedFrame(bitmap, screenRect ?: Rect(0, 0, width, height)))
        } catch (e: Exception) {
            Log.e(TAG, "Canvas capture error", e)
        }
    }

    fun captureLauncherScreen(activity: Activity, origin: LaunchOrigin) {
        captureWindow(activity) { frame ->
            val bmp = frame.bitmap
            val rect = frame.screenRect
            Log.d(TAG, "capture origin=$origin bitmap=${bmp.width}x${bmp.height} screenRect=$rect")
            when (origin) {
                LaunchOrigin.GRID -> {
                    LauncherPreviews.gridBitmap = bmp
                    LauncherPreviews.gridWindowRect = rect
                    val internalFile = File(activity.filesDir, "app_grid_preview.png")
                    saveBitmapAsync(bmp, internalFile.absolutePath)
                    saveBitmapAsync(bmp, "/data/local/tmp/app_grid_preview.png")
                    Log.d(TAG, "Saved grid preview bitmap (${bmp.width}x${bmp.height}) rect=$rect")
                }
                LaunchOrigin.RECENTS -> {
                    LauncherPreviews.recentsBitmap = bmp
                    LauncherPreviews.recentsWindowRect = rect
                    val internalFile = File(activity.filesDir, "recents_preview.png")
                    saveBitmapAsync(bmp, internalFile.absolutePath)
                    saveBitmapAsync(bmp, "/data/local/tmp/recents_preview.png")
                    Log.d(TAG, "Saved recents preview bitmap (${bmp.width}x${bmp.height}) rect=$rect")
                }
            }
        }
    }

    fun saveBitmapAsync(bitmap: Bitmap, path: String) {
        Thread {
            try {
                val file = File(path)
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                }
            } catch (e: Exception) {
                // Ignore permission error if writing to /data/local/tmp fails
            }
        }.start()
    }
}
