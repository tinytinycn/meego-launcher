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

/**
 * 一次手势滑出的完整记录，四项内容与时刻戳**同生共死**。
 *
 * 把它们收进一条不可变记录（而非三个平行的 `public var`），是为了让
 * 「写入必盖时刻戳」这一不变量在类型层面成立：不存在「只改了任务号、
 * 忘了更新时刻戳」这种半新半旧的中间态。
 *
 * @param taskId 该次滑出对应的任务栈 ID。无法确定时为 null，此时不产生置顶锁。
 * @param atMs   滑出发生的时刻（epoch ms），由 [TaskSnapshotCache.recordSwipe] 现场取。
 */
data class SwipeRecord(
    val taskId: Int?,
    val packageName: String?,
    val bitmap: Bitmap?,
    val atMs: Long
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

    /**
     * 最近一次手势滑出的记录。
     *
     * 刻意**私有**：滑出信息必须整条经 [recordSwipe] 写入、经 [activeSwipe] 读出。
     * 此前三个字段是 `public var`，任何调用点都能裸写 `lastSwipedTaskId` 而漏掉时刻戳，
     * 不变量只能靠约定维持 —— 这正是 v1.8.31 那个 A D B C 缺陷得以长成的原因。
     * 收敛为单条私有记录后，编译期即保证外部无法绕过「写入必盖时刻戳、读取必判有效期」。
     */
    private var lastSwipe: SwipeRecord? = null

    /**
     * 记录一次「手势滑出」事件，并把 taskId / 包名 / 画面 / 时刻戳**原子地**写成一条记录。
     *
     * [taskId] 为 null 或非正时表示「滑出了但未能确定任务号」：画面与包名仍然记录
     * （供快照回退使用），但不产生置顶锁 —— 没有任务号本就无从置顶。
     * 这一点也意味着：包名/画面与时刻戳**总是同时更新**，不会出现
     * 「画面是新的、时刻戳却是旧的」这种半新半旧状态。
     */
    @Synchronized
    fun recordSwipe(taskId: Int?, packageName: String?, bitmap: Bitmap?) {
        lastSwipe = SwipeRecord(
            taskId = if (taskId != null && taskId > 0) taskId else null,
            packageName = packageName,
            bitmap = bitmap,
            atMs = System.currentTimeMillis()
        )
    }

    /**
     * 取仍然有效的滑出记录；已过期或从未记录则返回 null。
     *
     * 「滑出置顶」保序锁的唯一目的，是补偿守护进程把 `dumpsys activity recents`
     * 新顺序落盘到 JSON 之前的短暂空窗期（主循环 2s 轮询），使刚滑出的任务
     * 不被陈旧数据反向拉回后方。
     *
     * **但它绝不能越过有效期**：一旦用户在此期间又唤起了别的应用，系统 recents 中
     * 真正的最近使用任务已经换人，此时若仍强行置顶旧任务，就会把新任务挤到第二位，
     * 表现为「打开 D 再退出后，卡片顺序是 A D B C 而不是 D A B C」。
     * 根因即滑出记录在 `remove`/`clear` 之外从无复位点，会无限期存活。
     *
     * 因此以时刻戳划定一个短窗口：窗口内视作「磁盘数据可能陈旧，信任置顶锁」，
     * 窗口外视作「锁已失效，一律以系统 recents 权威顺序为准」。
     * 窗口过期只会退化为**正确**行为，不会退化为缺陷，故该取值只需宽于落盘滞后。
     *
     * 这是滑出信息的**唯一**合法出口：调用方无需（也不应）自行判断有效期。
     */
    @Synchronized
    fun activeSwipe(): SwipeRecord? {
        val record = lastSwipe ?: return null
        val elapsed = System.currentTimeMillis() - record.atMs
        // elapsed < 0 说明系统时钟被回拨，此时宁可保守判为失效
        return if (elapsed in 0 until SWIPE_PIN_VALIDITY_MS) record else null
    }

    /**
     * 作废滑出记录。用于「用户已唤起新应用」的时刻 —— 新应用的启动优先级高于
     * 一切历史滑出记录，旧锁在此之后再无置顶理由。
     */
    @Synchronized
    fun clearSwipeRecord() {
        lastSwipe = null
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
        if (lastSwipe?.taskId == taskId) {
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
    val snapshotTimeMs: Long = 0L
    // 【v1.8.44】原有的 `snapshotIsSettled` 已删除。
    // 它区分「系统稳定帧」与「启动动画期的临时占位帧」，而那一档画面来自
    // `screencap -p` 的整屏帧。常驻 Task Snapshot 服务上线后，帧只有两种来源：
    //   · 可见任务 → takeTaskSnapshot 实时取当前画面（启动动画期也是真实画面）；
    //   · 后台任务 → getTaskSnapshot 取「变不可见」那一刻的最终态。
    // 两者都是真实帧，不再存在「半渲染过渡帧」，故该标志无意义。
)
