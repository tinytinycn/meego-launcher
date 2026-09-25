package com.meego.launcher.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.meego.launcher.model.AppItem
import com.meego.launcher.model.LaunchOrigin
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.ui.components.DefaultSquircleShape
import com.meego.launcher.ui.components.rememberMeeGoBounceController
import com.meego.launcher.ui.components.rememberMeeGoFlingBehavior
import com.meego.launcher.ui.theme.MeeGoCardBg
import com.meego.launcher.ui.theme.MeeGoCyan
import com.meego.launcher.ui.theme.MeeGoTextDim
import com.meego.launcher.ui.theme.MeeGoTextWhite
import com.meego.launcher.util.ScreenCaptureHelper

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppGridScreen(
    apps: List<AppItem>,
    columns: Int,
    onOpenSettings: () -> Unit,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val iconSize = if (columns == 4) 62.dp else 52.dp
    val fontSize = if (columns == 4) 12.sp else 11.sp
    val gridState = rememberLazyGridState()
    val flingBehavior = rememberMeeGoFlingBehavior()
    val bounceController = rememberMeeGoBounceController()

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            bounceController.reset()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // 顶部微间距与 MeeGo 极简标题
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Applications",
                color = MeeGoTextWhite,
                fontSize = 22.sp
            )
            Text(
                text = "${columns} 列",
                color = MeeGoCyan,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(DefaultSquircleShape)
                    .background(MeeGoCardBg)
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        CompositionLocalProvider(
            LocalOverscrollConfiguration provides null
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .nestedScroll(bounceController.nestedScrollConnection)
                    .graphicsLayer {
                        translationY = bounceController.overscrollOffset
                    }
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    flingBehavior = flingBehavior,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                items(
                    items = apps,
                    key = { it.packageName + it.activityName },
                    contentType = { "app_item" }
                ) { app ->
                    AppGridItem(
                    app = app,
                    iconSize = iconSize,
                    fontSize = fontSize,
                        onClick = {
                            if (app.isMeeGoSettings) {
                                onOpenSettings()
                            } else {
                                launchApp(context, app)
                            }
                        }
                    )
                }
            }
        }
    }
}
}

@Composable
private fun AppGridItem(
    app: AppItem,
    iconSize: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(DefaultSquircleShape)
                .background(MeeGoCardBg),
            contentAlignment = Alignment.Center
        ) {
            val iconBitmap = app.iconBitmap
            if (iconBitmap != null) {
                // 优先直接绘制异步预渲染的 ImageBitmap，主线程零内存分配，绝对满帧流畅
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (app.icon != null) {
                val fallbackBitmap = remember(app.icon) {
                    try {
                        app.icon.toBitmap(128, 128)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (fallbackBitmap != null) {
                    Image(
                        bitmap = fallbackBitmap.asImageBitmap(),
                        contentDescription = app.label,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = app.label,
            color = MeeGoTextWhite,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

private fun launchApp(context: Context, app: AppItem) {
    LaunchOriginTracker.lastOrigin = LaunchOrigin.GRID
    LaunchOriginTracker.currentPackageName = app.packageName
    LaunchOriginTracker.currentTaskId = null // 关键：清空旧应用的 TaskId，绝不在新应用中串扰使用
    (context as? Activity)?.let { activity ->
        ScreenCaptureHelper.captureLauncherScreen(activity, LaunchOrigin.GRID)
    }
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
