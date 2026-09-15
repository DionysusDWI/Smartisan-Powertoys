#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★ AR12b-2 · **多档采样监看**（亮度 → 电流曲线的采数过程落到 txt）

## 为什么需要它

AR12b-2 的判据是「**放电档位 ≥ 2**」。但在采样过程中，光看日志会漏掉三件事：

| 漏掉的东西 | 后果 |
|---|---|
| **当前是充电还是放电** | ★ 在充电态驻留 10 分钟 ⇒ **一条可用样本都不进**（曲线只在放电态生效） |
| **每个档位各攒了几条** | 驻留够不够**看不出来**；`1 条` 与 `8 条` 在卡片上长得一样 |
| **档位够不够出曲线** | 要等整轮结束跑判读脚本才知道，中途无法调整策略 |

⇒ 本脚本把这三件事**每轮刷新成一行**，让"还差什么"当场可见。

## ★ 关键纪律：这是【读数】工具，不是【判读】工具

它**只报事实**（档位、条数、充放电），**不报"好不好"**。
真正的判读在 `analyze_bkl_curve.py`（会做带外/反物理/档位不足三道闸）。
两边分开的理由：**判据如果跟着监看一起演进，监看就会说谎**。

## 用法

```bash
python scripts/watch_bkl_sampling.py --precheck        # 只看一眼当前状态（不循环）
python scripts/watch_bkl_sampling.py                   # 20s 一轮，跑到 Ctrl-C
python scripts/watch_bkl_sampling.py --minutes 30      # 跑 30 分钟自动停
python scripts/watch_bkl_sampling.py --interval 15
```

日志追加到 `.ref/ar12b2/<时间戳>_采样监看.log`。
"""

import argparse
import datetime as dt
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bkl_common import read_ledger, median  # noqa: E402
from analyze_bkl_curve import plateaus, fit_line, TOLERANCE_MCU  # noqa: E402

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(WS, ".ref", "ar12b2")

#: ★ 每个档位**建议**攒到的样本数（30 s 一轮 ⇒ 4 条 ≈ 2 min 驻留，与判据口径一致）
TARGET_N = 4


def now():
    return dt.datetime.now().strftime("%H:%M:%S")


def snapshot():
    """读一次真机台账 ⇒ 回一个纯数据 dict（不打印，便于自检复用）。"""
    samples, stats = read_ledger()
    dis = [s for s in samples if not s["chg"]]
    chg = [s for s in samples if s["chg"]]
    dis_lv = plateaus(dis)
    chg_lv = plateaus(chg)
    fit = fit_line(dis_lv)

    # ★ 最新一条样本：用来判断"此刻"是充电还是放电
    last = samples[-1] if samples else None

    # ★ 只统计**带 MCU 的放电样本** —— 旧格式（mcu=None）不参与拟合，数进来会虚高
    dis_new = [s for s in dis if s.get("mcu") is not None]

    return {
        "stats": stats,
        "total": len(samples),
        "dis_new": len(dis_new),
        "chg_new": len([s for s in chg if s.get("mcu") is not None]),
        "dis_levels": dis_lv,
        "chg_levels": chg_lv,
        "fit": fit,
        "last": last,
        "ok": len(dis_lv) >= 2 and fit is not None,
    }


def render(s):
    """把快照渲染成**一行摘要 + 档位明细**。"""
    last = s["last"]
    if last is None:
        state = "无样本"
    elif last["chg"]:
        state = f"★充电 chg=1  I=+{last['ma']}"
    else:
        state = f"放电 chg=2  I={last['ma']}"

    head = (f"[{now()}] 台账 {s['total']} 条（带 MCU：放 {s['dis_new']} / 充 {s['chg_new']}）"
            f"｜此刻 {state}")

    lines = [head]

    d = s["dis_levels"]
    if d:
        tot = sum(l["n"] for l in d)
        lines.append(f"    【放电】档位 {len(d)} 个（共 {tot} 条）")
        for l in d:
            flag = "✓" if l["n"] >= TARGET_N else f"⚠ 还差 {TARGET_N - l['n']} 条"
            lines.append(f"        MCU {l['mcu']:<5} n={l['n']:<3} 中位|I|={l['abs_ma']:<7.0f} {flag}")
    else:
        lines.append("    【放电】★ 0 档 —— 没有任何带 MCU 的放电样本")

    if s["fit"]:
        lines.append(f"    ⇒ 斜率 b = {s['fit']['b']:+.4f} mA/MCU ｜ a = {s['fit']['a']:.1f}"
                     f"   ★ 判据【放电档位 ≥2】达成 ⇒ 真曲线成立")
    else:
        need = 2 - len(d)
        lines.append(f"    ⇒ ⛔ 档位不足（还需 {max(need,1)} 个放电梯度）"
                     if d else "    ⇒ ⛔ 档位不足 —— 需要 ≥2 个放电档位")

    c = s["chg_levels"]
    if c:
        cl = "／".join(f"{l['mcu']}:{l['abs_ma']:.0f}(n{l['n']})" for l in c)
        lines.append(f"    【充电】档位 {len(c)} 个 —— {cl}")
        lines.append("        ⚠ 充电态**不进产品曲线**（曲线只在放电态生效）")
    else:
        lines.append("    【充电】0 档")

    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--interval", type=int, default=20, help="每轮间隔秒（默认 20）")
    ap.add_argument("--minutes", type=float, default=0, help="跑多少分钟自动停；0=无限")
    ap.add_argument("--precheck", action="store_true", help="只打一次就退出")
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)

    print("=" * 72)
    print("AR12b-2 · 多档采样监看")
    print(f"  判据：**【放电】档位 ≥ 2** ⇒ 出 I(MCU) 直线")
    print(f"  建议：每个亮度档驻留 ≥2 分钟（≈ 每档 {TARGET_N} 条，30 s 一轮）")
    print(f"  容差 TOLERANCE_MCU = {TOLERANCE_MCU}（与 Kotlin TntgoBklCurve 一致）")
    print("=" * 72)

    if args.precheck:
        s = snapshot()
        print(render(s))
        return 0 if s["ok"] else 1

    log_path = os.path.join(OUT_DIR, dt.datetime.now().strftime("%Y%m%d_%H%M%S") + "_采样监看.log")
    print(f"日志 → {os.path.relpath(log_path, WS)}\n")

    started = dt.datetime.now()
    with open(log_path, "w", encoding="utf-8") as f:
        f.write(f"# AR12b-2 采样监看　{started:%Y-%m-%d %H:%M:%S}　interval={args.interval}s\n")
        while True:
            try:
                s = snapshot()
                text = render(s)
            except Exception as e:                                   # noqa: BLE001
                text = f"[{now()}] ✗ 读数失败：{type(e).__name__}: {e}"
            print(text, flush=True)
            f.write(text + "\n")
            f.flush()

            if args.minutes and (dt.datetime.now() - started).total_seconds() >= args.minutes * 60:
                print(f"\n★ 到 {args.minutes} 分钟，停止。")
                break
            try:
                import time
                time.sleep(args.interval)
            except KeyboardInterrupt:
                print("\n★ 手动停止。")
                break
    return 0


if __name__ == "__main__":
    sys.exit(main())
