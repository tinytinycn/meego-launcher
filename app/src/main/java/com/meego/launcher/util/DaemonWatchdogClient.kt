package com.meego.launcher.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 守护进程存活守卫（App 侧辅助唤醒源）。
 *
 * ## 背景
 * 特权守护脚本 `meego_daemon.sh` 由 init 以 **oneshot** service 方式拉起。
 * oneshot 的语义是「进程一退出，init 就把 service 标记 stopped」，而
 * `on property:sys.boot_completed=1` 只在开机触发一次 —— 脚本一旦死亡，
 * service 就永久停在 stopped，直到下次重启。旧版部署脚本还会用 nohup
 * 手动拉起与 init 实例互相 kill -9，最终留下一个无人监管的孤儿进程。
 * 两者叠加的表象是：多任务屏点删除后卡片消失、但后台进程其实没被杀。
 *
 * ## 职责边界
 * **真正的自愈由 root 侧 watchdog 完成**（脚本内 `__WATCHDOG` 模式，每 5s
 * 校验主进程存活，连续 3 次确认死亡后用 `setprop ctl.start` 让 init 重新
 * 拉起）。本类只负责在启动器运行时**顺带**把主进程叫醒，省掉那 15s 等待。
 *
 * ## 为什么不直接拉起
 * 设备上没有 `su` 二进制，App 进程（u0_a209）无法提权，因此
 * `Runtime.exec("setprop ctl.start ...")` 这条路走不通。App 唯一可写之处
 * 是自身私有目录下的 `cmd_queue`，而那是**给守护进程发指令**的单向通道。
 * 所以 App 能做的最有力的事，是发现「watchdog 健在但主进程已死」时投一条
 * `ENSURE_DAEMON`，让 watchdog 跳过宽限期立即补拉。
 *
 * ## 为什么读文件而不是扫 /proc
 * 本机 `/proc` 挂载了 `hidepid=2`，普通 uid 看不到其他进程的 `/proc/<pid>`
 * 目录（已实测：root 能看到 watchdog，App 却扫不到）。因此双方改为各自
 * 申报心跳、App 读文件判断：
 *  - `/data/local/tmp/meego_daemon.alive`     —— 主进程每 2s 刷新
 *  - `/data/local/tmp/meego_daemon.watchdog`  —— watchdog 每 5s 刷新
 * 两者权限均为 666，内容为 `<pid> <epochSeconds>`，便于 App 直接判新旧。
 */
object DaemonWatchdogClient {

    private const val TAG = "MeeGoDaemonGuard"
    private const val CMD_QUEUE_DIR = "/data/user/0/com.meego.launcher/files/cmd_queue"

    // 心跳文件必须落在 App 私有目录：/data/local/tmp 属 shell_data_file 域，
    // 即便权限 666，App 侧仍会被 SELinux 拒绝（真机实测 avc: denied read）。
    private val ALIVE_FILE = File(CMD_QUEUE_DIR, "daemon.hb")
    private val WATCHDOG_FILE = File(CMD_QUEUE_DIR, "watchdog.hb")

    /** 心跳超过该秒数即视为失联（主进程 2s / watchdog 5s，留足余量）。 */
    private const val ALIVE_STALE_SEC = 15L
    private const val WATCHDOG_STALE_SEC = 20L

    private data class Heartbeat(val pid: Int, val stampSec: Long)

    /**
     * 读取心跳文件并判断是否新鲜。
     * @return 新鲜返回 true；文件缺失、格式错误或超时均返回 false。
     */
    private fun isHeartbeatFresh(file: File, staleAfterSec: Long): Boolean {
        if (!file.exists()) return false
        val stamp = runCatching {
            val parts = file.readText().trim().split(Regex("\\s+"))
            if (parts.size < 2) return false
            parts[1].toLong()
        }.getOrNull() ?: return false
        // 留 1s 容差：守护侧写入与 App 读取存在极小的时钟/调度偏差
        val ageSec = (System.currentTimeMillis() / 1000) - stamp
        return ageSec in -1..staleAfterSec
    }

    /**
     * 启动器启动时调用一次。
     *
     * - 主进程心跳新鲜 → 守护链路健在，读一次文件即返回（仅留一条 Info 日志，
     *   作为"守卫确实跑过"的唯一可观测点；启动器每次启动最多一条，可忽略）。
     * - 主进程心跳过期、但 watchdog 心跳新鲜 → 投 `ENSURE_DAEMON`，
     *   让 watchdog 立即补拉主进程，而不必等满 15s 宽限期。
     * - watchdog 心跳也过期 → App 无权提权重启（设备无 su），只能记录，
     *   该场景交由 init 的开机自启路径兜底。
     */
    fun ensureDaemonRunning(context: Context) {
        runCatching {
            if (isHeartbeatFresh(ALIVE_FILE, ALIVE_STALE_SEC)) {
                Log.i(TAG, "daemon alive, nothing to do")
                return
            }

            if (!isHeartbeatFresh(WATCHDOG_FILE, WATCHDOG_STALE_SEC)) {
                Log.w(
                    TAG,
                    "watchdog absent and main process down; " +
                        "App cannot restart it (no su). Rely on init boot service."
                )
                return
            }

            enqueueCommand("ENSURE_DAEMON")
            Log.i(TAG, "main process down but watchdog alive; requested immediate restart")
        }.onFailure {
            Log.w(TAG, "ensureDaemonRunning failed", it)
        }
    }

    /** 复用守护脚本既有的 cmd_queue 通道投递一条指令。 */
    private fun enqueueCommand(cmd: String) {
        val dir = File(CMD_QUEUE_DIR)
        if (!dir.exists()) dir.mkdirs()
        File(dir, "cmd_${System.currentTimeMillis()}_ensure.txt").writeText("$cmd\n")
    }
}
