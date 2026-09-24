#!/usr/bin/env bash
export JAVA_HOME="/home/tiny/trebuchet-build/prebuilts/jdk/jdk17/linux-x86"
export PATH="$JAVA_HOME/bin:/home/tiny/gradle/gradle-8.5/bin:$PATH"
export ANDROID_HOME="/home/tiny/android-sdk"
cd /home/tiny/dsh-workspace/test-android-launcher

gradle assembleDebug --console=plain > build.log 2>&1
echo "BUILD_EXIT_CODE=$?" >> build.log
