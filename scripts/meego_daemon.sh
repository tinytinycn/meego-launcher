#!/system/bin/sh
# ==============================================================================
# MeeGo Launcher 特权守护脚本 (运行在 adbd/root 身份下)
# 职责：
# 1. 维护 /data/local/tmp/recent_tasks.json 及 App 私有目录任务数据
# 2. 监听 App 私有目录命令队列 (cmd_queue) 与 FIFO 管道，执行真实后台进程杀死 (am force-stop, pkill)
# 3. 守护「常驻 Task Snapshot 服务」，并在其上驱动取帧（v1.8.44 起为本项目
#    唯一画面来源，对齐 AOSP/LineageOS Quickstep 的 TaskSnapshot 取帧原理）
# 4. 自愈并确保全屏边缘划出手势与无障碍顶级浮层生效
# ==============================================================================

FIFO="/data/local/tmp/meego_cmd"
TASKS_JSON="/data/local/tmp/recent_tasks.json"
SNAP_DIR="/data/system_ce/0/snapshots"
LINK_DIR="/data/local/tmp/snapshots"

# 特权侧 Task Snapshot 直读桥（v1.8.40 新增）
# 由 deploy.sh 用 javac + d8 编译本仓 scripts/TaskSnapBridge.java 得到，
# 常驻于 root 目录（App uid 加载 dex 会被 "Writable dex file" 策略拒绝，
# 故此处 root:root 755 恰是所需形态），由本脚本按需以 app_process 调用。
BRIDGE_DEX="/data/local/tmp/meego_daemon_bridge.dex"

# ---------------------------------------------------------------------------
# 0.A 常驻 Task Snapshot 服务（v1.8.44 新增，本版主链路）
# ---------------------------------------------------------------------------
# 【为什么必须常驻 —— 实测数据】
# 旧的一次性桥每次取帧都要 fork 一次 app_process，代价拆解为：
#     binder 11ms + wrap 10ms + PNG 246ms = 内部 ~267ms
#     + app_process 冷启动 ~1100ms        ← 真正的瓶颈
#     = 端到端 real ~1.35s（`time` 实测 1.33~1.41s）
#
# 常驻后实测单帧 **11~42ms**（takeTaskSnapshot 路径），提升约 40 倍。
# 且常驻进程内已持有 binder 代理与类加载结果，无需重复付出。
#
# 协议：stdin/stdout 逐行 JSON，命令见 scripts/TaskSnapService.java。
# 【FIFO 的关键坑，已实测】写端一旦退出，服务端 readLine() 立刻读到 EOF。
# 故这里用 `exec 9<>` 在**服务进程自身内部**同时持有读端与写端
# （`9<>` = 以读写方式打开，永不产生 EOF），从而服务不会因调用方断开而退出。
# 调用方则通过另一条 FIFO 写入指令，走行缓冲。
SVC_DEX="/data/local/tmp/meego_svc.dex"
SVC_FIFO="/data/local/tmp/meego_snap_cmd"
SVC_OUT="/data/local/tmp/meego_snap_out"
SVC_PID="/data/local/tmp/meego_svc.pid"
# 注意：SVC_HB 依赖 APP_CMD_DIR，故**必须**在下方 APP_DIR/APP_CMD_DIR 定义之后再赋值。
# 此前把它写在这一段里，导致解析期 APP_CMD_DIR 尚为空串、
# SVC_HB 退化成 "/svc.hb"，写入被 2>/dev/null 静默吞掉（服务心跳文件恒不出现）。

# 前台任务实时取帧的节奏（秒）。
# 【取值依据】常驻服务下单帧仅需 11~42ms，0.3s 意味着稳态帧龄上界约
# 「0.3s 等待 + 0.05s 取帧 + 0.25s 编码落盘」≈ 0.6s，比旧方案的 ~1.5s
# 明显更跟手；同时常驻进程几乎不耗电（无 fork、无 dumpsys）。
TAKE_INTERVAL=0.3

APP_DIR="/data/user/0/com.meego.launcher/files"
APP_CMD_DIR="$APP_DIR/cmd_queue"
# 常驻服务心跳：由 capture_task_snapshot 每次成功取帧后刷新，
# 供 check_device.sh 判断「服务是否真的在产出帧」（而非仅仅进程还在）。
SVC_HB="$APP_CMD_DIR/svc.hb"
APP_TASKS_JSON="$APP_DIR/recent_tasks.json"
APP_SNAP_DIR="$APP_DIR/snapshots"

# ---------------------------------------------------------------------------
# 0.0 系统快照的真实目录（v1.8.40 修复 Android 16 布局变更）
# ---------------------------------------------------------------------------
# 【这是一个已造成实质损害的既存 Bug，非预防性改动】
#
# Android 16 起，AOSP TaskSnapshotController 把快照改为**每用户一个 UUID 子目录**：
#
#   /data/system_ce/0/snapshots/8930c050-f26d-495b-9e37-8bfd7d2c5871/{689}.jpg
#                                                        {689}_reduced.jpg
#                                                        {689}.proto
#
# 而本脚本此前一直按**扁平路径** $SNAP_DIR/${tid}_reduced.jpg 取帧。实测后果：
#
#   $ ls /data/system_ce/0/snapshots/*_reduced.jpg
#   ls: ... No such file or directory        ← 零命中
#
# 即 sync_from_system_snapshot() / update_tasks() 里的系统快照同步
# **自升级以来一条都没生效过**。设备侧证据：App 私有目录里最新的
# _reduced.jpg 停在 13:36，而系统侧 14:11 的新快照从未同步下来。
# 这意味着 v1.8.38/v1.8.39 反复调优的「稳态帧龄」建立在一个从未接上系统
# 快照的降级管线上 —— 修好本项的收益大于任何新功能。
#
# 【为何必须运行时探测、不能按公式推导】
# 该 UUID 是**随机 v4 并持久化**（存于 system 分区的 persist.identifier），
# 无法由 userId/displayId 推导。已穷举 AOSP 各种 nameUUID 形式
# （TaskSnapshots-0、-displayId、user-displayId 等 8 种）全部不匹配。
# 故只能在运行时扫描出实际存在的那一个子目录。
#
# 【向后兼容】若子目录不存在（仍为扁平布局的系统），退回 $SNAP_DIR 本身，
# 旧设备行为完全不变。
SNAP_USER_DIR="$SNAP_DIR"
for _snap_sub in "$SNAP_DIR"/*/; do
    if [ -d "$_snap_sub" ]; then
        SNAP_USER_DIR="${_snap_sub%/}"
        break
    fi
done
unset _snap_sub

# ---------------------------------------------------------------------------
# 0.1 稳态快照刷新节奏（v1.8.39）
# ---------------------------------------------------------------------------
# 【「划出时截图不够及时」的根因与解法】
# 划出卡片显示的永远是「守护进程最后一次抓帧的结果」，故问题等价于「帧龄有多老」。
# 本机实测：`/data` 是 f2fs **闪存**（非 tmpfs），单次 `screencap -p` 写盘 ≈0.65s
# （其中 PNG 编码是大头；免编码的 RAW 也要 ≈0.18s），叠加主循环 `sleep 0.15` +
# `dumpsys` ≈0.03s。
#
# 原节奏为「10 次轮询抓 1 帧」= 1.8s 纯等待 + 0.65s 抓帧 ≈ **2.4s**，
# 与实测观察到的 ≈3s 刷新间隔吻合 —— 这就是用户看到的「截图不够及时」：
# 在应用里翻了好几页才划出，卡片仍停在此前那一页。
#
# 改为 5 次后，帧龄上界降至 ≈0.9s（等待）+ 0.65s（抓帧）≈ **1.5s**，
# 主观感受上「翻页后立刻划出」基本都能拿到对应的新画面。
#
# 【为何不能做得更快 / 为何不做「按需抓帧」】
# 0.65s 的闪存写是硬成本，翻倍轮询只会把常驻功耗与 IO 负担翻倍去换 0.9s，
# 且对 f2fs 而言收益递减（写放大 + GC）。更重要的是，**「手指落下后才开始抓帧」
# 在物理上根本来不及**：App 在 ACTION_DOWN 后约 30~50ms 即置位 gesture_active
# （deltaX > 25px 即触发），而最快的抓帧也要 0.18s > 50ms，且手势一旦开始，
# 像素里已混入 Overlay 底板，属污染数据。
# 故结论是：**画面的新鲜度只能由「手势开始前就已存在的帧」来保证**，
# 不存在「边滑边补抓」的有效解。这也是本版只调节奏、不加按需通道的原因。
STEADY_CAPTURE_EVERY=5

# ---------------------------------------------------------------------------
# 0. Watchdog：守护进程存活自愈
# ---------------------------------------------------------------------------
# 【背景：为什么需要 watchdog】
# 本脚本由 init 以 oneshot service 方式拉起，而 oneshot 的语义是「进程一退出，
# init 就把 service 标记为 stopped」；而 on property:sys.boot_completed=1 只在开机
# 触发一次。于是一旦脚本死亡，service 永久停在 stopped，直到下次重启。
# 旧版 deploy.sh 还会用 nohup 手动拉起脚本，与 init 管理的实例互相 kill -9，
# 手动实例上位后成为无人监管的孤儿 —— 这正是「删除/一键清理只清 UI 不杀进程」
# 的直接成因。
#
# 【真机实测结论：watchdog 必须先于 service 存在】
# 逐一验证过以下形态，结果只有一种能活：
#   (a) service 启动时 fork 出的 watchdog，setsid 后 PPID=1          -> 必死
#   (b) 同上再套一层 setsid 双重脱离，PPID=1、cgroup 与 PGID 均独立   -> 仍必死
#   (c) 在 service 启动【之前】就已存在的独立 watchdog                -> 存活
# 原因是 init 终止 oneshot service 时按「本次启动的派生树」清理，而 (a)(b) 的
# watchdog 都由该次 service 派生；setsid 只换进程组与会话，换不掉这层派生关系。
# 只要 watchdog 不是这次 service 派生的，就不在清理名单内。
#
# 【因此本脚本的约定】
# 启动顺序固定为「watchdog 先、主进程后」：主进程入口先确保 watchdog 已存在
# （watchdog_alive 判定为否才 fork），再继续向下执行主流程。deploy.sh 亦遵循
# 同一顺序 —— 先用 setsid 预置一个独立 watchdog，再拉起主进程。
# watchdog 每 5s 校验一次主进程存活，连续 3 次（15s 宽限期，覆盖互斥抢占与正常
# 重启的短暂空窗）确认死亡后，重新拉起【当前这份脚本】。
#
# 【为什么不用 setprop ctl.start 重启】
# init 的 meego_daemon.rc 指向 /system/bin/meego_daemon.sh，而 /system 受
# dm-verity 保护无法更新 —— 那里永远停留在首次刷入时的旧版本。实测 ctl.start
# 拉起的正是那份旧脚本（既不写心跳也没有本版修复），自愈一次就等于回退一次。
# 故统一改为直接拉起当前正在运行的这份脚本。
DAEMON_SCRIPT="$0"
case "$DAEMON_SCRIPT" in
    /*) ;;
    *) DAEMON_SCRIPT="/data/local/tmp/meego_daemon.sh" ;;
esac

# 读取某进程 cmdline 的第 N 段（1-based）。cmdline 以 NUL 分隔，tr 成行后
# 第 1 行=argv0(/system/bin/sh)、第 2 行=脚本路径、第 3 行=__WATCHDOG（若有）。
# 注意 __WATCHDOG 在第 3 行而非第 2 行 —— 误用 2p 会取到脚本路径，判定恒不
# 成立，进而让互斥逻辑把 watchdog 当普通进程杀掉。
cmdline_field() {
    tr '\0' '\n' < "/proc/$1/cmdline" 2>/dev/null | sed -n "${2}p"
}

# 是否存在【与本进程同源】的 watchdog。
#
# 刻意要求脚本路径一致，而不是「存在任意 watchdog 即可」：/system/bin 下可能
# 残留着早先部署写入的旧副本（/system 受 dm-verity 保护，无法更新或删除），
# 开机时 init 会按 .rc 启动那份旧脚本，于是它也会 fork 出指向自己的 watchdog。
# 若这里只按 __WATCHDOG 标记判定，就会误以为「watchdog 已存在」而不启动自己
# 的，紧接着单实例逻辑又把旧 watchdog 清掉 —— 结果是两个都没了。旧副本的
# watchdog 还会把旧版主进程拉回来，使修复整体回退。
watchdog_alive() {
    for wp in $(pgrep -f "meego_daemon.sh" 2>/dev/null || true); do
        [ "$(cmdline_field "$wp" 3)" = "__WATCHDOG" ] || continue
        [ "$(cmdline_field "$wp" 2)" = "$DAEMON_SCRIPT" ] && return 0
    done
    return 1
}

if [ "$1" != "__WATCHDOG" ]; then
    # 预置 watchdog：确保它独立于本次 service 启动而存在（见上文情形 (c)）。
    # 双重 setsid 不可省：内层 setsid 让 watchdog 自成会话，外层让中间 sh 立刻
    # 退出，从而 watchdog 被 init 收养为 PPID=1。
    if ! watchdog_alive; then
        setsid /system/bin/sh -c "setsid /system/bin/sh \"$DAEMON_SCRIPT\" __WATCHDOG >/dev/null 2>&1" >/dev/null 2>&1 &
    fi
    # 注意：此处【不能】exit 0。init 的 service 是 oneshot，入口一退出 init 就把
    # service 标记为 stopped，主进程从未真正跑起来，而 watchdog 又只能靠 ctl.start
    # 重启 —— ctl.start 重跑入口时 watchdog 已存在、同样只是空转，于是形成死结。
    # 因此入口必须继续向下执行，把本次启动真正变成「主进程」。
fi

# ---------------------------------------------------------------------------
# 0.1 Watchdog 主循环：主进程死掉就把当前这份脚本重新拉起来
# ---------------------------------------------------------------------------
if [ "$1" = "__WATCHDOG" ]; then
    MISS_COUNT=0
    while true; do
        sleep 5
        # 心跳：把自身 pid 与当前时间写入 App 可读的状态文件。
        # App 侧读不到 /proc（本机 /proc 挂载了 hidepid=2，普通 uid 看不到
        # 其他进程的 cmdline），故「watchdog 是否健在」只能由它自己申报。
        # 文件权限 666 是必须的 —— App 进程 u0_a209 需要能读。
        # 心跳必须落在 App 私有目录：/data/local/tmp 属 shell_data_file 域，
        # 即便权限 666，App（untrusted_app）仍会被 SELinux 拒绝读取（真机实测
        # avc: denied { read } ... tclass=lnk_file）。App 私有目录带
        # app_data_file 标签，是 App 唯一能稳定读到的 root 可写位置。
        mkdir -p "$APP_CMD_DIR" 2>/dev/null || true
        echo "$$ $(date +%s)" > "$APP_CMD_DIR/watchdog.hb" 2>/dev/null || true
        chmod 666 "$APP_CMD_DIR/watchdog.hb" 2>/dev/null || true
        chown "$(stat -c '%u:%g' "$APP_DIR" 2>/dev/null)" "$APP_CMD_DIR/watchdog.hb" 2>/dev/null || true

        MAIN_PID=$(cat /data/local/tmp/meego_daemon.pid 2>/dev/null | tr -d '\r\n ')
        if [ -n "$MAIN_PID" ] && [ -d "/proc/$MAIN_PID" ]; then
            MISS_COUNT=0
        else
            MISS_COUNT=$((MISS_COUNT + 1))
            if [ "$MISS_COUNT" -ge 3 ]; then
                MISS_COUNT=0
                # 注意：这里【不能】用 setprop ctl.start。
                # init 的 meego_daemon.rc 指向 /system/bin/meego_daemon.sh，
                # 而 /system 受 dm-verity 保护不可更新 —— 那里永远是首次刷入时的
                # 旧版本。经实测，ctl.start 拉起的正是 /system 旧版，它既不写
                # .alive 心跳，也缺少后续修复，一旦被拉起就等于回退。
                # 故直接拉起当前这份脚本（/data/local/tmp，由 deploy.sh 推送）。
                # 走 nohup + setsid：新主进程立刻成为 PPID=1 的独立进程，
                # 而本 watchdog 早已脱离该派生树，两者互不牵连。
                setsid /system/bin/sh -c "nohup \"$DAEMON_SCRIPT\" >/data/local/tmp/daemon.log 2>&1" >/dev/null 2>&1 &
            fi
        fi
    done
fi

# 1. 单实例保障：终止历史旧守护进程，严防竞争冲突
# 旧逻辑用 pgrep -f "meego_daemon.sh" 无差别匹配，把 watchdog 一起 kill -9 了
# —— 那正是「手动拉起一次之后无人监管」的直接成因。
# 现规则：
#   · 与本进程同源的 watchdog  -> 保留（它的职责正是把主进程拉回来）
#   · 其余一切同脚本名进程    -> 清除（含旧副本的 watchdog）
# 清除旧副本 watchdog 是本版收敛 stale /system 副本的关键：既然 /system 不可写，
# 就改为在运行期把它派生出来的守护链一并接管，使全局最终只剩当前这份脚本。
for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null || true); do
    if [ "$p" != "$$" ]; then
        if [ "$(cmdline_field "$p" 3)" = "__WATCHDOG" ] && \
           [ "$(cmdline_field "$p" 2)" = "$DAEMON_SCRIPT" ]; then
            continue
        fi
        kill -9 "$p" 2>/dev/null || true
    fi
done

# 业务主进程 pid 落盘，供 watchdog 存活检测
echo $$ > /data/local/tmp/meego_daemon.pid 2>/dev/null || true
chmod 666 /data/local/tmp/meego_daemon.pid 2>/dev/null || true

# ---------------------------------------------------------------------------
# 1.1 App 私有目录自愈
# ---------------------------------------------------------------------------
# App 被重装/清数据后 /data/user/0/<pkg>/files 会被删除，而本脚本的
# mkdir -p 在父目录缺失时会一并失败（此前被 `2>/dev/null || true` 静默吞掉，
# 表现为日志里连篇的 "can't create ... No such file or directory"，且命令队列
# 永远收不到指令 —— 此时删除/一键清理只清 UI 不杀进程）。
# 这里逐级创建并显式校准属主，确保 App 侧能读 cmd_queue、读写快照。
ensure_app_dirs() {
    if [ ! -d "$APP_DIR" ]; then
        # 父目录被删时无法凭 root 直接 mkdir 出带正确 SELinux 标签与属主的
        # 私有目录（label 缺失会导致 App 侧一律 Permission denied）。此时
        # 只能退化为「不建目录、仅记录」，等 App 下次启动自建后再由
        # 主循环 ensure_app_dirs 补齐 —— 见主循环中的兜底调用。
        return 1
    fi
    mkdir -p "$APP_CMD_DIR" "$APP_SNAP_DIR" 2>/dev/null || true
    chmod 777 "$APP_CMD_DIR" "$APP_SNAP_DIR" 2>/dev/null || true
    # 属主对齐 App uid（u0_a209 / uid 10209），否则 root 写入的文件 App 读不到
    APP_UID=$(stat -c '%u' "$APP_DIR" 2>/dev/null)
    if [ -n "$APP_UID" ] && [ "$APP_UID" != "0" ]; then
        chown -R "$APP_UID:$APP_UID" "$APP_CMD_DIR" "$APP_SNAP_DIR" 2>/dev/null || true
    fi
    return 0
}
APP_DIRS_OK=0
ensure_app_dirs && APP_DIRS_OK=1

# 写主进程存活心跳，供 App 侧读取（见 DaemonWatchdogClient 说明）。
# 落在 App 私有目录而非 /data/local/tmp：后者 SELinux 标签为 shell_data_file，
# 即便权限 666，App（untrusted_app）仍会被拒绝读取（真机实测 avc denied）。
# 必须放在 ensure_app_dirs 之后 —— 目录不存在时写入会静默失败。
write_daemon_heartbeat() {
    echo "$$ $(date +%s)" > "$APP_CMD_DIR/daemon.hb" 2>/dev/null || true
    chmod 666 "$APP_CMD_DIR/daemon.hb" 2>/dev/null || true
    chown "$(stat -c '%u:%g' "$APP_DIR" 2>/dev/null)" "$APP_CMD_DIR/daemon.hb" 2>/dev/null || true
}
write_daemon_heartbeat

mkdir -p /data/local/tmp
if [ ! -p "$FIFO" ]; then
    rm -f "$FIFO"
    mkfifo "$FIFO"
fi
chmod 777 "$FIFO" 2>/dev/null || true

# 2. 确保无障碍服务与全屏边缘侧滑所需系统配置始终生效
ensure_system_settings() {
    # 彻底消除 Android 系统原生侧滑返回冲突 (设为0屏蔽系统返回手势)
    settings put secure back_gesture_inset_scale_left 0 2>/dev/null || true
    settings put secure back_gesture_inset_scale_right 0 2>/dev/null || true
    # 解除 Android 系统对 APPLICATION_OVERLAY 的 0.8 强制透明度限制，确保全屏 100% 纯黑不透明遮罩
    settings put global maximum_obscuring_opacity_for_touch 1.0 2>/dev/null || true
    device_config put window_manager maximum_obscuring_opacity_for_touch 1.0 2>/dev/null || true

    # 启用 EdgeSwipeGestureService 无障碍服务通道，解锁 TYPE_ACCESSIBILITY_OVERLAY 权限以穿透系统设置页
    enabled=$(settings get secure enabled_accessibility_services 2>/dev/null)
    case "$enabled" in
        *com.meego.launcher/com.meego.launcher.service.EdgeSwipeGestureService*) ;;
        *)
            if [ -z "$enabled" ] || [ "$enabled" = "null" ]; then
                new_services="com.meego.launcher/com.meego.launcher.service.EdgeSwipeGestureService"
            else
                new_services="${enabled}:com.meego.launcher/com.meego.launcher.service.EdgeSwipeGestureService"
            fi
            settings put secure enabled_accessibility_services "$new_services" 2>/dev/null || true
            settings put secure accessibility_enabled 1 2>/dev/null || true
            ;;
    esac
}

ensure_system_settings

# 3. 建立快照软链接并保持权限
#
# 【v1.8.40：链接目标由 $SNAP_DIR 改为 $SNAP_USER_DIR】
# 此前指向父目录，于是 /data/local/tmp/snapshots/689_reduced.jpg 这类路径
# 实际不存在（真机实测：No such file or directory）—— 与 UUID 子目录 Bug
# 同源，两处一并修正。
#
# 【但由此暴露一个更隐蔽的污染，本版一并堵死】
# 链接一旦指对，capture_current_screen 里那两处
#     cp "$TMP_PNG" "/data/local/tmp/snapshots/${tid}_live.png"
# 就会**穿过软链写进系统快照目录**。真机实测后果：
#     /data/system_ce/0/snapshots/8930c050-.../691_live.png   ← root:root, 我们的垃圾
# 即把自采的整屏帧混进了 AOSP 自己的快照目录。那里应由 TaskSnapshotController
# 独占管理，混入外来文件会污染其 0700 目录语义，也会让
# sync_from_system_snapshot 的 `*.jpg` glob 与清理逻辑行为变得不可预期。
#
# 解法：**去掉软链**，改为把自采帧只写进 $APP_SNAP_DIR（App 私有目录）。
# 本项目所有 App 侧候选路径本来就优先读 filesDir/snapshots/（见
# RootTaskProvider 的 candidateFiles 顺序），软链只是历史兼容残留，
# 移除它零功能损失，却彻底断掉这条写入系统分区的意外路径。
if [ -L "$LINK_DIR" ] || [ -e "$LINK_DIR" ]; then
    rm -rf "$LINK_DIR"
fi
if [ -d "$SNAP_USER_DIR" ]; then
    chmod 755 /data/system_ce /data/system_ce/0 2>/dev/null || true
    chmod 755 "$SNAP_DIR" 2>/dev/null || true
    chmod 755 "$SNAP_USER_DIR" 2>/dev/null || true
    chmod 644 "$SNAP_USER_DIR"/* 2>/dev/null || true
fi

# ===========================================================================
# 4. 常驻 Task Snapshot 服务：启动、IPC 与取帧（v1.8.44 重写）
# ===========================================================================
#
# 【本版为何删除全部 screencap 采集】
# 旧实现用 `screencap -p` 抓**整块物理屏幕**，带来两个硬伤：
#   (a) 像素污染 —— 每张卡片都混进状态栏 / 导航栏 / 挖孔留白
#       （本机实测 contentInsets=[0,105][0,66]）；
#   (b) 性能 —— 单次 0.65s 闪存写，且必须靠 sleep 轮询猜「何时该抓」。
# 而 Quickstep 的做法是向 WMS 索取**只含该任务窗口**的 TaskSnapshot，
# 本机实测其像素与实时屏幕在应用区域**逐像素完全一致**（见 TECH_SPEC §4.14）。
# 既然常驻服务已能把单帧压到 11~42ms，screencap 已无任何存在理由。
#
# 【产品行为变更（经确认）】
# 失败即降级为**应用图标**，不再回退整屏 screencap 帧。
# 代价：应用刚冷启动、系统尚未落盘任何快照的极短窗口内，卡片可能短暂显示图标；
# 收益：画面永远不含系统栏杂质，且不会把整屏帧误当任务帧展示。

# --- 4.1 服务存活判定 -------------------------------------------------------
svc_alive() {
    [ -f "$SVC_PID" ] || return 1
    sp=$(cat "$SVC_PID" 2>/dev/null | tr -d '\r\n ')
    [ -n "$sp" ] && [ -d "/proc/$sp" ]
}

# --- 4.1b 清理孤儿实例 ------------------------------------------------------
# 【本函数解决一个已实测复现的严重缺陷】
# `pgrep -f "app_process / TaskSnapService" | head -1` 取到的是**最老**的匹配项。
# 若历史上手工/异常启动留下过一个 PPID=1 的孤儿实例，它会一直排在最前，于是：
#   · 新实例拉起后，pidfile 里写下的仍是那个**早已存在的旧 PID**；
#   · 随后 svc_alive 依据旧 PID 判定为「存活」，pidfile 永不被纠正；
#   · 结果是两个服务并存、pidfile 与真实实例脱节，故障时无从判断谁活着。
#
# 拉起新实例**之前**先清掉所有旧实例即可根治：它们无需保留任何状态
# （TaskSnapshotManager 只是 system_server 侧的一个代理，取帧状态本就在
#  系统缓存里，服务自身无状态）。
kill_orphan_svc() {
    ko_pids=$(pgrep -f "app_process / TaskSnapService" 2>/dev/null || true)
    [ -n "$ko_pids" ] || return 0
    for ko in $ko_pids; do
        kill -9 "$ko" 2>/dev/null || true
    done
    # 给 system_server 一点时间回收 binder 端点
    sleep 0.2
}

# --- 4.2 拉起常驻服务 -------------------------------------------------------
# 【FIFO EOF 陷阱，已实测踩坑】
# 朴素写法 `app_process ... < fifo` 会在「当前没有写端」时立刻收到 EOF 而退出。
# 正确做法：在服务进程内部用 `exec 9<>fifo` 以**读写方式**打开同一 FIFO，
# 写端由此常驻永不关闭，服务则可持续存活等待指令。
start_svc() {
    svc_alive && return 0
    [ -f "$SVC_DEX" ] || return 1

    # 关键：先清孤儿，保证随后 pgrep 取到的第一个就是**本次新建**的实例，
    # pidfile 因此始终与真实存活实例一一对应（见 kill_orphan_svc 注释）。
    kill_orphan_svc

    mkdir -p "$APP_CMD_DIR" 2>/dev/null || true
    rm -f "$SVC_FIFO" 2>/dev/null || true
    mkfifo "$SVC_FIFO" 2>/dev/null || true
    chmod 666 "$SVC_FIFO" 2>/dev/null || true
    : > /dev/null 2>&1
    rm -f "$SVC_OUT" 2>/dev/null || true

    setsid /system/bin/sh -c "
        exec 9<>'$SVC_FIFO'
        CLASSPATH='$SVC_DEX' exec app_process / TaskSnapService <&9 > '$SVC_OUT' 2>&1
    " </dev/null >/dev/null 2>&1 &

    # 等待服务就绪：以 SVC_OUT 出现 ready 为准，最多等 3s
    n=0
    while [ "$n" -lt 30 ]; do
        if [ -f "$SVC_OUT" ] && grep -q '"ready":true' "$SVC_OUT" 2>/dev/null; then
            sp=$(pgrep -f "app_process / TaskSnapService" 2>/dev/null | head -1)
            if [ -n "$sp" ]; then
                echo "$sp" > "$SVC_PID" 2>/dev/null || true
                chmod 666 "$SVC_PID" 2>/dev/null || true
                return 0
            fi
        fi
        sleep 0.1
        n=$((n + 1))
    done
    return 1
}

# --- 4.3 向服务发一条指令并取回最后一行响应 ---------------------------------
svc_cmd() {
    svc_alive || return 1
    # 记录当前响应行数，以便只取「本次新增」的那一行
    before=$(wc -l < "$SVC_OUT" 2>/dev/null || echo 0)
    echo "$1" > "$SVC_FIFO" 2>/dev/null || return 1

    n=0
    while [ "$n" -lt 40 ]; do
        after=$(wc -l < "$SVC_OUT" 2>/dev/null || echo 0)
        if [ "$after" -gt "$before" ]; then
            tail -n 1 "$SVC_OUT" 2>/dev/null
            return 0
        fi
        sleep 0.05
        n=$((n + 1))
    done
    return 1
}

svc_ping() {
    svc_cmd '{"cmd":"ping"}'
}

# --- 4.4 取一帧并落盘 -------------------------------------------------------
# live=1 → 优先 takeTaskSnapshot（仅对**可见**任务有效，取实时画面）
# live=0 → 优先 getTaskSnapshot（系统缓存帧，后台任务用它）
# 两者互为兜底：任一路径拿不到就试另一条，因此调用方不必关心任务可见性。
#
# 产物固定写 $APP_SNAP_DIR/${tid}_sys.jpg —— 与 App 侧既有候选列表首位同名，
# 故 App 侧**零改动**即可享受常驻服务产出的高帧率、纯净像素帧。
capture_task_snapshot() {
    cs_tid="$1"
    cs_live="${2:-0}"
    [ -z "$cs_tid" ] && return 1
    svc_alive || return 1

    mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
    cs_out="$APP_SNAP_DIR/${cs_tid}_sys.jpg"

    # 手势进行中不取：此刻系统仍在为该任务抓快照，take 会拿到过渡态画面
    [ -f /data/local/tmp/gesture_active ] && return 1

    cs_json=$(svc_cmd "{\"cmd\":\"write\",\"tid\":$cs_tid,\"out\":\"$cs_out\",\"live\":$cs_live}")

    case "$cs_json" in
        *'"ok":true'*)
            chown "$(stat -c '%u:%g' "$APP_DIR" 2>/dev/null)" "$cs_out" 2>/dev/null || true
            chmod 666 "$cs_out" 2>/dev/null || true
            echo "$$ $(date +%s)" > "$SVC_HB" 2>/dev/null || true
            chmod 666 "$SVC_HB" 2>/dev/null || true
            chown "$(stat -c '%u:%g' "$APP_DIR" 2>/dev/null)" "$SVC_HB" 2>/dev/null || true
            return 0
            ;;
        *)
            # 失败不留残file，避免 App 把半张图当成有效帧
            rm -f "$cs_out" 2>/dev/null || true
            return 1
            ;;
    esac
}

# --- 4.5 系统快照目录权限维持 -----------------------------------------------
# AOSP TaskSnapshotController 的目录为 system:system 0700；本脚本以 root 读取
# 需保证可遍历。此处只做 chmod，**不**再从中拷贝文件（取帧已全部走服务）。
maintain_system_snapshot_perms() {
    chmod 755 /data/system_ce /data/system_ce/0 2>/dev/null || true
    chmod 755 "$SNAP_DIR" 2>/dev/null || true
    if [ -d "$SNAP_USER_DIR" ]; then
        chmod 755 "$SNAP_USER_DIR" 2>/dev/null || true
    fi
}

# --- 4.6 前台任务实时取帧循环 -----------------------------------------------
# 【为什么可以放心高频轮询】
# 常驻服务下单帧 11~42ms，且没有 fork / dumpsys / 闪存整屏写，
# 因此即使 0.3s 一次也远达不到需要节流的程度。
# 【与旧 STEADY_CAPTURE_EVERY 的关系】
# 旧节奏 5 次轮询 ≈1.8s 等待 + 0.65s 抓帧 ≈ 2.4s 帧龄 —— 那是为 0.65s 的
# screencap 妥协的结果；换成常驻服务后该限制已消失。
take_foreground_loop() {
    tf_last=""
    while true; do
        tf_tid=$(cat /data/local/tmp/current_task_id.txt 2>/dev/null | tr -d '\r\n ')
        if [ -n "$tf_tid" ] && [ "$tf_tid" != "$tf_last" ]; then
            # 任务刚切到前台：立刻取一帧，避免首帧缺失
            capture_task_snapshot "$tf_tid" 1
            tf_last="$tf_tid"
            tf_t=0
        fi
        if [ -n "$tf_tid" ] && [ "$tf_tid" = "$tf_last" ]; then
            tf_t=$((tf_t + 1))
            if [ "$tf_t" -ge 2 ]; then
                tf_t=0
                # live=1：对可见任务取实时帧
                capture_task_snapshot "$tf_tid" 1
            fi
        fi
        sleep "$TAKE_INTERVAL"
    done
}

# 5. 刷新任务列表为 JSON (防并发与原子落盘)
update_tasks() {
    # 【v1.8.44】不再逐文件 chmod 整个系统快照目录。
    # 旧实现每 2s 就对 $SNAP_USER_DIR 下**所有**文件 chmod 644，在任务多时是
    # 可观的无谓元数据写；且自本版起我们根本不再读取该目录（取帧走常驻服务），
    # 只需保证目录可遍历即可 —— 由 maintain_system_snapshot_perms 负责。
    TMP_FILE="/data/local/tmp/recent_tasks_${MY_PID}_${RANDOM}.tmp"
    echo "[" > "$TMP_FILE"

    first=1
    dumpsys activity recents 2>/dev/null | awk '
        /\* Recent #[0-9]+: Task\{/ {
            for (i=1; i<=NF; i++) {
                if ($i ~ /^#[0-9]+/) {
                    cur_id = substr($i, 2);
                }
                if ($i ~ /^type=/) {
                    cur_type = substr($i, 6);
                }
            }
        }
        /mActivityComponent=/ {
            if (cur_id != "" && cur_type == "standard") {
                split($0, parts, "mActivityComponent=");
                split(parts[2], comp, "/");
                pkg = comp[1];
                gsub(/[^a-zA-Z0-9._]/, "", pkg);
                if (pkg != "" && pkg !~ /com.meego.launcher/ && pkg !~ /com.android.launcher3/ && pkg !~ /FallbackHome/) {
                    print cur_id ":" pkg;
                }
                cur_id = "";
            }
        }
    ' | while IFS=":" read -r tid tpkg; do
        [ -z "$tid" ] && continue

        # 【v1.8.44】系统快照目录不再被拷贝。
        # 取帧已全部由常驻 Task Snapshot 服务完成，产物固定为
        # $APP_SNAP_DIR/${tid}_sys.jpg。此前在此 cp 系统的 .jpg / _reduced.jpg
        # 会带来两个问题：
        #   (a) 它们是 AOSP TaskSnapshotController 的落盘产物，可能滞后于
        #       「任务刚变不可见」的时刻，而服务取的正是那一刻的内存帧；
        #   (b) 无谓的闪存拷贝（每个任务每次 update_tasks 都跑一遍）。
        # 唯一画面来源即 _sys.jpg，App 侧候选列表亦已收敛到它。
        mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
        snap_path="$APP_SNAP_DIR/${tid}_sys.jpg"
        has_snap="false"
        if [ -f "$snap_path" ]; then
            has_snap="true"
        fi

        if [ "$first" -eq 0 ]; then
            echo "," >> "$TMP_FILE"
        fi
        first=0
        echo "  {\"taskId\": $tid, \"packageName\": \"$tpkg\", \"snapshotPath\": \"$snap_path\", \"hasSnapshot\": $has_snap}" >> "$TMP_FILE"
    done

    echo "]" >> "$TMP_FILE"

    # 若抓取到了真实任务，或目标文件尚不存在，则原子替换落盘并赋予所有用户读写权限
    if grep -q "taskId" "$TMP_FILE" 2>/dev/null || [ ! -f "$TASKS_JSON" ]; then
        chmod 666 "$TMP_FILE" 2>/dev/null || true
        cp -f "$TMP_FILE" "$APP_TASKS_JSON" 2>/dev/null || true
        chmod 666 "$APP_TASKS_JSON" 2>/dev/null || true
        mv -f "$TMP_FILE" "$TASKS_JSON" 2>/dev/null || true
        chmod 666 "$TASKS_JSON" 2>/dev/null || true
    else
        # 避免在早期开机阶段将已有历史任务误擦除为 []
        rm -f "$TMP_FILE" 2>/dev/null || true
    fi
}

# 6. 执行核心指令处理（彻底销毁任务栈与强杀进程）
execute_command() {
    line="$1"
    [ -z "$line" ] && return
    cmd=$(echo "$line" | awk '{print $1}')
    arg=$(echo "$line" | awk '{print $2}')
    pkg=$(echo "$line" | awk '{print $3}')

    case "$cmd" in
        ENSURE_DAEMON)
            # App 侧辅助唤醒：启动器进程发现主进程已消失时投递本指令，
            # 用来跳过 watchdog 剩余的宽限期等待、立即把主进程拉回来。
            # 注意本分支由 monitor_cmd_queue 在【主进程】内执行 —— 若主进程
            # 真已死亡则它跑不到这里；实际承担补救的是 watchdog。本分支真正
            # 生效的场景是主进程尚存但 pid 文件曾被清掉（心跳过期而进程健在），
            # 此时就地重新拉起一份即可。
            MAIN_PID=$(cat /data/local/tmp/meego_daemon.pid 2>/dev/null | tr -d '\r\n ')
            if [ -z "$MAIN_PID" ] || [ ! -d "/proc/$MAIN_PID" ]; then
                # 同上：拉当前脚本而非 init service（后者指向不可更新的 /system 旧版）
                setsid /system/bin/sh -c "nohup \"$DAEMON_SCRIPT\" >/data/local/tmp/daemon.log 2>&1" >/dev/null 2>&1 &
            fi
            ;;
        SNAPSHOT)
            # 显式按需导出某任务的真实快照（仅任务窗口，无系统栏污染）。
            # App 侧目前不主动发这条指令（手势期按需抓帧已被 §4.11.4 否证），
            # 保留它是为了：1) 部署后自检；2) 将来「多任务屏打开时补齐全部卡片」
            # 这类**非手势期**的按需补采。
            if [ -n "$arg" ]; then
                capture_task_snapshot "$arg"
            fi
            ;;
        REMOVE)
            if [ -n "$arg" ]; then
                tid="$arg"
                # 1. 彻底从系统 Activity 任务栈中移除
                am stack remove "$tid" 2>/dev/null || true
                # 2. 清理系统快照及缓存
                # 【v1.8.40】路径改指 $SNAP_USER_DIR：此前指 $SNAP_DIR（父目录），
                # 在 Android 16 上等于删了个空目录，系统快照会永久残留并被
                # sync_from_system_snapshot 反复搬回。
                rm -f "$SNAP_USER_DIR/${tid}"* 2>/dev/null || true
                rm -f "$APP_SNAP_DIR/${tid}"* 2>/dev/null || true
                # 3. 彻底杀死该包名的全部后台进程
                if [ -n "$pkg" ] && [ "$pkg" != "com.meego.launcher" ] && [ "$pkg" != "com.android.launcher3" ]; then
                    am force-stop "$pkg" 2>/dev/null || true
                    for p in $(pgrep -f "$pkg" 2>/dev/null || true); do
                        if [ "$p" != "$$" ]; then
                            kill -9 "$p" 2>/dev/null || true
                        fi
                    done
                fi
            fi
            ;;
        CLEAR_ALL)
            dumpsys activity recents 2>/dev/null | awk '
                /\* Recent #[0-9]+: Task\{/ {
                    for (i=1; i<=NF; i++) {
                        if ($i ~ /^#[0-9]+/) cur_id = substr($i, 2);
                        if ($i ~ /^type=/) cur_type = substr($i, 6);
                    }
                }
                /mActivityComponent=/ {
                    if (cur_id != "" && cur_type == "standard") {
                        split($0, parts, "mActivityComponent=");
                        split(parts[2], comp, "/");
                        pkg = comp[1];
                        gsub(/[^a-zA-Z0-9._]/, "", pkg);
                        if (pkg != "" && pkg !~ /com.meego.launcher/ && pkg !~ /com.android.launcher3/ && pkg !~ /FallbackHome/) {
                            print cur_id " " pkg;
                        }
                        cur_id = "";
                    }
                }
            ' | while read -r tid pkg; do
                if [ -n "$tid" ]; then
                    am stack remove "$tid" 2>/dev/null || true
                    # 【v1.8.40】同 REMOVE 分支：改指 $SNAP_USER_DIR
                    rm -f "$SNAP_USER_DIR/${tid}"* 2>/dev/null || true
                    rm -f "$APP_SNAP_DIR/${tid}"* 2>/dev/null || true
                fi
                if [ -n "$pkg" ] && [ "$pkg" != "com.meego.launcher" ] && [ "$pkg" != "com.android.launcher3" ]; then
                    am force-stop "$pkg" 2>/dev/null || true
                    for p in $(pgrep -f "$pkg" 2>/dev/null || true); do
                        if [ "$p" != "$$" ]; then
                            kill -9 "$p" 2>/dev/null || true
                        fi
                    done
                fi
            done
            echo "[]" > "$TASKS_JSON"
            chmod 666 "$TASKS_JSON" 2>/dev/null || true
            echo "[]" > "$APP_TASKS_JSON" 2>/dev/null || true
            chmod 666 "$APP_TASKS_JSON" 2>/dev/null || true
            ;;
        SWITCH)
            if [ -n "$arg" ]; then
                tid="$arg"
                echo "$tid" > /data/local/tmp/current_task_id.txt
                chmod 666 /data/local/tmp/current_task_id.txt
                rm -f /data/local/tmp/current_app_tid.txt /data/local/tmp/current_app.png
            fi
            ;;
        SNAP)
            # 显式抓帧：v1.8.44 起统一走常驻服务。
            # 无 tid 时对当前前台任务取一帧实时画面。
            snap_tid=$(cat /data/local/tmp/current_task_id.txt 2>/dev/null | tr -d '\r\n ')
            if [ -n "$snap_tid" ]; then
                capture_task_snapshot "$snap_tid" 1
            fi
            ;;
        ENABLE_A11Y)
            ensure_system_settings
            ;;
    esac
    update_tasks
}

# 7. 监听 App 私有目录命令队列 (cmd_queue)
monitor_cmd_queue() {
    mkdir -p "$APP_CMD_DIR" 2>/dev/null || true
    chmod 777 "$APP_CMD_DIR" 2>/dev/null || true

    while true; do
        for f in "$APP_CMD_DIR"/cmd_*.txt; do
            if [ -f "$f" ]; then
                line=$(cat "$f" 2>/dev/null)
                rm -f "$f" 2>/dev/null
                if [ -n "$line" ]; then
                    execute_command "$line"
                fi
            fi
        done
        sleep 0.1
    done
}

# 8. 处理传统 FIFO 管道的后台函数 (兼容旧通道)
process_fifo() {
    while true; do
        while read -r line; do
            [ -z "$line" ] && continue
            execute_command "$line"
        done < "$FIFO"
        sleep 0.2
    done
}

# 9. 监控前台当前运行的 App，并实时捕获其界面
LAST_RESUMED=""
monitor_current_app() {
    count=0
    a11y_check_count=0
    while true; do
        a11y_check_count=$((a11y_check_count + 1))
        if [ "$a11y_check_count" -ge 30 ]; then
            a11y_check_count=0
            ensure_system_settings
        fi

        resumed_info=$(dumpsys activity activities 2>/dev/null | awk '
            /ResumedActivity:/ {
                for (i=1; i<=NF; i++) {
                    if ($i ~ /^t[0-9]+/) tid=substr($i,2)
                    if ($i ~ /^[a-zA-Z0-9._]+\/[a-zA-Z0-9._]+/) act=$i
                }
                gsub(/[^0-9]/, "", tid)
                if (act != "" && act !~ /com.meego.launcher/) {
                    print tid ":" act
                    exit
                }
            }
        ')

        if [ -n "$resumed_info" ]; then
            tid=$(echo "$resumed_info" | cut -d: -f1)
            act=$(echo "$resumed_info" | cut -d: -f2)
            pkg=$(echo "$act" | cut -d/ -f1)

            if [ "$resumed_info" != "$LAST_RESUMED" ]; then
                # ===== 退到后台采集（v1.8.44：改为常驻服务取缓存帧）=====
                # 关键顺序：必须在覆写任何状态文件之前，先对"即将退到后台的旧应用"
                # 补抓其最终画面。LAST_RESUMED 此时仍持有旧应用的 tid/pkg。
                #
                # 【为何此处 live=0】旧任务此刻**已不可见**，takeTaskSnapshot 对
                # 不可见任务恒返回 null（实测），故必须走 getTaskSnapshot 取系统
                # 在「任务变不可见」那一刻抓下的缓存帧 —— 那正是该任务的最终态。
                if [ -n "$LAST_RESUMED" ]; then
                    old_tid=$(echo "$LAST_RESUMED" | cut -d: -f1)
                    old_pkg=$(echo "$LAST_RESUMED" | cut -d: -f2 | cut -d/ -f1)
                    if [ -n "$old_tid" ] && [ "$old_tid" != "$tid" ]; then
                        capture_task_snapshot "$old_tid" 0
                    fi
                fi

                LAST_RESUMED="$resumed_info"
                # 切换至新前台应用，立即失效全局旧快照，杜绝脏读
                rm -f /data/local/tmp/current_app_tid.txt /data/local/tmp/current_app.png /data/local/tmp/current_app_pkg.txt
                rm -f "$APP_DIR/current_app_tid.txt" "$APP_DIR/current_app.png" "$APP_DIR/current_app_pkg.txt" 2>/dev/null || true

                echo "$tid" > /data/local/tmp/current_task_id.txt
                chmod 666 /data/local/tmp/current_task_id.txt
                echo "$pkg" > /data/local/tmp/current_app_pkg.txt
                chmod 666 /data/local/tmp/current_app_pkg.txt

                echo "$tid" > "$APP_DIR/current_task_id.txt" 2>/dev/null || true
                chmod 666 "$APP_DIR/current_task_id.txt" 2>/dev/null || true
                echo "$pkg" > "$APP_DIR/current_app_pkg.txt" 2>/dev/null || true
                chmod 666 "$APP_DIR/current_app_pkg.txt" 2>/dev/null || true

                # ===== 进入前台：立即取一帧实时画面 =====
                # v1.8.44 起新任务**不再**有启动动画期空窗：常驻服务对可见任务
                # 的 takeTaskSnapshot 只需 11~42ms，足以在应用还在启动动画时
                # 就拿到当前这一帧。因此旧版的 provisional 标记机制与
                # start_settle_window 加密抓帧窗口**整体删除** ——
                # 取帧循环 take_foreground_loop 会持续以 TAKE_INTERVAL 刷新。
                capture_task_snapshot "$tid" 1
                count=0
                update_tasks
            else
                count=$((count + 1))
                # 稳态刷新由 take_foreground_loop 负责（独立子 shell，0.3s 一次），
                # 主循环此处不再做取帧，避免与它争抢同一 FIFO。
                :
            fi
        else
            # ===== Phase 2：无前台应用 = 用户滑出回到启动器（或应用全部退到后台） =====
            # 这正是"手势滑出"的主路径：此时 LAST_RESUMED 仍持有刚退到后台的应用，
            # 必须在清空状态之前补抓它的最终画面，否则多任务卡片将缺失最新一帧。
            if [ -n "$LAST_RESUMED" ]; then
                old_tid=$(echo "$LAST_RESUMED" | cut -d: -f1)
                old_pkg=$(echo "$LAST_RESUMED" | cut -d: -f2 | cut -d/ -f1)
                if [ -n "$old_tid" ]; then
                    # ===== 划出回桌面：这是本项目最关键的一条采集路径 =====
                    # 用户抱怨的「卡片画面是几秒前的旧画面」正出在这里。
                    # v1.8.44 起只需一次 getTaskSnapshot（实测 1ms）即可拿到
                    # 系统在「任务变不可见」那一刻抓下的最终帧，
                    # 不再需要 sleep 0.35 等遮罩淡出、不再需要 0.65s 的 screencap。
                    #
                    # 【为何这里不需要 gesture_active 等待】
                    # 旧实现等待 0.35s 是为了让 screencap 避开划出遮罩；而
                    # TaskSnapshot 取的是**该任务自身的窗口内容**，与当前屏幕上
                    # 叠加了什么遮罩完全无关 —— 实测在通知栏浮窗覆盖时仍能取到
                    # 底层任务的干净画面。故此处直接取，无需等待。
                    capture_task_snapshot "$old_tid" 0
                    update_tasks
                fi
                LAST_RESUMED=""
                rm -f /data/local/tmp/current_task_id.txt /data/local/tmp/current_app_tid.txt /data/local/tmp/current_app.png /data/local/tmp/current_app_pkg.txt
                rm -f "$APP_DIR/current_task_id.txt" "$APP_DIR/current_app_tid.txt" "$APP_DIR/current_app.png" "$APP_DIR/current_app_pkg.txt" 2>/dev/null || true
            fi
        fi
        sleep 0.15
    done
}

monitor_cmd_queue &
process_fifo &
monitor_current_app &

# ===========================================================================
# 10. 拉起常驻 Task Snapshot 服务并开始取帧（v1.8.44）
# ===========================================================================
#
# 【启动顺序：先服务，后取帧】
# take_foreground_loop 每 0.3s 就要发一条指令，若服务尚未就绪则会连环失败
# 并刷满 $SVC_OUT。故此处先确认服务可用，再放行取帧循环。
start_svc
if ! svc_alive; then
    echo "$(date '+%m-%d %H:%M:%S') TaskSnapService 未能启动，Task Snapshot 管线不可用" \
        >> /data/local/tmp/daemon.log 2>/dev/null || true
fi

# 前台实时取帧循环独立成进程：它必须以 0.3s 节奏稳定运行，
# 绝不能被主循环的 update_tasks / dumpsys 阻塞。
take_foreground_loop &

# 11. 首次立即刷新任务列表，随后定期维护
update_tasks

while true; do
    # App 私有目录自愈：启动时若目录尚不存在（App 未安装 / 清数据 / 重装），
    # 需在 App 下次启动自建目录之后补齐 cmd_queue 与 snapshots 及其属主。
    # 正常情况下紧接着的一次 mkdir 是廉价的 stat，不构成轮询开销。
    if [ "$APP_DIRS_OK" -eq 0 ] && [ -d "$APP_DIR" ]; then
        ensure_app_dirs && APP_DIRS_OK=1
    fi
    # 存活心跳：App 侧读不到 /proc（hidepid=2），只能靠这个带时间戳的文件
    # 判断主进程是否健在；它同时是 App 侧 DaemonWatchdogClient 的唯一判据。
    write_daemon_heartbeat

    # 常驻快照服务自愈：服务是本版画面来源，它死了就没有新帧。
    # 与主进程不同，它没有 watchdog 兜底，只能由本主循环每 2s 巡检一次。
    # 拉起动作本身耗时（等待 ready 最多 3s），故只在真的缺失时才做。
    if ! svc_alive; then
        start_svc && \
            echo "$(date '+%m-%d %H:%M:%S') TaskSnapService 已自愈重启" \
                >> /data/local/tmp/daemon.log 2>/dev/null
    fi

    update_tasks
    # 系统快照目录权限维持（取帧已全部走服务，这里只保证可遍历）
    maintain_system_snapshot_perms
    sleep 2.0
done
