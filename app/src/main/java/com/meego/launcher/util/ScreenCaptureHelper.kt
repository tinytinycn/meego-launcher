package com.meego.launcher.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
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

    fun captureWindow(activity: Activity, onCaptured: (Bitmap) -> Unit) {
        val window = activity.window ?: return
        val decorView = window.decorView ?: return
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) return

        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PixelCopy.request(
                    window,
                    bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            onCaptured(bitmap)
                        } else {
                            Log.w(TAG, "PixelCopy failed with result $copyResult, falling back to Canvas draw")
                            captureWithCanvas(decorView, onCaptured)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } else {
                captureWithCanvas(decorView, onCaptured)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture window", e)
        }
    }

    private fun captureWithCanvas(view: android.view.View, onCaptured: (Bitmap) -> Unit) {
        try {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            onCaptured(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Canvas capture error", e)
        }
    }

    fun captureLauncherScreen(activity: Activity, origin: LaunchOrigin) {
        captureWindow(activity) { bmp ->
            when (origin) {
                LaunchOrigin.GRID -> {
                    LauncherPreviews.gridBitmap = bmp
                    val internalFile = File(activity.filesDir, "app_grid_preview.png")
                    saveBitmapAsync(bmp, internalFile.absolutePath)
                    saveBitmapAsync(bmp, "/data/local/tmp/app_grid_preview.png")
                    Log.d(TAG, "Saved grid preview bitmap (${bmp.width}x${bmp.height})")
                }
                LaunchOrigin.RECENTS -> {
                    LauncherPreviews.recentsBitmap = bmp
                    val internalFile = File(activity.filesDir, "recents_preview.png")
                    saveBitmapAsync(bmp, internalFile.absolutePath)
                    saveBitmapAsync(bmp, "/data/local/tmp/recents_preview.png")
                    Log.d(TAG, "Saved recents preview bitmap (${bmp.width}x${bmp.height})")
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
