package com.meego.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.meego.launcher.ui.components.SquircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meego.launcher.ui.theme.MeeGoCardBg
import com.meego.launcher.ui.theme.MeeGoCyan
import com.meego.launcher.ui.theme.MeeGoTextDim
import com.meego.launcher.ui.theme.MeeGoTextWhite
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH)
        while (true) {
            val now = Date()
            currentTime = timeFormat.format(now)
            currentDate = dateFormat.format(now)
            delay(1000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // MeeGo 经典大字时钟
        Text(
            text = currentTime.ifEmpty { "19:00" },
            color = MeeGoTextWhite,
            fontSize = 58.sp,
            letterSpacing = (-1).sp
        )
        Text(
            text = currentDate.ifEmpty { "Tuesday, September 22" },
            color = MeeGoCyan,
            fontSize = 17.sp
        )

        Spacer(modifier = Modifier.height(36.dp))

        Text(
            text = "NOTIFICATIONS & FEEDS",
            color = MeeGoCyan,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        FeedCard(
            title = "MeeGo Launcher v1.3.0",
            body = "Nokia N9 体验已点亮。支持 4/5 列排版与双指 2x2/3x3 缩放。",
            time = "Just now"
        )

        Spacer(modifier = Modifier.height(12.dp))

        FeedCard(
            title = "Root Privileges Active",
            body = "后台卡片管理通道已建立，支持实时秒切与清理。",
            time = "System"
        )
    }
}

@Composable
private fun FeedCard(
    title: String,
    body: String,
    time: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SquircleShape(cornerRadius = 16.dp))
            .background(MeeGoCardBg)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, color = MeeGoTextWhite, fontSize = 15.sp)
            Text(text = time, color = MeeGoTextDim, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = body, color = MeeGoTextDim, fontSize = 13.sp)
    }
}
