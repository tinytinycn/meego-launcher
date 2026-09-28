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

    # ---------------------------------------------------------------------------
    # 快照链路健康诊断（v1.8.40 新增）
    #
    # 背景：Android 16 把系统快照改为「每用户 UUID 子目录」布局，此前守护脚本
    # 按扁平路径取帧，导致系统快照同步链路**静默全失效**（ls 无报错、glob 零命中）。
    # 这类"安静地坏掉"的故障必须能被一条命令看出来，故固化到此。
    # ---------------------------------------------------------------------------
    echo ""
    echo "=== 快照链路诊断 (Task Snapshot) ==="

    SNAP_ROOT="/data/system_ce/0/snapshots"
    # 复刻守护脚本的子目录探测（flat 布局则退回根目录本身）
    SNAP_USER_DIR=$("$ADB" shell "for d in $SNAP_ROOT/*/; do [ -d \"\$d\" ] && echo \"\${d%/}\" && break; done" 2>/dev/null | tr -d '\r' | head -1)
    [ -z "$SNAP_USER_DIR" ] && SNAP_USER_DIR="$SNAP_ROOT"

    if [ "$SNAP_USER_DIR" = "$SNAP_ROOT" ]; then
        echo "  布局: 扁平（Android 15 及更早的布局，兼容模式）"
    else
        echo "  布局: 每用户 UUID 子目录 ✅ (Android 16)"
        echo "        $SNAP_USER_DIR"
    fi

    SYS_SNAP_COUNT=$("$ADB" shell "ls $SNAP_USER_DIR/*.jpg 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r ' || echo 0)
    echo -n "  系统快照张数: $SYS_SNAP_COUNT"
    if [ "$SYS_SNAP_COUNT" = "0" ]; then
        echo "  ⚠️ 为 0 —— 同步链路必然失效（检查守护脚本是否在跑）"
    else
        echo "  ✅"
    fi

    # 同步时效：App 私有目录里最新一帧 与 系统侧最新一帧 的 mtime 差
    APP_SNAP_DIR="/data/user/0/com.meego.launcher/files/snapshots"
    NEWEST_APP=$("$ADB" shell "ls -t $APP_SNAP_DIR/*.jpg 2>/dev/null | head -1" 2>/dev/null | tr -d '\r')
    NEWEST_SYS=$("$ADB" shell "ls -t $SNAP_USER_DIR/*.jpg 2>/dev/null | head -1" 2>/dev/null | tr -d '\r')
    if [ -n "$NEWEST_APP" ] && [ -n "$NEWEST_SYS" ]; then
        # 直接 stat 完整路径（basename 会被 /data/local/tmp 之类的前缀干扰，
        # 且在设备侧没有对应 cwd）
        T_APP=$("$ADB" shell "stat -c %Y '$NEWEST_APP' 2>/dev/null" 2>/dev/null | tr -d '\r' || echo 0)
        T_SYS=$("$ADB" shell "stat -c %Y '$NEWEST_SYS' 2>/dev/null" 2>/dev/null | tr -d '\r' || echo 0)
        if [ -n "$T_APP" ] && [ -n "$T_SYS" ] && [ "$T_SYS" -gt 0 ] 2>/dev/null; then
            LAG=$((T_SYS - T_APP))
            if [ "$LAG" -gt 0 ]; then
                echo "  ⚠️ App 侧快照落后系统侧 ${LAG}s —— 同步未生效（守护进程可能已死，见下方心跳）"
            else
                echo "  ✅ 快照同步正常（App 侧不落后系统侧）"
            fi
        fi
        echo "    App 最新: $(basename "$NEWEST_APP")"
        echo "    Sys 最新: $(basename "$NEWEST_SYS")"
    fi

    # TaskSnapBridge 存在性
    if "$ADB" shell "test -f /data/local/tmp/meego_daemon_bridge.dex" 2>/dev/null; then
        echo "  ✅ TaskSnapBridge 已部署"
    else
        echo "  ℹ️ TaskSnapBridge 未部署（纯 screencap 降级模式，仍可用）"
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
