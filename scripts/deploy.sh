#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env_detect.sh"

echo "=== [1/6] 编译 MeeGo Launcher Debug APK ==="
cd "$PROJECT_ROOT"
"$GRADLE_CMD" assembleDebug --console=plain

APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK 不存在，请检查编译日志: $APK_PATH"
    exit 1
fi

echo "APK 构建成功: $APK_PATH"

echo "=== [2/6] 推送并安装到物理机 ==="
"$ADB" install -r -d "$APK_PATH"

echo "=== [3/6] 部署特权守护脚本（不改动 /system 分区）==="
# 【重要：绝不写 /system】
# 本机 /system 受 dm-verity 校验，改写会导致验证失败进而无法开机。
# 脚本与开机自启配置一律放在 /data/local/tmp，部署过程只做 push + chmod。
if [ -f "$SCRIPT_DIR/meego_daemon.sh" ]; then
    "$ADB" push "$SCRIPT_DIR/meego_daemon.sh" /data/local/tmp/meego_daemon.sh
    "$ADB" shell chmod 755 /data/local/tmp/meego_daemon.sh

    # 开机自启：依赖既有的 meego_daemon.rc（若设备上已存在）。
    # 不再尝试写入 /system/etc/init —— 那是 dm-verity 保护区，不能碰。
    HAS_RC=$("$ADB" shell 'test -f /system/etc/init/meego_daemon.rc && echo yes || echo no' 2>/dev/null | tr -d '\r')
    if [ "$HAS_RC" = "yes" ]; then
        echo "  检测到既有 meego_daemon.rc，开机自启可用"
    else
        echo "  ⚠️ 设备上无 meego_daemon.rc；守护进程需由启动器首次运行时拉起"
    fi

    # 启动顺序至关重要：先预置独立 watchdog，再起主进程。
    # 真机实测（Redmi K40 / Android 16）：init 在 oneshot service 结束时会清理
    # 本次启动派生出的全部进程 —— 即使 watchdog 已双重 setsid 脱离、PPID=1、
    # cgroup 与 PGid 均独立，仍会与主进程同归于尽；反之，若 watchdog 在
    # service 启动【之前】就已存在，则完全不受该清理影响。故必须先落定它。
    "$ADB" shell '
        for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null); do
            w=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 3p)
            [ "$w" = "__WATCHDOG" ] && exit 0
        done
        setsid /system/bin/sh -c "setsid /system/bin/sh /data/local/tmp/meego_daemon.sh __WATCHDOG >/dev/null 2>&1" >/dev/null 2>&1 &
    ' 2>/dev/null
    sleep 2

    # 主进程一律用 nohup 直接启动【本脚本】，不走 setprop ctl.start。
    # 原因：init 的 meego_daemon.rc 指向 /system/bin/meego_daemon.sh，而 /system
    # 受 dm-verity 保护无法更新，那里永远停留在首次刷入时的旧版本。经实测
    # ctl.start 拉起的正是那份旧脚本 —— 它不写 .alive 心跳、也没有 watchdog 与
    # 目录自愈修复，自愈一次就等于回退一次。
    # 此刻 watchdog 已预先独立存在（见上），所以这里手动拉起是安全的：
    # 不存在旧版「手动实例与 init 实例互相 kill -9」导致 service 被打成
    # stopped 的问题。脚本开头「单实例保障」会清掉上一轮遗留的主进程进程。
    "$ADB" shell "nohup /data/local/tmp/meego_daemon.sh >/data/local/tmp/daemon.log 2>&1 &"

    sleep 3
    # 轮询确认业务主进程（argv3 != __WATCHDOG）确实起来了
    DAEMON_READY=0
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        if "$ADB" shell 'for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null); do w=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 3p); [ "$w" = "__WATCHDOG" ] || { echo ok; break; }; done' 2>/dev/null | grep -q ok; then
            DAEMON_READY=1
            break
        fi
        sleep 1
    done
    if [ "$DAEMON_READY" -eq 1 ]; then
        echo "✅ 特权守护进程已就绪（watchdog + 主进程）"
    else
        echo "⚠️ 守护进程未在 10s 内就绪，请检查 /data/local/tmp/daemon.log"
    fi
fi

# 4. 配置与授予特权/使用情况统计权限
"$ADB" shell appops set com.meego.launcher GET_USAGE_STATS allow 2>/dev/null || true
"$ADB" shell pm grant com.meego.launcher android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
"$ADB" shell pm grant com.meego.launcher android.permission.DUMP 2>/dev/null || true
"$ADB" shell pm grant com.meego.launcher android.permission.REAL_GET_TASKS 2>/dev/null || true

echo "=== [4/6] 强制停止历史进程并冷启动应用 ==="
"$ADB" shell am force-stop com.meego.launcher
"$ADB" shell am start -n com.meego.launcher/.MainActivity

echo "=== [5/6] 验证进程存活与前台状态 ==="
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

# 6. 守护进程自愈能力验证：杀掉业务主进程，确认 watchdog 能把它拉回来
# 这是本版修复的核心保证（v1.8.35），部署后自动验证一次。
echo "=== [6/6] 验证守护进程自愈（杀主进程后应自动恢复）==="
MAIN_PID_BEFORE=$("$ADB" shell 'for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null); do w=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 3p); [ "$w" = "__WATCHDOG" ] || { echo "$p"; break; }; done' 2>/dev/null | tr -d '\r' | grep -E '^[0-9]+' | head -1)
if [ -n "$MAIN_PID_BEFORE" ]; then
    echo "  业务主进程 PID=$MAIN_PID_BEFORE，主动 kill -9 模拟崩溃…"
    "$ADB" shell "kill -9 $MAIN_PID_BEFORE" 2>/dev/null || true
    RECOVERED=0
    for _ in $(seq 1 25); do
        sleep 1
        NEW_PID=$("$ADB" shell 'for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null); do w=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 3p); [ "$w" = "__WATCHDOG" ] || { echo "$p"; break; }; done' 2>/dev/null | tr -d '\r' | grep -E '^[0-9]+' | head -1)
        if [ -n "$NEW_PID" ] && [ "$NEW_PID" != "$MAIN_PID_BEFORE" ]; then
            echo "✅ 自愈成功：watchdog 已在 ~${_}s 内拉起新主进程 PID=$NEW_PID"
            RECOVERED=1
            break
        fi
    done
    if [ "$RECOVERED" -eq 0 ]; then
        echo "❌ 自愈失败：25s 内未恢复，请检查 watchdog 是否存活"
    fi
else
    echo "ℹ️ 未检测到业务主进程，跳过自愈验证"
fi
