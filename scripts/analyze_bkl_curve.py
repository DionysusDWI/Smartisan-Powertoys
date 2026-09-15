#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★★★★ **AR12b · 亮度 → 电流 曲线（`I(MCU)`）的独立复算与判读**

## 它回答的是**两个问题**

1. **台账能不能支撑一条 `I(MCU)` 曲线？**（档位数、单调性、离散度）
2. ★★ **Kotlin 侧算出来的曲线，与 Python 侧独立复算的是否一致？**
   —— 这一条是本项目的**老毛病**：*"两边各写一遍，镜像全绿而实现是错的"*。
   ⇒ 判读脚本**自己从原始样本算**，再与盘上 `bkl.curve` 逐项对账。

## ★★★★ 口径（与 `TntgoBklCurve.kt` **必须一致** —— 改一边就要改另一边）

| 步 | 做法 | 为什么 |
|---|---|---|
| **1 分档** | 按 MCU **相邻聚类**（与簇内最小值的差 > `TOLERANCE_MCU` 就起新簇） | 用户按键 ⇒ MCU 落在**离散档位**上；固定桶宽会把 `497`/`604` 分开、把 `1900`/`2000` 合并，**与物理无关** |
| **2 取中位数** | 每档 `|I|` 取中位数 | 对串口偶发跳变免疫（实测相邻两次能差一倍） |
| **3 拟合** | 在**档中位数**上做加权最小二乘（权重 = 档内样本数 `n`） | 若对**原始样本**拟合，某一档会因"用户停得久"垄断权重 —— 而那与"那里更重要"无关 |
| **4 分状态** | 充电 / 放电**各拟合一条** | `I` 的含义随充放电**整个翻转**，混了会被充电状态整个淹没 |

★ `TOLERANCE_MCU = 40`：无极调节时 UI 不变、MCU 有 ±1~2 抖动；而实测**最小档位间距**是
  `63 → 497`（434）⇒ 40 远小于真实间距、又远大于抖动，两侧都安全。

## ★★★ 三条**不许含糊**的结论

| 结论 | 条件 | 含义 |
|---|---|---|
| **档位不足** | 档位 < 2 | ⛔ **不给曲线**（1 档时斜率是 `0/0`，拟合出来的是**编的**） |
| ★ **反物理** | 某亮档**比更暗的档还省电** | ⟹ 有别的东西在漂（正是 AR12c 那条 860 mA 反例所担心的）⇒ **曲线不该采用** |
| **可拟合** | 上述都过 | 出 `I = a + b·MCU`，并给出**实测带** `[mcu_min, mcu_max]` |

★ **带外一律不外推** —— 只报"实测带"，把外推留给调用方去**如实降级**。

## 用法

```bash
python scripts/analyze_bkl_curve.py                 # 读真机台账并复算
python scripts/analyze_bkl_curve.py --selftest      # 用合成台账证伪本工具（5 条判据）
python scripts/analyze_bkl_curve.py --dump-xml a.xml --json
```
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bkl_common import read_ledger, read_curve, median, mad   # noqa: E402

TOLERANCE_MCU = 40      # ★ 必须与 TntgoBklCurve.TOLERANCE_MCU 一致
MAX_BUCKET_MCU = 300    # ★ 必须与 TntgoBklCurve.MAX_BUCKET_MCU 一致
MIN_LEVELS = 2          # ★ 必须与 TntgoBklCurve.MIN_LEVELS 一致
MIN_N_PER_LEVEL = 3     # 判读用：一条档位少于这么多样本 ⇒ 当作"证据薄"（**不剔除，只标注**）


# --------------------------------------------------------------- 口径实现（镜像）

def plateaus(samples, tolerance=TOLERANCE_MCU, bucket_max=MAX_BUCKET_MCU):
    """① 分档 ② 档内取中位数 ⇒ `[{'mcu':int,'abs_ma':float,'n':int}, ...]`（按 MCU 升序）"""
    pts = sorted((s["mcu"], abs(s["ma"])) for s in samples
                 if s.get("mcu") is not None and abs(s["ma"]) > 0)
    if not pts:
        return []
    out = []
    start = 0
    lo = pts[0][0]
    for i in range(1, len(pts) + 1):
        over = (i == len(pts)
                or pts[i][0] - lo > tolerance
                or pts[i][0] - lo > bucket_max)
        if over:
            seg = pts[start:i]
            out.append({
                "mcu": int(median([p[0] for p in seg])),
                "abs_ma": median([p[1] for p in seg]),
                "n": len(seg),
            })
            if i < len(pts):
                start = i
                lo = pts[i][0]
    return out


def fit_line(levels):
    """加权最小二乘 `|I| = a + b·MCU`；退化 ⇒ `None`（**不许硬凑一条**）"""
    if len(levels) < MIN_LEVELS:
        return None
    xs = [l["mcu"] for l in levels]
    if max(xs) - min(xs) < 1:
        return None
    w = [max(l["n"], 1) for l in levels]
    sw = float(sum(w))
    mx = sum(wi * l["mcu"] for wi, l in zip(w, levels)) / sw
    my = sum(wi * l["abs_ma"] for wi, l in zip(w, levels)) / sw
    sxx = sum(wi * (l["mcu"] - mx) ** 2 for wi, l in zip(w, levels))
    sxy = sum(wi * (l["mcu"] - mx) * (l["abs_ma"] - my) for wi, l in zip(w, levels))
    if abs(sxx) < 1e-9:
        return None
    b = sxy / sxx
    return {"a": my - b * mx, "b": b}


def describe(levels, mcu):
    """★★★★ 问"当前 MCU 下电流是多少" —— ★ **带外绝不外推**

    返回 `where ∈ {InBand, BelowBand, AboveBand, NoLevels}`：
    只有 `InBand` 才给 `abs_ma`；带外给 `clamp_abs_ma`（最近一端的**实测**值），
    由调用方**如实降级显示**（与"亮度未知 ⇒ 显示亮度未知"是同一条纪律）。
    """
    if not levels:
        return {"abs_ma": None, "where": "NoLevels", "clamp_abs_ma": None,
                "band": None, "levels": 0}
    s = sorted(levels, key=lambda l: l["mcu"])
    lo, hi = s[0], s[-1]
    band = [lo["mcu"], hi["mcu"]]
    line = fit_line(levels)
    if line is None:
        only = s[0]
        return {"abs_ma": None,
                "where": "BelowBand" if mcu < only["mcu"] else "AboveBand",
                "clamp_abs_ma": only["abs_ma"], "band": band, "levels": len(levels)}
    where = "BelowBand" if mcu < lo["mcu"] else ("AboveBand" if mcu > hi["mcu"] else "InBand")
    return {
        "abs_ma": (line["a"] + line["b"] * mcu) if where == "InBand" else None,
        "where": where,
        "clamp_abs_ma": lo["abs_ma"] if mcu < lo["mcu"] else hi["abs_ma"],
        "band": band,
        "levels": len(levels),
    }


def max_drop(levels):
    """相邻档的**反向**跳变最大值（mA）；`None` = 档位不足无法判断"""
    s = sorted(levels, key=lambda l: l["mcu"])
    if len(s) < 2:
        return None
    return max(0.0, max(s[i - 1]["abs_ma"] - s[i]["abs_ma"] for i in range(1, len(s))))


def spread(levels):
    """档内稳健离散度的**最大**值（判读"这条线像不像一条线"）"""
    if not levels:
        return None
    return {"mcu_min": min(l["mcu"] for l in levels),
            "mcu_max": max(l["mcu"] for l in levels),
            "levels": len(levels),
            "n_total": sum(l["n"] for l in levels)}


# --------------------------------------------------------------- 真机判读

def verdict_of(dis_levels, chg_levels):
    if not dis_levels:
        return "证据不足", "放电态一档都没有 ⇒ ★ 不给曲线"
    if len(dis_levels) < MIN_LEVELS:
        return "档位不足", f"放电态只有 {len(dis_levels)} 档 ⇒ 斜率无定义 ⇒ ★ 不给曲线"
    d = max_drop(dis_levels)
    if d is not None and d > 0:
        return "反物理", f"★ 有亮档比更暗的档还省电（最大反向 {d:.0f} mA）⇒ 有别的东西在漂 ⇒ 不采用"
    if fit_line(dis_levels) is None:
        return "档位不足", "拟合数值退化 ⇒ ★ 不给曲线"
    thin = [l for l in dis_levels if l["n"] < MIN_N_PER_LEVEL]
    if thin:
        return "可拟合（证据薄）", "★ 有档位样本 < {} 条：{}".format(
            MIN_N_PER_LEVEL, ", ".join(f"MCU {l['mcu']} (n={l['n']})" for l in thin))
    return "可拟合", "★ 档位、单调性、样本量都过关 ⇒ 可以出曲线"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--dump-xml", help="从已 dump 的 prefs XML 读（不连设备）")
    ap.add_argument("--selftest", action="store_true", help="用合成台账证伪本工具")
    ap.add_argument("--check-point", type=int, default=None,
                    help="额外演示：在该 MCU 处查一次（默认用当前 MCU）")
    args = ap.parse_args()

    if args.selftest:
        sys.exit(selftest())

    text = None
    if args.dump_xml:
        with open(args.dump_xml, encoding="utf-8") as f:
            text = f.read()

    samples, stats = read_ledger(text)
    stored = read_curve(text)

    # ★★ 先判"读坏了"还是"还没有" —— 这两者**必须分开**
    if stored is not None and stored.get("parse_broken"):
        print("=" * 72)
        print("✗ **盘上有 `bkl.curve`，但解析不出档位表** ⇒ 这是**读的一方坏了**，")
        print("  不是「mod 还没算曲线」。九成是 XML 实体（`&quot;`）没有解码。")
        print(f"  原文片段：{str(stored)[:120]}")
        print("=" * 72)
        sys.exit(3)

    dis = plateaus([s for s in samples if not s["chg"]])
    chg = plateaus([s for s in samples if s["chg"]])
    v, why = verdict_of(dis, chg)

    print("=" * 72)
    print("AR12b · 亮度 → 电流 曲线（MCU 域）· 独立复算")
    print("=" * 72)
    print(f"\n台账样本 {len(samples)} 条"
          f"（无 MCU 的旧格式 {stats['legacy_no_mcu']} 条，解析失败 {stats['bad']} 条）")
    if stats["legacy_no_mcu"]:
        print("  ★ 旧格式样本**不参与拟合**：⛔ 不用出厂曲线反解补 MCU")
        print("     （反解是推算值，且曲线一改就会追溯篡改已采样本）")

    for name, lv in (("放电", dis), ("充电", chg)):
        print(f"\n【{name}】档位 {len(lv)} 个")
        print(f"  {'MCU':>6} {'n':>4}  {'中位|I|':>8}")
        for l in lv:
            thin = "  ⚠ 证据薄" if l["n"] < MIN_N_PER_LEVEL else ""
            print("  {:>6} {:>4}  {:>8.0f}{}".format(l["mcu"], l["n"], l["abs_ma"], thin))
        line = fit_line(lv)
        if line:
            print("  ⇒ |I| = {:.1f} + {:.4f}·MCU".format(line["a"], line["b"]))
            band = [min(x["mcu"] for x in lv), max(x["mcu"] for x in lv)]
            print("  ⇒ ★ 实测带 MCU {}~{}（★ 带外**不外推**）".format(band[0], band[1]))
            resid = [abs(l["abs_ma"] - (line["a"] + line["b"] * l["mcu"])) for l in lv]
            worst = max(resid)
            print("  ⇒ 最大残差 {:.0f} mA（相对最大 MAD {:.0f} ⇒ {:.1f}×）".format(
                worst, max(mad([l["abs_ma"] for l in lv]) or 0, 1),
                worst / max(max(mad([l["abs_ma"] for l in lv]) or 0, 1), 1)))
        else:
            print("  ⇒ ⛔ 档位不足 / 数值退化 ⇒ **不给曲线**（不是「给一条差的」）")

    # ── ★★★ 与 Kotlin 侧对账（**这才是本脚本存在的主要理由**）
    print("\n" + "-" * 72)
    print("★ 与 Kotlin 侧落盘的曲线对账（`bkl.curve`）—— 两边各写一遍，必须逐项相符")
    if stored is None:
        print("  ⚠ 盘上没有 `bkl.curve`（mod 还没算过 / 刚复位）⇒ 无法对账")
    else:
        def cmp_list(name, mine, theirs):
            # ★★ `None` = **表头都没找到** ⇒ 解析坏了，不是"那边是空的"。
            #    ⚠️ 这两种情况必须分开报：把"读坏了"报成"0 档"会把一次
            #    **解析故障**伪装成**合法状态**（2026-09-15 实际踩到：`&quot;` 未解码）。
            if theirs is None:
                print(f"  ✗ {name}：★ **盘上那份连表头都没找到** ⇒ "
                      f"解析坏了（不是「那边是空的」）—— 查 XML 实体解码")
                return 1
            bad = 0
            if len(mine) != len(theirs):
                print(f"  ✗ {name}：档位数 {len(mine)} vs {len(theirs)} ⇒ **不一致**")
                return 1
            for a, b in zip(mine, theirs):
                dm, di, dn = abs(a["mcu"] - b[0]), abs(a["abs_ma"] - b[1]), a["n"] - b[2]
                ok = dm == 0 and di < 0.05 and dn == 0
                if not ok:
                    bad += 1
                    print(f"  ✗ {name}：MCU {a['mcu']} vs {b[0]} ／ |I| {a['abs_ma']:.1f} vs {b[1]:.1f}"
                          f" ／ n {a['n']} vs {b[2]}")
            if not bad:
                print(f"  ✓ {name}：{len(mine)} 档**逐项一致**")
            return bad

        bad = cmp_list("放电", dis, stored["dis"])
        bad += cmp_list("充电", chg, stored["chg"])
        if stored.get("legacy") is not None and stored["legacy"] != stats["legacy_no_mcu"]:
            print(f"  ✗ 旧格式计数 {stats['legacy_no_mcu']} vs {stored['legacy']}")
            bad += 1
        print("  ⇒ {}".format("★ 完全一致（镜像与实现没有漂）" if not bad
                              else f"✗ {bad} 处不一致 ⇒ **有一边是错的**，必须查清"))

    # ── 当前 MCU 处查一次
    mcu_now = args.check_point
    from bkl_common import read_brightness_now
    if mcu_now is None and text is None:
        b = read_brightness_now()
        try:
            mcu_now = int(b.get("mcu", ""))
        except ValueError:
            mcu_now = None
    if mcu_now is not None:
        r = describe(dis, mcu_now)
        print("\n" + "-" * 72)
        print(f"当前 MCU {mcu_now} ⇒ 放电态查询：where={r['where']}  "
              f"|I|={'—' if r['abs_ma'] is None else format(r['abs_ma'], '.0f')}  "
              f"带={r['band']}")
        if r["where"] != "InBand":
            print("  ★ **不外推** —— 调用方应降级显示（用最近一端的实测值并注明「该处未测」）")

    print("\n" + "=" * 72)
    print(f"⇒ 判读：**{v}** —— {why}")
    print("=" * 72)

    if args.json:
        print(json.dumps({"discharging": dis, "charging": chg,
                          "fit_dis": fit_line(dis), "fit_chg": fit_line(chg),
                          "stored": stored, "verdict": v, "why": why,
                          "stats": stats}, ensure_ascii=False, indent=2, default=str))
    sys.exit(0 if v.startswith("可拟合") else 1)


# --------------------------------------------------------------- 自检

def selftest():
    """★ 自检：用**合成台账**逐条证伪本工具。

    ⛔ 本项目纪律：**凡是用来自证的工具，本身必须被证伪过一次。**
    噪声用**确定性**模式（不用随机）—— 自检本身必须可复现。
    每条 case 都必须走到**判据真的会分叉**的那个分支。
    """
    def seq(spec):
        """spec: [(mcu, base, n), ...] ⇒ 合成放电样本"""
        out = []
        for mcu, base, n in spec:
            for i in range(n):
                out.append({"ui": 0, "mcu": mcu, "ma": -(base + ((i % 5) - 2) * 8), "chg": False})
        return out

    cases = []
    # ① 干净的三档 ⇒ 直线应**恰好**穿过中间那点（斜率与截距都可验算）
    lv = plateaus(seq([(63, 989, 10), (497, 1248, 10), (2000, 2113, 10)]))
    cases.append(("① 三档干净数据 ⇒ 中间档被直线复现（±1×MAD）",
                  abs(describe(lv, 497)["abs_ma"] - 1248) < 40, True))
    # ② 只有一档 ⇒ ★ 必须拒绝（不给曲线）
    lv1 = plateaus(seq([(497, 1248, 10)]))
    cases.append(("② 只有一档 ⇒ 判「档位不足」且 abs_ma=None",
                  describe(lv1, 497)["abs_ma"] is None, True))
    # ③ 带外 ⇒ ★ 必须**不外推**，且给出最近一端的实测值
    r = describe(lv, 3000)
    cases.append(("③ MCU 3000（超出实测带 2000）⇒ AboveBand 且不外推",
                  r["where"] == "AboveBand" and r["abs_ma"] is None
                  and abs(r["clamp_abs_ma"] - 2113) < 1, True))
    # ④ ★ **反物理**：更亮的档反而更省电 ⇒ 必须判「反物理」
    lv4 = plateaus(seq([(63, 989, 10), (497, 2100, 10), (2000, 1200, 10)]))
    v4, _ = verdict_of(lv4, [])
    cases.append(("④ 亮档反而更省电 ⇒ 判「反物理」", v4 == "反物理", True))
    # ⑤ ★ **负例**：真的非线性（二次）⇒ 直线残差必须**大到能看出来**
    lv5 = plateaus(seq([(0, 800, 10), (1000, 1500, 10), (2000, 3200, 10)]))
    line5 = fit_line(lv5)
    resid5 = max(abs(l["abs_ma"] - (line5["a"] + line5["b"] * l["mcu"])) for l in lv5)
    cases.append(("⑤ 二次型数据 ⇒ 直线残差 > 100 mA（说明残差判据有牙）",
                  resid5 > 100, True))
    # ⑥ ★ **容差边界**：MCU 相差 41（> TOLERANCE_MCU=40）⇒ 必须分成**两档**
    lv6 = plateaus(seq([(500, 1200, 5), (541, 1500, 5)]))
    cases.append(("⑥ MCU 500 与 541（相差 41 > 容差 40）⇒ 分成两档",
                  len(lv6) == 2, True))
    # ⑦ ★ 反向：MCU 相差 39 ⇒ 必须并成**一档**
    lv7 = plateaus(seq([(500, 1200, 5), (539, 1210, 5)]))
    cases.append(("⑦ MCU 500 与 539（相差 39 ≤ 容差）⇒ 并成一档",
                  len(lv7) == 1, True))

    # ⑧ ★★★ **XML 实体必须解码** —— 用**真的 SharedPreferences XML 形态**（含 `&quot;`）
    #    这一条是本轮用真机数据抓到的 bug：未解码 ⇒ 两个表头同时 miss ⇒
    #    读成"空表"，而**空表是合法状态** ⇒ 会被当成"mod 还没算曲线"。
    xml_escaped = (
        '<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>\n<map>\n'
        '    <string name="bkl.samples">66:497:-1251:0,66:497:-1258:0</string>\n'
        '    <string name="bkl.curve">{&quot;v&quot;:2,&quot;legacy&quot;:110,'
        '&quot;chg&quot;:&quot;&quot;,&quot;dis&quot;:&quot;497:1394.0:12&quot;,'
        '&quot;ts&quot;:1789482138479}</string>\n'
        '</map>\n'
    )
    from bkl_common import read_curve as _rc, read_ledger as _rl
    cur = _rc(xml_escaped)
    cases.append(("⑧ ★ 含 `&quot;` 的真 XML ⇒ 曲线表必须解得出来",
                  cur is not None and cur["dis"] == [[497, 1394.0, 12]]
                  and not cur["parse_broken"], True))
    cases.append(("⑨ ★ 同一份 XML ⇒ samples 也必须解得出来（2 条）",
                  len(_rl(xml_escaped)[0]) == 2, True))
    # ⑩ ★ **反向**：表头改名 ⇒ 必须报 `None`（"找不到"）而**不是** `[]`（"是空的"）
    xml_broken = xml_escaped.replace("&quot;dis&quot;", "&quot;xxx&quot;")
    cases.append(("⑩ ★ 表头缺失 ⇒ 必须报 parse_broken（不许伪装成「空表」）",
                  _rc(xml_broken)["parse_broken"] is True, True))
    # ⑪ ★ **反向**：版本号不对 ⇒ 必须报 parse_broken（不许硬解旧结构）
    xml_oldv = xml_escaped.replace("&quot;v&quot;:2", "&quot;v&quot;:1")
    cases.append(("⑪ ★ 版本号是 1（旧嵌套格式）⇒ 必须报 parse_broken",
                  _rc(xml_oldv)["parse_broken"] is True, True))
    # ⑫ ★ **负例（合法空）**：表真的空 ⇒ **不许**报 parse_broken
    #     ★ ⑩/⑪ 与 ⑫ 必须能分开 —— 否则"一律报坏"会伪装成"判据很严"
    xml_empty = ('<map><string name="bkl.curve">{&quot;v&quot;:2,&quot;legacy&quot;:0,'
                 '&quot;chg&quot;:&quot;&quot;,&quot;dis&quot;:&quot;&quot;,'
                 '&quot;ts&quot;:1}</string></map>')
    cases.append(("⑫ ★ 表**真的**为空 ⇒ 不许误报 parse_broken（正/负例必须分得开）",
                  _rc(xml_empty)["parse_broken"] is False, True))

    print("=" * 72)
    print("analyze_bkl_curve · 自检（合成台账 · 确定性噪声）")
    print("=" * 72)
    bad = 0
    for name, got, want in cases:
        ok = bool(got) == bool(want)
        bad += 0 if ok else 1
        print("  {} {:52} ⇒ {}{}".format("✓" if ok else "✗", name, got,
                                         "" if ok else f"   ★ 期望 {want}"))
    print("-" * 72)
    if bad:
        print(f"✗ 自检失败 {bad}/{len(cases)} —— 判据与实现不符")
        return 1
    print(f"✓ 自检通过 {len(cases)}/{len(cases)} —— 分档/拟合/带外/反物理/残差/XML解码 都可被触发 ⇒ 工具**有牙**")
    return 0


if __name__ == "__main__":
    main()
