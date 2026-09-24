#!/system/bin/sh
# MeeGo Launcher 特权守护脚本 (运行在 adbd/root 身份下)

FIFO="/data/local/tmp/meego_cmd"
TASKS_JSON="/data/local/tmp/recent_tasks.json"
SNAP_DIR="/data/system_ce/0/snapshots"
LINK_DIR="/data/local/tmp/snapshots"

mkdir -p /data/local/tmp
rm -f "$FIFO"
mkfifo "$FIFO"
chmod 777 "$FIFO"

# 确保无障碍服务与全屏边缘侧滑所需系统配置始终生效
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

# 建立快照软链接并保持权限
if [ -d "$SNAP_DIR" ]; then
    rm -rf "$LINK_DIR"
    ln -s "$SNAP_DIR" "$LINK_DIR"
    chmod 755 /data/system_ce /data/system_ce/0 2>/dev/null || true
    chmod 755 "$SNAP_DIR" 2>/dev/null || true
    chmod 644 "$SNAP_DIR"/* 2>/dev/null || true
fi

# 捕获当前屏幕画面到 snapshots/${target_tid}_live.png 与 current_app.png
capture_current_screen() {
    target_tid="$1"
    # 1. 若手势正在进行中，坚决不捕获，避免截取到划出过程中带有桌面背景的画面
    if [ -f /data/local/tmp/gesture_active ]; then
        return
    fi

    screencap -p /data/local/tmp/current_app.tmp.png 2>/dev/null
    # 截屏耗时期间若手势被触发，立刻丢弃截屏
    if [ -f /data/local/tmp/gesture_active ]; then
        rm -f /data/local/tmp/current_app.tmp.png
        return
    fi

    if [ -s /data/local/tmp/current_app.tmp.png ]; then
        if [ -n "$target_tid" ]; then
            mkdir -p /data/local/tmp/snapshots
            cp -f /data/local/tmp/current_app.tmp.png "/data/local/tmp/snapshots/${target_tid}_live.png" 2>/dev/null || true
            chmod 666 "/data/local/tmp/snapshots/${target_tid}_live.png" 2>/dev/null || true
            echo "$target_tid" > /data/local/tmp/current_app_tid.txt
            chmod 666 /data/local/tmp/current_app_tid.txt
        fi
        mv -f /data/local/tmp/current_app.tmp.png /data/local/tmp/current_app.png
        chmod 666 /data/local/tmp/current_app.png
    fi
}

# 处理指令循环的后台函数
process_commands() {
    while true; do
        if read -r line < "$FIFO"; then
            cmd=$(echo "$line" | awk '{print $1}')
            arg=$(echo "$line" | awk '{print $2}')
            case "$cmd" in
                REMOVE)
                    if [ -n "$arg" ]; then
                        tid="$arg"
                        pkg=$(echo "$line" | awk '{print $3}')
                        am stack remove "$tid" 2>/dev/null || true
                        rm -f "/data/system_ce/0/snapshots/${tid}"* 2>/dev/null || true
                        rm -f "/data/local/tmp/snapshots/${tid}"* 2>/dev/null || true
                        if [ -n "$pkg" ] && [ "$pkg" != "com.meego.launcher" ] && [ "$pkg" != "com.android.launcher3" ]; then
                            am force-stop "$pkg" 2>/dev/null || true
                        fi
                    fi
                    ;;
                SWITCH)
                    if [ -n "$arg" ]; then
                        tid="$arg"
                        echo "$tid" > /data/local/tmp/current_task_id.txt
                        chmod 666 /data/local/tmp/current_task_id.txt
                        rm -f /data/local/tmp/current_app_tid.txt /data/local/tmp/current_app.png
                    fi
                    ;;
                CLEAR_ALL)
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
                                    print cur_id " " pkg;
                                }
                                cur_id = "";
                            }
                        }
                    ' | while read -r tid pkg; do
                        if [ -n "$tid" ]; then
                            am stack remove "$tid" 2>/dev/null || true
                            rm -f "/data/system_ce/0/snapshots/${tid}"* 2>/dev/null || true
                        fi
                        if [ -n "$pkg" ] && [ "$pkg" != "com.meego.launcher" ] && [ "$pkg" != "com.android.launcher3" ]; then
                            am force-stop "$pkg" 2>/dev/null || true
                        fi
                    done
                    ;;
                SNAP)
                    capture_current_screen
                    ;;
                ENABLE_A11Y)
                    ensure_system_settings
                    ;;
                UPDATE)
                    ;;
            esac
            update_tasks
        fi
    done
}

# 监控前台当前运行的 App，并实时捕获其界面
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

            if [ "$resumed_info" != "$LAST_RESUMED" ]; then
                LAST_RESUMED="$resumed_info"
                # 切换至新前台应用，立即失效全局旧快照，杜绝脏读
                rm -f /data/local/tmp/current_app_tid.txt /data/local/tmp/current_app.png
                echo "$tid" > /data/local/tmp/current_task_id.txt
                chmod 666 /data/local/tmp/current_task_id.txt
                sleep 0.15
                capture_current_screen "$tid"
                count=0
            else
                count=$((count + 1))
                if [ "$count" -ge 10 ]; then
                    count=0
                    capture_current_screen "$tid"
                fi
            fi
        else
            if [ -n "$LAST_RESUMED" ]; then
                LAST_RESUMED=""
                rm -f /data/local/tmp/current_task_id.txt /data/local/tmp/current_app_tid.txt /data/local/tmp/current_app.png
            fi
        fi
        sleep 0.15
    done
}

# 刷新任务列表为 JSON
update_tasks() {
    chmod 755 /data/system_ce /data/system_ce/0 2>/dev/null || true
    chmod 755 "$SNAP_DIR" 2>/dev/null || true
    chmod 644 "$SNAP_DIR"/* 2>/dev/null || true

    TMP_FILE="/data/local/tmp/recent_tasks.$$.tmp"
    echo "[" > "$TMP_FILE"

    first=1
    dumpsys activity recents | awk '
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
                if (pkg != "" && pkg !~ /com.meego.launcher/ && pkg !~ /FallbackHome/) {
                    print cur_id ":" pkg;
                }
                cur_id = "";
            }
        }
    ' | while IFS=":" read -r tid tpkg; do
        [ -z "$tid" ] && continue

        snap_path="/data/local/tmp/snapshots/${tid}_live.png"
        if [ ! -f "$snap_path" ]; then
            snap_path="/data/local/tmp/snapshots/${tid}_reduced.jpg"
            if [ ! -f "$snap_path" ]; then
                snap_path="/data/local/tmp/snapshots/${tid}.jpg"
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
    chmod 666 "$TMP_FILE"
    mv -f "$TMP_FILE" "$TASKS_JSON"
    chmod 666 "$TASKS_JSON"
}

process_commands &
monitor_current_app &

while true; do
    update_tasks
    sleep 2.0
done
