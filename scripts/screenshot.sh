#!/usr/bin/env bash
set -e

ADB="/home/tiny/trebuchet-build/prebuilts/runtime/adb"
if ! [ -x "$ADB" ]; then
    ADB="adb"
fi

OUTPUT_DIR="/home/tiny/dsh-workspace/test-android-launcher/screenshots"
mkdir -p "$OUTPUT_DIR"

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
FILENAME="${OUTPUT_DIR}/device_screen_${TIMESTAMP}.png"
LATEST="${OUTPUT_DIR}/latest.png"

echo "正在捕获物理机屏幕快照..."
$ADB exec-out screencap -p > "$FILENAME"
cp "$FILENAME" "$LATEST"

echo "截图已保存至: $FILENAME"
echo "最新快照链接: $LATEST"
ls -lh "$FILENAME"
