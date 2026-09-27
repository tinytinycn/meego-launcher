package com.meego.launcher.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MeeGo 启动器「顶部安全间距」修饰符。
 *
 * ## 背景
 *
 * 状态栏与导航条已由 `MainActivity` 通过 `WindowInsetsControllerCompat`
 * **真正隐藏**（`controller.hide(WindowInsetsCompat.Type.systemBars())`），
 * 屏幕上不再有任何系统栏。
 *
 * 但各页面此前统一使用 `statusBarsPadding()`，仍按 105px 的系统栏高度避让，
 * 于是屏幕顶部出现一大片空白。实测（Redmi K40 / 1080x2400 / 440dpi）：
 * 第一行应用图标被推到 **y=117**（= statusBarsPadding 105px + Spacer 8dp 22px）。
 *
 * ## 处理原则
 *
 * 状态栏既已隐藏，**真正需要避让的只剩挖孔（刘海 / 摄像头孔）**。
 * 因此本 Modifier 基于官方的 [displayCutoutPadding]，
 * 只避让 `displayCutout`，不再按已隐藏的状态栏高度留白：
 *
 * - 有挖孔机型（本案为居中挖孔 y=0..95）：保留挖孔高度，图标不会被摄像头遮挡；
 * - 无挖孔机型：挖孔 inset 为 0，仅保留 [minTop] 的最小呼吸空间，
 *   内容几乎完全贴合屏幕顶端。
 *
 * @param minTop 无挖孔时的最小顶部间距（挖孔存在时以其高度为准）
 */
fun Modifier.meeGoTopSafeArea(minTop: Dp = 4.dp): Modifier = composed {
    // 先按官方语义避让挖孔（含左右侧边），再补足最小顶部间距。
    // displayCutoutPadding 内部已处理 layoutDirection，无需手动换算。
    val minTopPadding = Modifier.padding(top = minTop)
    this.displayCutoutPadding().then(minTopPadding)
}
