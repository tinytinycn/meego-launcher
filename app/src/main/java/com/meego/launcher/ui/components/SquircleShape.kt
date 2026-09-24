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
    private val n: Float = 2.732f,
    private val cornerRadius: Dp? = null
) : Shape {
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
            return Outline.Generic(path)
        }

        // 长方形容器：四角采用 n = 2.732 黄金超椭圆过渡，边线保持平滑直线连接
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
        return Outline.Generic(path)
    }
}
