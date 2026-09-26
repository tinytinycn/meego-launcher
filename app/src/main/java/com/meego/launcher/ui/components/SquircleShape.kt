package com.meego.launcher.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * MeeGo 经典超椭圆 (Squircle / Lamé 曲线) 形状:
 * 公式: |x/a|^n + |y/b|^n = 1, 其中 n = 2.732 (MeeGo Harmattan 黄金超椭圆曲率指数, 1 + √3)
 *
 * - 当容器为正方形（如桌面图标）或未指定角半径时，退化为经典全局 Lamé 超椭圆。
 * - 当容器为长方形（如多任务卡片、按钮等）时，四角应用黄金超椭圆连续曲率倒角，边线保持平直，呈现正统 MeeGo 卡片质感。
 */
class SquircleShape(
    val n: Float = 2.732f,
    val cornerRadius: Dp? = null
) : Shape {
    // 全局共享 Outline 缓存（§3.2）。
    // 此前缓存是每个 SquircleShape 实例私有的单槽位缓存，而 DefaultSquircleShape 是全局单例，
    // 同一尺寸的图标在 Grid / 多任务卡片 / 标题栏等处被交替请求时会互相挤掉单槽，
    // 造成「首次测量又重新遍历 72 点 Path」。改为按 (w,h,density,n,cornerRadius) 键的
    // 有界全局表，滑动期图标绘制稳定命中，且不同屏幕/列数组合各自独立缓存。
    // 上限见 MAX_OUTLINE_CACHE_ENTRIES，防止长尾尺寸组合无界增长。
    private var lastWidth: Float = -1f
    private var lastHeight: Float = -1f
    private var lastDensity: Float = -1f
    private var cachedOutline: Outline? = null

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) {
            return Outline.Generic(Path())
        }

        // 先查实例单槽（最热路径：同一图标尺寸连续绘制）
        if (width == lastWidth && height == lastHeight && density.density == lastDensity && cachedOutline != null) {
            return cachedOutline!!
        }

        // 再查/写全局共享表
        val key = OutlineCacheKey(n, cornerRadius?.value ?: Float.NaN, width, height, density.density)
        OutlineCache.get(key)?.let { shared ->
            lastWidth = width
            lastHeight = height
            lastDensity = density.density
            cachedOutline = shared
            return shared
        }

        val maxR = minOf(width, height) / 2f
        val r = if (cornerRadius != null) {
            with(density) { cornerRadius.toPx() }.coerceIn(0f, maxR)
        } else {
            if (abs(width - height) < 1f) {
                maxR
            } else {
                with(density) { 20.dp.toPx() }.coerceAtMost(maxR)
            }
        }

        val outline: Outline

        // 如果半径正好等于半宽高（正方形全局超椭圆），执行标准全局超椭圆渲染
        if (r >= maxR && abs(width - height) < 1f) {
            val path = Path()
            val a = width / 2f
            val b = height / 2f
            val cx = a
            val cy = b

            val points = 72
            for (i in 0 until points) {
                val theta = (i * 2 * Math.PI / points).toFloat()
                val cosT = cos(theta)
                val sinT = sin(theta)

                val x = cx + a * kotlin.math.sign(cosT) * abs(cosT).pow(2f / n)
                val y = cy + b * kotlin.math.sign(sinT) * abs(sinT).pow(2f / n)

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            path.close()
            outline = Outline.Generic(path)
        } else {
            outline = Outline.Generic(buildRoundedSquirclePath(width, height, r, n))
        }

        lastWidth = width
        lastHeight = height
        lastDensity = density.density
        cachedOutline = outline
        OutlineCache.put(key, outline)
        return outline
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SquircleShape) return false
        return n == other.n && cornerRadius == other.cornerRadius
    }

    override fun hashCode(): Int {
        var result = n.hashCode()
        result = 31 * result + (cornerRadius?.hashCode() ?: 0)
        return result
    }
}

/** 长方形 MeeGo 卡片超椭圆路径：四角 n 指数倒角 + 平直边线。 */
private fun buildRoundedSquirclePath(width: Float, height: Float, r: Float, n: Float): Path {
    val path = Path()
    val stepsPerCorner = 18

    // 1. 顶边
    path.moveTo(r, 0f)
    path.lineTo(width - r, 0f)

    // 2. 右上角 (从 theta = π/2 到 0)
    for (i in 0..stepsPerCorner) {
        val t = (Math.PI / 2.0 * (1.0 - i.toDouble() / stepsPerCorner)).toFloat()
        val cosT = abs(cos(t)).coerceIn(0f, 1f)
        val sinT = abs(sin(t)).coerceIn(0f, 1f)
        val x = (width - r) + r * cosT.pow(2f / n)
        val y = r - r * sinT.pow(2f / n)
        path.lineTo(x, y)
    }

    // 3. 右边
    path.lineTo(width, height - r)

    // 4. 右下角 (从 theta = 0 到 π/2)
    for (i in 0..stepsPerCorner) {
        val t = (Math.PI / 2.0 * (i.toDouble() / stepsPerCorner)).toFloat()
        val cosT = abs(cos(t)).coerceIn(0f, 1f)
        val sinT = abs(sin(t)).coerceIn(0f, 1f)
        val x = (width - r) + r * cosT.pow(2f / n)
        val y = (height - r) + r * sinT.pow(2f / n)
        path.lineTo(x, y)
    }

    // 5. 底边
    path.lineTo(r, height)

    // 6. 左下角 (从 theta = π/2 到 0)
    for (i in 0..stepsPerCorner) {
        val t = (Math.PI / 2.0 * (1.0 - i.toDouble() / stepsPerCorner)).toFloat()
        val cosT = abs(cos(t)).coerceIn(0f, 1f)
        val sinT = abs(sin(t)).coerceIn(0f, 1f)
        val x = r - r * cosT.pow(2f / n)
        val y = (height - r) + r * sinT.pow(2f / n)
        path.lineTo(x, y)
    }

    // 7. 左边
    path.lineTo(0f, r)

    // 8. 左上角 (从 theta = 0 到 π/2)
    for (i in 0..stepsPerCorner) {
        val t = (Math.PI / 2.0 * (i.toDouble() / stepsPerCorner)).toFloat()
        val cosT = abs(cos(t)).coerceIn(0f, 1f)
        val sinT = abs(sin(t)).coerceIn(0f, 1f)
        val x = r - r * cosT.pow(2f / n)
        val y = r - r * sinT.pow(2f / n)
        path.lineTo(x, y)
    }

    path.close()
    return path
}

/** 全局 Outline 缓存键。 */
private data class OutlineCacheKey(
    val n: Float,
    val cornerRadiusPx: Float,
    val width: Float,
    val height: Float,
    val density: Float
)

/**
 * 有界全局 Outline 缓存。
 * 键的不同取值组合在真实设备上极少（不同 dp 尺寸 × 密度 × 曲率），
 * 设置上限后即使遇到长尾组合也只会整体清空重建，不会无界增长。
 */
private object OutlineCache {
    private const val MAX_OUTLINE_CACHE_ENTRIES = 128
    private val map = HashMap<OutlineCacheKey, Outline>(32)

    fun get(key: OutlineCacheKey): Outline? = synchronized(map) { map[key] }

    fun put(key: OutlineCacheKey, outline: Outline) {
        synchronized(map) {
            if (map.size >= MAX_OUTLINE_CACHE_ENTRIES) map.clear()
            map[key] = outline
        }
    }
}

val DefaultSquircleShape = SquircleShape(2.732f)
