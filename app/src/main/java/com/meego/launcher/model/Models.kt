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

/**
 * 一帧快照及其真实元信息。
 *
 * @param captureTimeMs 该帧被**抓取**的时刻（epoch ms），必须来自文件 mtime 或抓帧现场，
 *                      绝不能用「写入内存的时刻」代替 —— 否则手势动画里把帧塞进缓存时
 *                      会盖上「现在」的时间戳，把一帧旧过渡帧伪装成最新帧，新鲜度仲裁随之失效。
 * @param isSettled 该帧是否已确认是应用稳定后的画面。false 表示仍处于启动动画期，
 *                   渲染层应降级为图标占位而非展示半渲染画面。
 */
data class SnapshotEntry(
    val bitmap: Bitmap,
    val captureTimeMs: Long,
    val isSettled: Boolean
)

object TaskSnapshotCache {
    private val cache = mutableMapOf<Int, Bitmap>()
    private val pkgCache = mutableMapOf<String, Bitmap>()
    private val entries = mutableMapOf<Int, SnapshotEntry>()
    private val pkgEntries = mutableMapOf<String, SnapshotEntry>()

    var lastSwipedTaskId: Int? = null
    var lastSwipedPackageName: String? = null
    var lastSwipedBitmap: Bitmap? = null

    @Synchronized
    fun put(
        taskId: Int,
        bitmap: Bitmap,
        packageName: String? = null,
        captureTimeMs: Long = System.currentTimeMillis(),
        isSettled: Boolean = true
    ) {
        cache[taskId] = bitmap
        entries[taskId] = SnapshotEntry(bitmap, captureTimeMs, isSettled)
        if (packageName != null) {
            pkgCache[packageName] = bitmap
            pkgEntries[packageName] = SnapshotEntry(bitmap, captureTimeMs, isSettled)
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

    /** 取该任务的帧及其元信息；无记录时返回 null。 */
    @Synchronized
    fun getEntry(taskId: Int): SnapshotEntry? {
        return entries[taskId]
    }

    @Synchronized
    fun remove(taskId: Int) {
        val removed = cache.remove(taskId)
        entries.remove(taskId)
        if (lastSwipedTaskId == taskId) {
            lastSwipedTaskId = null
            lastSwipedBitmap = null
        }
        // 包名索引可能仍指向该任务，顺手剔除，避免已删除任务继续被按包名命中
        if (removed != null) {
            pkgCache.entries.removeAll { it.value === removed }
            pkgEntries.entries.removeAll { it.value.bitmap === removed }
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        pkgCache.clear()
        entries.clear()
        pkgEntries.clear()
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
) {
    /**
     * LazyVerticalGrid 的稳定 key，构造时一次性算好。
     *
     * 性能要点：此前 `key = { it.packageName + it.activityName }` 会在每次 measure/recycle
     * 时对列表中的每个可见项做一次字符串拼接（30+ 应用 = 每帧 30+ 次 String 分配），
     * 属于典型的滑动期主线程分配抖动。改为在 data class 构造时预计算成不可变字段，
     * key lambda 退化为一次字段读取。
     */
    val gridKey: String = packageName + "/" + activityName
}

data class RecentTask(
    val taskId: Int,
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    val snapshot: Bitmap? = null,
    val isForeground: Boolean = false,
    /**
     * 该快照被**抓取**的时刻（epoch ms）。渲染层可据此判断画面是否已过期，
     * 避免长期展示陈旧帧。仅作观测用途，不参与 data class 相等性比较之外的逻辑。
     */
    val snapshotTimeMs: Long = 0L,
    /** 该快照是否已确认是应用稳定后的画面；false 时渲染层应降级为图标占位。 */
    val snapshotIsSettled: Boolean = true
)
