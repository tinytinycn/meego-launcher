#!/system/bin/sh
# ==============================================================================
# MeeGo Launcher 特权守护脚本 (运行在 adbd/root 身份下)
# 职责：
# 1. 维护 /data/local/tmp/recent_tasks.json 及 App 私有目录任务数据
# 2. 监听 App 私有目录命令队列 (cmd_queue) 与 FIFO 管道，执行真实后台进程杀死 (am force-stop, pkill)
# 3. 监听前台应用切换并捕获无水印实时快照
# 4. 维护快照文件权限并同步至 App 私有目录
# 5. 自愈并确保全屏边缘划出手势与无障碍顶级浮层生效
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

APP_DIR="/data/user/0/com.meego.launcher/files"
APP_CMD_DIR="$APP_DIR/cmd_queue"
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

# 4. 捕获当前屏幕画面到 snapshots/${target_tid}_live.png 与 current_app.png
#    settled=0 -> 标记为 provisional（应用仍处于启动动画期，渲染层应降级为图标占位）
#    settled=1 -> 清除 provisional 标记（已确认是应用稳定后的画面）
capture_current_screen() {
    target_tid="$1"
    target_pkg="$2"
    settled="${3:-1}"

    # 若手势正在进行中，坚决不捕获，避免截取到划出过程中带有桌面背景的画面。
    #
    # 【v1.8.39 重要澄清】曾在此为「按需抓帧」开过 DEMAND 豁免，**已撤销**。
    # 实测证明该方案在物理上不可行：App 在 ACTION_DOWN 后约 30~50ms 就置位
    # gesture_active，而最快的 `screencap`（RAW，0.18s）也远超该窗口 —— 手势开始后
    # 才启动的抓帧，其像素**必然已含 Overlay 混合画面**，属于污染数据。
    # 叠加下方「截屏耗时期间若 gesture_active 存在即丢弃」的后置守卫，产物会被丢弃，
    # 白白浪费一次 0.65s 闪存写。结论：**画面的新鲜度只能靠手势开始前就已存在的帧
    # 来保证**（见 STEADY_CAPTURE_EVERY），不存在「边滑边补抓」的有效解。
    if [ -f /data/local/tmp/gesture_active ]; then
        return
    fi

    # 临时文件按 tid 隔离：加密抓帧窗口与主循环可能并发抓取，共用同一临时文件会互相覆盖
    if [ -n "$target_tid" ]; then
        TMP_PNG="/data/local/tmp/current_app_${target_tid}.tmp.png"
    else
        TMP_PNG="/data/local/tmp/current_app.tmp.png"
    fi

    screencap -p "$TMP_PNG" 2>/dev/null
    # 截屏耗时期间若手势被触发，立刻丢弃截屏
    if [ -f /data/local/tmp/gesture_active ]; then
        rm -f "$TMP_PNG"
        return
    fi

    if [ -s "$TMP_PNG" ]; then
        if [ -n "$target_tid" ]; then
            # 【v1.8.40】自采帧只写 App 私有目录。原先还额外往
            # /data/local/tmp/snapshots/ 写一份，而那个路径曾是通往系统快照目录的
            # 软链 —— 修正软链指向后，它会把我们的整屏帧写进 AOSP 独占管理的
            # 目录（真机实测产出过 691_live.png）。该副本本就无人可靠读取
            # （App 侧候选列表优先读 filesDir），删除零功能损失。
            mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
            cp -f "$TMP_PNG" "$APP_SNAP_DIR/${target_tid}_live.png" 2>/dev/null || true
            chown "$(stat -c '%u:%g' "$APP_DIR" 2>/dev/null)" "$APP_SNAP_DIR/${target_tid}_live.png" 2>/dev/null || true
            chmod 666 "$APP_SNAP_DIR/${target_tid}_live.png" 2>/dev/null || true

            # provisional 标记：只维护在 App 私有目录（App 只能可靠读取自身 filesDir）
            if [ "$settled" = "0" ]; then
                touch "$APP_SNAP_DIR/${target_tid}_live.provisional" 2>/dev/null || true
                chmod 666 "$APP_SNAP_DIR/${target_tid}_live.provisional" 2>/dev/null || true
            else
                rm -f "$APP_SNAP_DIR/${target_tid}_live.provisional" 2>/dev/null || true
            fi

            echo "$target_tid" > /data/local/tmp/current_app_tid.txt
            chmod 666 /data/local/tmp/current_app_tid.txt
            echo "$target_tid" > "$APP_DIR/current_app_tid.txt" 2>/dev/null || true
            chmod 666 "$APP_DIR/current_app_tid.txt" 2>/dev/null || true
        fi
        if [ -n "$target_pkg" ]; then
            echo "$target_pkg" > /data/local/tmp/current_app_pkg.txt
            chmod 666 /data/local/tmp/current_app_pkg.txt
            echo "$target_pkg" > "$APP_DIR/current_app_pkg.txt" 2>/dev/null || true
            chmod 666 "$APP_DIR/current_app_pkg.txt" 2>/dev/null || true
        fi
        if [ -z "$target_tid" ]; then
            mv -f "$TMP_PNG" /data/local/tmp/current_app.png
            chmod 666 /data/local/tmp/current_app.png
            cp -f /data/local/tmp/current_app.png "$APP_DIR/current_app.png" 2>/dev/null || true
            chmod 666 "$APP_DIR/current_app.png" 2>/dev/null || true
        else
            # taskId 已隔离，无需再写全局 current_app.png：
            # 该文件仅在无 tid 的 SNAP 指令路径下有语义，混写会污染全局所有权校验
            rm -f "$TMP_PNG"
        fi
    else
        rm -f "$TMP_PNG"
    fi
}

# 4.2 退到后台采集（对齐 AOSP 语义）
#     AOSP TaskSnapshotController 在任务"变不可见"那一刻抓取快照，本机实测证实：
#     应用刚进前台时 /data/system_ce/0/snapshots/ 下尚无其快照，切走后立刻出现。
#     本函数在检测到前台切换的瞬间，对**即将退到后台的旧应用**补抓一帧最终画面。
#
#     时序要点：检测到切换时旧应用刚失去前台、最后一帧仍完整可见，而屏幕已经开始
#     渲染新应用——因此这次 screencap 抓到的就是旧应用的最终态，直接标记为 settled。
#
#     注意：本函数**只写旧应用自己的 ${tid}_live.png**，绝不触碰任何全局所有权文件
#     （current_app_tid / current_app_pkg / current_app.png），否则会污染新应用的
#     所有权校验链条，制造跨应用串扰。
capture_backgrounding() {
    bg_tid="$1"
    bg_pkg="$2"
    [ -z "$bg_tid" ] && return

    # 若此刻正在手势划出，屏幕正被桌面遮罩覆盖，抓到的会是划出过程画面，直接放弃
    if [ -f /data/local/tmp/gesture_active ]; then
        return
    fi

    bg_tmp="/data/local/tmp/current_app_${bg_tid}.bg.png"
    screencap -p "$bg_tmp" 2>/dev/null
    if [ -f /data/local/tmp/gesture_active ]; then
        rm -f "$bg_tmp"
        return
    fi

    if [ -s "$bg_tmp" ]; then
        # 【v1.8.40】同 capture_current_screen：不再经软链写系统快照目录
        mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
        cp -f "$bg_tmp" "$APP_SNAP_DIR/${bg_tid}_live.png" 2>/dev/null || true
        chmod 666 "$APP_SNAP_DIR/${bg_tid}_live.png" 2>/dev/null || true

        # 已离开前台，画面确定是最终态：清除 provisional 标记
        rm -f "$APP_SNAP_DIR/${bg_tid}_live.provisional" 2>/dev/null || true
    fi
    rm -f "$bg_tmp"
}

# 4.4 provisional 标记兜底清理（TTL）
#     极端情况下 settle 子 shell 可能被杀死（守护进程重启、任务被删等），
#     导致 provisional 标记长期残留，使该任务永远降级为图标。
#     超过 TTL 的 provisional 视为失效，直接清除。
PROVISIONAL_TTL=15
expire_stale_provisionals() {
    now=$(date +%s 2>/dev/null || echo 0)
    [ "$now" = "0" ] && return
    for f in "$APP_SNAP_DIR"/*_live.provisional; do
        [ -f "$f" ] || continue
        m=$(stat -c %Y "$f" 2>/dev/null || echo 0)
        [ "$m" = "0" ] && continue
        if [ $(( now - m )) -gt "$PROVISIONAL_TTL" ]; then
            rm -f "$f" 2>/dev/null || true
        fi
    done
}

# 4.3 系统快照校正
#     周期性地用系统权威快照刷新陈旧的 live 帧。系统快照内容与 mtime 均已实测可靠
#     （实测某任务 _live.png 老化 3725s 而 _reduced.jpg 仅 0s），此处仅在系统快照
#     确实更新时同步，避免无谓 I/O。
#
# 【v1.8.40 两处修复】
#  (a) glob 由 "$SNAP_DIR"/*_reduced.jpg 改为 "$SNAP_USER_DIR"/*_reduced.jpg
#      —— 前者在 Android 16 上零命中（见 0.0 节），本函数此前形同虚设。
#  (b) 由「只搬 _reduced.jpg」扩展为**同时搬全分辨率 .jpg**。
#      原实现只同步 reduced（540x1200，实测 28~41KB），而全分辨率
#      （1080x2400，实测 70~198KB）才是多任务卡片真正该用的那一档 ——
#      reduced 只是丢失细节的降级产物。此前全分辨率帧从未被搬进 App 目录。
sync_from_system_snapshot() {
    [ -d "$SNAP_USER_DIR" ] || return
    for sf in "$SNAP_USER_DIR"/*.jpg; do
        [ -f "$sf" ] || continue
        b=$(basename "$sf")
        case "$b" in
            *_reduced.jpg) s_tid="${b%_reduced.jpg}" ;;
            *.jpg)         s_tid="${b%.jpg}" ;;
            *) continue ;;
        esac
        [ -z "$s_tid" ] && continue
        mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
        dest="$APP_SNAP_DIR/$b"
        # 仅当 App 私有目录缺失或比系统快照更旧时才同步
        if [ ! -f "$dest" ] || [ "$sf" -nt "$dest" ]; then
            cp -f "$sf" "$dest" 2>/dev/null || true
            # 属主对齐 App uid，否则 App（untrusted_app）读不到 root 拷贝的文件。
            # 沿用 ensure_app_dirs 的做法：目录属主即 App uid。
            chown "$(stat -c '%u:%g' "$APP_DIR" 2>/dev/null)" "$dest" 2>/dev/null || true
            chmod 666 "$dest" 2>/dev/null || true
        fi
    done
}

# 4.5 真实任务快照直读（v1.8.40 新增，Task Snapshot 管线核心）
#
# 【与 screencap 的本质区别 —— 这是本函数存在的全部理由】
# capture_current_screen 走 `screencap -p`，抓的是**整块物理屏幕**，于是
# 每一张多任务卡片里都混进了状态栏、导航栏、挖孔留白（实测本机 contentInsets
# 为 [0,105][0,66]，即顶部 105px 挖孔 + 底部 66px 导航栏），在纯黑 MeeGo
# 卡片上一眼就能看出「这不是这个应用的画面」。
#
# AOSP 为每个任务维护了一份**只含该任务窗口**的真实快照，由
# TaskSnapshotController 在任务「变不可见」那一刻抓取。本函数通过
# TaskSnapBridge 走 IActivityTaskManager.getTaskSnapshotManager() 把那份
# 快照的真实像素取出，绕过整屏污染。
#
# 【为何必须在 root 侧（实测结论，勿删本注释）】
# getTaskSnapshotManager 权限为 allOf={MANAGE_ACTIVITY_TASKS,
# READ_FRAME_BUFFER}，二者皆 signature 级。实测：
#   uid=10209（untrusted_app）→ SecurityException: Access denied
#   uid=0 / adb shell          → 通过
#
# 【性能】实测 275ms（binder 9ms + wrap 11ms + PNG 254ms），
# 对比 screencap -p 的 ≈650ms，且像素只含任务窗口。
#
# 【与 §4.11.4「按需抓帧不可行」的关系】
# 该否证结论依然成立：手势开始后 30~50ms 内无任何手段能产出干净像素。
# 本函数改善的是**帧龄**与**像素纯净度**，不是手势期补抓，因此
# 不接受 SNAPSHOT 指令的「手势期突发」用法。
#
# 产物：$APP_SNAP_DIR/${tid}_sys.jpg（仅任务窗口，1080x2400）
# 失败：不留残留文件，返回非 0，由调用方决定是否回退到 screencap。
SNAPSHOT_RESOLUTION=1
capture_task_snapshot() {
    st_tid="$1"
    [ -z "$st_tid" ] && return 1
    [ -f "$BRIDGE_DEX" ] || return 1

    # 手势进行中不取：此刻任务仍可见，系统尚未为其抓快照，
    # 强行调用只会得到 null，白白浪费一次 app_process 冷启动。
    [ -f /data/local/tmp/gesture_active ] && return 1

    mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
    st_out="$APP_SNAP_DIR/${st_tid}_sys.jpg"

    st_json=$(CLASSPATH="$BRIDGE_DEX" app_process / TaskSnapBridge \
                  "$st_tid" "$SNAPSHOT_RESOLUTION" "$st_out" 2>/dev/null | head -1)

    case "$st_json" in
        *'"ok":true'*)
            chown "$(stat -c '%u:%g' "$APP_DIR" 2>/dev/null)" "$st_out" 2>/dev/null || true
            chmod 666 "$st_out" 2>/dev/null || true
            return 0
            ;;
        *)
            # 失败时清掉可能存在的半成品，避免 App 把残缺文件当成有效帧
            rm -f "$st_out" 2>/dev/null || true
            return 1
            ;;
    esac
}

# 4.1 应用切换后的加密抓帧窗口
#     应用启动后约 3-4s 才稳定（实测微信冷启动），单次抓帧只会拍到启动动画帧。
#     此处在 SETTLE_WINDOW 秒内每 SETTLE_INTERVAL 秒补抓一帧，窗口结束时标记为稳定。
#     必须后台化：主循环承担手势期前台检测，同步阻塞会导致滑出检测滞后最多 3s。
SETTLE_WINDOW=3
SETTLE_INTERVAL=0.5
start_settle_window() {
    s_tid="$1"
    s_pkg="$2"
    (
        n=0
        while [ "$n" -lt "$SETTLE_WINDOW" ]; do
            sleep "$SETTLE_INTERVAL"
            n=$((n+1))
            # 用户可能已离开该应用，此时无须继续为其抓帧
            cur=$(cat /data/local/tmp/current_task_id.txt 2>/dev/null | tr -d '\r\n ')
            if [ "$cur" != "$s_tid" ]; then
                break
            fi
            capture_current_screen "$s_tid" "$s_pkg" 0
        done
        # 窗口结束，无论是否提前 break，都以稳定帧收尾
        capture_current_screen "$s_tid" "$s_pkg" 1
    ) &
}

# 5. 刷新任务列表为 JSON (防并发与原子落盘)
update_tasks() {
    chmod 755 /data/system_ce /data/system_ce/0 2>/dev/null || true
    chmod 755 "$SNAP_DIR" 2>/dev/null || true
    chmod 755 "$SNAP_USER_DIR" 2>/dev/null || true
    chmod 644 "$SNAP_USER_DIR"/* 2>/dev/null || true

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

        # 同步系统真实快照到 App 私有目录并赋予可读权限
        # 【v1.8.40】路径改指 $SNAP_USER_DIR（Android 16 的 UUID 子目录），
        # 与 0.0 节 / sync_from_system_snapshot 保持一致，否则此处同样零命中。
        mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
        for sys_snap in "$SNAP_USER_DIR/${tid}_reduced.jpg" "$SNAP_USER_DIR/${tid}.jpg"; do
            [ -f "$sys_snap" ] || continue
            b=$(basename "$sys_snap")
            cp -f "$sys_snap" "$APP_SNAP_DIR/$b" 2>/dev/null || true
            chown "$(stat -c '%u:%g' "$APP_DIR" 2>/dev/null)" "$APP_SNAP_DIR/$b" 2>/dev/null || true
            chmod 666 "$APP_SNAP_DIR/$b" 2>/dev/null || true
        done

        # 候选优先级：Task Snapshot 直读帧 > 退后台整屏帧 > 系统 reduced > 系统全分辨率
        # 【v1.8.40】最末位的 /data/local/tmp/snapshots 回退已移除（软链不再存在，
        # 见第 3 节）。App 侧 RootTaskProvider 同样以 filesDir 为首选来源。
        snap_path="$APP_SNAP_DIR/${tid}_sys.jpg"
        if [ ! -f "$snap_path" ]; then
            snap_path="$APP_SNAP_DIR/${tid}_live.png"
            if [ ! -f "$snap_path" ]; then
                snap_path="$APP_SNAP_DIR/${tid}_reduced.jpg"
                if [ ! -f "$snap_path" ]; then
                    snap_path="$APP_SNAP_DIR/${tid}.jpg"
                fi
            fi
        fi
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
            # 无 tid 的显式抓帧路径：沿用全局 current_app.png 语义，直接标记为稳定帧
            capture_current_screen "" "" 1
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
                # ===== Phase 2：退到后台采集 =====
                # 关键顺序：必须在覆写任何状态文件之前，先对"即将退到后台的旧应用"
                # 补抓其最终画面。LAST_RESUMED 此时仍持有旧应用的 tid/pkg。
                if [ -n "$LAST_RESUMED" ]; then
                    old_tid=$(echo "$LAST_RESUMED" | cut -d: -f1)
                    old_pkg=$(echo "$LAST_RESUMED" | cut -d: -f2 | cut -d/ -f1)
                    if [ -n "$old_tid" ] && [ "$old_tid" != "$tid" ]; then
                        capture_backgrounding "$old_tid" "$old_pkg"
                        # ===== v1.8.40 新增：退后台瞬间取系统权威快照 =====
                        # AOSP TaskSnapshotController 恰在「任务变不可见」这一刻
                        # 抓取该任务快照，所以此刻调用 getTaskSnapshot 命中率最高、
                        # 帧龄最小。产物 _sys.jpg 只含任务窗口，是卡片最该用的一档。
                        # 与 capture_backgrounding 并列而非取代：后者是整屏
                        # screencap（含系统栏），在应用刚退后台、系统尚未落盘时
                        # 仍能立刻给出一帧；二者由 App 侧仲裁层按 mtime 取优。
                        capture_task_snapshot "$old_tid"
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

                # ===== 进入前台采集：仅作为临时占位（provisional） =====
                # 用户若在应用启动动画期内立刻滑出，此帧是唯一可用的"最新画面"，
                # 供 Multitasking 卡片短暂显示；它带 provisional 标记，渲染层
                # 会主动降级为图标，直到退后台采集或系统快照给出最终帧。
                # 稳态低频刷新（settled=1）见下方 else 分支。
                sleep 0.15
                capture_current_screen "$tid" "$pkg" 0
                # 启动加密抓帧窗口：补抓到应用稳定帧
                start_settle_window "$tid" "$pkg"
                count=0
                update_tasks
            else
                count=$((count + 1))
                # 稳态刷新节奏（v1.8.39 由 10 降为 5）：
                # 单次 PNG screencap 实测 ≈0.65s，故原 10 次轮询（≈1.8s 纯等待
                # + 0.65s 抓帧 ≈2.4s）与实测观察到的 ≈3s 刷新间隔吻合 —— 这正是
                # 「划出时截图不够及时」的根因。降到 5 次后稳态帧龄由 ≈3s 降至 ≈1.5s。
                if [ "$count" -ge "$STEADY_CAPTURE_EVERY" ]; then
                    count=0
                    # 稳态刷新：应用已在前台稳定运行，此帧可直接标记为 settled
                    capture_current_screen "$tid" "$pkg" 1
                fi
            fi
        else
            # ===== Phase 2：无前台应用 = 用户滑出回到启动器（或应用全部退到后台） =====
            # 这正是"手势滑出"的主路径：此时 LAST_RESUMED 仍持有刚退到后台的应用，
            # 必须在清空状态之前补抓它的最终画面，否则多任务卡片将缺失最新一帧。
            if [ -n "$LAST_RESUMED" ]; then
                old_tid=$(echo "$LAST_RESUMED" | cut -d: -f1)
                old_pkg=$(echo "$LAST_RESUMED" | cut -d: -f2 | cut -d/ -f1)
                if [ -n "$old_tid" ]; then
                    # 手势进行中 gesture_active 存在，此时抓屏会拍到划出遮罩；
                    # 但启动器首帧绘制完成后遮罩已在 60ms 内淡出，稍作等待再抓更可靠。
                    if [ -f /data/local/tmp/gesture_active ]; then
                        sleep 0.35
                    fi
                    capture_backgrounding "$old_tid" "$old_pkg"
                    # 【v1.8.40】同上：退后台瞬间取系统权威快照。
                    # 这里尤其重要 —— 它是「划出返回启动器」的主路径，
                    # 也正是用户抱怨「卡片画面是几秒前的旧画面」的那条路径。
                    # capture_backgrounding 自带 gesture_active 前置守卫，
                    # 此刻遮罩已淡出，抓到的是应用最终态；随后系统快照落盘，
                    # _sys.jpg 以更纯净的像素在同一仲裁层里顶掉它。
                    capture_task_snapshot "$old_tid"
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

# 10. 首次立即刷新任务列表，随后定期维护
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
    update_tasks
    # 系统快照校正 + provisional TTL 兜底：保证 live 帧不会长期陈旧，
    # 且不会因 settle 子 shell 意外死亡而永久停留在图标降级态
    sync_from_system_snapshot
    expire_stale_provisionals
    sleep 2.0
done
