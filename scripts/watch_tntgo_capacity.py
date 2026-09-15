#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★ TNT GO 容量自学习 —— **持续监看 ＋ 落盘**（任务 AR · AR8b）

## 为什么需要它

容量自学习（`TntgoCapacity.kt`）要靠**真实放电**慢慢积累：一段要满足
**全程放电、电量掉 ≥5%、历时 ≥10 分钟、中途不断档**。
用户在正常使用中会自然积累，但**这个积累过程是看不见的** ——
学歪了、压根没在学、被断档反复清掉，光看卡片是发现不了的。

⇒ 这个脚本把过程**落成 txt**，让"到底学没学到"变成可回溯的事实。

## ⚠️ 为什么必须在手机侧读

串口在**手机**上（mod 独占 USB），PC 读不到 TNT GO。
所以脚本**只能通过 adb 读 mod 的状态**，不能自己算。

## ★ 它看三样东西

| # | 来源 | 看什么 |
|---|---|---|
| 1 | `shared_prefs/tntgo_battery.xml` | `cap.samples`（学到的估计）／`cap.seg.*`（进行中的段） |
| 2 | `logcat -s ModeMod/Tntgo` | 「★ 学到一个容量估计」等事件 |
| 3 | 同上的 `raw=+BATCG=` 行 | ★ **当前是充电还是放电** —— 不放电就永远学不到 |

## 用法

```bash
python scripts/watch_tntgo_capacity.py                # 默认 60s 一轮，跑到 Ctrl-C
python scripts/watch_tntgo_capacity.py --interval 30
python scripts/watch_tntgo_capacity.py --minutes 30   # 跑 30 分钟自动停
```

日志落在 `.ref/20260915_容量学习监看.log`（**追加**，多次运行不断档）。
"""

import argparse
import datetime as dt
import os
import re
import subprocess
import sys
import time

# ★ Windows GBK 控制台会把 ✓ / °C 这类字符变成 UnicodeEncodeError，
#   进而让退出码失真（CC 审计 F3 就是这么栽的）。这里显式改成 UTF-8。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.join(WS, "toolchain", "android-sdk", "platform-tools", "adb.exe")
SERIAL = os.environ.get("ANDROID_SERIAL", "")
PKG = "com.shware.mode"
PREFS = f"/data/data/{PKG}/shared_prefs/tntgo_battery.xml"
LOG = os.path.join(WS, ".ref", "20260915_容量学习监看.log")

BATCG = re.compile(r"\+BATCG=(-?\d+),(-?\d+),(-?\d+),(-?\d+),(-?\d+)")
CAP_EVENT = re.compile(r"ModeMod/Tntgo: (★ 学到一个容量估计.*|.*容量更新.*|.*段结束.*|.*断档.*|.*不采信.*|.*离谱.*)")

# ★★★ 串口读数与容量事件现在分属【两个 TAG】
#
#   AS3b（2026-09-15）把设备 I/O 统一到 `ModeMod/Serial`，
#   而容量学习仍在 `ModeMod/Tntgo`（`TntgoBatteryService` 的 TAG 没变）。
#
#   ⚠️ **实际踩到**：本脚本原来只过滤 `ModeMod/Tntgo` ⇒ `raw=+BATCG` **一条都抓不到**
#      ⇒ 连报 **9 分钟「无读数」**，而 mod 其实每 30 s 都在正常轮询（`cap.last.ms` 新鲜）。
#      ★ 症状极具迷惑性：**看起来像"mod 停摆了"**。
#
#   ⇒ 两个都要收；并且下面加了**新鲜度交叉校验**（见 `read_state`）——
#     把这类静默失效**变成响的**，而不是靠人记得。
LOG_TAGS = ["ModeMod/Serial", "ModeMod/Tntgo"]


def _logcat(lines):
    """★ 统一走这里 —— 不要在别处硬编码 tag，否则改一处漏一处"""
    return run(["logcat", "-d", "-t", str(lines), "-s"] + LOG_TAGS)


def run(args, timeout=25):
    """跑一条 adb 命令，返回 stdout（失败给空串，不抛）"""
    try:
        p = subprocess.run([ADB, "-s", SERIAL] + args,
                           capture_output=True, timeout=timeout)
        return p.stdout.decode("utf-8", "replace")
    except Exception as e:
        return f"<adb 失败: {e}>"


def read_state():
    """读 mod 的容量学习状态 + 最近一次串口读数"""
    xml = run(["shell", f"run-as {PKG} cat {PREFS}"])

    def grab(key):
        """★ 读一个 prefs 键。

        ⚠️⚠️ **两种存储格式，必须都认**（本脚本第一版只认了第一种 ⇒
        `cap.samples` 永远读到 `None` ⇒ **一直误报「尚未学到（0 段）」**）：

        ```xml
        <string name="cap.samples">7137.005</string>   <!-- String：值是【元素文本】 -->
        <float  name="cap.seg.mah" value="135.27" />   <!-- int/float/long：值是【属性】 -->
        ```

        ★ 症状极具迷惑性：段在正常积累、`cap.seg.*` 也读得到，
        **只有"学到的样本"这一项永远是空** ⇒ 看起来像"mod 学不出来"。
        ⇒ 定论：**先确认仪器本身没坏，再去怀疑被测对象。**
        """
        m = re.search(rf'name="{re.escape(key)}"\s+value="([^"]*)"', xml)
        if m:
            return m.group(1)
        m = re.search(rf'name="{re.escape(key)}"[^>]*>([^<]*)</', xml)
        return m.group(1) if m else None

    samples = grab("cap.samples")
    seg_soc = grab("cap.seg.soc")
    seg_mah = grab("cap.seg.mah")
    last_ms = grab("cap.last.ms")

    # 最近一次 +BATCG（★ 用它判断充放电 —— 不放电就永远学不到）
    #
    # ⚠️ 不要写 `-t 200` —— 它作用在【整个缓冲区】上（这个 ROM 很吵），
    #    再按 tag 过滤就只剩零星几行，实测会误报"无读数"。
    #    用 `-s` 让 logcat 在服务端过滤，再给一个宽裕的 `-t`。
    lg = _logcat(5000)
    readings = BATCG.findall(lg)
    last = readings[-1] if readings else None

    # ★★★ 新鲜度交叉校验 —— **把静默失效变响**
    #
    # `cap.last.ms` 是 mod 每轮写盘的时刻。它很新却抓不到 `+BATCG`
    # ⇒ 只可能是**日志 TAG/格式漂移**，不可能是"mod 没在跑"。
    # ⚠️ 2026-09-15 因为缺这一步，监看连报 9 分钟"无读数"而 mod 完全健康。
    stale_hint = None
    if last is None and last_ms:
        try:
            age_s = time.time() - int(last_ms) / 1000.0
        except (TypeError, ValueError):
            age_s = -1
        if 0 <= age_s < 180:
            stale_hint = (f"⚠️ cap.last.ms 只有 {age_s:.0f}s 前 ⇒ mod 在正常轮询，"
                          f"却抓不到 +BATCG ⇒ ★★ 极可能是【日志 TAG/格式漂移】"
                          f"（脚本当前过滤：{', '.join(LOG_TAGS)}）")

    return {
        "samples": [s for s in (samples or "").split(",") if s.strip()],
        "seg_soc": seg_soc,
        "seg_mah": seg_mah,
        "last_ms": last_ms,
        "last": last,
        "stale_hint": stale_hint,
    }


def describe(r):
    """把状态变成一行中文"""
    n = len(r["samples"])
    if r["last"] is None:
        # ★ 不是干巴巴一句"无读数" —— 有交叉证据就把它摆出来
        chg = "无读数" + (f" ｜ {r['stale_hint']}" if r.get("stale_hint") else "")
    else:
        mv, soc, st, ma, t10 = (int(x) for x in r["last"])
        # ★ 第 3 字段：1=充电 / 2=放电（2026-09-15 实测解出）
        state = {1: "充电中", 2: "放电中"}.get(st, f"状态{st}")
        icon = "⚡" if ma >= 0 else "↓"
        chg = f"{soc}% {state} {icon}{ma:+d}mA {mv}mV {t10/10:.1f}°C"

    if r["seg_soc"] is not None:
        seg = f"进行中：起点 {r['seg_soc']}%（已累计 {float(r['seg_mah'] or 0):.0f} mAh）"
    else:
        seg = "无进行中的段"

    if n == 0:
        learned = "尚未学到（0 段）"
    elif n < 2:
        learned = f"已学 {n} 段（★ 满 2 段才切换）：{r['samples']}"
    else:
        vals = sorted(float(x) for x in r["samples"])
        med = vals[len(vals) // 2]
        learned = f"★ 已学 {n} 段 ⇒ 中位数 ≈ {med:.0f} mAh"

    return chg, seg, learned


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--interval", type=int, default=60, help="轮询秒数（默认 60）")
    ap.add_argument("--minutes", type=float, default=0, help="跑多少分钟（0=一直跑）")
    args = ap.parse_args()

    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    deadline = time.time() + args.minutes * 60 if args.minutes else None

    # ★ 自己连设备 —— 否则 adb 视角里设备不在，所有命令静默返回空，
    #   表现为"无读数"，很容易被误判成"mod 没在学"
    run(["connect", SERIAL], timeout=15)

    # 先把 logcat 缓冲清掉，之后的都是本次的
    run(["logcat", "-c"])

    with open(LOG, "a", encoding="utf-8") as f:
        f.write(f"\n{'='*72}\n")
        f.write(f"监看开始 {dt.datetime.now():%Y-%m-%d %H:%M:%S}  "
                f"（间隔 {args.interval}s，设备 {SERIAL}）\n")
        f.write(f"{'='*72}\n")
        f.flush()

        prev_samples = None
        tick = 0
        while True:
            tick += 1
            now = dt.datetime.now()
            r = read_state()
            chg, seg, learned = describe(r)

            line = f"[{now:%H:%M:%S}] {chg} ｜ {seg} ｜ {learned}"
            print(line, flush=True)
            f.write(line + "\n")

            # ★ 新学到一段 ⇒ 高亮记一笔（这是这个脚本存在的意义）
            if prev_samples is not None and len(r["samples"]) > len(prev_samples):
                new = r["samples"][len(prev_samples):]
                msg = f"    ★★★ 新学到容量估计：{new}  ⇒ 累计 {len(r['samples'])} 段"
                print(msg, flush=True)
                f.write(msg + "\n")
            prev_samples = r["samples"]

            # 把本轮的 mod 事件也抄进来
            lg = _logcat(300)
            for m in CAP_EVENT.finditer(lg):
                f.write(f"    · {m.group(1)}\n")

            f.flush()

            if deadline and time.time() >= deadline:
                f.write(f"监看结束 {dt.datetime.now():%Y-%m-%d %H:%M:%S}（到时退出）\n")
                print("到时退出", flush=True)
                break
            time.sleep(args.interval)


if __name__ == "__main__":
    main()
