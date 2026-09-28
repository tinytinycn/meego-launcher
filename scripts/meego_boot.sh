#!/system/bin/sh
# ==============================================================================
# MeeGo Launcher 守护链拉起器
#
# 【为什么单独抽成一个脚本，而不是各处内联】
# 启动顺序「watchdog 先、主进程后」是 v1.8.35 用四次真机否决换来的结论
# （完整推导见 meego_daemon.sh 顶部注释）。deploy.sh 与开机钩子若各写一份
# 内联逻辑，这两份迟早会漂移 —— 而顺序一旦漂移，watchdog 就会被
# init / Magisk 的「本次启动派生树」清理一并带走，表现为「守护进程又失联了」，
# 且这种回归极难复现（只在开机路径上出现）。故把顺序收敛到此一处，
# 两条入口都调用它。
#
# 用法：
#   meego_boot.sh                立即拉起（deploy.sh 部署后调用）
#   meego_boot.sh --wait-boot    先等 sys.boot_completed=1 再拉起
#   （放在 /data/adb/service.d/ 下时自动等同 --wait-boot 并自脱离，见第 0 节）
#
# 本脚本**幂等**：任一步已就绪则跳过，可安全重复执行。
# ==============================================================================

LOG="/data/local/tmp/meego_boot.log"
DAEMON="/data/local/tmp/meego_daemon.sh"
PIDFILE="/data/local/tmp/meego_daemon.pid"

log() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null
}

# 日志自轮转：只保留最近 200 行，避免长期开机累积成垃圾
if [ -f "$LOG" ]; then
    tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
fi

# ------------------------------------------------------------------------------
# 0. 开机路径的入口保护：先自脱离，再等待
#
# 【为什么必须自脱离（这是一条防砖保护，不是洁癖）】
# 本脚本在开机路径下要先等 sys.boot_completed=1，而该属性恰恰是**开机流程
# 走完之后**才由 system_server 置位的。若执行本脚本的上层（Magisk / init）
# 会等待脚本返回，就构成死锁：上层等脚本 → 脚本等 boot_completed →
# boot_completed 等上层放行 → **开机永远完不成**。
#
# Magisk 当前实现用的是 `fork_dont_care()`（不等待），所以现实中不会死锁；
# 但这个失败模式的代价是「设备卡在开机」，且它取决于第三方实现细节 ——
# 不能赌。故这里无条件把自己转入后台后立即退出：无论上层是否等待，
# 都不可能被我们拖住。
#
# 【为何用「路径自识别」而不是准备两份脚本】
# 同一份 meego_boot.sh 会被放在两个位置并各自执行：
#   /data/local/tmp/meego_boot.sh     ← deploy.sh 立即拉起时执行
#   /data/adb/service.d/meego_boot.sh ← Magisk 开机时执行
# 二者的唯一差别是「要不要先等开机」——用 $0 判断即可，无需生成包装脚本。
# 这样「启动顺序」这个关键逻辑在全项目只有一份，不可能漂移。
# ------------------------------------------------------------------------------
WAIT_BOOT=0
case "$0" in
    /data/adb/service.d/*) WAIT_BOOT=1 ;;
esac
[ "$1" = "--wait-boot" ] && WAIT_BOOT=1

if [ "$WAIT_BOOT" = "1" ] && [ "$1" != "--detached" ]; then
    # 转入后台并立即返回（--detached 同时充当递归哨兵，避免无限自我复制）
    ( exec /system/bin/sh "$0" --detached ) >> "$LOG" 2>&1 &
    log "detached from boot stage, will wait for boot_completed in background"
    exit 0
fi
[ "$1" = "--detached" ] && WAIT_BOOT=1

if [ "$WAIT_BOOT" = "1" ]; then
    n=0
    while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
        sleep 2
        n=$((n + 1))
        if [ "$n" -gt 150 ]; then
            log "WARN boot_completed timeout after 300s, proceeding anyway"
            break
        fi
    done
    # 开机完成到系统服务稳定之间还有一段窗口，留 3s 余量
    sleep 3
fi

if [ ! -f "$DAEMON" ]; then
    log "FATAL daemon script missing: $DAEMON"
    echo "meego_boot: daemon script missing: $DAEMON" >&2
    exit 1
fi

# ------------------------------------------------------------------------------
# 探针：只看「主进程」与「watchdog」
#
# 不能用 `pgrep -f meego_daemon.sh` 直接当判据 —— 它会同时匹配到：
#   · watchdog
#   · monitor_cmd_queue / process_fifo / monitor_current_app 三个后台子 shell
#     （fork 自同一脚本，cmdline 与主进程完全相同）
#   · **`setsid /system/bin/sh -c "... meego_daemon.sh ..."` 的外层包装进程**
#     （本文件第 1 步拉起 watchdog 时就是这么调的；它的 cmdline 里含脚本路径，
#      但 argv2 是 `-c`）
#
# 【实测踩坑】漏掉第三类会造成极隐蔽的后果：包装进程 argv3 为空、看起来就像
# 主进程，于是被判为「main already alive」而**跳过真正的拉起**，最终整条链只剩
# 一个 watchdog、没有任何主进程在工作。故判据必须同时校验：
#   argv2 == $DAEMON      （排除 `-c` 包装进程）
#   argv3 == __WATCHDOG   （区分 watchdog 与主进程）
# 这与 meego_daemon.sh 内部 watchdog_alive() 的判据保持一致。
# ------------------------------------------------------------------------------
cmdline_field() {
    tr '\0' '\n' < "/proc/$1/cmdline" 2>/dev/null | sed -n "${2}p"
}

is_daemon_proc() {
    [ "$(cmdline_field "$1" 2)" = "$DAEMON" ]
}

watchdog_pid() {
    for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null); do
        is_daemon_proc "$p" || continue
        [ "$(cmdline_field "$p" 3)" = "__WATCHDOG" ] && { echo "$p"; return 0; }
    done
    return 1
}

# 主进程 = argv2 为本脚本且 argv3 非 watchdog 的第一个匹配。
# 之所以「第一个」即可：主进程总是先启动，三个 monitor 子 shell 由它 fork，
# pid 必然更大；pgrep 按 pid 升序输出。
main_pid() {
    # 首选权威来源：守护脚本自己用 $$ 写入的 pidfile
    # （该行只有主进程会执行 —— watchdog 在其上方进入无限循环，永不抵达）
    if [ -f "$PIDFILE" ]; then
        p=$(cat "$PIDFILE" 2>/dev/null | tr -d '\r\n ')
        if [ -n "$p" ] && [ -d "/proc/$p" ]; then
            echo "$p"
            return 0
        fi
    fi
    for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null); do
        is_daemon_proc "$p" || continue
        [ "$(cmdline_field "$p" 3)" != "__WATCHDOG" ] && { echo "$p"; return 0; }
    done
    return 1
}

# ------------------------------------------------------------------------------
# 1. 预置独立 watchdog（必须在主进程之前）
#
# 关键：watchdog 必须**不是**本次调用派生的进程树中的一环。若由主进程 fork，
# init 终止 oneshot service（或 Magisk 清理本次启动派生树）时会连带把它带走，
# 自愈链随即整体消失。故这里先用双重 setsid 让它自成会话并被 init 收养。
# ------------------------------------------------------------------------------
W_PID=$(watchdog_pid || true)
if [ -n "$W_PID" ]; then
    log "watchdog already alive (pid=$W_PID), skip"
else
    setsid /system/bin/sh -c "setsid /system/bin/sh \"$DAEMON\" __WATCHDOG >/dev/null 2>&1" >/dev/null 2>&1 &
    log "watchdog spawned"
    sleep 2
fi

# ------------------------------------------------------------------------------
# 2. 拉起主进程
#
# 刻意不用 `setprop ctl.start`：init 的 meego_daemon.rc 指向
# /system/bin/meego_daemon.sh，而 /system 受 dm-verity 保护无法更新，
# 那里永远停留在首次刷入时的旧版本 —— ctl.start 拉起的是那份旧脚本，
# 自愈一次等于回退一次。故一律直接执行 /data/local/tmp 下的当前版本。
# ------------------------------------------------------------------------------
M_PID=$(main_pid || true)
if [ -n "$M_PID" ]; then
    log "main already alive (pid=$M_PID), skip"
    exit 0
fi

nohup "$DAEMON" > /data/local/tmp/daemon.log 2>&1 &

# 最多等 10s 确认主进程落定（守护脚本启动时要建目录、建 FIFO、跑首轮 update_tasks）
n=0
while [ "$n" -lt 10 ]; do
    sleep 1
    n=$((n + 1))
    M_PID=$(main_pid || true)
    if [ -n "$M_PID" ]; then
        log "main started (pid=$M_PID, ${n}s)"
        exit 0
    fi
done

log "WARN main not up after 10s, see /data/local/tmp/daemon.log"
exit 1
