#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env_detect.sh"

OUTPUT_DIR="$PROJECT_ROOT/screenshots"
mkdir -p "$OUTPUT_DIR"

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
FILENAME="${OUTPUT_DIR}/device_screen_${TIMESTAMP}.png"
LATEST="${OUTPUT_DIR}/latest.png"

echo "正在捕获物理机屏幕快照 (ADB: $ADB)..."
"$ADB" exec-out screencap -p > "$FILENAME"
cp "$FILENAME" "$LATEST"

echo "截图已保存至: $FILENAME"
echo "最新快照链接: $LATEST"
ls -lh "$FILENAME"
