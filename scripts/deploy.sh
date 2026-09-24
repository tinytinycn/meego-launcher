#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

export JAVA_HOME="/home/tiny/trebuchet-build/prebuilts/jdk/jdk17/linux-x86"
export PATH="$JAVA_HOME/bin:/home/tiny/gradle/gradle-8.5/bin:$PATH"
export ANDROID_HOME="/home/tiny/android-sdk"
ADB="/home/tiny/trebuchet-build/prebuilts/runtime/adb"

echo "=== [1/5] 编译 MeeGo Launcher Debug APK ==="
cd "$PROJECT_ROOT"
gradle assembleDebug --console=plain

APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK 文件未生成: $APK_PATH"
    exit 1
fi

echo "APK 构建成功: $APK_PATH"

echo "=== [2/5] 推送并安装到物理机 (Redmi K40) ==="
"$ADB" install -r -d "$APK_PATH"

echo "=== [3/5] 更新并配置特权守护脚本与权限 ==="
"$ADB" shell appops set com.meego.launcher SYSTEM_ALERT_WINDOW allow || true
"$ADB" shell settings put secure enabled_accessibility_services com.meego.launcher/com.meego.launcher.service.EdgeSwipeGestureService || true
"$ADB" shell settings put secure accessibility_enabled 1 || true
"$ADB" push "$SCRIPT_DIR/meego_daemon.sh" /data/local/tmp/meego_daemon.sh
"$ADB" shell chmod 755 /data/local/tmp/meego_daemon.sh
# 清理可能被污染的旧截屏临时文件与手势标志
"$ADB" shell "rm -f /data/local/tmp/current_app*.png /data/local/tmp/gesture_active"
# 重启守护进程确保最新逻辑生效
"$ADB" shell "pkill -f meego_daemon.sh || true"
"$ADB" shell "nohup /data/local/tmp/meego_daemon.sh </dev/null >/data/local/tmp/daemon.log 2>&1 &"

echo "=== [4/5] 启动 MeeGo Launcher ==="
"$ADB" shell am force-stop com.meego.launcher
"$ADB" shell am start -n com.meego.launcher/.MainActivity

echo "=== [5/5] 获取物理机最新渲染截屏 ==="
sleep 2
bash "$SCRIPT_DIR/screenshot.sh"

echo "=== 部署与真机点亮验证完成！ ==="
