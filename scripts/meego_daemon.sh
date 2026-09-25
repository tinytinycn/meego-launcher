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
capture_current_screen() {
    target_tid="$1"
    target_pkg="$2"
    # 若手势正在进行中，坚决不捕获，避免截取到划出过程中带有桌面背景的画面
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
            
            mkdir -p "$APP_SNAP_DIR" 2>/dev/null || true
            cp -f /data/local/tmp/current_app.tmp.png "$APP_SNAP_DIR/${target_tid}_live.png" 2>/dev/null || true
            chmod 666 "$APP_SNAP_DIR/${target_tid}_live.png" 2>/dev/null || true

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
        mv -f /data/local/tmp/current_app.tmp.png /data/local/tmp/current_app.png
        chmod 666 /data/local/tmp/current_app.png
        cp -f /data/local/tmp/current_app.png "$APP_DIR/current_app.png" 2>/dev/null || true
        chmod 666 "$APP_DIR/current_app.png" 2>/dev/null || true
    fi
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
            capture_current_screen
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

                sleep 0.15
                capture_current_screen "$tid" "$pkg"
                count=0
                update_tasks
            else
                count=$((count + 1))
                if [ "$count" -ge 10 ]; then
                    count=0
                    capture_current_screen "$tid" "$pkg"
                fi
            fi
        else
            if [ -n "$LAST_RESUMED" ]; then
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
    sleep 2.0
done
