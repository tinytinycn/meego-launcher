#!/usr/bin/env python3
"""
解析 `dumpsys gfxinfo <pkg> framestats` 的逐帧分段耗时。

为什么需要它（实测结论，见 docs/perf/ 与 MEEGO_LAUNCHER_TECH_SPEC.md）：
`dumpsys gfxinfo` 汇总里的 "50th percentile: 12ms / Janky frames: 17%" 把
**present/swap 等待时间**也计入了帧时长，并且以"是否错过 deadline"判定 jank。
在本机（Redmi K40 / LineageOS 23 / Android 16，120Hz）实测：

  - MeeGo Launcher : 真实 UI+绘制工作仅 ~0.22ms，端到端 Frame Start->Completed
                     P50 约 7.3ms / P95 约 8.1ms，**已在 8.33ms 预算内**；
  - Trebuchet     : 真实 UI+绘制工作约 0.38ms，端到端 P50 约 2.6ms。

两者的绘制工作量差异极小，差距几乎全部落在 IssueDrawCommandsStart->FrameCompleted
这一段（即 buffer 提交 / SurfaceFlinger 合成 / 送显），属于设备与合成管线特性，
不是 Compose 重组或图标绘制可以优化的部分。

因此判断"滑动是否真的流畅"应看本脚本输出的：
  - `frame_total`（FrameStartTime->FrameCompleted）：端到端帧时长
  - `ui_work`   （PerformTraversalsStart->IssueDrawCommandsStart）：应用真实主线程开销
  - `present`   （IssueDrawCommandsStart->FrameCompleted）：送显等待
  - `FrameInterval` 反推出的实际刷新率（用于确认设备是否真在 120Hz）

用法： python3 scripts/analyze_framestats.py <framestats.txt> [...]
"""
import sys


def load(path):
    with open(path, "r", errors="replace") as f:
        lines = f.read().splitlines()
    header_positions = [
        i for i, line in enumerate(lines) if line.startswith("Flags,FrameTimelineVsyncId")
    ]
    best = None
    for hp in header_positions:
        header = lines[hp].split(",")
        rows = []
        for line in lines[hp + 1:]:
            parts = line.split(",")
            if len(parts) < len(header):
                break
            # FrameTimelineVsyncId == 0 表示无有效帧
            if parts[1] == "0":
                continue
            rows.append(parts)
        if best is None or len(rows) > len(best[1]):
            best = (rows, header)
    return best or ([], [])


def percentile(sorted_values, pct):
    if not sorted_values:
        return 0.0
    n = len(sorted_values)
    return sorted_values[min(n - 1, int(n * pct / 100))]


def analyze(path):
    rows, header = load(path)
    if not rows:
        return None
    idx = {name: i for i, name in enumerate(header)}

    def col(row, name):
        try:
            return int(row[idx[name]])
        except (KeyError, IndexError, ValueError):
            return 0

    frame_total, ui_work, present, latency, traversals = [], [], [], [], []
    intervals = []
    for row in rows:
        fst = col(row, "FrameStartTime")
        fc = col(row, "FrameCompleted")
        pt = col(row, "PerformTraversalsStart")
        idc = col(row, "IssueDrawCommandsStart")
        his = col(row, "HandleInputStart")
        fi = col(row, "FrameInterval")
        if fst and fc:
            frame_total.append((fc - fst) / 1e6)
        if pt and idc:
            ui_work.append((idc - pt) / 1e6)
        if idc and fc:
            present.append((fc - idc) / 1e6)
        if his and pt:
            latency.append((pt - his) / 1e6)
        if pt and col(row, "DrawStart"):
            traversals.append((col(row, "DrawStart") - pt) / 1e6)
        if fi:
            intervals.append(fi / 1e6)

    return {
        "count": len(rows),
        "frame_total": frame_total,
        "ui_work": ui_work,
        "present": present,
        "latency": latency,
        "traversals": traversals,
        "hz": (1000.0 / percentile(sorted(intervals), 50)) if intervals else 0.0,
    }


def fmt(values, label):
    if not values:
        return f"  {label:44s} (无数据)"
    v = sorted(values)
    return (
        f"  {label:44s} n={len(v):4d} "
        f"P50={percentile(v, 50):7.2f}ms  P90={percentile(v, 90):7.2f}ms  "
        f"P95={percentile(v, 95):7.2f}ms  max={v[-1]:7.2f}ms"
    )


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    for path in sys.argv[1:]:
        stats = analyze(path)
        print(f"=== {path} ===")
        if stats is None:
            print("  未解析到有效帧（应用可能未在绘制，或 framestats 被系统回收）")
            continue
        print(f"  采样帧数: {stats['count']}")
        print(f"  实测刷新率 (FrameInterval 反推): {stats['hz']:.1f} Hz")
        print(fmt(stats["traversals"], "UI: PerformTraversals->DrawStart (布局)"))
        print(fmt(stats["ui_work"], "UI: PerformTraversals->IssueDraw (主线程总)"))
        print(fmt(stats["present"], "present: IssueDraw->FrameCompleted (送显)"))
        print(fmt(stats["frame_total"], "frame_total: FrameStart->Completed (端到端)"))
        print(fmt(stats["latency"], "输入->遍历 延迟"))
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
