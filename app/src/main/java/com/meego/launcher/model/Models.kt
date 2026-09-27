package com.meego.launcher.model

import android.graphics.Bitmap
import android.graphics.Rect
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

    /**
     * 当前三屏所在的页序：0 = Events Feed，1 = Application Grid，2 = Multitasking View。
     *
     * 与 [lastOrigin] **刻意不同**，二者不可混用：
     * - [lastOrigin] 记录「应用是从哪一屏被唤起的」，决定划出后**回溯到哪一页**；
     * - 本字段记录「用户此刻正停在哪一屏」，决定划出**动画**该如何表现。
     *
     * 之所以必须独立：Events Feed 从不写入 [lastOrigin]（它不是应用唤起来源，
     * `LaunchOrigin` 枚举中也无对应值），故停留在 Feed 时 [lastOrigin] 仍停留在
     * 上一次的 GRID/RECENTS。若拿 [lastOrigin] 判断「是否给底层桌面加层级微缩放」，
     * 就会在 Feed 上误判为 Grid 而错误地加上一层缩放动画。
     */
    var currentScreenIndex: Int = LaunchOrigin.GRID.pageIndex

    fun reset() {
        currentTaskId = null
        currentPackageName = null
    }
}

object LauncherPreviews {
    var gridBitmap: Bitmap? = null
    var recentsBitmap: Bitmap? = null

    /**
     * 抓帧时该窗口在**物理屏幕**上的绝对位置与尺寸（px）。
     *
     * 快照位图的大小 == 窗口大小，而非物理屏幕大小：挖孔机型上 MainActivity 的窗口
     * 被挖孔 inset 顶掉（如 Redmi K40 1080x2400 / 挖孔 95px → 窗口 [0,95][1080,2400]，
     * 高 2305），于是快照是 1080x2305。而手势 Overlay 是带 `FLAG_LAYOUT_NO_LIMITS`
     * 的全屏 1080x2400。若把 2305 高的快照直接 `FIT_XY` 铺进 2400 高的 Overlay，
     * 整幅画面会被纵向拉伸 1.0412 倍，且顶部 95px 挖孔留白被撑没 —— 即
     * 「手动划出过程中 applications grid 顶到屏幕最上沿、铺满全屏」的根因。
     *
     * 因此必须一并记住**窗口的真实屏幕矩形**，渲染层据此把快照 1:1 贴回原位，
     * 而非铺满 Overlay。
     */
    var gridWindowRect: Rect? = null
    var recentsWindowRect: Rect? = null
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
    /**
     * 滑出保序锁的有效期。
     *
     * 需覆盖「滑出 → 守护进程 `update_tasks` 落盘」的最大滞后。守护进程主循环
     * 轮询间隔 2s（`meego_daemon.sh` 末尾 `sleep 2.0`），且落盘前还要做
     * `cp` 快照同步与 JSON 组装，故取 5s 留足余量。
     *
     * 取值过小 → 锁在磁盘数据到达前就失效，卡片会先回弹到旧位再跳回（v1.8.13 修掉的抖动）。
     * 取值过大 → 锁越过用户后续的唤起动作仍在置顶旧任务，即本次修复的 A D B C 缺陷。
     */
    private const val SWIPE_PIN_VALIDITY_MS = 5_000L

    private val cache = mutableMapOf<Int, Bitmap>()
    private val pkgCache = mutableMapOf<String, Bitmap>()
    private val entries = mutableMapOf<Int, SnapshotEntry>()
    private val pkgEntries = mutableMapOf<String, SnapshotEntry>()

    var lastSwipedTaskId: Int? = null
    var lastSwipedPackageName: String? = null
    var lastSwipedBitmap: Bitmap? = null

    /**
     * 最近一次手势滑出的时刻（epoch ms），用于判定 [lastSwipedTaskId] 是否仍然有效。
     */
    private var lastSwipedAtMs: Long = 0L

    /**
     * 记录一次「手势滑出」事件并盖上时刻戳。
     *
     * 必须经由本方法写入 [lastSwipedTaskId]，否则 [isSwipePinValid] 会因时刻戳缺失
     * 而恒判为失效，滑出保护随之失效。
     */
    @Synchronized
    fun recordSwipe(taskId: Int?, packageName: String?, bitmap: Bitmap?) {
        if (taskId != null && taskId > 0) {
            lastSwipedTaskId = taskId
            lastSwipedAtMs = System.currentTimeMillis()
        }
        if (packageName != null) {
            lastSwipedPackageName = packageName
        }
        if (bitmap != null) {
            lastSwipedBitmap = bitmap
        }
    }

    /**
     * 「滑出置顶」保序锁当前是否仍然可信。
     *
     * 该锁存在的唯一目的，是补偿守护进程把 `dumpsys activity recents` 新顺序落盘到
     * JSON 之前的短暂空窗期（主循环 2s 轮询 + 滑出路径的即时刷盘），使刚滑出的任务
     * 不被陈旧数据反向拉回后方。
     *
     * **但它绝不能越过有效期**：一旦用户在此期间又唤起了别的应用，系统 recents 中
     * 真正的最近使用任务已经换人，此时若仍强行置顶旧任务，就会把新任务挤到第二位，
     * 表现为「打开 D 再退出后，卡片顺序是 A D B C 而不是 D A B C」。
     * 根因即 `lastSwipedTaskId` 在 `remove`/`clear` 之外从无复位点，会无限期存活。
     *
     * 因此以时刻戳划定一个短窗口：窗口内视作「磁盘数据可能陈旧，信任置顶锁」，
     * 窗口外视作「锁已失效，一律以系统 recents 权威顺序为准」。
     * 窗口过期只会退化为**正确**行为，不会退化为缺陷，故该取值只需宽于落盘滞后。
     */
    @Synchronized
    fun isSwipePinValid(): Boolean {
        val id = lastSwipedTaskId ?: return false
        if (id <= 0) return false
        val elapsed = System.currentTimeMillis() - lastSwipedAtMs
        return elapsed >= 0 && elapsed < SWIPE_PIN_VALIDITY_MS
    }

    /**
     * 作废滑出保序锁。用于「用户已唤起新应用」的时刻 —— 新应用的启动优先级高于
     * 一切历史滑出记录，旧锁在此之后再无置顶理由。
     */
    @Synchronized
    fun clearSwipeRecord() {
        lastSwipedTaskId = null
        lastSwipedPackageName = null
        lastSwipedBitmap = null
        lastSwipedAtMs = 0L
    }

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
            clearSwipeRecord()
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
        clearSwipeRecord()
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
