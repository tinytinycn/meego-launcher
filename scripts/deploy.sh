#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env_detect.sh"

echo "=== [1/5] 编译 MeeGo Launcher Debug APK ==="
cd "$PROJECT_ROOT"
"$GRADLE_CMD" assembleDebug --console=plain

APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK 不存在，请检查编译日志: $APK_PATH"
    exit 1
fi

echo "APK 构建成功: $APK_PATH"

echo "=== [2/5] 推送并安装到物理机 ==="
"$ADB" install -r -d "$APK_PATH"

echo "=== [3/5] 更新并配置特权守护脚本与开机自启动 ==="
# 1. 推送特权伴侣守护脚本到手机 /data/local/tmp
if [ -f "$SCRIPT_DIR/meego_daemon.sh" ]; then
    "$ADB" push "$SCRIPT_DIR/meego_daemon.sh" /data/local/tmp/meego_daemon.sh
    "$ADB" shell chmod 755 /data/local/tmp/meego_daemon.sh
    
    # 2. 若系统分区支持可写，同步安装至 /system/bin 与 /system/etc/init/ 以实现开机自动拉起
    "$ADB" remount >/dev/null 2>&1 || true
    "$ADB" shell "
        if [ -w /system/bin ]; then
            cp -f /data/local/tmp/meego_daemon.sh /system/bin/meego_daemon.sh
            chmod 755 /system/bin/meego_daemon.sh
            cat << 'EOF' > /system/etc/init/meego_daemon.rc
service meego_daemon /system/bin/sh /system/bin/meego_daemon.sh
    class late_start
    user root
    group root system
    seclabel u:r:su:s0
    oneshot

on property:sys.boot_completed=1
    start meego_daemon
EOF
            chmod 644 /system/etc/init/meego_daemon.rc
        fi
    " 2>/dev/null || true

    # 3. 立即启动/重载守护进程，确保后台卡片即时刷新
    "$ADB" shell "nohup /data/local/tmp/meego_daemon.sh > /data/local/tmp/daemon.log 2>&1 &"
fi

# 4. 配置与授予特权/使用情况统计权限
"$ADB" shell appops set com.meego.launcher GET_USAGE_STATS allow 2>/dev/null || true
"$ADB" shell pm grant com.meego.launcher android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
"$ADB" shell pm grant com.meego.launcher android.permission.DUMP 2>/dev/null || true
"$ADB" shell pm grant com.meego.launcher android.permission.REAL_GET_TASKS 2>/dev/null || true

echo "=== [4/5] 强制停止历史进程并冷启动应用 ==="
"$ADB" shell am force-stop com.meego.launcher
"$ADB" shell am start -n com.meego.launcher/.MainActivity

echo "=== [5/5] 验证进程存活与前台状态 ==="
sleep 2
PID=$("$ADB" shell pidof com.meego.launcher || true)
DAEMON_PID=$("$ADB" shell pgrep -f "meego_daemon.sh" || true)

if [ -n "$PID" ]; then
    echo "✅ MeeGo Launcher 启动成功！PID: $PID"
else
    echo "⚠️ 警告：未检测到 com.meego.launcher 进程，请检查 logcat 报错！"
    "$ADB" logcat -d -t 50 | grep -E "AndroidRuntime|MeeGo" || true
    exit 1
fi

if [ -n "$DAEMON_PID" ]; then
    echo "✅ MeeGo 特权守护脚本已在后台就绪！PID: $DAEMON_PID"
else
    echo "ℹ️ 特权守护脚本未以服务形式常驻，将依托持久化缓存与 UsageStats 兜底"
fi
