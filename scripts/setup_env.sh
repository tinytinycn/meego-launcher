#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=== [1/4] 配置 JDK 17 环境 ==="
export JAVA_HOME="/home/tiny/trebuchet-build/prebuilts/jdk/jdk17/linux-x86"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo "=== [2/4] 配置 Android SDK (/home/tiny/android-sdk) ==="
export ANDROID_HOME="/home/tiny/android-sdk"
mkdir -p "$ANDROID_HOME/licenses"

# 预写入官方许可证 hash 避免交互卡死
echo -e "24333f8a63cbd8246e0b79c2dd81b0bc084c143a\n89386a27e86313fb72749b84772490e4791fcdd0\nd56f5187479451eabf01fb78af6dfcb131a6481e" > "$ANDROID_HOME/licenses/android-sdk-license"
echo -e "84831b9409646a3fe1ee562d27b82a407d24d2d6" > "$ANDROID_HOME/licenses/android-sdk-preview-license"

SDKMANAGER="/home/tiny/trebuchet-build/prebuilts/cmdline-tools/tools/bin/sdkmanager"

if [ ! -d "$ANDROID_HOME/platforms/android-34" ] || [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
    echo "正在下载安装 Android SDK 34 与 Build Tools 34.0.0 ..."
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "platforms;android-34" "build-tools;34.0.0" "platform-tools" || true
fi

echo "Android SDK 目录状态："
ls -la "$ANDROID_HOME"

echo "=== [3/4] 配置 Gradle 8.5 ==="
GRADLE_DIR="/home/tiny/gradle/gradle-8.5"
if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
    echo "正在准备 Gradle 8.5 ..."
    mkdir -p /home/tiny/gradle
    if [ ! -f /tmp/gradle-8.5-bin.zip ]; then
        curl -L -o /tmp/gradle-8.5-bin.zip https://services.gradle.org/distributions/gradle-8.5-bin.zip
    fi
    unzip -q -o /tmp/gradle-8.5-bin.zip -d /home/tiny/gradle/
    rm -f /tmp/gradle-8.5-bin.zip
fi
export PATH="$GRADLE_DIR/bin:$PATH"
gradle -version

echo "=== [4/4] 验证 ADB 物理机连接与 Root 状态 ==="
ADB="/home/tiny/trebuchet-build/prebuilts/runtime/adb"
"$ADB" devices
"$ADB" shell id

echo "=== 全部环境就绪完成！ ==="
