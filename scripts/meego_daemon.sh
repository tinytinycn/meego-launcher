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

APP_DIR="/data/user/0/com.meego.launcher/files"
APP_CMD_DIR="$APP_DIR/cmd_queue"
APP_TASKS_JSON="$APP_DIR/recent_tasks.json"
APP_SNAP_DIR="$APP_DIR/snapshots"

# 1. 单实例保障：终止历史旧守护进程，严防竞争冲突
MY_PID=$$
for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null || true); do
    if [ "$p" != "$MY_PID" ]; then
        kill -9 "$p" 2>/dev/null || true
    fi
done

mkdir -p /data/local/tmp
if [ ! -p "$FIFO" ]; then
    rm -f "$FIFO"
    mkfifo "$FIFO"
fi
chmod 777 "$FIFO" 2>/dev/null || true

mkdir -p "$APP_CMD_DIR" 2>/dev/null || true
chmod 777 "$APP_CMD_DIR" 2>/dev/null || true
mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
chmod 777 "$APP_SNAP_DIR" 2>/dev/null || true

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
if [ -d "$SNAP_DIR" ]; then
    rm -rf "$LINK_DIR"
    ln -s "$SNAP_DIR" "$LINK_DIR"
    chmod 755 /data/system_ce /data/system_ce/0 2>/dev/null || true
    chmod 755 "$SNAP_DIR" 2>/dev/null || true
    chmod 644 "$SNAP_DIR"/* 2>/dev/null || true
fi

# 4. 捕获当前屏幕画面到 snapshots/${target_tid}_live.png 与 current_app.png
#    settled=0 -> 标记为 provisional（应用仍处于启动动画期，渲染层应降级为图标占位）
#    settled=1 -> 清除 provisional 标记（已确认是应用稳定后的画面）
capture_current_screen() {
    target_tid="$1"
    target_pkg="$2"
    settled="${3:-1}"

    # 若手势正在进行中，坚决不捕获，避免截取到划出过程中带有桌面背景的画面
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
            mkdir -p /data/local/tmp/snapshots
            cp -f "$TMP_PNG" "/data/local/tmp/snapshots/${target_tid}_live.png" 2>/dev/null || true
            chmod 666 "/data/local/tmp/snapshots/${target_tid}_live.png" 2>/dev/null || true

            mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
            cp -f "$TMP_PNG" "$APP_SNAP_DIR/${target_tid}_live.png" 2>/dev/null || true
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
        mkdir -p /data/local/tmp/snapshots 2>/dev/null || true
        cp -f "$bg_tmp" "/data/local/tmp/snapshots/${bg_tid}_live.png" 2>/dev/null || true
        chmod 666 "/data/local/tmp/snapshots/${bg_tid}_live.png" 2>/dev/null || true

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
sync_from_system_snapshot() {
    for sf in "$SNAP_DIR"/*_reduced.jpg; do
        [ -f "$sf" ] || continue
        b=$(basename "$sf")
        case "$b" in
            *_reduced.jpg) s_tid="${b%_reduced.jpg}" ;;
            *) continue ;;
        esac
        [ -z "$s_tid" ] && continue
        mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
        dest="$APP_SNAP_DIR/$b"
        # 仅当 App 私有目录缺失或比系统快照更旧时才同步
        if [ ! -f "$dest" ] || [ "$sf" -nt "$dest" ]; then
            cp -f "$sf" "$dest" 2>/dev/null || true
            chmod 666 "$dest" 2>/dev/null || true
        fi
    done
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
    chmod 644 "$SNAP_DIR"/* 2>/dev/null || true

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
        mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
        if [ -f "$SNAP_DIR/${tid}_reduced.jpg" ]; then
            cp -f "$SNAP_DIR/${tid}_reduced.jpg" "$APP_SNAP_DIR/${tid}_reduced.jpg" 2>/dev/null || true
            chmod 666 "$APP_SNAP_DIR/${tid}_reduced.jpg" 2>/dev/null || true
        fi
        if [ -f "$SNAP_DIR/${tid}.jpg" ]; then
            cp -f "$SNAP_DIR/${tid}.jpg" "$APP_SNAP_DIR/${tid}.jpg" 2>/dev/null || true
            chmod 666 "$APP_SNAP_DIR/${tid}.jpg" 2>/dev/null || true
        fi

        snap_path="$APP_SNAP_DIR/${tid}_live.png"
        if [ ! -f "$snap_path" ]; then
            snap_path="$APP_SNAP_DIR/${tid}_reduced.jpg"
            if [ ! -f "$snap_path" ]; then
                snap_path="$APP_SNAP_DIR/${tid}.jpg"
                if [ ! -f "$snap_path" ]; then
                    snap_path="/data/local/tmp/snapshots/${tid}_live.png"
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
        REMOVE)
            if [ -n "$arg" ]; then
                tid="$arg"
                # 1. 彻底从系统 Activity 任务栈中移除
                am stack remove "$tid" 2>/dev/null || true
                # 2. 清理系统快照及缓存
                rm -f "/data/system_ce/0/snapshots/${tid}"* 2>/dev/null || true
                rm -f "/data/local/tmp/snapshots/${tid}"* 2>/dev/null || true
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
                    rm -f "/data/system_ce/0/snapshots/${tid}"* 2>/dev/null || true
                    rm -f "/data/local/tmp/snapshots/${tid}"* 2>/dev/null || true
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
                if [ "$count" -ge 10 ]; then
                    count=0
                    # 稳态低频刷新：应用已在前台稳定运行，此帧可直接标记为 settled
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
    update_tasks
    # 系统快照校正 + provisional TTL 兜底：保证 live 帧不会长期陈旧，
    # 且不会因 settle 子 shell 意外死亡而永久停留在图标降级态
    sync_from_system_snapshot
    expire_stale_provisionals
    sleep 2.0
done
