package com.meego.launcher.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * MeeGo 统一应用图标光栅化器。
 *
 * ## 为什么必须存在这一层
 *
 * 桌面图标形状不统一的直接成因，是 `PackageManager.loadIcon()` 的返回值在两类应用上
 * **行为完全不同**：
 *
 * - **自适应图标（AdaptiveIconDrawable，API 26+ 应用）**：其 `draw()` 内部会先合成
 *   背景层 + 前景层，再**用系统图标形状（`config_icon_mask`）做一次 mask 裁剪**。
 *   LineageOS 默认该形状为**圆形**，于是圆形被永久烘焙进位图 —— 之后桌面再套
 *   `DefaultSquircleShape` 裁剪，得到的是「圆角方形底板上贴着一个圆形图标」。
 * - **传统图标（legacy BitmapDrawable / 普通 VectorDrawable）**：无任何 mask，整幅
 *   铺满 bounds，裁出来就是干净的 MeeGo 超椭圆。
 *
 * 两者混在一起，就是「有的超椭圆、有的原 LineageOS 圆形」的现象。
 *
 * ## 修复原理
 *
 * 自适应图标的「背景层 + 前景层」本身就是按 108dp 画布、72dp 安全区设计的，
 * 系统 mask 只是**在最后一步裁掉外圈**。因此这里**绕开 `AdaptiveIconDrawable.draw()`**，
 * 直接把两层绘制到画布上，得到一张**无 mask 的全幅方形原始图标**；形状交给渲染层统一的
 * `DefaultSquircleShape` 裁剪。
 *
 * **层几何必须与 AOSP 逐项对齐**，否则会引入「图标主体尺寸不对」的新缺陷：
 * 绘制两层之前，把画布**整体**绕中心放大 [ADAPTIVE_LAYER_SCALE] 倍。
 * 注意是**整个图层合成**一起放大，而非只放大前景层 —— 背景层虽多为全幅纯色
 * （缩放不可见），但含图形素材的背景层会因此暴露尺寸差异。
 *
 * 这样图标主体尺寸与系统 mask 渲染**逐像素一致**，唯一区别是四角不再被提前裁掉；
 * 两类图标自此都收敛为「全幅方形 + 统一超椭圆裁剪」，形状完全一致。
 */
object AppIconRenderer {

    /** 图标默认光栅化边长（px）。桌面网格为 52~62dp，144 足够覆盖 3x 密度屏。 */
    const val DEFAULT_SIZE = 144

    /**
     * 自适应图标**图层合成**的放大系数，必须与 AOSP `AdaptiveIconDrawable` 保持一致。
     *
     * AOSP 合成图层时把内容 bounds 每边外扩 `EXTRA_INSET_PERCENTAGE = 0.25`：
     * 108dp 画布 → 108 + 2×27 = 162dp，等效于绕中心放大 **162/108 = 1.5 倍**。
     *
     * **这一系数不可省略，且必须作用于背景层与前景层两者**：
     * - 若完全不缩放，自适应图标的 logo 会**小 1/3**（MeeGo Settings 图标由此从
     *   「整块青色」退化为「黑底漂浮小 logo」，即该缺陷被用户发现的入口）；
     * - 若只缩放前景层，含图形素材的**背景层**仍会小 1/3（真机 A/B 实测：某图标外圈
     *   特征半径系统渲染 ±57px、只缩前景时 ±36px，比值 1.58 ≈ 1.5，据此定位）。
     *
     * 真机实测反推（Redmi K40，前景素材几何已知）：白色 "N" 的 path bbox 为
     * x40..68 / y36..72（108 视口），瓦片 171px 时——
     * 系统渲染实测 **65×85 px**，精确匹配 1.5× 预测值 66.5×85.5；而 1.0× 预测
     * 44.3×57.0、2.0× 预测 88.7×114.0 均不匹配，据此确证系数为 1.5。
     */
    private const val ADAPTIVE_LAYER_SCALE = 1.5f

    /** 供 Compose 渲染层使用的 ImageBitmap 出口。 */
    fun toImageBitmap(drawable: Drawable?, size: Int = DEFAULT_SIZE): ImageBitmap? {
        return toBitmap(drawable, size)?.asImageBitmap()
    }

    /** 供 View 体系（ImageView / 手势 Overlay）使用的 Bitmap 出口。 */
    fun toBitmap(drawable: Drawable?, size: Int = DEFAULT_SIZE): Bitmap? {
        if (drawable == null || size <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawUnmasked(drawable, canvas, size)
            bitmap
        } catch (e: Exception) {
            // 单个三方图标的异常绝不拖垮整轮图标装载
            null
        }
    }

    /**
     * 无 mask 绘制：自适应图标拆层直绘，其余图标按原样整体铺满。
     *
     * 拆层失败（如背景/前景层均为空）时回退到标准 `draw()`，保证任何畸形图标都有输出。
     */
    private fun drawUnmasked(drawable: Drawable, canvas: Canvas, size: Int) {
        if (drawable is AdaptiveIconDrawable) {
            val background = drawable.background
            val foreground = drawable.foreground
            if (background != null || foreground != null) {
                // 背景层与前景层**整体**绕中心放大 1.5 倍，与 AOSP 的合成几何严格一致。
                // 注意必须两层同缩放：背景层虽多为全幅纯色（缩放不可见），
                // 但也有含图形/圆角矩形素材的图标，只缩前景会让那类图标的主体小 1/3。
                val half = size / 2f
                canvas.save()
                canvas.scale(ADAPTIVE_LAYER_SCALE, ADAPTIVE_LAYER_SCALE, half, half)
                background?.let {
                    it.setBounds(0, 0, size, size)
                    it.draw(canvas)
                }
                foreground?.let {
                    it.setBounds(0, 0, size, size)
                    it.draw(canvas)
                }
                canvas.restore()
                return
            }
        }
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
    }
}
