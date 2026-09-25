package com.meego.launcher.model

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

enum class LaunchOrigin(val pageIndex: Int) {
    GRID(1),
    RECENTS(2)
}

object LaunchOriginTracker {
    var lastOrigin: LaunchOrigin = LaunchOrigin.GRID
    var currentTaskId: Int? = null
    var currentPackageName: String? = null

    fun reset() {
        currentTaskId = null
        currentPackageName = null
    }
}

object LauncherPreviews {
    var gridBitmap: Bitmap? = null
    var recentsBitmap: Bitmap? = null
}

object TaskSnapshotCache {
    private val cache = mutableMapOf<Int, Bitmap>()
    private val pkgCache = mutableMapOf<String, Bitmap>()
    var lastSwipedTaskId: Int? = null
    var lastSwipedPackageName: String? = null
    var lastSwipedBitmap: Bitmap? = null

    @Synchronized
    fun put(taskId: Int, bitmap: Bitmap, packageName: String? = null) {
        cache[taskId] = bitmap
        if (packageName != null) {
            pkgCache[packageName] = bitmap
        }
    }

    @Synchronized
    fun get(taskId: Int): Bitmap? {
        return cache[taskId]
    }

    @Synchronized
    fun getByPackage(packageName: String): Bitmap? {
        return pkgCache[packageName]
    }

    @Synchronized
    fun remove(taskId: Int) {
        cache.remove(taskId)
        if (lastSwipedTaskId == taskId) {
            lastSwipedTaskId = null
            lastSwipedBitmap = null
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        pkgCache.clear()
        lastSwipedTaskId = null
        lastSwipedPackageName = null
        lastSwipedBitmap = null
    }
}

@Immutable
data class AppItem(
    val packageName: String,
    val activityName: String = "",
    val label: String,
    val icon: Drawable? = null,
    val iconBitmap: ImageBitmap? = null,
    val isMeeGoSettings: Boolean = false
)

data class RecentTask(
    val taskId: Int,
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    val snapshot: Bitmap? = null,
    val isForeground: Boolean = false
)
