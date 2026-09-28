#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env_detect.sh"

echo "=== MeeGo Launcher 物理机连接诊断 (OS: $OS_TYPE) ==="
echo "ADB 路径: $ADB"
"$ADB" version || true

echo ""
echo "=== 正在检测已连接的 Android 设备 ==="
DEVICES=$("$ADB" devices -l | grep -v "List of devices attached" | grep -v "^$" || true)

if [ -z "$DEVICES" ]; then
    echo "⚠️ 未检测到已连接的设备。"
    echo "请按以下步骤检查物理机："
    echo "1. 手机开启【开发者选项】并打开【USB 调试】（部分机型如 MIUI/HyperOS 需开启【USB 安装/USB 调试安全设置】）"
    echo "2. 使用数据线连接电脑并在手机弹窗中勾选【一律允许使用这台计算机进行调试】"
    if [ "$OS_TYPE" = "Linux" ]; then
        echo "   * Linux 用户若提示 no permissions，需配置 udev 规则: /etc/udev/rules.d/51-android.rules"
    fi
    echo "3. 若使用无线调试，请运行：$ADB pair <IP>:<PORT> && $ADB connect <IP>:<PORT>"
else
    echo "已发现设备："
    echo "$DEVICES"
    echo ""
    echo "=== 设备屏幕与系统信息 ==="
    echo -n "设备型号: " && "$ADB" shell getprop ro.product.model 2>/dev/null || echo "未知"
    echo -n "Android 版本: " && "$ADB" shell getprop ro.build.version.release 2>/dev/null || echo "未知"
    echo -n "屏幕分辨率: " && "$ADB" shell wm size 2>/dev/null || true
    echo -n "屏幕密度 (DPI): " && "$ADB" shell wm density 2>/dev/null || true
    echo -n "Root 状态: " && ("$ADB" shell id 2>/dev/null || echo "非 Root/不可用")

    # 常驻 Task Snapshot 链路健康诊断（v1.8.44 重写）
    #
    # 背景：自本版起，多任务卡片的**唯一**画面来源是常驻特权服务
    # meego_svc.dex。它不工作时应用不会崩、不会报错，只是所有卡片安静地
    # 退化成应用图标 —— 典型的「安静地坏掉」，必须能被一条命令看出来。
    # ---------------------------------------------------------------------------
    echo ""
    echo "=== 常驻快照服务诊断 (TaskSnapService) ==="

    APP_SNAP_DIR="/data/user/0/com.meego.launcher/files/snapshots"

    # --- 1. 服务进程存活 ---
    SVC_PID=$("$ADB" shell 'cat /data/local/tmp/meego_svc.pid 2>/dev/null | tr -d "\r\n "' 2>/dev/null | tr -cd '0-9')
    if [ -n "$SVC_PID" ] && "$ADB" shell "test -d /proc/$SVC_PID" 2>/dev/null; then
        echo "  ✅ 服务进程存活（PID=${SVC_PID}）"
    else
        echo "  ❌ 服务进程未运行 —— 卡片将只能显示应用图标"
        echo "     排查：· /data/local/tmp/meego_svc.dex 是否存在"
        echo "          · adb shell 是否 root（TaskSnapshot 需签名级权限）"
        echo "          · /data/local/tmp/daemon.log 末尾是否有 'TaskSnapService 未能启动'"
    fi

    # --- 2. 服务 IPC 活性（ping 往返）---
    # ping 往返必须「先记行数、再发指令、轮询等新行」，不能固定 sleep 后 tail。
    # 固定 sleep 会与服务处理耗时赛跑 —— 抢跑时 tail 取到的是**上一条**早已存在的
    # 响应（例如启动横幅 {"ok":true,"ready":true}），它同样含 "ok":true 能骗过
    # case 判断，却**不含 "threads"**，于是打印出 `线程数=?` 这种似是而非的结果。
    SVC_PING=$("$ADB" shell '
        exec 3>/data/local/tmp/meego_snap_cmd 2>/dev/null || exit
        before=$(wc -l < /data/local/tmp/meego_snap_out 2>/dev/null || echo 0)
        echo "{\"cmd\":\"ping\"}" >&3
        exec 3>&-
        n=0
        while [ "$n" -lt 30 ]; do
            after=$(wc -l < /data/local/tmp/meego_snap_out 2>/dev/null || echo 0)
            if [ "$after" -gt "$before" ]; then
                tail -n 1 /data/local/tmp/meego_snap_out
                exit
            fi
            sleep 0.05
            n=$((n + 1))
        done
    ' 2>/dev/null | tr -d '\r' | tail -1)
    case "$SVC_PING" in
        *'"ok":true'*)
            THREADS=$(echo "$SVC_PING" | sed -n 's/.*"threads":\([0-9-]*\).*/\1/p')
            echo "  ✅ IPC 正常响应（Binder 线程数=${THREADS:-?}，>1 才具备回调能力）"
            ;;
        *)
            echo "  ⚠️ ping 无响应：${SVC_PING:-<空>}"
            echo "     检查 /data/local/tmp/meego_snap_out"
            ;;
    esac

    # --- 3. 产物新鲜度：最新 _sys.jpg 距今多久 ---
    #
    # 【为什么必须先看「当前前台是谁」再判定陈旧】
    # 取帧循环只对**前台的应用**取帧；桌面（com.meego.launcher）自己在最前时，
    # 根本没有可采的任务，帧自然不再刷新 —— 那是**正常静息态**，不是故障。
    # 不区分这一点就会在用户最常处的桌面状态打出「⚠️ 帧已陈旧 —— 采集循环可能已停止」
    # 的假警报。此类假警报比不报更糟：它会训练用户忽略这条诊断。
    CUR_FOCUS=$("$ADB" shell "dumpsys activity activities 2>/dev/null | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r')
    case "$CUR_FOCUS" in
        *com.meego.launcher*) AT_HOME=1 ;;
        *)                    AT_HOME=0 ;;
    esac

    NEWEST=$("$ADB" shell "ls -t $APP_SNAP_DIR/*_sys.jpg 2>/dev/null | head -1" 2>/dev/null | tr -d '\r')
    if [ -n "$NEWEST" ]; then
        T_APP=$("$ADB" shell "stat -c %Y '$NEWEST' 2>/dev/null" 2>/dev/null | tr -d '\r')
        NOW=$("$ADB" shell "date +%s" 2>/dev/null | tr -d '\r')
        if [ -n "$T_APP" ] && [ -n "$NOW" ] && [ "$T_APP" -gt 0 ] 2>/dev/null; then
            AGE=$((NOW - T_APP))
            echo -n "  最新帧: $(basename "$NEWEST")（${AGE}s 前）"
            # 前台任务由 take_foreground_loop 以 0.3s 节奏刷新；
            # 若长期无新帧，通常是守护主循环或服务已死。
            if [ "$AGE" -gt 120 ] 2>/dev/null; then
                if [ "$AT_HOME" -eq 1 ]; then
                    echo "  ℹ️ 桌面在最前，无可采任务（正常静息态）"
                else
                    echo "  ⚠️ 帧已陈旧 —— 采集循环可能已停止"
                fi
            else
                echo "  ✅"
            fi
        fi
        FRAME_COUNT=$("$ADB" shell "ls $APP_SNAP_DIR/*_sys.jpg 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r ')
        echo "  产物总数: ${FRAME_COUNT:-0} 张"
    else
        echo "  ⚠️ 尚无任何 _sys.jpg 产物"
    fi

    # --- 4. 取帧产物是否纯净（尺寸应为任务窗口，1080x2400）---
    # 用 sips/ImageIO 在设备侧不便，改用文件大小量级做粗判：
    # 一张 1080x2400 的 TaskSnapshot JPEG 通常 > 20KB；
    # 若全部是很小的文件，多半取帧失败或写出了空图。
    TINY=$("$ADB" shell "find $APP_SNAP_DIR -name '*_sys.jpg' -size -20k 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r ')
    TOTAL=$("$ADB" shell "ls $APP_SNAP_DIR/*_sys.jpg 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r ')
    if [ -n "$TOTAL" ] && [ "$TOTAL" != "0" ]; then
        if [ "$TINY" = "0" ]; then
            echo "  ✅ 全部产物尺寸正常（无 <20KB 的可疑小图）"
        else
            echo "  ⚠️ 有 ${TINY}/${TOTAL} 张产物 < 20KB —— 取帧可能失败"
        fi
    fi

    # --- 5. 一次性 bridge（仅自检/排障兜底，非主链路）---
    if "$ADB" shell "test -f /data/local/tmp/meego_daemon_bridge.dex" 2>/dev/null; then
        echo "  ✅ 一次性 TaskSnapBridge 已部署（自检/排障兜底）"
    else
        echo "  ℹ️ 一次性 TaskSnapBridge 未部署（不影响主链路）"
    fi

    # 守护进程心跳（心跳落在 App 私有目录：/data/local/tmp 属 shell_data_file，
    # App 侧会被 SELinux 拒绝读取）
    HB=$("$ADB" shell "cat $APP_SNAP_DIR/../cmd_queue/daemon.hb 2>/dev/null" 2>/dev/null | tr -d '\r' || true)
    NOW=$("$ADB" shell "date +%s" 2>/dev/null | tr -d '\r')
    if [ -n "$HB" ]; then
        HB_TS=$(echo "$HB" | awk '{print $2}')
        if [ -n "$HB_TS" ] && [ -n "$NOW" ]; then
            AGE=$((NOW - HB_TS))
            if [ "$AGE" -gt 15 ] 2>/dev/null; then
                echo "  ⚠️ 守护进程心跳已过期 ${AGE}s —— 主进程已死，Task Snapshot 管线不会更新"
            else
                echo "  ✅ 守护进程存活（心跳 ${AGE}s 前）"
            fi
        fi
    else
        echo "  ⚠️ 无守护进程心跳文件 —— 守护进程从未启动过"
    fi

    # ---------------------------------------------------------------------------
    # 开机自启诊断（v1.8.41）
    #
    # 本机不存在 /system/etc/init/meego_daemon.rc 且 /system 受 dm-verity 保护
    # 不可写，故开机自启改由 Magisk 的 service.d 钩子承担。此项决定了
    # 「重启之后守护链还在不在」—— 必须能从诊断里直接看出来。
    # ---------------------------------------------------------------------------
    echo ""
    echo "=== 开机自启诊断 ==="
    if "$ADB" shell "su -c 'test -d /data/adb'" 2>/dev/null; then
        if "$ADB" shell "su -c 'test -x /data/adb/service.d/meego_boot.sh'" 2>/dev/null; then
            echo "  ✅ Magisk 开机钩子已安装（/data/adb/service.d/meego_boot.sh）"
        else
            echo "  ⚠️ Magisk 存在但开机钩子未安装 —— 重启后守护链不会自启（跑 deploy.sh 可安装）"
        fi
    else
        echo "  ℹ️ 未检测到 Magisk（无 /data/adb）"
    fi
    if "$ADB" shell "test -f /system/etc/init/meego_daemon.rc" 2>/dev/null; then
        echo "  ℹ️ 亦存在 init 路径 rc（/system/etc/init/meego_daemon.rc）"
    fi
    # 最近一次开机拉起的结果：由 meego_boot.sh 写入，是排查「重启后没起来」的第一现场
    BOOTLOG=$("$ADB" shell "tail -n 4 /data/local/tmp/meego_boot.log 2>/dev/null" 2>/dev/null | tr -d '\r' || true)
    if [ -n "$BOOTLOG" ]; then
        echo "  最近一次拉起日志（/data/local/tmp/meego_boot.log）："
        echo "$BOOTLOG" | sed 's/^/    /'
    else
        echo "  ℹ️ 无拉起日志（meego_boot.log 不存在，可能是升级前启动的守护链）"
    fi
fi
