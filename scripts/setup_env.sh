#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env_detect.sh"

echo "=== [1/4] 配置 JDK 17 环境 (OS: $OS_TYPE) ==="
echo "JAVA_HOME: ${JAVA_HOME:-未设置 (使用系统默认)}"
if command -v java >/dev/null 2>&1; then
    java -version 2>&1 | head -n 2
else
    echo "⚠️ 未找到 java 命令，请通过 SDKMAN (sdk install java 17.0.1-open) 或系统包管理器安装 JDK 17"
fi

echo ""
echo "=== [2/4] 配置 Android SDK ==="
if [ -n "$ANDROID_HOME" ]; then
    echo "ANDROID_HOME: $ANDROID_HOME"
    mkdir -p "$ANDROID_HOME/licenses" 2>/dev/null || true
    # 预写入官方许可证 hash 避免交互卡死
    echo -e "24333f8a63cbd8246e0b79c2dd81b0bc084c143a\n89386a27e86313fb72749b84772490e4791fcdd0\nd56f5187479451eabf01fb78af6dfcb131a6481e" > "$ANDROID_HOME/licenses/android-sdk-license" 2>/dev/null || true
    echo -e "84831b9409646a3fe1ee562d27b82a407d24d2d6" > "$ANDROID_HOME/licenses/android-sdk-preview-license" 2>/dev/null || true
    
    echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_ROOT/local.properties"
    echo "已写入 local.properties: sdk.dir=$ANDROID_HOME"
else
    echo "⚠️ 未找到有效的 Android SDK 目录（需包含 platforms 子目录）！"
    if [ "$OS_TYPE" = "Darwin" ]; then
        echo "   macOS 默认路径通常为: ~/Library/Android/sdk"
    else
        echo "   Ubuntu / Linux 默认路径通常为: ~/Android/Sdk 或 /home/tiny/android-sdk"
    fi
fi

echo ""
echo "=== [3/4] 配置 Gradle ==="
echo "GRADLE_CMD: $GRADLE_CMD"
if [ -x "$GRADLE_CMD" ]; then
    "$GRADLE_CMD" -version 2>&1 | head -n 4
else
    echo "⚠️ 未找到 Gradle 可执行文件"
fi

echo ""
echo "=== [4/4] 验证 ADB 物理机连接与环境 ==="
echo "ADB 路径: $ADB"
if command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ]; then
    "$ADB" version | head -n 2 || true
    echo "设备列表："
    "$ADB" devices
else
    echo "⚠️ 未找到 adb 可执行文件，请安装 Android SDK platform-tools 或将 adb 加入 PATH。"
fi

echo ""
echo "=== 全部环境就绪完成！ ==="
