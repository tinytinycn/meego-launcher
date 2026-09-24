package com.meego.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meego.launcher.ui.components.SquircleShape
import com.meego.launcher.ui.theme.MeeGoCardBg
import com.meego.launcher.ui.theme.MeeGoCyan
import com.meego.launcher.ui.theme.MeeGoTextDim
import com.meego.launcher.ui.theme.MeeGoTextWhite

@Composable
fun SettingsScreen(
    currentColumns: Int,
    onSelectColumns: (Int) -> Unit,
    edgeSwipeEnabled: Boolean,
    onToggleEdgeSwipe: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部返回按钮与标题
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MeeGoTextWhite
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "MeeGo Settings",
                color = MeeGoTextWhite,
                fontSize = 22.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 分组 1: Application Grid 列数
        Text(
            text = "APPLICATION GRID 图标列数",
            color = MeeGoCyan,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SquircleShape(cornerRadius = 16.dp))
                .background(MeeGoCardBg)
        ) {
            SettingRadioItem(
                title = "4 列图标 (经典 Nokia N9 比例)",
                description = "舒展大气呼吸感，适合经典视效体验",
                isSelected = currentColumns == 4,
                onClick = { onSelectColumns(4) }
            )
            Divider(color = Color(0xFF2C2C2C), thickness = 0.5.dp)
            SettingRadioItem(
                title = "5 列图标 (现代全面屏高密紧凑)",
                description = "充分利用 20:9 物理长屏纵深，单屏展示更多应用",
                isSelected = currentColumns == 5,
                onClick = { onSelectColumns(5) }
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 分组 2: 全局上半屏边缘划回手势
        Text(
            text = "ORIGIN-AWARE SWIPE 边缘手势",
            color = MeeGoCyan,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SquircleShape(cornerRadius = 16.dp))
                .background(MeeGoCardBg)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "全屏边缘侧滑切回",
                        color = MeeGoTextWhite,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "从任意 App 屏幕左右边缘划入 1/2 松手，切回启动源页面",
                        color = MeeGoTextDim,
                        fontSize = 13.sp
                    )
                }
                Switch(
                    checked = edgeSwipeEnabled,
                    onCheckedChange = onToggleEdgeSwipe,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MeeGoCyan
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 分组 3: 系统与物理机信息
        Text(
            text = "SYSTEM & HARDWARE",
            color = MeeGoCyan,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SquircleShape(cornerRadius = 16.dp))
                .background(MeeGoCardBg)
                .padding(16.dp)
        ) {
            InfoRow("设备机型", "Redmi K40 / POCO F3 (alioth)")
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow("操作系统", "LineageOS (Android 16)")
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow("ADB 状态", "Root Privileges (Active)")
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow("安全模式", "独立组件调试（未破坏原生桌面）")
        }
    }
}

@Composable
private fun SettingRadioItem(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = MeeGoTextWhite, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, color = MeeGoTextDim, fontSize = 12.sp)
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MeeGoCyan,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = key, color = MeeGoTextDim, fontSize = 14.sp)
        Text(text = value, color = MeeGoTextWhite, fontSize = 14.sp)
    }
}
