#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★★ AR12c · **亮度-功耗曲线 · 可行性判读**

## 它回答的是**一个问题**

> **两个亮度稳态下，TNT GO 的电流 `I` 到底有没有可分辨差异？**

★ 这个问题**必须先答**（AR §阶段 C+ 与 AS5 的判据）。
答"没有"**也是一个结果** —— 那就**不建曲线**，而不是硬凑一条。

## ★★★ 为什么不能只看"两个中位数不一样"

`I` 本身有噪声：实测 100% 稳态下 7 个样本落在 `-2012 .. -2420`（含一个离群）。
**光看中位数差 100 mA 就宣布"有差异"，等于把噪声当信号。**

⇒ 判据用**稳健效应量**：

```
separation = |median_A − median_B| / pooled_MAD
```
其中 `MAD` 是**同一个 MAD 口径**（AR12 与 N9 已统一用它，不用标准差 —— 后者被离群值主导）。

**阈值 = 3**：中位数之差要达到"组内典型离散度"的 3 倍才算可分辨。

## ★★ 三种结论必须分开（不许含糊）

| 结论 | 条件 |
|---|---|
| **证据不足** | 任一亮度组样本 < [MIN_N]，或两组不在**同一充电状态** |
| **不可分辨** | 样本够，但 `separation < 3` ⇒ ★ **不建曲线** |
| **可分辨** | `separation ≥ 3` ⇒ 可以建 `I(b)` |

## 用法

```bash
python scripts/analyze_bkl_power.py              # 读真机台账并判读
python scripts/analyze_bkl_power.py --bucket 5   # 桶宽 5%
python scripts/analyze_bkl_power.py --json
```
"""

import argparse
import json
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bkl_common import (                   # noqa: E402  ★ AR12b：解析只写一次
    WS, ADB, SERIAL, PKG, PREFS,
    adb, read_brightness_now, read_ledger, median, mad,
)

MIN_N = 5           # 每组最少样本数
SEP_MIN = 3.0       # 稳健效应量阈值


def read_ledger_compat():
    """★ AR12b：台账新增了 MCU 字段（`ui:mcu:mA:chg`），**两种格式都必须能读**。

    ⛔ 旧 3 字段样本**不补 MCU**（不用出厂曲线反解）—— 那会让"曲线一改、
       已采样本被追溯篡改"。它们仍可用于 UI 域的判读，只是不进曲线拟合。
    """
    return read_ledger()


def read_brightness_now_compat():
    return read_brightness_now()


def analyze(samples, bucket):
    """按 (充电状态, 亮度档) 分组，给出中位数与稳健离散度

    ★ AR12b：**有 MCU 的样本按 MCU 档分组**（那是电流的自变量域），
      旧格式样本退回按 UI 桶分组 —— 两拨**不混**（混了会把 `MCU 497` 与
      `UI 66` 算成一档，而它们根本不是一回事）。
    """
    groups = {}
    for s in samples:
        if s["mcu"] is not None:
            key = (s["chg"], "mcu", s["mcu"])
        else:
            key = (s["chg"], "ui", s["ui"] // bucket * bucket)
        groups.setdefault(key, []).append(abs(s["ma"]))
    out = []
    for (chg, domain, lo), vals in sorted(groups.items(), key=lambda kv: (kv[0][0], kv[0][1], kv[0][2])):
        out.append({
            "charging": chg,
            "domain": domain,
            "lo": lo,
            "ui_lo": lo if domain == "ui" else None,
            "n": len(vals),
            "median_abs_ma": median(vals),
            "mad": mad(vals),
            "min": min(vals),
            "max": max(vals),
        })
    return out


def runs(samples):
    """★★ 把台账按【连续同亮度 + 同充电状态】切成**时间序区段**。

    ★ 台账是**有序**的 ⇒ "用户把亮度调回 100%" 这件事**可以从数据本身看出来**，
    不需要信任任何外部记录。这是反转检验能成立的前提。

    ★ AR12b：同一 UI 亮度可能对应**完全相同的 MCU**（用户没动过键）⇒ `mcu` 也进
      分段键，这样"无极微调"与"真的换档"能分开。
    """
    out = []
    for s in samples:
        lv = s["mcu"] if s["mcu"] is not None else s["ui"]
        if out and out[-1]["ui"] == s["ui"] and out[-1]["chg"] == s["chg"] \
                and out[-1]["level"] == lv:
            out[-1]["vals"].append(abs(s["ma"]))
        else:
            out.append({"ui": s["ui"], "level": lv, "mcu": s["mcu"],
                        "chg": s["chg"], "vals": [abs(s["ma"])]})
    for i, r in enumerate(out):
        r["idx"] = i
        r["n"] = len(r["vals"])
        r["median"] = median(r["vals"])
        r["mad"] = mad(r["vals"])
    return out


def reversal_check(rs, bucket, min_n=MIN_N, sep_min=SEP_MIN):
    """★★★ ABA 反转复现检验 —— 它比"两组比较"强在哪

    两组（如 100% vs 27%）是在**不同时段**测的 ⇒ **"差异"与"时间"混淆**：
    任何同期缓慢漂移（温度、后台负载、SOC）都会冒充亮度效应。

    ★ 若把亮度**调回**同一个档，就白送一次对照：同一亮度被测**两次**、中间隔着另一档。

    ⇒ 判据是**两条**，第二条才是关键：

    | 检验 | 要求 | 不满足时 |
    |---|---|---|
    | **档间**可分辨 | `separation ≥ 3` | 效应不存在 ⇒ **不建曲线** |
    | ★ **档内**可复现 | 同亮度两次测量的 `separation < 3` | ★ **效应归不得亮度** —— 有别的东西在漂 |

    ⛔ 这条检验**可以失败**，且失败时的结论与"不可分辨"一样是**不建曲线**。

    ★ AR12b：分组键从"UI 桶"改成**区段自身的亮度档**（有 MCU 就是 MCU 值）。
      `bucket` 参数保留只为兼容调用点（同一档就是**完全相同的值**，不需要容差）。
    """
    levels = {}
    for r in rs:
        if r["n"] >= min_n:
            levels.setdefault(r["level"], []).append(r)

    res = {"levels": [], "within": [], "cross": [], "reproducible": None,
           "cross_ok": None, "verdict": "证据不足"}

    for lv, rs_ in sorted(levels.items()):
        allv = [v for r in rs_ for v in r["vals"]]
        res["levels"].append({
            "level": lv, "ui": rs_[0]["ui"],
            "runs": [r["idx"] for r in rs_],
            "n_total": len(allv), "median": median(allv), "mad": mad(allv),
        })
        for i in range(len(rs_)):                       # ★ 档内：同亮度不同区段两两比
            for j in range(i + 1, len(rs_)):
                a, b = rs_[i], rs_[j]
                pooled = max(x for x in [a["mad"], b["mad"], 1.0] if x is not None)
                sep = abs(a["median"] - b["median"]) / pooled
                res["within"].append({
                    "level": lv, "run_a": a["idx"], "run_b": b["idx"],
                    "median_a": a["median"], "median_b": b["median"],
                    "pooled_mad": pooled, "separation": sep, "agree": sep < sep_min,
                })

    lvs = sorted(levels.keys())
    for i in range(len(lvs)):                            # 档间
        for j in range(i + 1, len(lvs)):
            A = [v for r in levels[lvs[i]] for v in r["vals"]]
            B = [v for r in levels[lvs[j]] for v in r["vals"]]
            pooled = max(x for x in [mad(A), mad(B), 1.0] if x is not None)
            sep = abs(median(A) - median(B)) / pooled
            res["cross"].append({
                "level_a": lvs[i], "level_b": lvs[j],
                "median_a": median(A), "median_b": median(B),
                "pooled_mad": pooled, "separation": sep, "distinguishable": sep >= sep_min,
            })

    if res["within"]:
        res["reproducible"] = all(w["agree"] for w in res["within"])
    if res["cross"]:
        res["cross_ok"] = all(c["distinguishable"] for c in res["cross"])

    # ★ 判读**顺序即优先级**：档内不一致是**更具体**的发现，必须压过档间的结论。
    #   理由：同亮度测两次就对不上，本身已经证明"有别的东西在漂"；
    #   此时档间比出来的差异**无论多大都不能归给亮度**。
    if res["reproducible"] is False:
        res["verdict"] = "复现失败"
    elif res["cross_ok"] is False:
        res["verdict"] = "不可分辨"
    elif res["cross_ok"]:
        res["verdict"] = "反转复现成立" if res["reproducible"] else "可分辨（未检验复现）"
    else:
        res["verdict"] = "证据不足"
    return res


def self_test():
    """★ 自检：用**合成台账**逐条证伪本工具的判据。

    ⛔ 本项目纪律：**凡是用来自证的工具，本身必须被证伪过一次。**
    （一个不会失败的断言，比"名大于实"还靠前一步 —— 见 PROGRESS-STATE 纪律 ⑥。）

    噪声用**确定性**模式生成（不用随机）—— 自检本身必须可复现。
    每条 case 都必须是**判据真的会走到**的分支，否则它只是在陪跑。
    """
    def seq(spec):
        """spec: [(ui, base, n), ...] ⇒ 合成台账（全部放电，MCU 由 ui 线性映射而来）"""
        out = []
        for ui, base, n in spec:
            mcu = ui * 20                      # ★ 合成 MCU = UI×20（只为让"档位"互不相同）
            for i in range(n):
                out.append({"ui": ui, "mcu": mcu, "ma": -(base + ((i % 5) - 2) * 12), "chg": False})
        return out

    cases = [
        ("① ABA 反转复现（应成立）",
         seq([(100, 2100, 10), (27, 990, 10), (100, 2110, 10)]),
         "反转复现成立"),
        ("② ★ 调回高档却停在低档 ⇒ 有别的东西在漂",
         seq([(100, 2100, 10), (27, 990, 10), (100, 1010, 10)]),
         "复现失败"),
        ("③ 两档差异小于组内噪声 ⇒ 不可分辨",
         seq([(100, 2100, 10), (27, 2080, 10)]),
         "不可分辨"),
        ("④ 每档样本不足（n<5）⇒ 证据不足",
         seq([(100, 2100, 3), (27, 990, 3)]),
         "证据不足"),
    ]

    print("=" * 72)
    print("analyze_bkl_power · 自检（合成台账 · 确定性噪声）")
    print("=" * 72)
    bad = 0
    for name, s, want in cases:
        r = reversal_check(runs(s), 10)
        ok = r["verdict"] == want
        bad += 0 if ok else 1
        print("  {} {:36} ⇒ {}{}".format(
            "✓" if ok else "✗", name, r["verdict"],
            "" if ok else "   ★ 期望「{}」".format(want)))
    print("-" * 72)
    if bad:
        print("✗ 自检失败 {}/{} —— 判据与实现不符".format(bad, len(cases)))
        return 1
    print("✓ 自检通过 {}/{} —— 四条判据都可被触发 ⇒ 工具**有牙**".format(len(cases), len(cases)))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bucket", type=int, default=10, help="亮度桶宽（默认 10%%）")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--no-reversal", action="store_true", help="跳过 ABA 反转检验")
    ap.add_argument("--self-test", action="store_true", help="用合成台账证伪本工具")
    args = ap.parse_args()

    if args.self_test:
        sys.exit(self_test())

    samples, stats = read_ledger_compat()
    bkl = read_brightness_now_compat()

    print("=" * 72)
    print("AR12c · 亮度-功耗 · 可行性判读")
    print("=" * 72)
    print(f"\n台账样本 {len(samples)} 条"
          f"（其中 ★ 无 MCU 的旧格式 {stats['legacy_no_mcu']} 条，解析失败 {stats['bad']} 条）")
    print(f"当前亮度：{bkl.get('ui', '?')}% （MCU {bkl.get('mcu', '?')}）")

    if not samples:
        print("\n⚠️ 台账为空。可能原因：亮度 mod 没运行 ⇒ 电量侧「亮度未知」⇒ 按红线不入账。")
        sys.exit(2)

    groups = analyze(samples, args.bucket)
    print(f"\n按【充电状态 × 亮度档】分组（有 MCU 的按 MCU 档，旧格式退回 UI 桶{args.bucket}%）：")
    print(f"  {'状态':6} {'域':4} {'档位':>6} {'n':>3}  {'中位|I|':>8}  {'MAD':>6}  {'范围':>14}")
    for g in groups:
        st = "充电" if g["charging"] else "放电"
        print("  {:6} {:4} {:>6} {:>3}  {:>8.0f}  {:>6}  {:>6}-{:<6}".format(
            st, g["domain"], g["lo"], g["n"], g["median_abs_ma"],
            "{:.0f}".format(g["mad"]) if g["mad"] is not None else "n/a",
            g["min"], g["max"]))

    # ── ★★ 时间序区段（台账有序 ⇒ "亮度被调回同一档"这件事本身可观测）
    rs = runs(samples)
    print(f"\n按【时间序】切成 {len(rs)} 个区段（连续同 UI + 同档位 + 同充电状态）：")
    print(f"  {'#':>2} {'状态':4} {'UI':>4} {'MCU':>5} {'n':>3}  {'中位|I|':>8}  {'MAD':>5}")
    for r in rs:
        st = "充电" if r["chg"] else "放电"
        print("  {:>2} {:4} {:>4} {:>5} {:>3}  {:>8.0f}  {:>5}".format(
            r["idx"], st, r["ui"], "—" if r["mcu"] is None else r["mcu"], r["n"], r["median"],
            "{:.0f}".format(r["mad"]) if r["mad"] is not None else "n/a"))

    # ── 判读：同一充电状态内，样本数够的两组两两比较
    print("\n" + "-" * 72)
    verdict = {"conclusion": "证据不足", "pairs": []}
    ok = [g for g in groups if g["n"] >= MIN_N]
    found = False
    for i in range(len(ok)):
        for j in range(i + 1, len(ok)):
            a, b = ok[i], ok[j]
            if a["charging"] != b["charging"]:
                continue          # ★ 绝不跨充放电比较（含义整个翻转）
            if a["domain"] != b["domain"]:
                continue          # ★ 域不同不比（MCU 497 与 UI 66 不是一回事）
            if a["domain"] == "ui" and abs(a["lo"] - b["lo"]) < args.bucket:
                continue
            pooled = max(x for x in [a["mad"], b["mad"], 1.0] if x is not None)
            sep = abs(a["median_abs_ma"] - b["median_abs_ma"]) / pooled
            found = True
            good = sep >= SEP_MIN
            print(f"  {'✓' if good else '✗'} {a['domain']} {a['lo']} vs {b['lo']}  "
                  f"|I| {a['median_abs_ma']:.0f} vs {b['median_abs_ma']:.0f} mA  "
                  f"Δ={abs(a['median_abs_ma']-b['median_abs_ma']):.0f}  "
                  f"pooledMAD={pooled:.0f}  ⇒  separation={sep:.2f}")
            verdict["pairs"].append({
                "domain": a["domain"], "level_a": a["lo"], "level_b": b["lo"],
                "median_a": a["median_abs_ma"], "median_b": b["median_abs_ma"],
                "delta": abs(a["median_abs_ma"] - b["median_abs_ma"]),
                "pooled_mad": pooled, "separation": sep, "distinguishable": good,
            })

    if found:
        verdict["conclusion"] = ("可分辨" if all(p["distinguishable"] for p in verdict["pairs"])
                                 else "不可分辨")
    else:
        print(f"  （没有两组同时满足：同一充电状态、样本数 ≥ {MIN_N}、亮度桶不同）")

    # ── ★★★ ABA 反转复现检验
    rev = None
    if not args.no_reversal:
        rev = reversal_check(rs, args.bucket)
        print("\n" + "-" * 72)
        print("★ ABA 反转复现检验（同亮度被测两次 ⇒ 把「亮度」与「时间」拆开）")
        for lv in rev["levels"]:
            print("  档位 {}（UI {}%）：区段 {} ／ n={} ／ 中位 {:.0f} ／ MAD {:.0f}".format(
                lv["level"], lv["ui"], lv["runs"], lv["n_total"], lv["median"], lv["mad"]))
        if rev["within"]:
            for w in rev["within"]:
                print("  {} 档内复现：区段 {} vs {}  |I| {:.0f} vs {:.0f}  "
                      "Δ={:.0f}  ⇒  separation={:.2f}  {}".format(
                          "✓" if w["agree"] else "✗",
                          w["run_a"], w["run_b"], w["median_a"], w["median_b"],
                          abs(w["median_a"] - w["median_b"]),
                          w["separation"],
                          "一致" if w["agree"] else "★ 不一致 —— 有别的东西在漂"))
        else:
            print("  （每档只有一个 ≥{} 样本的区段 ⇒ **无法检验复现**）".format(MIN_N))
        for c_ in rev["cross"]:
            print("  档间：{} vs {}  ⇒  separation={:.2f}".format(
                c_["level_a"], c_["level_b"], c_["separation"]))
        print(f"  ⇒ 反转判读：**{rev['verdict']}**")

    print("\n" + "=" * 72)
    c = verdict["conclusion"]
    rv = rev["verdict"] if rev is not None else None
    if c == "可分辨" and rv == "复现失败":
        print("✗ 复现失败 —— 档间差异确实存在，但★**同亮度重复测量本身就不一致**")
        print("  ⇒ 差异**归不得亮度**（时间／温度／负载在漂）⇒ ★ **不建曲线**")
        c = "复现失败"
    elif c == "可分辨" and rv == "不可分辨":
        print("✗ 按时间序判读为**不可分辨**（分组口径给了相反结论 ⇒ 采信更严的那个）")
        c = "不可分辨"
    elif c == "可分辨":
        print("✓ 可分辨 —— 亮度对电流有**稳健可测**的影响 ⇒ **可以建 I(b) 曲线**")
        if rv == "反转复现成立":
            print("  ★ 且 **ABA 反转复现成立**：亮度调回原档后电流也回到原档 ⇒ 效应可归因")
    elif c == "不可分辨":
        print("✗ 不可分辨 —— 样本够但效应量不足 ⇒ ★ **不建曲线**（如实记录，这是结果不是失败）")
    else:
        print(f"… 证据不足 —— 需要【两个亮度稳态】各驻留 ≥5 分钟（每桶 ≥{MIN_N} 个样本）")
    print("=" * 72)

    if args.json:
        print(json.dumps({"groups": groups, "runs": rs, "verdict": verdict,
                          "reversal": rev}, ensure_ascii=False, indent=2, default=str))
    sys.exit(0 if c in ("可分辨",) else (1 if c in ("不可分辨", "复现失败") else 2))


if __name__ == "__main__":
    main()
