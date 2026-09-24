package com.meego.launcher.model

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

enum class LaunchOrigin(val pageIndex: Int) {
    GRID(1),
    RECENTS(2)
}

object LaunchOriginTracker {
    var lastOrigin: LaunchOrigin = LaunchOrigin.GRID
    var currentTaskId: Int? = null
    var currentPackageName: String? = null
}

object LauncherPreviews {
    var gridBitmap: Bitmap? = null
    var recentsBitmap: Bitmap? = null
}

object TaskSnapshotCache {
    private val cache = mutableMapOf<Int, Bitmap>()
    var lastSwipedTaskId: Int? = null
    var lastSwipedPackageName: String? = null
    var lastSwipedBitmap: Bitmap? = null

    @Synchronized
    fun put(taskId: Int, bitmap: Bitmap) {
        cache[taskId] = bitmap
    }

    @Synchronized
    fun get(taskId: Int): Bitmap? {
        return cache[taskId]
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
        lastSwipedTaskId = null
        lastSwipedPackageName = null
        lastSwipedBitmap = null
    }
}

data class AppItem(
    val packageName: String,
    val activityName: String = "",
    val label: String,
    val icon: Drawable? = null,
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
