package com.meego.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MeeGoBlack = Color(0xFF000000)
val MeeGoDarkGrey = Color(0xFF141414)
val MeeGoCardBg = Color(0xFF1E1E1E)
val MeeGoCyan = Color(0xFF00A2E8)
val MeeGoRed = Color(0xFFE81123)
val MeeGoTextWhite = Color(0xFFFFFFFF)
val MeeGoTextDim = Color(0xFFAAAAAA)

private val DarkColorScheme = darkColorScheme(
    primary = MeeGoCyan,
    background = MeeGoBlack,
    surface = MeeGoDarkGrey,
    onPrimary = Color.White,
    onBackground = MeeGoTextWhite,
    onSurface = MeeGoTextWhite
)

@Composable
fun MeeGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
