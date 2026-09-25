#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env_detect.sh"

cd "$PROJECT_ROOT"

echo "=== 开始编译 MeeGo Launcher Debug APK ==="
echo "操作系统: $OS_TYPE"
echo "JAVA_HOME: $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"
echo "GRADLE_CMD: $GRADLE_CMD"

"$GRADLE_CMD" assembleDebug --console=plain > build.log 2>&1
BUILD_CODE=$?
echo "BUILD_EXIT_CODE=$BUILD_CODE" >> build.log

if [ $BUILD_CODE -eq 0 ]; then
    echo "✅ 构建成功: $PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
else
    echo "❌ 构建失败，详情请查看 build.log"
    tail -n 25 build.log
    exit $BUILD_CODE
fi
