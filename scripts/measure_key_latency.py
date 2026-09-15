#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★ AS3b 判据 3 —— **按键 → 真正写进串口** 的延迟分布（真人按键后跑）

## 它为什么存在（★ 这条判据不是原方案的一部分，是我们自己加的）

AS3b 原方案的判据只有一条：「串口争抢 = 0」。
但 **"争抢 = 0" 可以用"让按键变慢"来换到** —— 只验争抢，等于允许把体验改坏。

⇒ 所以加了这条：**按键到写入的延迟 P95 必须 ≤ 200 ms**。

★ 而它当场就抓出了 AS3b **自己引入**的一条回退：
  亮度「补发」去 `exec` 抢端口锁，**而它自己的流正持着那把锁**
  ⇒ `ReentrantLock` 同线程可重入"成功"，但**文件锁不行**（同 JVM 抛 `OverlappingFileLockException`）
  ⇒ **空转 6.5 s**，同时握着内部锁 ⇒ 卡顿传播到短按路径
  ⇒ 实测 **8775 / 9509 ms**。修完 **P95 = 42 ms**。

## 配对规则

| 场景 | 起点 | 终点 |
|---|---|---|
| 短按 | `★ 短按步进`（决定要发什么的那一刻） | 其后第一条 `✓ AT+BKL=` |
| 长按 | `进入长按无极调节` | 其后第一条 `流式会话已开` |

⚠️ **不配对 `收到按键` 行**：它与步进行同毫秒，但步进行才代表"决定发什么"，
   语义更贴"用户意图 → 设备收到"。

## 用法

```bash
adb logcat -c                       # 先清
# ★ 在 TNT GO 键盘上按键（短按若干次 ＋ 长按一次）
python scripts/measure_key_latency.py
python scripts/measure_key_latency.py --max-ms 200     # 自定义阈值（默认 200）
```

退出码：**0 = 全在阈值内**；**1 = 有超阈值的**（可直接当判据用）。
"""

import argparse
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

TS = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)")


def to_ms(line):
    m = TS.match(line)
    if not m:
        return None
    mo, d, h, mi, s, milli = (int(x) for x in m.groups())
    return ((((mo * 31 + d) * 24 + h) * 60 + mi) * 60 + s) * 1000 + milli


def collect():
    raw = subprocess.run(["adb", "logcat", "-d"], capture_output=True, text=True,
                         encoding="utf-8", errors="replace").stdout
    events = []
    for line in raw.splitlines():
        t = to_ms(line)
        if t is None:
            continue
        if "★ 短按步进" in line:
            events.append((t, "press"))
        elif "进入长按无极调节" in line:
            events.append((t, "longpress"))
        elif "✓ AT+BKL=" in line:
            events.append((t, "write"))
        elif "流式会话已开" in line:
            events.append((t, "stream_open"))
    return events


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-ms", type=int, default=200,
                    help="判据阈值（默认 200 ms，见 AS 计划书 §3.4 判据 3）")
    args = ap.parse_args()

    lat = []
    mode = None
    start = 0
    for t, kind in collect():
        if kind in ("press", "longpress"):
            mode, start = kind, t
        elif mode == "press" and kind == "write":
            lat.append((t, t - start, "短按")); mode = None
        elif mode == "longpress" and kind == "stream_open":
            lat.append((t, t - start, "长按")); mode = None

    if not lat:
        print("✗ 没有配到任何一对 —— 没按键？还是日志格式变了？")
        sys.exit(1)

    vals = sorted(v for _, v, _ in lat)
    n = len(vals)

    def pct(p):
        return vals[min(n - 1, int(round((n - 1) * p / 100.0)))]

    print(f"配到 {n} 对：短按 {sum(1 for _, _, k in lat if k == '短按')}"
          f" ／ 长按 {sum(1 for _, _, k in lat if k == '长按')}")
    print(f"min={vals[0]}  P50={pct(50)}  P95={pct(95)}  max={vals[-1]}  (ms)")

    over = [dt for _, dt, _ in lat if dt > args.max_ms]
    print(f"\n超过 {args.max_ms} ms 的：{len(over)} 个" + (f" -> {over}" if over else ""))

    if over:
        print("\n✗ 判据 3 未过。常见根因（按可能性排序）：")
        print("  ① 补发/重试路径又去抢了自己已持有的端口（见本文件头）")
        print("  ② 电量侧轮询在长按期间持锁过久（应 lockWaitMs=0 立刻让路）")
        print("  ③ 设备本身慢（罕见；先用单次 P50 判断）")
        sys.exit(1)

    print("\n✓ 判据 3 通过")
    sys.exit(0)


if __name__ == "__main__":
    main()
