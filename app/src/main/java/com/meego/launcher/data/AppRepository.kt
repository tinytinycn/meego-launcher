package com.meego.launcher.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.meego.launcher.R
import com.meego.launcher.model.AppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    suspend fun getInstalledApps(): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val ownPackageName = context.packageName

        val apps = mutableListOf<AppItem>()

        // 1. 注入 MeeGo Settings 专属应用入口
        val settingsDrawable = context.getDrawable(R.mipmap.ic_launcher)
        val settingsBitmap = drawableToImageBitmap(settingsDrawable)
        apps.add(
            AppItem(
                packageName = ownPackageName,
                activityName = "MeeGoSettings",
                label = "MeeGo Settings",
                icon = settingsDrawable,
                iconBitmap = settingsBitmap,
                isMeeGoSettings = true
            )
        )

        // 2. 异步预解码并渲染真实已安装 App 图标为 ImageBitmap，彻底消除滑动主线程瞬时卡顿
        val regularApps = resolveInfos
            .filter { it.activityInfo.packageName != ownPackageName }
            .map { resolveInfo ->
                val iconDrawable = resolveInfo.loadIcon(pm)
                val iconBitmap = drawableToImageBitmap(iconDrawable)
                AppItem(
                    packageName = resolveInfo.activityInfo.packageName,
                    activityName = resolveInfo.activityInfo.name,
                    label = resolveInfo.loadLabel(pm).toString(),
                    icon = iconDrawable,
                    iconBitmap = iconBitmap,
                    isMeeGoSettings = false
                )
            }
            .sortedBy { it.label.lowercase() }

        apps.addAll(regularApps)

        // 3. 注入 20 个高辨识度 MeeGo 经典风格测试 App 图标占位 (点击直接调起 MeeGo Launcher 设置页，方便上下滑动测试)
        val testPalette = listOf(
            0xFF00ADEF, // MeeGo Cyan
            0xFF0050EF, // Nokia Cobalt
            0xFFE51400, // MeeGo Carmine Red
            0xFFFA6800, // Nokia Orange
            0xFF008A00, // MeeGo Emerald
            0xFFA4C400, // Lime
            0xFF00ABA9, // Teal
            0xFFAA00FF, // Violet
            0xFFF0A30A, // Amber
            0xFF1BA1E2, // Sky
            0xFFA20025, // Crimson
            0xFF6A00FF, // Indigo
            0xFF76608A, // Mauve
            0xFF6D8764, // Olive
            0xFF647687, // Steel
            0xFFFF5722, // Deep Orange
            0xFF03A9F4, // Light Blue
            0xFF26A69A, // Mint
            0xFFE91E63, // Pink
            0xFFFFB300  // Gold
        )

        val testApps = (1..20).map { i ->
            val color = testPalette[(i - 1) % testPalette.size]
            val iconBitmap = createTestAppIconBitmap(i, color)
            val numStr = String.format("%02d", i)
            AppItem(
                packageName = "com.meego.launcher.test.app$numStr",
                activityName = "TestActivity$numStr",
                label = "Test $numStr",
                icon = null,
                iconBitmap = iconBitmap,
                isMeeGoSettings = true
            )
        }

        apps.addAll(testApps)

        apps
    }

    private fun drawableToImageBitmap(drawable: Drawable?, size: Int = 144): ImageBitmap? {
        if (drawable == null) return null
        return try {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private fun createTestAppIconBitmap(number: Int, color: Long, size: Int = 144): ImageBitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 绘制高饱和度底色
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // 绘制质感白色高光微晕顶层
        val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat() * 0.38f, glossPaint)

        // 绘制醒目纯白编号 "01", "02"...
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val text = String.format("%02d", number)
        val textY = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, size / 2f, textY, textPaint)

        return bitmap.asImageBitmap()
    }
}
