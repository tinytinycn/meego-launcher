#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env_detect.sh"

# ---------------------------------------------------------------------------
# 守护链进程探测助手
#
# 【为什么需要收敛为函数】此前三处都直接用 `pgrep -f meego_daemon.sh` 取 PID。
# 但该模式会同时匹配到 watchdog 与 monitor_cmd_queue / process_fifo /
# monitor_current_app 三个后台子 shell（它们 fork 自同一脚本、cmdline 完全相同），
# 于是同一条命令会返回 6 行 —— 直接后果：
#   · 第 [6/7] 步把 6 个 PID 全部打印出来，输出错乱；
#   · 第 [7/7] 步的「业务主进程 PID」被多行字符串污染，出现乱码。
# 现统一改为「cmdline 第 3 段判定」，且输出**收敛为单个纯数字**。
#
# ⚠️ 【本文件一条硬性书写规范：变量后紧跟中文必须写成 ${VAR}】
# macOS 自带的是 GNU bash 3.2.57（2007 年，Apple 因 GPLv3 不再更新）。
# 它有一个**解析器缺陷**：当 `$VAR` 后面紧跟一个多字节（UTF-8）字符时，
# 变量名边界会被误判 —— 变量被当成未定义而展开为空，紧跟的那个中文字符
# 还会被咬掉首字节，最终在终端上显示成乱码 `��`。
#
# 最小复现（本机 bash 3.2.57 实测）：
#     V=11474
#     echo "PID=$V，watchdog"      →  PID=\xbc\x8cwatchdog          ← 变量被吞
#     echo "PID=${V}，watchdog"    →  PID=11474\xef\xbc\x8cwatchdog  ← 正确
#
# 本项目所有脚本都往终端打印中文，故**凡变量后接中文一律加花括号**。
# 对照：第 [6/7] 步之所以一直正常，正是因为它写的是 `${DAEMON_MAIN:-?}`。
# ---------------------------------------------------------------------------

# 主进程 PID。首选权威来源：守护脚本自己用 `$$` 写入的 pidfile
# （见 meego_daemon.sh 第 228 行 —— 该行只有主进程会执行，watchdog 在
#  第 171 行进入无限循环、永不抵达，故该文件即主进程的精确身份）。
# 心跳/pidfile 缺失或进程已死时，退回按 cmdline 判定。
#
# 【判据必须同时校验 argv2】只校验 argv3 会把
# `setsid /system/bin/sh -c "... meego_daemon.sh ..."` 的外层包装进程
# 误判成主进程（它 argv3 为空、cmdline 里含脚本路径，但 argv2 是 `-c`），
# 后果是「以为主进程已就绪」而跳过拉起。同 meego_boot.sh 的判据。
daemon_main_pid() {
    local p
    p=$("$ADB" shell 'cat /data/local/tmp/meego_daemon.pid 2>/dev/null' 2>/dev/null \
            | tr -d '\r' | awk 'NF==1 && $1 ~ /^[0-9]+$/ {print $1; exit}')
    if [ -n "$p" ] && "$ADB" shell "test -d /proc/$p" 2>/dev/null; then
        echo "$p"
        return 0
    fi
    # 兜底：argv2 为脚本路径（排除 `-c` 包装进程）、argv3 非 watchdog 的首个匹配
    "$ADB" shell '
        for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null); do
            a2=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 2p)
            a3=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 3p)
            if [ "$a2" = "/data/local/tmp/meego_daemon.sh" ] && [ "$a3" != "__WATCHDOG" ]; then
                echo "$p"; break
            fi
        done
    ' 2>/dev/null | tr -d '\r' | awk 'NF==1 && $1 ~ /^[0-9]+$/ {print $1; exit}'
}

# watchdog PID（无则输出空）
daemon_watchdog_pid() {
    "$ADB" shell '
        for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null); do
            a2=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 2p)
            a3=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 3p)
            if [ "$a2" = "/data/local/tmp/meego_daemon.sh" ] && [ "$a3" = "__WATCHDOG" ]; then
                echo "$p"; break
            fi
        done
    ' 2>/dev/null | tr -d '\r' | awk 'NF==1 && $1 ~ /^[0-9]+$/ {print $1; exit}'
}

# 同脚本所有进程数（主进程 + watchdog + 三个 monitor 子 shell）
daemon_proc_count() {
    "$ADB" shell 'pgrep -f "meego_daemon.sh" 2>/dev/null | wc -l' 2>/dev/null | tr -cd '0-9'
}

# ===========================================================================
# 前置检查：设备连接 + Root 可用性
#
# 【为什么必须在编译之前检查 Root】
# 特权守护进程**必须以 uid 0 运行** —— Task Snapshot 直读桥所需权限为
#   allOf = {MANAGE_ACTIVITY_TASKS, READ_FRAME_BUFFER}
# 二者皆 signature 级，只有 root 能满足（实测 uid=10209 直接 SecurityException）。
#
# 而部署流程是用 `adb shell` 拉起守护进程的，故「`adb shell` 是否 root」
# 直接决定成败：
#   `adb root` 可用（LineageOS userdebug/eng）→ adb shell 即 uid 0 → 正常
#   仅 Magisk su 可用而 adb 非 root      → adb shell 是 shell uid(2000)
#                                          → 守护进程非 root → 桥静默失效
#
# 【为何要显式拦下，而不是让它自然失败】
# 非 root 时桥会返回 `{"ok":false,...}`，守护脚本按设计**静默回退**到纯
# screencap 管线 —— 于是整套部署"看起来成功"，只是多任务卡片始终是整屏截图、
# 永远拿不到纯净的任务窗口帧。这种"安静地降级"极难归因，故此处 fail fast，
# 并直接给出两种修法。
# ===========================================================================
echo "=== 前置检查：设备连接与 Root 权限 ==="
DEV_COUNT=$("$ADB" devices 2>/dev/null | grep -c -E '\bdevice$' || echo 0)
if [ "$DEV_COUNT" -eq 0 ]; then
    echo "❌ 未检测到已连接的设备。请先连接真机（USB 或无线调试），并确认 'adb devices' 能看到设备。"
    exit 1
fi
echo "  ✅ 已连接设备 $DEV_COUNT 台"

SHELL_UID=$("$ADB" shell 'id -u' 2>/dev/null | tr -d '\r' | tr -cd '0-9')
if [ "$SHELL_UID" = "0" ]; then
    echo "  ✅ adb shell 为 root（uid=0），特权守护链具备所需权限"
else
    echo "  ❌ adb shell 当前 uid=${SHELL_UID:-未知}，**不是 root**。"
    echo ""
    echo "     本项目特权守护链必须以 uid 0 运行（Task Snapshot 直读需要"
    echo "     MANAGE_ACTIVITY_TASKS + READ_FRAME_BUFFER，二者均为 signature 级）。"
    echo "     若强行继续，部署会「看起来成功」，但多任务卡片会静默退化为整屏截图。"
    echo ""
    echo "     请任选一种方式修复后重试："
    echo "       ① 执行 adb root（LineageOS userdebug/eng 构建支持）"
    echo "       ② 若为 user 构建仅有 Magisk：需改为经 su 拉起守护链，"
    echo "          当前 deploy.sh 尚未支持该路径"
    exit 1
fi

echo "=== [1/8] 编译 MeeGo Launcher Debug APK ==="
cd "$PROJECT_ROOT"
"$GRADLE_CMD" assembleDebug --console=plain

APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK 不存在，请检查编译日志: $APK_PATH"
    exit 1
fi

echo "APK 构建成功: $APK_PATH"

echo "=== [2/8] 推送并安装到物理机 ==="
"$ADB" install -r -d "$APK_PATH"

echo "=== [3/8] 编译并部署常驻 Task Snapshot 服务 + 直读桥 ==="
# ---------------------------------------------------------------------------------------
# 编译两个 dex：
#   1. TaskSnapService.java —— **本版主链路**，常驻 root 进程，对齐 Quickstep 取帧原理
#   2. TaskSnapBridge.java  —— 保留的一次性桥，仅供部署自检 / 排障兜底
#
# 【为什么必须在部署时编译，而不是提交预编译 dex】
#  dex 与设备 ROM 强绑定（ART 版本、hidden API 名单都会变）。提交二进制等于把
#  它焊死在某一次刷机上；现编现用则永远跟随当次 deploy 的 SDK。
#  TaskSnapService 尤其如此 —— 它大量使用反射访问 hidden API。
#
# 【服务缺失的后果（与旧 bridge 不同）】
#  旧 bridge 缺失只是「降级为 screencap」；而 v1.8.44 起 **TaskSnapshot 服务是
#  唯一画面来源**，缺失意味着多任务卡片只剩应用图标。故此处失败会明确告警。
# ---------------------------------------------------------------------------------------
SVC_DEX_LOCAL="$PROJECT_ROOT/.tmp-build/meego_svc.dex"
SVC_DEX_REMOTE="/data/local/tmp/meego_svc.dex"
BRIDGE_DEX_REMOTE="/data/local/tmp/meego_daemon_bridge.dex"

ANDROID_JAR=""
for cand in \
    "$ANDROID_HOME/platforms/android-35/android.jar" \
    "$ANDROID_HOME/platforms/android-36.1/android.jar" \
    "$ANDROID_HOME/platforms/android-36/android.jar" \
    "$ANDROID_HOME/platforms/android-34/android.jar"
do
    [ -f "$cand" ] && { ANDROID_JAR="$cand"; break; }
done
# 兜底：任意一个已安装的 platform
if [ -z "$ANDROID_JAR" ] && [ -d "$ANDROID_HOME/platforms" ]; then
    ANDROID_JAR=$(ls -d "$ANDROID_HOME"/platforms/*/ 2>/dev/null | sort -V -r \
        | while read -r d; do [ -f "$d/android.jar" ] && { echo "${d%/}/android.jar"; break; }; done)
fi
D8_BIN=$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V -r \
    | while read -r d; do [ -x "${d}d8" ] && { echo "${d}d8"; break; }; done)

if [ -z "$ANDROID_JAR" ] || [ -z "$D8_BIN" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "  ❌ 缺少 android.jar / d8 / javac，无法编译常驻快照服务"
    echo "     多任务卡片将只能显示应用图标（无真实画面）。请检查 Android SDK 环境。"
elif [ ! -f "$SCRIPT_DIR/TaskSnapService.java" ]; then
    echo "  ❌ 未找到 scripts/TaskSnapService.java —— 多任务卡片将无真实画面"
else
    rm -rf "$PROJECT_ROOT/.tmp-build/svc"
    mkdir -p "$PROJECT_ROOT/.tmp-build/svc/classes" "$PROJECT_ROOT/.tmp-build/svc/out"
    if "$JAVA_HOME/bin/javac" -nowarn -source 8 -target 8 \
         -bootclasspath "$ANDROID_JAR" \
         -classpath "$ANDROID_JAR" \
         -d "$PROJECT_ROOT/.tmp-build/svc/classes" \
         "$SCRIPT_DIR/TaskSnapService.java" >/dev/null 2>&1 \
       && "$D8_BIN" --min-api 28 --lib "$ANDROID_JAR" \
         --output "$PROJECT_ROOT/.tmp-build/svc/out" \
         "$PROJECT_ROOT/.tmp-build/svc/classes/"*.class >/dev/null 2>&1
    then
        cp "$PROJECT_ROOT/.tmp-build/svc/out/classes.dex" "$SVC_DEX_LOCAL"
        "$ADB" push "$SVC_DEX_LOCAL" "$SVC_DEX_REMOTE" >/dev/null
        # root:root 755 是刻意形态：App uid 加载 dex 会被 ART 的
        # "Writable dex file ... is not allowed" 拒绝，而本服务恰恰
        # 只该由 root 侧守护脚本拉起。
        "$ADB" shell "chown root:root $SVC_DEX_REMOTE; chmod 755 $SVC_DEX_REMOTE"
        echo "  ✅ 常驻 TaskSnapService 已部署 ($SVC_DEX_REMOTE)"
    else
        echo "  ❌ 常驻快照服务编译失败 —— 多任务卡片将只能显示应用图标"
    fi
fi

# 旧的一次性桥：仅保留用于部署自检与排障，不再是主链路
if [ -f "$SCRIPT_DIR/TaskSnapBridge.java" ] && [ -n "$ANDROID_JAR" ] && [ -n "$D8_BIN" ]; then
    rm -rf "$PROJECT_ROOT/.tmp-build/bridge"
    mkdir -p "$PROJECT_ROOT/.tmp-build/bridge/classes" "$PROJECT_ROOT/.tmp-build/bridge/out"
    if "$JAVA_HOME/bin/javac" -nowarn -source 17 -target 17 \
         -classpath "$ANDROID_JAR" \
         -d "$PROJECT_ROOT/.tmp-build/bridge/classes" \
         "$SCRIPT_DIR/TaskSnapBridge.java" >/dev/null 2>&1 \
       && "$D8_BIN" --min-api 28 --lib "$ANDROID_JAR" \
         --output "$PROJECT_ROOT/.tmp-build/bridge/out" \
         "$PROJECT_ROOT/.tmp-build/bridge/classes/TaskSnapBridge.class" >/dev/null 2>&1
    then
        "$ADB" push "$PROJECT_ROOT/.tmp-build/bridge/out/classes.dex" "$BRIDGE_DEX_REMOTE" >/dev/null
        "$ADB" shell "chown root:root $BRIDGE_DEX_REMOTE; chmod 755 $BRIDGE_DEX_REMOTE"
        echo "  ✅ 一次性 TaskSnapBridge 已部署（自检/排障兜底）"
    else
        echo "  ⚠️ 一次性 bridge 编译失败（不影响主链路）"
    fi
else
    echo "  ℹ️ 跳过一次性 bridge（仅自检/排障需要）"
fi

echo "=== [4/8] 部署特权守护脚本（全部落在 /data 分区）==="
# 【部署位置：一律 /data，不写真实分区】
# 脚本与开机自启配置都放 /data/local/tmp 与 /data/adb，部署过程只做 push + chmod，
# 因此可直接 adb install 迭代、无需重刷。
# 真实分区当前以只读挂载（/proc/mounts: /dev/block/dm-1 / ext4 ro），
# 直接 remount,rw 后写真实分区不是受支持路径；
# 若将来确需改动 /system 内容（例如把启动器装成 privapp），走 Magisk 模块叠加。
if [ -f "$SCRIPT_DIR/meego_daemon.sh" ]; then
    "$ADB" push "$SCRIPT_DIR/meego_daemon.sh" /data/local/tmp/meego_daemon.sh
    "$ADB" shell chmod 755 /data/local/tmp/meego_daemon.sh

    # 开机自启：依赖既有的 meego_daemon.rc（若设备上已存在）。
    # 不再尝试写入 /system/etc/init —— 真实分区只读，需走 Magisk 模块叠加。
    # -----------------------------------------------------------------------
    # 开机自启：安装 Magisk service.d 钩子
    #
    # 【为什么用 service.d 而不是自建 Magisk 模块】
    # Magisk 官方为「开机跑一个脚本」这一需求提供了现成钩子：
    #   /data/adb/post-fs-data.d/   极早期（zygote 之前）
    #   /data/adb/service.d/        late_start service 阶段  ← 本用途
    # 二者都是「放进目录即生效、无需 module.prop」的通用脚本位，正是为这种
    # 场景设计的。自建模块要多维护 module.prop / 版本号 / updateJson 一整套
    # 打包元数据，而本项目的守护进程**不覆盖 /system 任何文件**，没有任何
    # 需要靠模块挂载才能实现的东西 —— 纯粹是给「跑个脚本」套一层壳。
    # 故选择 service.d：一处 push、无元数据、随 Magisk 版本升级自然存活。
    #
    # 【为什么钩子里要包一层 `&` 并且等 boot_completed】
    # service.d 在 late_start service 阶段执行，那时 system_server 可能尚未
    # 就绪，而守护脚本依赖 `dumpsys` / `settings`。钩子先等 sys.boot_completed
    # 再拉起；等待期间必须后台化，否则会把 Magisk 的开机流程一起拖住。
    # 等待逻辑本体在 meego_boot.sh 内（--wait-boot），此处只做安装。
    #
    # 【为什么 /system/etc/init/*.rc 不再是主路径】
    # 本机根本不存在 meego_daemon.rc（该 .rc 从未随构建刷入）——
    # 那条路在本设备上从未成立。下面的探测仅作信息展示。
    # -----------------------------------------------------------------------
    HAS_RC=$("$ADB" shell 'test -f /system/etc/init/meego_daemon.rc && echo yes || echo no' 2>/dev/null | tr -d '\r')
    if [ "$HAS_RC" = "yes" ]; then
        echo "  检测到既有 meego_daemon.rc（init 路径可用）"
    else
        echo "  设备上无 meego_daemon.rc（该 .rc 从未随构建刷入，属预期）"
    fi

    if [ -f "$SCRIPT_DIR/meego_boot.sh" ]; then
        "$ADB" push "$SCRIPT_DIR/meego_boot.sh" /data/local/tmp/meego_boot.sh >/dev/null
        "$ADB" shell chmod 755 /data/local/tmp/meego_boot.sh

        # Magisk service.d 钩子：**直接放同一份 meego_boot.sh**，不生成包装脚本。
        # 脚本靠 `$0` 落在 /data/adb/service.d/ 下来自动判定「这是开机路径，
        # 需先等 boot_completed」（见其第 0 节）。这样「启动顺序」这一关键逻辑
        # 在全项目只有一份实现，deploy 手动拉起与开机自动拉起不可能漂移。
        MAGISK_SERVICE_D="/data/adb/service.d"
        HAS_MAGISK=$("$ADB" shell "su -c 'test -d /data/adb && echo yes || echo no'" 2>/dev/null | tr -d '\r')
        if [ "$HAS_MAGISK" = "yes" ]; then
            # /data/adb 属 root，需 su 写入；从 /data/local/tmp 中转以免二次 push
            if "$ADB" shell "su -c 'mkdir -p $MAGISK_SERVICE_D && cp -f /data/local/tmp/meego_boot.sh $MAGISK_SERVICE_D/meego_boot.sh && chown root:root $MAGISK_SERVICE_D/meego_boot.sh && chmod 755 $MAGISK_SERVICE_D/meego_boot.sh'" >/dev/null 2>&1; then
                echo "  ✅ 已安装 Magisk 开机自启钩子（$MAGISK_SERVICE_D/meego_boot.sh）"
            else
                echo "  ⚠️ 安装开机钩子失败（需 Magisk su）；本次仍可用，但重启后不自启"
            fi
        else
            echo "  ⚠️ 未检测到 Magisk（/data/adb 不存在），跳过开机自启安装"
        fi
    else
        echo "  ⚠️ 未找到 meego_boot.sh，跳过拉起器与开机自启安装"
    fi

    # -----------------------------------------------------------------------
    # 立即拉起当前会话的守护链
    #
    # 复用与开机钩子**完全相同**的 meego_boot.sh（不带 --wait-boot，因为现在
    # 系统已就绪），确保「本次手动拉起」与「重启后自动拉起」走的是同一条代码
    # 路径 —— 这是本脚本抽公共拉起器的核心目的。
    # 该脚本幂等：watchdog 或主进程已就绪则自动跳过。
    # -----------------------------------------------------------------------
    if "$ADB" shell 'test -x /data/local/tmp/meego_boot.sh' 2>/dev/null; then
        "$ADB" shell "/data/local/tmp/meego_boot.sh" >/dev/null 2>&1 || true
    else
        # 拉起器缺失时的降级路径（与拉起器同样的顺序与判据，保证仍能工作）
        "$ADB" shell '
            W=""
            for p in $(pgrep -f "meego_daemon.sh" 2>/dev/null); do
                a2=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 2p)
                a3=$(tr "\0" "\n" < /proc/$p/cmdline 2>/dev/null | sed -n 3p)
                [ "$a2" = "/data/local/tmp/meego_daemon.sh" ] && [ "$a3" = "__WATCHDOG" ] && W=$p
            done
            [ -n "$W" ] || setsid /system/bin/sh -c "setsid /system/bin/sh /data/local/tmp/meego_daemon.sh __WATCHDOG >/dev/null 2>&1" >/dev/null 2>&1 &
        ' 2>/dev/null
        sleep 2
        "$ADB" shell "nohup /data/local/tmp/meego_daemon.sh >/data/local/tmp/daemon.log 2>&1 &"
    fi

    # 确认业务主进程与 watchdog 双双落定
    DAEMON_READY=0
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        if [ -n "$(daemon_main_pid)" ] && [ -n "$(daemon_watchdog_pid)" ]; then
            DAEMON_READY=1
            break
        fi
        sleep 1
    done
    if [ "$DAEMON_READY" -eq 1 ]; then
        echo "✅ 特权守护进程已就绪（主进程 PID=$(daemon_main_pid)、watchdog PID=$(daemon_watchdog_pid)）"
    else
        echo "⚠️ 守护进程未在 10s 内就绪，请检查 /data/local/tmp/daemon.log 与 meego_boot.log"
    fi
fi

# 4. 配置与授予特权/使用情况统计权限
"$ADB" shell appops set com.meego.launcher GET_USAGE_STATS allow 2>/dev/null || true
"$ADB" shell pm grant com.meego.launcher android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
"$ADB" shell pm grant com.meego.launcher android.permission.DUMP 2>/dev/null || true
"$ADB" shell pm grant com.meego.launcher android.permission.REAL_GET_TASKS 2>/dev/null || true

echo "=== [5/8] 强制停止历史进程并冷启动应用 ==="
"$ADB" shell am force-stop com.meego.launcher
"$ADB" shell am start -n com.meego.launcher/.MainActivity

echo "=== [6/8] 验证进程存活与前台状态 ==="
sleep 2
PID=$("$ADB" shell pidof com.meego.launcher 2>/dev/null | tr -d '\r' | awk '{print $1}')
DAEMON_MAIN=$(daemon_main_pid)
DAEMON_WD=$(daemon_watchdog_pid)
DAEMON_ALL=$(daemon_proc_count)

if [ -n "$PID" ]; then
    echo "✅ MeeGo Launcher 启动成功！PID: $PID"
else
    echo "⚠️ 警告：未检测到 com.meego.launcher 进程，请检查 logcat 报错！"
    "$ADB" logcat -d -t 50 | grep -E "AndroidRuntime|MeeGo" || true
    exit 1
fi

# 只报主进程与 watchdog 两个真正有意义的值；总进程数仅作参考。
# （此前直接打印 pgrep 全量输出，会把三个 monitor 子 shell 一起列出来，
#   表现为 6 行 PID 的错乱输出。）
if [ -n "$DAEMON_MAIN" ] || [ -n "$DAEMON_WD" ]; then
    echo "✅ 特权守护链就绪：主进程 PID=${DAEMON_MAIN:-?}、watchdog PID=${DAEMON_WD:-?}（同脚本进程共 ${DAEMON_ALL:-?} 个）"
    if [ -z "$DAEMON_WD" ]; then
        echo "  ⚠️ watchdog 缺失 —— 主进程崩溃后将无人拉起，请检查 meego_boot.log"
    fi
else
    echo "ℹ️ 特权守护脚本未以服务形式常驻，将依托持久化缓存与 UsageStats 兜底"
fi

# 6. 守护进程自愈能力验证：杀掉业务主进程，确认 watchdog 能把它拉回来
# 这是 v1.8.35 修复的核心保证，部署后自动验证一次。
echo "=== [7/8] 验证守护进程自愈（杀主进程后应自动恢复）==="
MAIN_PID_BEFORE=$(daemon_main_pid)
if [ -n "$MAIN_PID_BEFORE" ] && [ -n "$DAEMON_WD" ]; then
    echo "  业务主进程 PID=${MAIN_PID_BEFORE}，watchdog PID=${DAEMON_WD}；主动 kill -9 模拟崩溃…"
    "$ADB" shell "kill -9 $MAIN_PID_BEFORE" 2>/dev/null || true
    RECOVERED=0
    # 注意：循环变量**不能**用 `_` —— 它是 bash 的特殊变量（上一条命令的末参数），
    # 在下面的字符串插值里会展开成任意内容，此前正是它把耗时打印成了 `~]s`。
    elapsed=0
    while [ "$elapsed" -lt 25 ]; do
        sleep 1
        elapsed=$((elapsed + 1))
        NEW_PID=$(daemon_main_pid)
        if [ -n "$NEW_PID" ] && [ "$NEW_PID" != "$MAIN_PID_BEFORE" ]; then
            echo "✅ 自愈成功：watchdog 已在 ~${elapsed}s 内拉起新主进程 PID=$NEW_PID"
            RECOVERED=1
            break
        fi
    done
    if [ "$RECOVERED" -eq 0 ]; then
        echo "❌ 自愈失败：25s 内未恢复，请检查 watchdog 是否存活"
    fi
else
    echo "ℹ️ 业务主进程与 watchdog 未双双就绪，跳过自愈验证"
fi

# 7. 常驻 Task Snapshot 服务验证（v1.8.44 新增）
#
# 【为什么必须单独验证】自 v1.8.44 起，多任务卡片的**唯一**画面来源是这个常驻服务。
# 它若没起来，应用不会崩、不会报错，只是所有卡片安静地退化成应用图标 ——
# 属于典型的「安静地坏掉」，必须在部署阶段就 fail fast。
echo "=== [8/8] 验证常驻 Task Snapshot 服务（画面来源）==="
sleep 2   # 给守护主循环一次拉起服务的机会

SVC_STATUS=$("$ADB" shell '
    if [ ! -f /data/local/tmp/meego_svc.pid ]; then echo "NOPID"; exit; fi
    p=$(cat /data/local/tmp/meego_svc.pid 2>/dev/null | tr -d "\r\n ")
    if [ -n "$p" ] && [ -d "/proc/$p" ]; then echo "ALIVE:$p"; else echo "DEAD"; fi
' 2>/dev/null | tr -d '\r' | tail -1)

case "$SVC_STATUS" in
    ALIVE:*)
        SVC_PID_ON_DEV="${SVC_STATUS#ALIVE:}"
        echo "  ✅ 常驻快照服务存活（PID=${SVC_PID_ON_DEV}）"
        # 主动 ping 一次，确认 IPC 通路真的通（而不只是进程在）
        # 轮询等新行，不固定 sleep —— 抢跑会取到上一条旧响应（含 ok:true 却无 threads）
        PING_OUT=$("$ADB" shell '
            exec 3>/data/local/tmp/meego_snap_cmd 2>/dev/null || exit
            before=$(wc -l < /data/local/tmp/meego_snap_out 2>/dev/null || echo 0)
            echo "{\"cmd\":\"ping\"}" >&3
            exec 3>&-
            n=0
            while [ "$n" -lt 30 ]; do
                after=$(wc -l < /data/local/tmp/meego_snap_out 2>/dev/null || echo 0)
                if [ "$after" -gt "$before" ]; then
                    tail -n 1 /data/local/tmp/meego_snap_out
                    exit
                fi
                sleep 0.05
                n=$((n + 1))
            done
        ' 2>/dev/null | tr -d '\r' | tail -1)
        case "$PING_OUT" in
            *'"ok":true'*)
                echo "  ✅ 服务 IPC 正常响应：${PING_OUT}"
                ;;
            *)
                echo "  ⚠️ 服务进程在，但 ping 无响应：${PING_OUT:-<空>}"
                echo "     画面将无法更新，检查 /data/local/tmp/meego_snap_out"
                ;;
        esac
        ;;
    *)
        echo "  ❌ 常驻快照服务未运行（$SVC_STATUS）"
        echo "     多任务卡片将只能显示应用图标。请检查："
        echo "       · /data/local/tmp/meego_svc.dex 是否已部署"
        echo "       · adb shell 是否为 root（TaskSnapshot 需要签名级权限）"
        ;;
esac
