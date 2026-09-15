#!/usr/bin/env python3
"""
verify_parsetasks.py —— 用【真实坚果 dump】离线验证 ShellGateway.parseTasks 的解析逻辑。

为什么需要：`parseTasks` 是**静默失败**型 —— 正则写错时 `listTasks()` 只是返回空列表，
不报任何错，表现为「App 里点了按钮但什么都没发生」。
上机试错很难定位，对着真机 dump 离线跑一遍秒级定位。

⚠️ 本文件是 `ShellGateway.kt` 里 `parseTasks` 的**逐字镜像**，
   两边的正则与状态机必须同步改。

用法：
  python tools/verify_parsetasks.py <dump文件> [期望的关键字...]
  python tools/verify_parsetasks.py .ref/_diag/nt_act2.txt calculator
"""
import re
import sys

# --- 必须与 ShellGateway.kt 逐字一致 -----------------------------------------
# ⚠️ re.ASCII —— Kotlin 的 \w 是 ASCII-only，Python 默认含 Unicode，不加会不一致
FLAGS = re.ASCII
RX_DISPLAY = re.compile(r"Display #(\d+)", FLAGS)
RX_STACK = re.compile(r"^\s*Stack #(\d+):\s*type=\S+\s+mode=(\S+)", FLAGS)
RX_TASK_ID = re.compile(r"^\s*Task id #(\d+)\s*$", FLAGS)
RX_TASK_RECORD = re.compile(r"\* TaskRecord\{[^}#]*#(\d+)\s+A=([\w.]+)", FLAGS)
RX_BOUNDS_RECT = re.compile(r"mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", FLAGS)
RX_BOUNDS_BRACKET = re.compile(r"bounds=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]", FLAGS)


def parse_tasks(dump: str) -> list:
    """镜像 Kotlin 的 parseTasks（**逐行**处理，与 lineSequence 一致）。"""
    out, seen = [], set()
    display_id, stack_mode = -1, "?"
    pending_id, pending_bounds = -1, None

    for line in dump.splitlines():
        m = RX_DISPLAY.search(line)
        if m:
            display_id = int(m.group(1)) if m.group(1).isdigit() else -1
            pending_id, pending_bounds = -1, None
            continue

        m = RX_STACK.search(line)
        if m:
            stack_mode = m.group(2)
            continue

        m = RX_TASK_ID.search(line)
        if m:
            pending_id = int(m.group(1)) if m.group(1).isdigit() else -1
            pending_bounds = None
            continue

        if pending_id >= 0 and pending_bounds is None:
            bm = RX_BOUNDS_RECT.search(line) or RX_BOUNDS_BRACKET.search(line)
            if bm:
                pending_bounds = tuple(int(bm.group(i)) for i in range(1, 5))
                continue

        m = RX_TASK_RECORD.search(line)
        if m:
            tid = int(m.group(1)) if m.group(1).isdigit() else None
            if tid is not None and tid == pending_id and tid not in seen:
                seen.add(tid)
                out.append(dict(task=tid, display=display_id, pkg=m.group(2),
                                mode=stack_mode, bounds=pending_bounds))
            pending_id, pending_bounds = -1, None

    return out


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    path = sys.argv[1]
    expects = sys.argv[2:]
    dump = open(path, encoding="utf-8", errors="replace").read()

    tasks = parse_tasks(dump)
    print(f"=== {path} ===")
    print(f"解析出 {len(tasks)} 个任务：\n")
    for t in tasks:
        b = t["bounds"]
        bs = f"[{b[0]},{b[1]}][{b[2]},{b[3]}]  {b[2]-b[0]}x{b[3]-b[1]}" if b else "(无 bounds)"
        print(f"  task {t['task']:<5} display {t['display']:<7} {t['mode']:<10} {bs:<28} {t['pkg']}")

    ok = True
    for e in expects:
        hit = any(e in t["pkg"] for t in tasks)
        ok &= hit
        print(f"\n  {'✓' if hit else '✗'} 期望出现 {e!r}")
    # 没有 bounds 的任务要报警（前缀别名的经典症状）
    nob = [t for t in tasks if t["bounds"] is None]
    if nob:
        ok = False
        print(f"\n  ✗ 有 {len(nob)} 个任务没解析到 bounds —— 解析链断了")
    print("\n" + ("★★ 通过" if ok else "✗ 有失败项"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
