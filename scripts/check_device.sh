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
fi
