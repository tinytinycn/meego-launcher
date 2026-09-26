#!/usr/bin/env bash
# ==============================================================================
# MeeGo Launcher — Application Grid 滑动流畅度基准脚本
# ==============================================================================
# 用途：在真机上对 Application Grid 做可复现的滚动流畅度基准测量，
#        并以 LineageOS 原生 Trebuchet (com.android.launcher3) 应用抽屉作为
#        同机同手势对照基线，输出 Total/Janky frames 与 P50/P90/P95/P99 帧时长。
#
# 依据：docs/GRID_SCROLL_PERFORMANCE_PLAN.md §3.5 / §4 步骤 1、步骤 6。
#
# 用法：
#   ./scripts/perf_grid.sh                     # 同时测 MeeGo + Trebuchet 对照
#   ./scripts/perf_grid.sh --pkg com.meego.launcher --label "after"
#   ./scripts/perf_grid.sh --iterations 12 --serial <adb-serial>
#
# 依赖：adb 已连接真机并授权（当前项目 scripts/env_detect.sh 会自动定位 adb）。
# 输出：docs/ 目录下的 perf_grid_<label>_<timestamp>.md，以及 raw gfxinfo 文本。
# ==============================================================================
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# env_detect.sh 会 source sdkman-init.sh，它在部分 shell 下引用未定义变量，
# 因此必须在 `set -u` 生效之前完成 source。
# shellcheck source=./env_detect.sh
source "$SCRIPT_DIR/env_detect.sh"
set -u

ADB_SERIAL="${ANDROID_SERIAL:-}"
PKG_DEFAULT="com.meego.launcher"
REF_PKG="com.android.launcher3"          # LineageOS Trebuchet 应用抽屉
LABEL="baseline"
ITERATIONS=10
LAUNCH_ACTIVITY=""                          # 可选：显式指定启动 Activity
KEEP_REFRESH=0                              # 1 = 测量后恢复原始刷新率

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)     ADB_SERIAL="$2"; shift 2 ;;
    --pkg)        PKG_DEFAULT="$2"; shift 2 ;;
    --ref)        REF_PKG="$2"; shift 2 ;;
    --label)      LABEL="$2"; shift 2 ;;
    --iterations) ITERATIONS="$2"; shift 2 ;;
    --activity)   LAUNCH_ACTIVITY="$2"; shift 2 ;;
    --keep-refresh) KEEP_REFRESH=1; shift ;;
    -h|--help)    sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then
    "$ADB" -s "$ADB_SERIAL" "$@"
  else
    "$ADB" "$@"
  fi
}

# --- 前置检查：设备在线 -------------------------------------------------------
if ! adb_cmd get-state >/dev/null 2>&1; then
  echo "错误：未检测到在线 adb 设备。请连接真机并接受 USB 调试授权后重试。" >&2
  exit 3
fi

OUTPUT_DIR="$PROJECT_ROOT/docs/perf"
mkdir -p "$OUTPUT_DIR"
TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"

# --- 记录并设置刷新率到 120Hz（120Hz 下的对比才有意义）----------------------
#
# 重要（实测结论）：本机 Redmi K40 / LineageOS 上，**只设 peak_refresh_rate 不够**，
# 只要 min_refresh_rate 仍为 60，mActiveSfDisplayMode 就会停留在 60Hz 模式，
# 此时应用虽然按 8.26ms 的节奏出帧，但每一帧都要排队等 16.6ms 的 vsync，
# 导致 dumpsys 把几乎所有帧判成 jank —— 那是"测量档位"造成的假象，不是应用慢。
# 因此必须 peak/min/user/secure 四处一起设，并强校验 mActiveSfDisplayMode。
ORIG_PEAK="$(adb_cmd shell -T settings get system peak_refresh_rate 2>/dev/null | tr -d '\r' || echo "")"
ORIG_MIN="$(adb_cmd shell -T settings get system min_refresh_rate 2>/dev/null | tr -d '\r' || echo "")"
ORIG_USER="$(adb_cmd shell -T settings get system user_refresh_rate 2>/dev/null | tr -d '\r' || echo "")"
ORIG_PREF="$(adb_cmd shell -T settings get secure preferred_refresh_rate 2>/dev/null | tr -d '\r' || echo "")"

set_refresh() {
  local rate="$1"
  adb_cmd shell settings put system peak_refresh_rate "$rate" >/dev/null 2>&1 || true
  adb_cmd shell settings put system min_refresh_rate "$rate" >/dev/null 2>&1 || true
  adb_cmd shell settings put system user_refresh_rate "$rate" >/dev/null 2>&1 || true
  adb_cmd shell settings put secure preferred_refresh_rate "$rate" >/dev/null 2>&1 || true
}

# 读取真正生效的 SF 显示模式刷新率（比 displayMetrics 更可信）
active_sf_refresh() {
  adb_cmd shell dumpsys display 2>/dev/null \
    | grep -m1 "mActiveSfDisplayMode" \
    | grep -oE "peakRefreshRate=[0-9.]+" | cut -d= -f2 || true
}

restore_refresh() {
  if [[ "$KEEP_REFRESH" == "1" ]]; then
    echo "保持当前刷新率设置不变。" >&2
    return
  fi
  [[ "$ORIG_PEAK" != "null" && -n "$ORIG_PEAK" ]] && adb_cmd shell settings put system peak_refresh_rate "$ORIG_PEAK" >/dev/null 2>&1 || true
  [[ "$ORIG_MIN"  != "null" && -n "$ORIG_MIN"  ]] && adb_cmd shell settings put system min_refresh_rate  "$ORIG_MIN"  >/dev/null 2>&1 || true
  [[ "$ORIG_USER" != "null" && -n "$ORIG_USER" ]] && adb_cmd shell settings put system user_refresh_rate "$ORIG_USER" >/dev/null 2>&1 || true
  [[ "$ORIG_PREF" != "null" && -n "$ORIG_PREF" ]] && adb_cmd shell settings put secure preferred_refresh_rate "$ORIG_PREF" >/dev/null 2>&1 || true
  return 0
}
trap restore_refresh EXIT

echo "== 正在将刷新率提升到 120Hz =="
set_refresh 120
sleep 3
ACTIVE_REFRESH="$(active_sf_refresh)"
echo "   mActiveSfDisplayMode peakRefreshRate = ${ACTIVE_REFRESH:-未知} Hz"
if [[ -z "$ACTIVE_REFRESH" ]] || awk -v r="$ACTIVE_REFRESH" 'BEGIN{exit !(r<90)}'; then
  cat >&2 <<EOF
错误：设备未真正切到 120Hz（当前 ${ACTIVE_REFRESH:-未知} Hz），本次数据不可用于 120Hz 对比。
  原因通常是 min_refresh_rate 仍为 60，或系统"高刷新率"开关处于关闭状态。
  请在 设置 > 显示与亮度 中开启高刷新率，或用 --keep-refresh 保持现状后按 60Hz 基线解读。
EOF
  exit 4
fi

# 保持系统动画开启（不通过关闭动画作弊）
adb_cmd shell settings put global animator_duration_scale 1 >/dev/null 2>&1 || true
adb_cmd shell settings put global transition_animation_scale 1 >/dev/null 2>&1 || true
adb_cmd shell settings put global window_animation_scale 1 >/dev/null 2>&1 || true

SCREEN_W=$(adb_cmd shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)
SCREEN_H=$(adb_cmd shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)
CENTER_X=$(( SCREEN_W / 2 ))
# 滑动起止点：避开顶部状态栏与底部手势条，在内容区中部做大幅往返
SWIPE_Y_START=$(( SCREEN_H * 30 / 100 ))
SWIPE_Y_END=$(( SCREEN_H * 75 / 100 ))

# 唤醒并解锁到桌面（不依赖 root）
adb_cmd shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb_cmd shell wm dismiss-keyguard >/dev/null 2>&1 || true

launch_pkg() {
  local pkg="$1"
  if [[ -n "$LAUNCH_ACTIVITY" ]]; then
    adb_cmd shell am start -W -n "$pkg/$LAUNCH_ACTIVITY" >/dev/null 2>&1 || true
  else
    adb_cmd shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  fi
  sleep 3   # 等待首屏稳定（图标预解码 + 首次组合完成），避免把冷启动计入基准
}

# 解析 dumpsys gfxinfo 的关键统计
# 注意：
#  - 必须用 `adb shell -T`（raw）避免 CRLF/回显污染；
#  - "Janky frames" 与 "Janky frames (legacy)" 两行只取前者（modern 判定）；
#  - 所有诊断性输出走 stderr，保证 stdout 只有一行统计，便于 $(...) 捕获。
parse_gfxinfo() {
  local raw="$1"
  tr -d '\r' < "$raw" | awk '
    # 形如 "Total frames rendered: 828"        -> 计数在 $NF
    # 形如 "Janky frames: 145 (17.51%)"        -> 计数在 $3
    # 形如 "Number Slow UI thread: 142"        -> 计数在 $NF
    function num(s) { gsub(/[^0-9]/,"",s); return s+0 }
    /Total frames rendered:/  { if (total == "") total=num($NF) }
    /^Janky frames:/         { if (janky == "") janky=num($3) }
    /^50th percentile:/      { if (p50 == "") p50=$NF }
    /^90th percentile:/      { if (p90 == "") p90=$NF }
    /^95th percentile:/      { if (p95 == "") p95=$NF }
    /^99th percentile:/      { if (p99 == "") p99=$NF }
    /Number Missed Vsync:/             { if (mvs  == "") mvs=num($NF) }
    /Number High input latency:/       { if (hil  == "") hil=num($NF) }
    /Number Slow UI thread:/           { if (sui  == "") sui=num($NF) }
    /Number Slow bitmap uploads:/      { if (sbu  == "") sbu=num($NF) }
    /Number Slow issue draw commands:/ { if (sidc == "") sidc=num($NF) }
    END {
      if (total == 0) {
        printf "MISSING MISSING MISSING MISSING MISSING MISSING MISSING MISSING MISSING MISSING MISSING\n"
        exit
      }
      jank_pct = janky * 100.0 / total
      printf "%d %d %.2f %s %s %s %s %d %d %d %d %d\n", \
        total, janky, jank_pct, p50, p90, p95, p99, mvs, hil, sui, sbu, sidc
    }
  '
}

run_measure() {
  local pkg="$1" tag="$2"
  local raw="${OUTPUT_DIR}/gfxinfo_${tag}_${TIMESTAMP}.txt"

  log "== 测量 [$tag] : $pkg （往返 ${ITERATIONS} 次） =="

  # 回到桌面后再单独拉起目标，保证目标应用确实在前台被测量
  adb_cmd shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
  sleep 2
  launch_pkg "$pkg"

  # 预热：两轮不计入，避免首屏组合/图标解码的一次性成本污染统计
  for _ in 1 2; do
    adb_cmd shell input swipe "$CENTER_X" "$SWIPE_Y_START" "$CENTER_X" "$SWIPE_Y_END" 260 >/dev/null 2>&1 || true
    adb_cmd shell input swipe "$CENTER_X" "$SWIPE_Y_END" "$CENTER_X" "$SWIPE_Y_START" 260 >/dev/null 2>&1 || true
  done
  sleep 1

  adb_cmd shell dumpsys gfxinfo "$pkg" reset >/dev/null 2>&1 || true
  sleep 1

  # 正式测量：N 轮大幅往返滑动
  for _ in $(seq 1 "$ITERATIONS"); do
    adb_cmd shell input swipe "$CENTER_X" "$SWIPE_Y_START" "$CENTER_X" "$SWIPE_Y_END" 300 >/dev/null 2>&1 || true
    adb_cmd shell input swipe "$CENTER_X" "$SWIPE_Y_END" "$CENTER_X" "$SWIPE_Y_START" 300 >/dev/null 2>&1 || true
  done
  # 等待惯性滑行完全收敛，避免把尾部 fling 帧漏掉
  sleep 3

  adb_cmd shell -T dumpsys gfxinfo "$pkg" > "$raw" 2>/dev/null || true
  # framestats：逐帧分段耗时，才是"端到端是否踩到 vsync 预算"的真实口径
  adb_cmd shell -T dumpsys gfxinfo "$pkg" framestats > "${raw%.txt}_framestats.txt" 2>/dev/null || true
  # 只把统计写到 stdout（供调用方捕获），诊断信息一律走 stderr
  parse_gfxinfo "$raw"
}

# 统一的诊断输出出口：始终写 stderr，绝不污染 $(...) 捕获
log() { echo "$@" >&2; }

echo "############################################################"
echo "# MeeGo Grid Scroll Performance Benchmark"
echo "# 时间: $TIMESTAMP   刷新率: ${ACTIVE_REFRESH:-?}Hz"
echo "# 屏幕: ${SCREEN_W}x${SCREEN_H}   往返次数: $ITERATIONS"
echo "############################################################"

# 1) 测本项目 MeeGo Launcher
MEEGO_STATS="$(run_measure "$PKG_DEFAULT" "meego")"

# 2) 测 Trebuchet (LineageOS 原生应用抽屉) 作为同机对照
REF_STATS=""
if [[ "$REF_PKG" != "none" ]]; then
  REF_STATS="$(run_measure "$REF_PKG" "trebuchet")" || true
fi

# --- 生成 Markdown 报告 ------------------------------------------------------
REPORT="${OUTPUT_DIR}/perf_grid_${LABEL}_${TIMESTAMP}.md"
{
  echo "# Grid 滑动流畅度基准报告 (${LABEL})"
  echo
  echo "- 生成时间: $(date +"%Y-%m-%d %H:%M:%S")"
  echo "- 设备: $(adb_cmd shell -T getprop ro.product.model 2>/dev/null | tr -d '\r') / Android $(adb_cmd shell -T getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  echo "- 测量时 renderFrameRate: ${ACTIVE_REFRESH:-未知} Hz"
  echo "- 手势: 同一屏内 ${SWIPE_Y_START}px→${SWIPE_Y_END}px 往返 ${ITERATIONS} 轮 (300ms/次)"
  echo
  echo "## 汇总"
  echo
  echo "| 指标 | MeeGo (${PKG_DEFAULT}) | Trebuchet (${REF_PKG}) |"
  echo "|---|---|---|"
  if [[ -n "$REF_STATS" ]]; then
    echo "| Total frames | $(echo "$MEEGO_STATS" | awk '{print $1}') | $(echo "$REF_STATS" | awk '{print $1}') |"
    echo "| Janky frames | $(echo "$MEEGO_STATS" | awk '{print $2}') | $(echo "$REF_STATS" | awk '{print $2}') |"
    echo "| Janky % | $(echo "$MEEGO_STATS" | awk '{print $3}') | $(echo "$REF_STATS" | awk '{print $3}') |"
    echo "| P50 (ms) | $(echo "$MEEGO_STATS" | awk '{print $4}') | $(echo "$REF_STATS" | awk '{print $4}') |"
    echo "| P90 (ms) | $(echo "$MEEGO_STATS" | awk '{print $5}') | $(echo "$REF_STATS" | awk '{print $5}') |"
    echo "| P95 (ms) | $(echo "$MEEGO_STATS" | awk '{print $6}') | $(echo "$REF_STATS" | awk '{print $6}') |"
    echo "| P99 (ms) | $(echo "$MEEGO_STATS" | awk '{print $7}') | $(echo "$REF_STATS" | awk '{print $7}') |"
    echo "| Missed Vsync | $(echo "$MEEGO_STATS" | awk '{print $8}') | $(echo "$REF_STATS" | awk '{print $8}') |"
    echo "| Slow UI thread | $(echo "$MEEGO_STATS" | awk '{print $10}') | $(echo "$REF_STATS" | awk '{print $10}') |"
    echo "| Slow issue draw cmds | $(echo "$MEEGO_STATS" | awk '{print $12}') | $(echo "$REF_STATS" | awk '{print $12}') |"
  else
    echo "| Total frames | $(echo "$MEEGO_STATS" | awk '{print $1}') | - |"
    echo "| Janky frames | $(echo "$MEEGO_STATS" | awk '{print $2}') | - |"
    echo "| Janky % | $(echo "$MEEGO_STATS" | awk '{print $3}') | - |"
    echo "| P95 (ms) | $(echo "$MEEGO_STATS" | awk '{print $6}') | - |"
    echo "| P99 (ms) | $(echo "$MEEGO_STATS" | awk '{print $7}') | - |"
  fi
  echo
  echo "## 目标阈值 (方案 §1)"
  echo "- 帧时长 P95 ≤ 12ms、P99 ≤ 16ms、jank 比例 ≤ 3%"
  echo
  echo "原始 gfxinfo: 见同目录 gfxinfo_*.txt"
} > "$REPORT"

echo ""
echo "== 报告已生成: $REPORT =="
cat "$REPORT"
