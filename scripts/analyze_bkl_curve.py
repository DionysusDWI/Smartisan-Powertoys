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
| ★ **反物理** | 数据走向与**该状态的物理方向相反** | ⟹ 有别的东西在漂（正是 AR12c 那条 860 mA 反例所担心的）⇒ **曲线不该采用** |
| **可拟合** | 上述都过 | 出 `I = a + b·MCU`，并给出**实测带** `[mcu_min, mcu_max]` |

★ **带外一律不外推** —— 只报"实测带"，把外推留给调用方去**如实降级**。

## ★★★★ 物理方向是**两个方向**，判据必须带方向（AR13 更正）

```
Trend.Discharge:  亮度↑ ⇒ |I| **上升** ⇒ 出现"更亮的一档更省电" = 违规
Trend.Charge:     亮度↑ ⇒ |I| **下降** ⇒ 出现"更亮的一档更耗电" = 违规（实测 −0.4295）
```

⚠️ **原文（AR12b）只朝一个方向判**（等价于「更亮更省电 ⇒ 拒绝」），
并且**只对放电施加** —— 当时是对的（离线工具确实只喂放电），但它有**两个**后果：

1. ★ **充电曲线完全在判据之外**：`verdict_of(dis, chg)` 的 `chg` 参数**从未被读过**；
2. ★★ **那个函数不能改个名字就接进运行时**：`max_drop()` 会**把正确的充电曲线判死**
   （充电态 `I = 充电器供给 − 负载` ⇒ `|I|` 随亮度**下降**是物理正确的）。

⇒ AR13 起，两侧统一用 `worst_violation(levels, trend)`：**拒绝条件 = 走向与物理相反**。

## 用法

```bash
python scripts/analyze_bkl_curve.py                 # 读真机台账并复算
python scripts/analyze_bkl_curve.py --selftest      # 用合成台账证伪本工具（逐条报，含 G2 方向区分性）
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
MIN_N_PER_LEVEL = 3     # ★★ G1：一档**至少**这么多样本才算"一档"（**不足 ⇒ 剔除，不只是标注**）
# ⚠️ 2026-09-16 之前这里是"证据薄（**不剔除，只标注**）"⇒ 判读仍拿薄档去判
#    ⇒ 与产品侧行为**不一致**。现在两侧都**剔除**，且门槛**钉在判读入口**（见 wire_levels）。
# ★ 必须与 `TntgoBklCurve.MIN_N_PER_LEVEL` 一致。


# --------------------------------------------------------------- 口径实现（镜像）

def plateaus(samples, tolerance=TOLERANCE_MCU, bucket_max=MAX_BUCKET_MCU,
             min_n=MIN_N_PER_LEVEL):
    """① 分档 ② 档内取中位数 ⇒ `[{'mcu':int,'abs_ma':float,'n':int}, ...]`（按 MCU 升序）

    ★★ G1（2026-09-16）：**`n < min_n` 的簇不是一档，直接不产出**。

    ⚠️ 与 `TntgoBklCurve.plateauing(minN=…)` 是**同一个口径**（那边是产品、这边是镜像）。

    为什么必须剔、而不是"留下再标注"：闸门判违规靠**相邻两档中位数之差**。
    真机实测 `mcu=178` 只有 2 条（`1116` 与 `2108`，充/放切换瞬态）⇒ 中位数 `1612`
    落在两个真值**中间** —— **那个档位根本不存在**，却比 `497` 档的 `1344` 还高
    ⇒ 判出 `178→497 反向 268 mA` ⇒ **整条放电曲线被拒**。剔掉它之后
    放电 `980 → 1344 → 2068` **完全单调**。

    ★ 需要**含薄档的原始表**时显式传 `min_n=1`（门槛矩阵/诊断用）。
    """
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
            # ★★ G1：证据太薄的簇**不是一档** ⇒ 不进表（下游闸门/拟合/插值带全都只认这张表）
            if min_n <= 1 or len(seg) >= min_n:
                out.append({
                    "mcu": int(median([p[0] for p in seg])),
                    "abs_ma": median([p[1] for p in seg]),
                    "n": len(seg),
                })
            if i < len(pts):
                start = i
                lo = pts[i][0]
    return out


def wire_levels(levels, min_n=MIN_N_PER_LEVEL):
    """★★ G1 的过滤本身 —— 与 `plateaus(min_n=…)` **同一口径**。

    ⚠️ 为什么判读侧还要再滤一次：`levels` 可能是**从盘上回读**的
    （回读路径不经过 `plateaus`）⇒ 门槛必须钉在判读入口上，
    否则"回读的曲线"会绕过 G1。**判据要长在唯一入口上，不是长在某个调用点上。**
    """
    if min_n <= 1:
        return list(levels)
    return [l for l in levels if l["n"] >= min_n]


def _same_levels(a, b):
    """档位表逐项相等（`b` 是盘上那份 `[(mcu, abs_ma, n), …]` 形态）。"""
    if len(a) != len(b):
        return False
    for x, y in zip(a, b):
        if abs(x["mcu"] - y[0]) != 0 or abs(x["abs_ma"] - y[1]) >= 0.05 or x["n"] != y[2]:
            return False
    return True


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


def describe(levels, mcu, min_n=MIN_N_PER_LEVEL, trend="dis"):
    """★★★★ 问"当前 MCU 下电流是多少" —— ★ **带外绝不外推**

    返回 `where ∈ {InBand, BelowBand, AboveBand, NoLevels}`：
    只有 `InBand` 才给 `abs_ma`；带外给 `clamp_abs_ma`（最近一端的**实测**值），
    由调用方**如实降级显示**（与"亮度未知 ⇒ 显示亮度未知"是同一条纪律）。

    ★★ G1（2026-09-16）：与 `TntgoBklCurve.estimate` **同一口径** ——
       薄档既不进拟合，**也不算进实测带端点**（一条 n=2 的档给出的"带宽"和它的中位数一样是编的）。

    ★★★★ G3（2026-09-16）：新增 `unusable ∈ {None, "Rejected", "NotEnoughLevels"}`
       —— **与 `where` 正交的第二维**，回答"给不出数的【原因】"。

    ⛔ **改这里的原因不是"补个字段"，而是镜像本身有同一个盲点**：
       G3 之前 `fit_line` 返回 `None` 时这里**根本不查闸门** ⇒
       「走向反物理」与「档位不足」被压成同一个 `where` ⇒
       **镜像说不出"被拒"，正如卡片显示不出"被拒"**。
       ★ 两处是同一个设计缺陷的两份实现 ⇒ 必须一起改，
         否则下一轮对账会在这一维上**各自错、并且一致地错**（纪律 ⑪ 的形态）。
    """
    wire = wire_levels(levels, min_n)
    if not wire:
        return {"abs_ma": None, "where": "NoLevels", "clamp_abs_ma": None,
                "band": None, "levels": 0, "unusable": "NotEnoughLevels"}
    s = sorted(wire, key=lambda l: l["mcu"])
    lo, hi = s[0], s[-1]
    band = [lo["mcu"], hi["mcu"]]
    where = "BelowBand" if mcu < lo["mcu"] else ("AboveBand" if mcu > hi["mcu"] else "InBand")
    # ★★★ G3：**先过闸门，再决定给不给数** —— 与 Kotlin `estimate()` 完全同口径。
    #   ⛔ 原代码只在 `fit_line is None` 时才说"给不出数" ⇒ **漏了闸门**：
    #      走向反物理时 `fit_line` **照样能拟合出一条直线**（那正是"能拟合但不该用"），
    #      于是镜像会**照常给出一个数**，而产品侧给不出数 ⇒ 两边不一致。
    #   ★ 这个洞是**本轮新加的 G3 判据抓出来的**（G3-a／G3-e 当场变红）——
    #     不是"顺手多写两条"，而是它们真的分叉了。
    verdict = check_mode(wire, trend, min_n)[0]
    line = fit_line(wire) if verdict == "可拟合" else None
    if line is None:
        # ★ G3：位置照报（与可用分支同口径），**原因**另说 ——
        #   ⚠️ 原代码把变量叫 `only`（"唯一那档"），那是"拟合失败＝只剩一档"时代的遗留名；
        #      被拒时它其实是**最暗端那一档**。位置判定改用 `lo`，与 Kotlin 对齐。
        return {"abs_ma": None,
                "where": where,
                "clamp_abs_ma": lo["abs_ma"] if mcu < lo["mcu"] else hi["abs_ma"],
                "band": band, "levels": len(wire),
                # ★ 只有"真的被闸门拒了"才叫 Rejected；其余（档位不足／数值退化）另说
                "unusable": "Rejected" if verdict.startswith("反物理") else "NotEnoughLevels"}
    return {
        "abs_ma": (line["a"] + line["b"] * mcu) if where == "InBand" else None,
        "where": where,
        "clamp_abs_ma": lo["abs_ma"] if mcu < lo["mcu"] else hi["abs_ma"],
        "band": band,
        "levels": len(wire),
        # ★ G3：这条路径上**一定**能给出数（`line` 非 None 且 `unusable` 为 None）
        "unusable": None,
    }


def worst_violation(levels, trend):
    """★★★★ **走向与物理方向相反**的最大幅度（mA）；`None` = 档位不足无法判断、或没有违规。

    ## 为什么必须带 `trend`（AR13 更正，见模块注释）

    `|I|` 随亮度的**正确走向**随充放状态**翻转**：

    | trend | 正确走向 | 违规（"反物理"） |
    |---|---|---|
    | `Discharge` | 亮度↑ ⇒ `\\|I\\|` **上升** | 更亮的一档**更省电** |
    | `Charge` | 亮度↑ ⇒ `\\|I\\|` **下降** | 更亮的一档**更耗电** |

    ⚠️ 旧版 `max_drop()` **只表达放电方向**。直接把它接进产品运行时，
    会**把正确的充电曲线判死** —— 所以这里是参数化的,不是改个名字。

    ★ **与 [widening_pair] 的关系**：同一判据的两种粒度（这里幅度、那里一整对）。
      两者**必须一致** —— 回归里有判据盯着（C1）。
    """
    w = widening_pair(levels, trend)
    return w["violation_ma"] if w else None


def widening_pair(levels, trend):
    """★★★★★ **"走向与物理相反"的那一对档 ＋ 幅度**（C1，2026-09-16）；`None` = 没有违规。

    与 [worst_violation] **同一个判据** —— 这里返回"是哪一对"，那里只返回幅度。
    ⚠️ 两者**必须一致**；回归里有判据盯着（`widening_pair(...)['violation_ma'] == worst_violation(...)`）。

    ## ★★ 为什么必须存在（C1）

    原来只报幅度 ⇒ 设备日志与判读输出里只有"反物理（走向与 X 态相悖）"，
    **看不出是哪一对**。⛔ 后果不是"少一点信息"，而是**会把读的人引到相反的结论**：

    | 只报 verdict | 读的人会得出 |
    |---|---|
    | 放电被拒 ＋ 充电被拒 | ★ **「方向无关」**（＝闸门坏了） |
    | **真相** | 两个方向**各自**拒了**不同**的一对 ⇒ 闸门**按方向判得好好的** |

    ★ 而"两条都拒 ⇒ 方向无关"**正是 G2 一开始把判据写错的同一个陷阱**
      （见 `docs/20260916_AR13_G2_方向区分性证据.md` §2.3）⇒ `pairwise_violation()` 报的是
      **全表逐对**，本函数报的是**表级**"到底哪一对把闸门打响"，两者互补。

    @return `{'lo','hi','lo_abs_ma','hi_abs_ma','delta','violation_ma','trend','brief'}`
    """
    s = sorted(levels, key=lambda l: l["mcu"])
    if len(s) < 2:
        return None
    best = None
    for i in range(1, len(s)):
        lo, hi = s[i - 1], s[i]
        delta = hi["abs_ma"] - lo["abs_ma"]
        bad = -delta if trend == "dis" else delta
        if bad > 0 and bad > (best["violation_ma"] if best else 0.0):
            best = {"lo": lo["mcu"], "hi": hi["mcu"],
                    "lo_abs_ma": lo["abs_ma"], "hi_abs_ma": hi["abs_ma"],
                    "delta": delta, "violation_ma": bad, "trend": trend,
                    "brief": "{}→{} 反向 {:.0f} mA".format(lo["mcu"], hi["mcu"], bad)}
    return best


def max_drop(levels):
    """⚠️ **兼容保留**：等价于「放电方向的违规」。新代码请用 [worst_violation]。"""
    return worst_violation(levels, "dis") or 0.0


def pairwise_violation(levels):
    """★★★★ **逐相邻对**给出两个方向的违规幅度（G2 的方向区分性判据）。

    ## 为什么需要它 —— 原 G2 判据在真机数据上**不成立**（2026-09-16 实测）

    G2 原本写作「**同一批数据换 `Trend` 必须合法**」。那个说法**只在单调数据上成立**：

    | 数据 | 本方向 | 换方向 |
    |---|---|---|
    | 单调上升 | 违规 / 无 | 无 / 违规 ⇒ ★ 判据成立 |
    | ★ **真机归档（非单调）** | 违规 | **也违规** ⇒ ⛔ 判据**假失败** |

    真机那份台账里 `|I|` 是**先升后降再升**的 ⇒ **两个方向各自都能找到违规的一对**
    （实测：放电 268 mA 来自 `178→497`，充电 724 mA 来自 `497→2000`，**是两对不同的档**）。
    ⇒ ★ 结论不是「闸门不敏感」，而是「**判据写错了**」——
      「两条都拒」与「方向无关」是**两件事**，而原判据把前者读成了后者。

    ## 真正与数据形状无关的性质

    **逐对**看：一对相邻档只可能朝一个方向违规 ——

    | Δ = `|I|(亮) − |I|(暗)` | 放电方向 | 充电方向 |
    |---|---|---|
    | `> 0`（变亮更耗电） | 合法 | ★ 违规 |
    | `< 0`（变亮更省电） | ★ 违规 | 合法 |
    | `= 0` | 合法 | 合法 |

    ⇒ ★★ **`min(pdis, pchg) == 0` 必须对【每一对】成立**。
    这是数学上的互斥，**与数据单调与否无关**，因此对**任意真机台账**都能判它。
    若 `trend` 被忽略（两个分支算出同一个数），这一条**必然失败** —— 判据**有牙**。

    @return `{'pairs': [{'lo','hi','delta','dis','chg'}...], 'worst_dis','worst_chg',
             'both_violating': [反例], 'decisive': 方向差最大的那一对}`
    """
    s = sorted(levels, key=lambda l: l["mcu"])
    pairs = []
    for i in range(1, len(s)):
        delta = s[i]["abs_ma"] - s[i - 1]["abs_ma"]
        # ★ 与 worst_violation 同口径：放电违规 = 变亮反而更省电；充电违规 = 变亮反而更耗电
        pdis, pchg = max(0.0, -delta), max(0.0, delta)
        pairs.append({"lo": s[i - 1]["mcu"], "hi": s[i]["mcu"], "delta": delta,
                      "dis": pdis, "chg": pchg, "exclusive": min(pdis, pchg) == 0.0})
    if not pairs:
        return {"pairs": [], "worst_dis": 0.0, "worst_chg": 0.0,
                "both_violating": [], "decisive": None}
    decisive = max(pairs, key=lambda p: abs(p["dis"] - p["chg"]))
    return {
        "pairs": pairs,
        "worst_dis": max(p["dis"] for p in pairs),
        "worst_chg": max(p["chg"] for p in pairs),
        "both_violating": [p for p in pairs if not p["exclusive"]],
        "decisive": decisive,
    }


def spread(levels):
    """档内稳健离散度的**最大**值（判读"这条线像不像一条线"）"""
    if not levels:
        return None
    return {"mcu_min": min(l["mcu"] for l in levels),
            "mcu_max": max(l["mcu"] for l in levels),
            "levels": len(levels),
            "n_total": sum(l["n"] for l in levels)}


# --------------------------------------------------------------- 真机判读

#: ★ 方向的**自证用语**（C1）—— 与 `TntgoBklCurve.Trend.riseVerb()/badVerb()` **必须一致**
TREND_RISE = {"dis": "上升", "chg": "下降"}
TREND_BAD = {"dis": "更省电", "chg": "更耗电"}


def check_mode(levels, trend, min_n=MIN_N_PER_LEVEL):
    """★★★ **单状态判读**（AR13：充电侧也走这里,不再有"只判放电"的暗门）。

    ★★ C1（2026-09-16）：判读语与 `facts` **都带上"是哪一对"** ——
       只报"反物理"而不报对，读的人**无法**分辨「两个方向各自拒了不同的一对」
       与「两个方向都拒同一批数 ⇒ 方向无关」。

    ★★ G1（2026-09-16）：**薄档（`n < min_n`）不进判据**，且判读语要说出剔了几档 ——
       见 `wire_levels`。`min_n=1` = 关掉 G1（只给"有牙证伪"用）。

    @return `(verdict, why, facts)`；`facts` 供调用方做机器判读（回归套件用）。
    """
    facts = {"levels": len(levels), "wire": 0, "thin_dropped": 0, "violation_ma": None,
             "thin": [], "fit": None, "trend": trend, "widening": None}
    wire = wire_levels(levels, min_n)
    facts["wire"] = len(wire)
    facts["thin_dropped"] = len(levels) - len(wire)
    if not levels:
        return "证据不足", "这一态一档都没有 ⇒ ★ 不给曲线", facts
    if not wire:
        return ("档位不足",
                "原 {} 档**全部**样本 < {} 条 ⇒ ★ G1 全剔 ⇒ 不给曲线".format(
                    len(levels), min_n), facts)
    if len(wire) < MIN_LEVELS:
        drop = "（★ G1 已剔除证据太薄的 {} 档）".format(facts["thin_dropped"]) \
            if facts["thin_dropped"] else ""
        return "档位不足", "可用只有 {} 档{} ⇒ 斜率无定义 ⇒ ★ 不给曲线".format(len(wire), drop), facts
    w = widening_pair(wire, trend)
    facts["violation_ma"] = w["violation_ma"] if w else None
    facts["widening"] = w
    if w is not None:
        state = "放电" if trend == "dis" else "充电"
        return ("反物理",
                "★ 有亮档比更暗的档还{}（{}，按{}态判：亮度↑ 时 |I| 应当{}）"
                "⇒ 有别的东西在漂 ⇒ 不采用".format(
                    TREND_BAD[trend], w["brief"], state, TREND_RISE[trend]),
                facts)
    if fit_line(wire) is None:
        return "档位不足", "拟合数值退化 ⇒ ★ 不给曲线", facts
    facts["fit"] = fit_line(wire)
    drop = "（★ G1 已剔除证据太薄的 {} 档）".format(facts["thin_dropped"]) \
        if facts["thin_dropped"] else ""
    return "可拟合", "★ 档位、单调性、样本量都过关{} ⇒ 可以出曲线".format(drop), facts


def verdict_of(dis_levels, chg_levels):
    """放电态判读（**返回值保持 2 元组** —— 有调用方按 2 个解包）。

    ★ AR13：`chg_levels` 不再是**没被读过的形参**。充电态如果**反物理**,
      会在放电判读之外**单独报出来**（返回值的第 3 项,旧调用方不受影响）。
    """
    v, why, facts = check_mode(dis_levels, "dis")
    _, cwhy, cfacts = check_mode(chg_levels, "chg")
    facts = dict(facts, charging={"verdict": check_mode(chg_levels, "chg")[0],
                                  "why": cwhy, **cfacts})
    return v, why, facts



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

    # ★★ G1（2026-09-16）：**两张表都要**
    #   `raw_*`  = 含薄档的完整分档 —— ★ **盘上 `bkl.curve` 存的就是它**（对账必须用它）
    #   `dis/chg` = 闸门真正吃的那张表（G1 已剔薄档）—— **拟合与判读用它**
    # ⚠️ 只打印一张会出事：拿过滤后的表去对盘上的原始表 ⇒ **假不一致**（本轮实际踩到）。
    raw_dis = plateaus([s for s in samples if not s["chg"]], min_n=1)
    raw_chg = plateaus([s for s in samples if s["chg"]], min_n=1)
    dis = plateaus([s for s in samples if not s["chg"]])
    chg = plateaus([s for s in samples if s["chg"]])
    v, why, vfacts = verdict_of(dis, chg)

    print("=" * 72)
    print("AR12b · 亮度 → 电流 曲线（MCU 域）· 独立复算")
    print("=" * 72)
    print(f"\n台账样本 {len(samples)} 条"
          f"（无 MCU 的旧格式 {stats['legacy_no_mcu']} 条，解析失败 {stats['bad']} 条）")
    if stats["legacy_no_mcu"]:
        print("  ★ 旧格式样本**不参与拟合**：⛔ 不用出厂曲线反解补 MCU")
        print("     （反解是推算值，且曲线一改就会追溯篡改已采样本）")

    for name, raw, lv in (("放电", raw_dis, dis), ("充电", raw_chg, chg)):
        print(f"\n【{name}】分档 {len(raw)} 个 ⇒ G1 后可用 {len(lv)} 个")
        print(f"  {'MCU':>6} {'n':>4}  {'中位|I|':>8}")
        for l in raw:
            thin = ""
            if l["n"] < MIN_N_PER_LEVEL:
                thin = "  ⛔ G1 剔除（样本 < {} ⇒ 中位数不代表这一档）".format(MIN_N_PER_LEVEL)
            print("  {:>6} {:>4}  {:>8.0f}{}".format(l["mcu"], l["n"], l["abs_ma"], thin))
        line = fit_line(lv)
        if line:
            print("  ⇒ |I| = {:.1f} + {:.4f}·MCU".format(line["a"], line["b"]))
            band = [min(x["mcu"] for x in lv), max(x["mcu"] for x in lv)]
            print("  ⇒ ★ 实测带 MCU {}~{}（★ 带外**不外推**；★ G1 之后带**只由可用档定**）"
                  .format(band[0], band[1]))
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
    print("  ⚠️ 对的是**同一件事的两种合法形态**：G1 **之前**落盘的是未过滤表；"
          "G1 **之后**落盘的就是过滤后的表（`refreshCurve` 走 `buildCurve()`）。")
    print("  ⛔ 两种都认，但**只认这两种** —— 别的任何差异都判红（不许把漂移放过去）。")
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
            # ★★★★ G1（2026-09-16）：盘上那份**有两种合法形态**，必须分开认：
            #   ① **G1 之前**落盘的：原样是**未过滤**的档位表
            #   ② **G1 之后**落盘的：产品写的就是**过滤后**的表（`refreshCurve` 走 `buildCurve()`）
            # ⚠️ 只按形态 ① 判 ⇒ G1 之后**每次**都会报"档位数不一致 ⇒ 有一边是错的"（本轮实测）。
            #    ⇒ 那是一条**假红**，而它的措辞（"有一边是错的"）会把人引去查一个**不存在的漂移**。
            #    ★ 认法：`theirs` 若**恰好等于** `mine` 的 G1 过滤结果 ⇒ 判为"G1 之后的形态"，✓。
            wire = wire_levels(mine)
            if len(theirs) != len(mine):
                if len(theirs) == len(wire) and _same_levels(wire, theirs):
                    print(f"  ✓ {name}：{len(theirs)} 档**逐项一致**"
                          f"（★ 盘上是 **G1 之后**的形态：原始 {len(mine)} 档 → 过滤 {len(wire)} 档）")
                    return 0
                print(f"  ✗ {name}：档位数 {len(mine)} vs {len(theirs)} ⇒ **不一致**"
                      f"（G1 过滤后本应是 {len(wire)} 档）")
                return 1
            bad = 0
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

        bad = cmp_list("放电", raw_dis, stored["dis"])
        bad += cmp_list("充电", raw_chg, stored["chg"])
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
        # ★ 传【放电】方向 —— 卡片这一支本来就是放电态的查询（充电态卡片不用曲线）
        r = describe(dis, mcu_now, trend="dis")
        print("\n" + "-" * 72)
        print(f"当前 MCU {mcu_now} ⇒ 放电态查询：where={r['where']}  "
              f"unusable={r['unusable']}  "
              f"|I|={'—' if r['abs_ma'] is None else format(r['abs_ma'], '.0f')}  "
              f"带={r['band']}")
        # ★★★ G3（2026-09-16）：**把卡片第二行逐字打出来**，并区分两种"给不出数"。
        #   理由：这句话是**用户唯一能看到的降级说明**，它必须能被离线复现
        #   （否则措辞只能靠读 Kotlin 源码来确认 —— 而那是"两份实现各说各话"的土壤）。
        band_s = ("（实测 {}~{}）".format(r["band"][0], r["band"][1])
                  if r["band"] else "")
        if r["unusable"] == "Rejected":
            card = "★ 曲线被拒（走向反物理）"
        elif r["unusable"] == "NotEnoughLevels":
            card = "★ 尚无实测档位"
        elif r["where"] == "InBand":
            card = "（曲线）"
        else:
            card = "★ 该处未测{}，未外推".format(band_s)
        print(f"  ⇒ 卡片第二行：@ 亮度 N% {card}")
        if r["unusable"] is not None:
            print("  ★ **不是「没测过」，是「测了但不可信」** —— "
                  "两者在卡片上必须分开说（前者该【等】，后者该【重采】）")
        elif r["where"] != "InBand":
            print("  ★ **不外推** —— 调用方应降级显示（用最近一端的实测值并注明「该处未测」）")

    print("\n" + "=" * 72)
    print(f"⇒ 判读（放电）：**{v}** —— {why}")
    cv = vfacts.get("charging", {})
    cverdict = cv.get("verdict")
    if cverdict and cverdict != "证据不足":
        # ★ AR13：充电态**单独**报判读（它有自己的物理方向,不能借用放电的结论）
        mark = "✓" if cverdict.startswith("可拟合") else "⚠"
        print(f"{mark} 判读（充电）：**{cverdict}** —— {cv.get('why', '')}")
    print("=" * 72)

    if args.json:
        print(json.dumps({"discharging": dis, "charging": chg,
                          "fit_dis": fit_line(dis), "fit_chg": fit_line(chg),
                          "stored": stored, "verdict": v, "why": why,
                          "verdict_charging": cverdict,
                          "violation_ma": vfacts.get("violation_ma"),
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
    # ④ ★ **反物理（放电）**：更亮的档反而更省电 ⇒ 必须判「反物理」
    lv4 = plateaus(seq([(63, 989, 10), (497, 2100, 10), (2000, 1200, 10)]))
    v4, _, _ = verdict_of(lv4, [])
    cases.append(("④ 放电：亮档反而更省电 ⇒ 判「反物理」", v4 == "反物理", True))
    # ④b ★★★ **方向负例（AR13）**：充电态的【正确】走向是**下降** ⇒ 不许判它反物理
    #     ⚠️ 这一条就是"把 max_drop 直接接进运行时"会犯的错 —— 必须挡住
    lv4b = plateaus(seq([(63, 3704, 10), (497, 3536, 10), (2000, 2888, 10)]))
    v4b, why4b, _ = check_mode(lv4b, "chg")
    cases.append(("④b ★ 充电：|I| 随亮度【下降】是物理正确的 ⇒ 必须判「可拟合」",
                  v4b.startswith("可拟合"), True))
    # ④c ★★ **方向正例（AR13）**：充电态出现【上升】⇒ 这才是充电侧的反物理
    #     ★ ⑲ 的教训：判据要能失败,而且失败要在**它该失败的那个方向**上
    lv4c = plateaus(seq([(63, 2888, 10), (497, 3536, 10), (2000, 3704, 10)]))
    v4c, _, f4c = check_mode(lv4c, "chg")
    cases.append(("④c ★ 充电：|I| 随亮度上升（与充电物理相反）⇒ 判「反物理」",
                  v4c == "反物理" and f4c["violation_ma"] is not None, True))
    # ④d ★★ **反向证伪**：同一份【上升】数据,在**放电**方向下是**合法**的
    #     ⇒ 证明这条闸门真的**看方向**,而不是"看见排序就报"
    v4d, _, _ = check_mode(lv4c, "dis")
    cases.append(("④d ★ 同一份上升数据在【放电】方向下必须合法（证明闸门真的看方向）",
                  v4d.startswith("可拟合"), True))
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

    # ── AR13：★★★ 曲线**落盘格式**的 Python↔Kotlin 对齐（不需要编译、不需要设备）
    #
    # ⚠️ 这里刻意**不照抄** Kotlin 的数值（照抄只能证明我抄得对）。
    #    做法：从**源码文本**里抽出 `levelsText` 的实现，用**它自己的表达式**渲染，
    #    再让 Python 的 `read_curve` 解析 ⇒ 验的是「两边格式真的对得上」和
    #    「Kotlin 的 Int 除法截断真的发生」。
    import re as _re
    _src_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "..", "projects", "mode-launcher", "src",
                             "mod-tntgo-battery", "src", "main", "java",
                             "com", "shware", "mode", "mod", "tntgo",
                             "TntgoBklCurve.kt")
    if os.path.exists(_src_path):
        _src = open(_src_path, encoding="utf-8").read()
        _m = _re.search(r"private fun medianInt\(v: List<Int>\): Int \{(.*?)\n    \}",
                        _src, _re.S)
        # ★ 抽到的是**真源码文本** —— 用「有没有 `/ 2`（整型相除）」判它是不是那个实现。
        #   ⚠️ 别写成 `// 2`：Kotlin 写的是 `s[n / 2]`（有空格）,不是 Python 的 `//`。
        _int_div = bool(_m) and "/ 2" in _m.group(1)

        def _k_median_int(vals):
            """★ 一个**独立实现**（不是从源码翻译来的,而是 Kotlin 整型语义的手写复刻）：
            `medianInt` 里的 `(a + b) / 2` 是 **Int/Int ⇒ 截断**,与 Python 的 `//` 相同。
            ⚠️ 只有 MCU 这种**非负**量才两者等价（负数时 Kotlin `-3/2 = -1`,Python `-3//2 = -2`）。
            """
            s = sorted(vals)
            n = len(s)
            return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) // 2

        lv_k = plateaus(seq([(63, 989, 10), (497, 1248, 9)]))
        text_k = ";".join("{0}:{1}:{2}".format(l["mcu"], "%.1f" % l["abs_ma"], l["n"])
                          for l in lv_k)
        xml_k = ('<map><string name="bkl.curve">{&quot;v&quot;:2,&quot;legacy&quot;:0,'
                 '&quot;chg&quot;:&quot;&quot;,&quot;dis&quot;:&quot;%s&quot;,'
                 '&quot;ts&quot;:1}</string></map>') % text_k
        back = _rc(xml_k)
        cases.append(("⑬ ★ 1 位小数 ＋ `%.1f` 的落盘文本 ⇒ Python 必须原值读回（不截断）",
                      back["dis"] == [[l["mcu"], l["abs_ma"], l["n"]] for l in lv_k], True))
        cases.append(("⑭ ★ Kotlin `medianInt` 的【整型截断】方向与 Python 一致（.0 形态）",
                      _int_div and _k_median_int([63] * 10 + [497] * 9) == 63, True))
    else:
        cases.append(("⑬ ★ 找不到 `TntgoBklCurve.kt` ⇒ **必须报失败**,不许静默跳过",
                      False, True))
        cases.append(("⑭ ★ 同上", False, True))

    # ── ★★★★★ AR13-G2（2026-09-16）：闸门**方向区分性**的回归判据
    #
    # ⚠️ 原 G2 判据写作「同一批数据换 Trend 必须合法」—— 实测在**真机归档上不成立**，
    #    因为那份台账是**非单调**的 ⇒ 两个方向各自都能找到违规的一对。
    #    本组用例把「真机归档」和「单调数据」**分开处理**，并用一个与数据形状无关的
    #    性质（逐对互斥）去盯住 `trend` 参数：
    #      · 真机归档  ⇒ 逐对互斥 ＋ 两方向的最大违规来自**不同的对**
    #      · 单调数据  ⇒ 换方向**必须**翻面（④d 已覆盖正例；⑱ 覆盖反例）
    _fix = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "fixtures", "20260916_ar13_g2_direction_ledger.xml")
    if os.path.exists(_fix):
        _xml = open(_fix, encoding="utf-8").read()
        _smp, _st = read_ledger(_xml)
        # ★★ G1（2026-09-16）：**两张表都要看**
        #    `_raw_*` = 含薄档的原始表（诊断用，`min_n=1`）
        #    `_dis/_chg` = **闸门真正吃的那张表**（G1 已剔薄档）—— 下面所有产品行为判据都用它
        _raw_dis = plateaus([s for s in _smp if not s["chg"]], min_n=1)
        _raw_chg = plateaus([s for s in _smp if s["chg"]], min_n=1)
        _dis = plateaus([s for s in _smp if not s["chg"]])
        _chg = plateaus([s for s in _smp if s["chg"]])
        _stored = read_curve(_xml)
        # ⚠️ 逐对判据钉在**闸门真正吃的表**上（G1 之后表里不该再有薄档）
        _pd, _pc = pairwise_violation(_dis), pairwise_violation(_chg)

        # ── ★★★★★ G1 的三条（2026-09-16）
        #
        # ⚠️ 这一组**必须存在**，因为「G1 有没有生效」在**别的判据上全都看不出来**：
        #    G1 生效与不生效，⑮⑯⑰ 之类都可能照样绿。它需要一个**能变红**的判据。
        cases.append(("G1-a ★★ 真机台账里 `n < MIN_N_PER_LEVEL` 的薄档**被剔掉**（放电 5→3、充电 4→3）",
                      len(_raw_dis) == 5 and len(_dis) == 3
                      and len(_raw_chg) == 4 and len(_chg) == 3
                      and [l["mcu"] for l in _raw_dis] == [44, 94, 178, 497, 2000]
                      and [l["mcu"] for l in _dis] == [44, 497, 2000]
                      and [l["mcu"] for l in _chg] == [178, 497, 2000], True))
        cases.append(("G1-b ★★ 剩下的每一档都 `n >= MIN_N_PER_LEVEL`（不许留一条薄档）",
                      bool(_dis) and bool(_chg)
                      and all(l["n"] >= MIN_N_PER_LEVEL for l in _dis + _chg), True))
        # ★★★ 这一条是 **G1 的因果证据**：同一份台账，**只在**开关 G1 时判读不同。
        #     没有它，"曲线现在能拟合了"可能被归因成**别的东西**（比如夹具换了）。
        cases.append(("G1-c ★★ 同一份台账：**关掉 G1 ⇒ 放电被拒（268）**；**开着 ⇒ 可拟合**"
                      "（⇒ 薄档就是那个唯一原因）",
                      worst_violation(_raw_dis, "dis") is not None
                      and abs(worst_violation(_raw_dis, "dis") - 268.0) < 0.5
                      and check_mode(_raw_dis, "dis", min_n=1)[0] == "反物理"
                      and worst_violation(_dis, "dis") is None
                      and check_mode(_dis, "dis")[0] == "可拟合", True))
        cases.append(("G1-d ★★ 充电侧同一因果：关 G1 ⇒ 拒（356）；开 ⇒ 可拟合",
                      worst_violation(_raw_chg, "chg") is not None
                      and abs(worst_violation(_raw_chg, "chg") - 356.0) < 0.5
                      and worst_violation(_chg, "chg") is None
                      and check_mode(_chg, "chg")[0] == "可拟合", True))
        # ★★ 负例守门：**不许把闸门修哑** —— 厚档真违规必须仍然被拒
        cases.append(("G1-e ★★ 负例守门：厚档（n≥8）真反物理**必须仍然被拒**"
                      "（放电更亮却更省电／充电更亮却更耗电）",
                      check_mode([{"mcu": 497, "abs_ma": 1344.0, "n": 23},
                                  {"mcu": 2000, "abs_ma": 1044.0, "n": 8}], "dis")[0] == "反物理"
                      and check_mode([{"mcu": 497, "abs_ma": 3536.0, "n": 70},
                                      {"mcu": 2000, "abs_ma": 3836.0, "n": 8}], "chg")[0] == "反物理", True))
        # ★★ 判据要长在**唯一入口**上：回读的曲线（不经过 `plateaus`）也不能绕过 G1
        cases.append(("G1-f ★★ 回读路径不能绕过 G1：`check_mode(含薄档的表)` 仍然把它们剔掉",
                      check_mode(_raw_dis, "dis")[2]["thin_dropped"] == 2
                      and check_mode(_raw_chg, "chg")[2]["thin_dropped"] == 1
                      and check_mode(_raw_dis, "dis")[2]["wire"] == 3, True))
        # ★ 全剔光 ⇒ 必须说"档位不足"，**不许**在空表上给曲线
        cases.append(("G1-g ★ 一档样本都不够 ⇒ 报【档位不足】，不给曲线",
                      check_mode([{"mcu": 44, "abs_ma": 980.0, "n": 1},
                                  {"mcu": 497, "abs_ma": 1344.0, "n": 2}], "dis")[0] == "档位不足", True))

        # ⑮ ★★ 核心性质：**逐对方向互斥**（与数据单调与否无关）
        cases.append(("⑮ ★★ 真机归档：**每一对**相邻档的违规方向都互斥（放电/充电不可同时违规）",
                      bool(_pd["pairs"]) and bool(_pc["pairs"])
                      and not _pd["both_violating"] and not _pc["both_violating"], True))
        # ⑯ ★ 逐对 max 必须**复现**表级判定 —— 防"只看第一对"式的假绿
        #    ⚠️ 这里**故意用原始表**（G1 之后的表是单调的 ⇒ 逐对判据没有可判的东西）。
        #       预期值**现从夹具算**，不写死（写死的转述值已经错过一次，见 ㉑ 的注释）。
        _pd_raw = pairwise_violation(_raw_dis)
        cases.append(("⑯ ★ 逐对 max 复现表级 worst_violation（同表同方向，逐项相符）",
                      _pd_raw["worst_dis"] == (worst_violation(_raw_dis, "dis") or 0.0)
                      and _pd_raw["worst_chg"] == (worst_violation(_raw_dis, "chg") or 0.0)
                      and abs(_pd_raw["worst_dis"] - 268.0) < 0.5
                      and abs(_pd_raw["worst_chg"] - 724.0) < 0.5, True))
        # ⑰ ★★ **方向真的换得动**：取一对真实测到的相邻档，把两档**调过来**
        #     ⇒ 违规方向必须**跟着翻**（否则闸门不看方向、只看"有没有下降"）
        _k = _pd_raw["decisive"]                   # 原始表上是 497 → 2000（Δ=724）
        _pair = [{"mcu": _k["lo"], "abs_ma": 1000.0, "n": 5},
                 {"mcu": _k["hi"], "abs_ma": 1000.0 + _k["delta"], "n": 5}]
        _db = worst_violation(_pair, "dis")        # 单调上升 ⇒ 放电合法、充电违规
        _cb = worst_violation(_pair, "chg")
        _pair_rev = [{"mcu": _k["lo"], "abs_ma": 1000.0 + _k["delta"], "n": 5},
                     {"mcu": _k["hi"], "abs_ma": 1000.0, "n": 5}]
        _da = worst_violation(_pair_rev, "dis")    # 翻过来 ⇒ 放电违规、充电合法
        _ca = worst_violation(_pair_rev, "chg")
        cases.append(("⑰ ★★ 同一对真机档位【调换】⇒ 违规方向必须跟着翻（闸门真的看 trend）",
                      _db is None and _cb is not None and _da is not None and _ca is None, True))
        # ⑱ ★★ **负例**：原 G2 判据（"换方向必须合法"）在**未过滤的**真机数据上**必须失败**
        #     —— 把它钉住，防止有人"修好"这条判据而把一次**假失败**当成真结论。
        #     ★ G1（2026-09-16）之后这条**只剩历史意义**：闸门吃的那张表已经单调，
        #       于是"两个方向都能找到违规的一对"**不再成立**（这正是 G1 要的结果）。
        #       ⇒ 判据改成：**原始表**上成立、**G1 之后的表**上不成立。两边都钉住。
        cases.append(("⑱ ★★ 原 G2 判据在**未过滤**真机数据上必须不成立；在 **G1 之后**的表上"
                      "变成【两个方向都合法】（＝ G1 真的把误杀消掉了）",
                      (worst_violation(_raw_dis, "chg") is not None)
                      and (worst_violation(_raw_chg, "dis") is not None)
                      and worst_violation(_dis, "dis") is None
                      and worst_violation(_chg, "chg") is None, True))
        # ⑲ ★ 夹具必须**真的**是那台设备的那份台账（不是手抄的几档）
        #    ⚠️ 与盘上 `bkl.curve` 对账的是**原始档位表**（盘上存的就是它）
        cases.append(("⑲ ★ 夹具保真：240 条样本 ＋ 24 条旧格式 ＋ 表与盘上 `bkl.curve` 逐项一致"
                      "（**用原始表对** —— 盘上存的是未过滤的档位）",
                      len(_smp) == 240 and _st["legacy_no_mcu"] == 24
                      and _stored is not None and not _stored["parse_broken"]
                      and _stored["legacy"] == 24
                      and [[l["mcu"], l["abs_ma"], l["n"]] for l in _raw_dis] == _stored["dis"]
                      and [[l["mcu"], l["abs_ma"], l["n"]] for l in _raw_chg] == _stored["chg"], True))

        # ── ★★★★★ C1（2026-09-16）：判读**必须说出"是哪一对"**
        #
        # ⚠️⚠️ **G1 之后，C1 的动机在【这份真机台账上】不再出现** —— 必须如实记下：
        #   C1 原本的理由是「放电被拒 ＋ 充电被拒」会被读成「方向无关」。
        #   而 G1 把薄档剔掉之后，**这份台账的两个方向都判"可拟合"** ⇒ 那个歧义
        #   **在这份数据上不存在了**。C1 的机制（指名那一对）**仍然必须留着** ——
        #   将来真出现"厚档反物理"时它照样是唯一能自证方向的东西（见 G1-e）。
        #   ⇒ 所以下面改用**原始表**验机制，并**另加**一条钉住"G1 之后两向都合法"。
        # ⚠️ 两条硬要求：
        #   ① the pair 必须**由闸门自己的函数产出**（不许在这里重算 Δ —— 那就是自证，G2 §5.1）
        #   ② 它必须与 `worst_violation` **数值一致**（同一判据的两种粒度，不许漂）
        _wd = widening_pair(_raw_dis, "dis")
        _wc = widening_pair(_raw_chg, "chg")
        cases.append(("⑳ ★★ 表级违规必须能指名【哪一对】（原始放电表 178→497 反向 268 mA）",
                      _wd is not None and (_wd["lo"], _wd["hi"]) == (178, 497)
                      and abs(_wd["violation_ma"] - 268.0) < 0.5
                      and _wd["brief"] == "178→497 反向 268 mA", True))
        # ⚠️ 这里原先按 G2 证据文档 §3.2 的**转述表**写成 `497→2000 / 724` —— **写错了**：
        #    `497→2000 反向 724` 是【放电表】在【充电方向】下的那一对（见 ㉒）。
        #    充电表在**本方向**下的那一对是 `23→178`（`3704→4060`，反向 **356**）。
        #    ⇒ ★ 教训与 G2 同一族：**表格里的转述不能当预期值**，预期必须现从夹具算。
        cases.append(("㉑ ★★ 充电表也指名（原始充电表 23→178 反向 356 mA）＋ 与 worst_violation 数值一致",
                      _wc is not None and (_wc["lo"], _wc["hi"]) == (23, 178)
                      and abs(_wc["violation_ma"] - 356.0) < 0.5
                      and _wc["violation_ma"] == worst_violation(_raw_chg, "chg")
                      and _wd["violation_ma"] == worst_violation(_raw_dis, "dis"), True))
        # ㉒ ★★ **方向自证**：同一张表在两个方向下指名的那一对**必须不同** ——
        #    这一条正是"设备日志要带对与方向"要保住的性质（否则歧义又回来了）。
        _wd_wrong_trend = widening_pair(_raw_dis, "chg")
        cases.append(("㉒ ★★ 判读语里带的方向必须换得动（同一张表换方向 ⇒ 指名的那一对**必须变**）",
                      _wd_wrong_trend is not None
                      and (_wd_wrong_trend["lo"], _wd_wrong_trend["hi"]) != (_wd["lo"], _wd["hi"])
                      and _wd_wrong_trend["trend"] == "chg" and _wd["trend"] == "dis", True))
        # ㉓ ★ 判读语（why）里**真的**带上了对与方向（不只是在 facts 里）
        #    ⚠️ 用 `min_n=1` 才拿得到那句"反物理" —— G1 之后闸门吃的那张表是单调的。
        _why_c1 = check_mode(_raw_dis, "dis", min_n=1)[1]
        cases.append(("㉓ ★ 判读语（why）里**真的**出现那一对与方向（人读的那句，不只是 facts）",
                      "178→497" in _why_c1 and "放电" in _why_c1 and "上升" in _why_c1, True))
        # ㉔ ★★ G1 之后，真机台账的两个方向都要判【可拟合】（这才叫"修好了"）
        cases.append(("㉔ ★★ G1 之后真机台账**两个方向都判可拟合**（放电 3 档／充电 3 档）",
                      check_mode(_dis, "dis")[0] == "可拟合"
                      and check_mode(_chg, "chg")[0] == "可拟合", True))
    else:
        cases.append(("⑮ ★ 找不到真机夹具（`scripts/fixtures/…g2_direction_ledger.xml`）"
                      "⇒ **必须报失败**,不许静默跳过", False, True))
        for _n in ("G1-a", "G1-b", "G1-c", "G1-d", "G1-e", "G1-f", "G1-g",
                   "⑯", "⑰", "⑱", "⑲", "⑳", "㉑", "㉒", "㉓", "㉔"):
            cases.append(("{} ★ 同上（夹具不在 ⇒ 不许静默跳过）".format(_n), False, True))

    # ── ★★★★★ G1 的**真机端到端**证据（装机后 adb pull 的那份台账，已提交为夹具）
    #
    # ⚠️ 为什么必须单独一条：上面 ⑮–㉔ 用的是 **G1 之前**落盘的那份夹具，
    #    它的放电表**只有 2 个薄档**。而装机后新采的样本里又多出一条 `mcu=1000 n=1`
    #    ⇒ 薄档数从 **2** 变成 **4**。★ "G1 在**新数据**上也剔得对"**只在这份夹具上看得到**。
    # ⛔ 不提交这份夹具 ⇒ `.ref/` 被 gitignore ⇒ 这条在新克隆上会**静默消失**
    #    （AR13 已经为同一件事踩过一次，见 g2 夹具的注释）。
    # ⚠️ 本文件里**没有** `SCRIPTS` 这个名字（那是 `run_bkl_tests.py` 的常量）
    #    ⇒ 必须**自己取**当前文件所在目录（第一次写就踩到 `NameError`）。
    _here = os.path.dirname(os.path.abspath(__file__))
    g1fix = os.path.join(_here, "fixtures", "20260916_g1_post_deploy_ledger.xml")
    if os.path.exists(g1fix):
        _x = open(g1fix, encoding="utf-8").read()
        _s, _st = read_ledger(_x)
        _rd = plateaus([q for q in _s if not q["chg"]], min_n=1)
        _rc = plateaus([q for q in _s if q["chg"]], min_n=1)
        _d = plateaus([q for q in _s if not q["chg"]])
        _c = plateaus([q for q in _s if q["chg"]])
        _stored = read_curve(_x)
        cases.append(("G1-装机-a ★★ 装机后台账：薄档 **4** 个被剔（放电 `94/178/1000` ＋ 充电 `23`）"
                      "⇒ 与设备日志「★ G1 剔除样本 < 3 的档 4 个」**逐字吻合**",
                      len(_s) == 240 and _st["legacy_no_mcu"] == 23
                      and [l["mcu"] for l in _rd] == [44, 94, 178, 497, 1000, 2000]
                      and [l["mcu"] for l in _d] == [44, 497, 2000]
                      and [l["mcu"] for l in _rc] == [23, 178, 497, 2000]
                      and [l["mcu"] for l in _c] == [178, 497, 2000]
                      and (len(_rd) - len(_d)) + (len(_rc) - len(_c)) == 4, True))
        cases.append(("G1-装机-b ★★ 装机后台账：两条曲线都判【可拟合】"
                      "（＝设备日志「放电档位 3 个 ⇒ 可插值｜充电档位 3 个 ⇒ 可插值」）",
                      check_mode(_d, "dis")[0] == "可拟合"
                      and check_mode(_c, "chg")[0] == "可拟合"
                      and len(_d) == 3 and len(_c) == 3, True))
        cases.append(("G1-装机-c ★★ 盘上 `bkl.curve` 是 **G1 之后**的形态："
                      "逐项等于过滤后的表（不是原始表）",
                      _stored is not None and not _stored["parse_broken"]
                      and _same_levels(_d, _stored["dis"])
                      and _same_levels(_c, _stored["chg"]), True))
    else:
        cases.append(("G1-装机-a ★ 找不到装机后台账夹具"
                      "（`scripts/fixtures/20260916_g1_post_deploy_ledger.xml`）⇒ **报失败**",
                      False, True))
        for _n in ("G1-装机-b", "G1-装机-c"):
            cases.append(("{} ★ 同上（夹具不在 ⇒ 不许静默跳过）".format(_n), False, True))

    # ── ★★★★ G3（2026-09-16）：给不出数的【原因】必须与【位置】分开 ────────────
    #    ⛔ 这一组守的是一个**已经发生过的 bug**：G3 之前
    #       「曲线被拒」被压成 `where != InBand` ⇒ 卡片把它说成"该处未测"。
    #       两个原因对应**相反的动作**（等 vs 重采），所以必须能分开。
    def _card_reason(r):
        """★ 与 Kotlin `powerNoteText()` **同口径**的措辞 —— 只用来判"分不分得开"，
        不作为产品文案的唯一定义（真正的措辞由 `run_bkl_tests` 的结构判据钉住）。"""
        if r["unusable"] == "Rejected":
            return "★ 曲线被拒（走向反物理）"
        if r["unusable"] == "NotEnoughLevels":
            return "★ 尚无实测档位"
        if r["where"] == "InBand":
            return "（曲线）"
        return "★ 该处未测，未外推"

    _g3_rej = plateaus(seq([(63, 989, 10), (497, 2100, 10), (2000, 1200, 10)]))
    _g3_thin = plateaus(seq([(44, 980, 1), (497, 1344, 1)]))     # 全被 G1 剔掉
    _r_rej_in = describe(_g3_rej, 497)                            # ★ 带内、但被拒
    _r_rej_out = describe(_g3_rej, 2000)                          # ★ 带上沿、被拒
    _r_thin = describe(_g3_thin, 497)
    _r_ok = describe(plateaus(seq([(63, 989, 10), (497, 1248, 10), (2000, 2113, 10)])), 497)
    cases.append(("G3-a ★★ 曲线被拒时 `unusable=Rejected`（**带内也照样说被拒**）",
                  _r_rej_in["unusable"] == "Rejected"
                  and _r_rej_in["where"] == "InBand"
                  and _r_rej_in["abs_ma"] is None, True))
    cases.append(("G3-b ★★ 「被拒」与「该处未测」在卡片上是**两句不同的话**"
                  "（＝G3 要修的那个 bug；两句一样就报红）",
                  _card_reason(_r_rej_in) != _card_reason(describe(lv, 3000))
                  and _card_reason(_r_rej_in) != _card_reason(_r_thin), True))
    cases.append(("G3-c ★ 「档位全被剔」⇒ `NotEnoughLevels`（不是 Rejected，也不是带外）",
                  _r_thin["unusable"] == "NotEnoughLevels"
                  and _r_thin["where"] == "NoLevels", True))
    cases.append(("G3-d ★ 可用时 `unusable=None`（**不许把好数据的结论也标成不可信**）",
                  _r_ok["unusable"] is None and _r_ok["abs_ma"] is not None, True))
    cases.append(("G3-e ★ 被拒时**位置照旧报对**（上沿 2000 仍是 InBand，不是 AboveBand）"
                  "—— 位置与原因**正交**",
                  _r_rej_out["where"] == "InBand"
                  and _r_rej_out["unusable"] == "Rejected", True))

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
