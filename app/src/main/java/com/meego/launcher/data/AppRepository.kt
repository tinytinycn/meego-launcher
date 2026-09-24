package com.meego.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

        // 注入 MeeGo Settings 专属应用入口
        apps.add(
            AppItem(
                packageName = ownPackageName,
                activityName = "MeeGoSettings",
                label = "MeeGo Settings",
                icon = context.getDrawable(R.mipmap.ic_launcher),
                isMeeGoSettings = true
            )
        )

        val regularApps = resolveInfos
            .filter { it.activityInfo.packageName != ownPackageName }
            .map { resolveInfo ->
                AppItem(
                    packageName = resolveInfo.activityInfo.packageName,
                    activityName = resolveInfo.activityInfo.name,
                    label = resolveInfo.loadLabel(pm).toString(),
                    icon = resolveInfo.loadIcon(pm),
                    isMeeGoSettings = false
                )
            }
            .sortedBy { it.label.lowercase() }

        apps.addAll(regularApps)
        apps
    }
}
