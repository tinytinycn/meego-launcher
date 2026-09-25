#!/usr/bin/env bash
# ==============================================================================
# MeeGo Launcher - 跨平台环境智能探测与配置脚本
# 适用平台：macOS (Darwin) 与 Ubuntu / Debian (Linux)
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
export OS_TYPE="$(uname -s)"

# ------------------------------------------------------------------------------
# 1. 初始化 SDKMAN 环境（若存在）
# ------------------------------------------------------------------------------
if [ -z "$SDKMAN_DIR" ]; then
    export SDKMAN_DIR="$HOME/.sdkman"
fi
if [ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]; then
    # shellcheck disable=SC1090
    source "$SDKMAN_DIR/bin/sdkman-init.sh"
fi

# ------------------------------------------------------------------------------
# 2. 探测与配置 JDK 17
# ------------------------------------------------------------------------------
DETECTED_JAVA=""

# 2.1 SDKMAN 中的 Java 17
if [ -d "$SDKMAN_DIR/candidates/java/17.0.1-open" ]; then
    DETECTED_JAVA="$SDKMAN_DIR/candidates/java/17.0.1-open"
elif [ -d "$SDKMAN_DIR/candidates/java" ]; then
    SDKMAN_JAVA17="$(find "$SDKMAN_DIR/candidates/java" -maxdepth 1 -name "17*" 2>/dev/null | head -n 1)"
    if [ -n "$SDKMAN_JAVA17" ] && [ -d "$SDKMAN_JAVA17" ]; then
        DETECTED_JAVA="$SDKMAN_JAVA17"
    fi
fi

# 2.2 macOS (Darwin) 系统探测
if [ -z "$DETECTED_JAVA" ] && [ "$OS_TYPE" = "Darwin" ]; then
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        MAC_JAVA17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
        if [ -n "$MAC_JAVA17" ] && [ -d "$MAC_JAVA17" ]; then
            DETECTED_JAVA="$MAC_JAVA17"
        fi
    fi
    if [ -z "$DETECTED_JAVA" ]; then
        if [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
            DETECTED_JAVA="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
        elif [ -d "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
            DETECTED_JAVA="/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
        fi
    fi
fi

# 2.3 Ubuntu / Linux 系统探测
if [ -z "$DETECTED_JAVA" ] && [ "$OS_TYPE" = "Linux" ]; then
    if [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
        DETECTED_JAVA="/usr/lib/jvm/java-17-openjdk-amd64"
    elif [ -d "/usr/lib/jvm/java-17-openjdk-arm64" ]; then
        DETECTED_JAVA="/usr/lib/jvm/java-17-openjdk-arm64"
    elif [ -d "/home/tiny/trebuchet-build/prebuilts/jdk/jdk17/linux-x86" ]; then
        DETECTED_JAVA="/home/tiny/trebuchet-build/prebuilts/jdk/jdk17/linux-x86"
    fi
fi

# 2.4 生效并导出 JAVA_HOME
if [ -n "$DETECTED_JAVA" ]; then
    export JAVA_HOME="$DETECTED_JAVA"
    export PATH="$JAVA_HOME/bin:$PATH"
elif [ -n "$JAVA_HOME" ] && [ -d "$JAVA_HOME" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# ------------------------------------------------------------------------------
# 3. 探测与配置 Android SDK
# ------------------------------------------------------------------------------
DETECTED_SDK=""

if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/platforms" ]; then
    DETECTED_SDK="$ANDROID_HOME"
elif [ -n "$ANDROID_SDK_ROOT" ] && [ -d "$ANDROID_SDK_ROOT/platforms" ]; then
    DETECTED_SDK="$ANDROID_SDK_ROOT"
elif [ "$OS_TYPE" = "Darwin" ] && [ -d "$HOME/Library/Android/sdk/platforms" ]; then
    DETECTED_SDK="$HOME/Library/Android/sdk"
elif [ "$OS_TYPE" = "Linux" ] && [ -d "$HOME/Android/Sdk/platforms" ]; then
    DETECTED_SDK="$HOME/Android/Sdk"
elif [ -d "/home/tiny/android-sdk/platforms" ]; then
    DETECTED_SDK="/home/tiny/android-sdk"
elif [ -d "$HOME/Library/Android/sdk" ]; then
    DETECTED_SDK="$HOME/Library/Android/sdk"
elif [ -d "$HOME/Android/Sdk" ]; then
    DETECTED_SDK="$HOME/Android/Sdk"
fi

if [ -n "$DETECTED_SDK" ]; then
    export ANDROID_HOME="$DETECTED_SDK"
    export ANDROID_SDK_ROOT="$DETECTED_SDK"
    
    # 自动写入 / 更新 local.properties
    if [ ! -f "$PROJECT_ROOT/local.properties" ] || ! grep -q "^sdk.dir=" "$PROJECT_ROOT/local.properties" 2>/dev/null; then
        echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_ROOT/local.properties"
    fi
fi

# ------------------------------------------------------------------------------
# 4. 探测与配置 Gradle
# ------------------------------------------------------------------------------
DETECTED_GRADLE=""

if [ -x "$PROJECT_ROOT/gradlew" ]; then
    DETECTED_GRADLE="$PROJECT_ROOT/gradlew"
elif [ -d "$SDKMAN_DIR/candidates/gradle/8.5/bin" ]; then
    export PATH="$SDKMAN_DIR/candidates/gradle/8.5/bin:$PATH"
    DETECTED_GRADLE="$SDKMAN_DIR/candidates/gradle/8.5/bin/gradle"
elif [ -d "/home/tiny/gradle/gradle-8.5/bin" ]; then
    export PATH="/home/tiny/gradle/gradle-8.5/bin:$PATH"
    DETECTED_GRADLE="/home/tiny/gradle/gradle-8.5/bin/gradle"
elif command -v gradle >/dev/null 2>&1; then
    DETECTED_GRADLE="$(command -v gradle)"
fi
export GRADLE_CMD="${DETECTED_GRADLE:-$PROJECT_ROOT/gradlew}"

# ------------------------------------------------------------------------------
# 5. 探测与配置 ADB
# ------------------------------------------------------------------------------
DETECTED_ADB=""

if command -v adb >/dev/null 2>&1; then
    DETECTED_ADB="$(command -v adb)"
elif [ -n "$ANDROID_HOME" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    DETECTED_ADB="$ANDROID_HOME/platform-tools/adb"
elif [ "$OS_TYPE" = "Darwin" ]; then
    if [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        DETECTED_ADB="$HOME/Library/Android/sdk/platform-tools/adb"
    elif [ -x "/opt/homebrew/bin/adb" ]; then
        DETECTED_ADB="/opt/homebrew/bin/adb"
    elif [ -x "/usr/local/bin/adb" ]; then
        DETECTED_ADB="/usr/local/bin/adb"
    fi
elif [ "$OS_TYPE" = "Linux" ]; then
    if [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
        DETECTED_ADB="$HOME/Android/Sdk/platform-tools/adb"
    elif [ -x "/home/tiny/trebuchet-build/prebuilts/runtime/adb" ]; then
        DETECTED_ADB="/home/tiny/trebuchet-build/prebuilts/runtime/adb"
    elif [ -x "/usr/bin/adb" ]; then
        DETECTED_ADB="/usr/bin/adb"
    fi
fi
export ADB="${DETECTED_ADB:-adb}"
