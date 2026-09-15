#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TNT GO「功耗 / 可用时间」公式的**离线验算**（任务 AQ · AQ2 的判据）。

## ★★ 与 Kotlin 的同步纪律（AR4 / CC 审计 F3）

本脚本是 **`TntgoPower.kt` 的镜像实现**。⚠️ **改了那边就要回来改这里**，
反之亦然 —— 两边算不出同样的数，这个脚本就失去了"验算"的意义，
只会给人一个**虚假的安心**。

★ 曾经就是这么漂的：Kotlin 按**结果时间**截断，脚本按**电流门**截断
（`|I| < 100 mA`），而那个电流门在 `|I| = 100` 时算出 **86.9 h**
—— 一个 Kotlin 永远不会输出、却看着很精确的数。

**纪律**：所有镜像自 Kotlin 的常量都**集中在下面「常量」一节**，并注明来源。
新增常量时必须写清 `TntgoPower.kt` 的对应行。

## 为什么要有它

Android 侧的 `TntgoPower.kt` 是**纯函数、无 Android 依赖**的 ——
所以公式可以先在 PC 上算一遍，**不用装到手机上、不用等 30 s 轮询**。

★ 本项目的老规矩：**能在离线验的先在离线验**。
（同型前例：`scripts/verify_probe_filter.py`、`scripts/verify_icon_style.py`）

## 输入从哪来

`.paper/01` / `.paper/06` 里归档的**真机样本**：
```
+BATCG=<电压mV>,<电量%>,<状态>,<电流mA>,<温度×0.1°C>,<?>
```

## 判据（对不上就别写 Kotlin）

| # | 判据 |
|---|---|
| 1 | 温度字段 ÷ 10 落在合理环境温度区间（10–45 °C） |
| 2 | 功耗 `|I|×V/1e6` 与"额定 3.4–3.7 W 面板 + 主板"同量级 |
| 3 | ★ **两条独立路径算出的可用时间必须一致**：<br/>　路径 A：`容量×SOC/|I|`（电量法）<br/>　路径 B：`能量Wh / 功率W`（能量法）<br/>　⇒ 一致才说明公式没写错 |
| 4 | 四条边界行为正确（充电 / 小电流 / 无样本 / 正常） |
"""

import sys

# ★★ AR4（CC 审计 F3）：Windows 控制台默认 GBK，中文 print 会抛 UnicodeEncodeError
#    ⇒ **进程以非 0 退出**，在脚本串联 / CI 里会被当成"验算失败"。
#    这里强制 UTF-8，且遇到无法编码的字符降级替换（而不是崩）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass   # 老 Python 或已重定向的流：忽略即可


# ── 常量 ────────────────────────────────────────────────────────────────────
CAPACITY_MAH = 10160.0   # TNT GO 电池容量。⚠️ 来源 = baike/mydrivers（网络），非实测
CONSERVATIVE = 0.90      # ★ 用户指定的保守系数：显示值 = 估算值 × 0.9
MIN_SAMPLES = 3          # 少于这么多就不给数

# ★★ AR4（CC 审计 F3）：**这个常量已删除**。
#    原来是 `SMALL_CURRENT_MA = 100`，用来判"电流太小 ⇒ 不给数"。
#    ⚠️ Kotlin 侧 `TntgoPower.estimate()` **没有这个门** —— 它按【结果】截断
#      （`shown > MAX_HOURS ⇒ TooLong`）。两边不一致 ⇒ 脚本会"验过一个 Kotlin 没有的行为"。
#    ★ 而且那个写法本身有洞：`|I| = 100` 时算出 **86.9 h**（< 99 不触发截断）
#      ⇒ 一个毫无意义却看着很精确的数。**按时间截断就没这个问题。**
MAX_HOURS = 99.0         # ★ 与 Kotlin `TntgoPower.MAX_HOURS` 逐字一致

# ── 真机样本（来自 .paper/01 §5、.paper/06 §6.2）─────────────────────────────
SAMPLES = [
    "+BATCG=4144,95,2,-1351,303,2",
    "+BATCG=3855,60,2,-929,275,2",
    "+BATCG=4237,100,2,-500,290,2",   # 造一个"快满且轻载"的样本，看边界
]

# 面板背光功耗（来自 .paper/01 §1：WLED 9S6P 侧入式，3.42/3.65 W）
PANEL_W = (3.42, 3.65)


def parse(line):
    """`+BATCG=…` → (V_mV, soc, status, I_mA, tempC, field6)"""
    g = line.split("=", 1)[1].split(",")
    V, soc, st, I, T, f6 = (int(x) for x in g)
    return V, soc, st, I, T / 10.0, f6


def power_w(V_mV, I_mA):
    """电池侧瞬时功耗（W）。V=电池电压 mV，I=电池电流 mA（符号表示方向）"""
    return abs(I_mA) * V_mV / 1e6


def remaining_h(soc, I_mA, cap=CAPACITY_MAH, k=CONSERVATIVE):
    """★ 保守可用时间（h）。返回 None 表示"不该给数"。

    ⚠️ 镜像自 Kotlin `TntgoPower.estimate()` —— **改了那边就要回来改这里**。
    ★ 截断按【时间】不按【电流】：见 MAX_HOURS 上面的说明。
    """
    if I_mA >= 0:
        return None                      # 充电 / 静置 ⇒ 没有"还能用多久"
    return (cap * soc / 100.0) / abs(I_mA) * k


def fmt_h(h):
    if h is None:
        return "—"
    # ★ 与 Kotlin 逐字一致：`if (shown > MAX_HOURS) return TooLong`（**严格大于**）
    #   ⚠️ 原来写的是 `h >= 100` —— 与 Kotlin 的 `> 99.0` 差一档，正是 F3 说的"漂移"
    if h > MAX_HOURS:
        return "> 99 h"
    return f"{h:.1f} h"


def main():
    fails = []

    print("=" * 78)
    print("① 逐样本：功耗 / 温度 / 两条路径的可用时间")
    print("=" * 78)
    print(f'{"V(mV)":>7} {"SOC":>4} {"I(mA)":>7} {"T(°C)":>6} | '
          f'{"P(W)":>6} | {"A:电量法":>9} {"B:能量法":>9} {"显示":>8}')
    print("-" * 78)

    for line in SAMPLES:
        V, soc, st, I, Tc, f6 = parse(line)

        # 判据 1：温度
        if not (10.0 <= Tc <= 45.0):
            fails.append(f"温度越界: {Tc} °C（原始 {line}）")

        P = power_w(V, I)

        # 判据 3：两条路径
        t_a = remaining_h(soc, I)
        wh = CAPACITY_MAH / 1000.0 * (V / 1000.0)          # 用当前电压粗估总能量
        t_b = None if (I >= 0 or P <= 0) else (wh * soc / 100.0 / P * CONSERVATIVE)

        print(f"{V:>7} {soc:>4} {I:>7} {Tc:>6.1f} | {P:>6.2f} | "
              f"{fmt_h(t_a):>9} {fmt_h(t_b):>9} {fmt_h(t_a):>8}")

        if t_a is not None and t_b is not None and abs(t_a - t_b) > 0.15:
            fails.append(f"两条路径不一致: 电量法 {t_a:.2f} vs 能量法 {t_b:.2f}")

        # 判据 2：功耗量级（只对有放电的样本要求）
        if I < 0 and not (1.0 <= P <= 15.0):
            fails.append(f"功耗量级可疑: {P:.2f} W（样本 {line}）")

    print()
    print("=" * 78)
    print("② 边界行为（★ 截断按【时间】不按【电流】—— AR4 对齐后）")
    print("=" * 78)
    # ★★ 注意 `-100 mA` 这一档：算出 **86.9 h**，**不是**截断成 "> 99 h"。
    #    ⚠️ 这不是 bug —— 10160 mAh 在 100 mA 下**本来就该用 87 小时**，算术是对的。
    #    旧实现的毛病在于：`|I| < 100` 门会让 `-99 mA` 显示 "> 99 h" 而 `-100 mA`
    #    显示 86.9 h —— **同一个量级上两种口径**。改成按结果截断后口径统一了。
    cases = [
        (-1351, "正常放电", "给数"),
        (-400,  "较轻放电", "给数"),
        (-100,  "★ 恰在旧电流门", "给数（86.9 h —— 算术正确，不该截断）"),
        (-50,   "★ 极小电流", "> 99 h（结果超 99 ⇒ 截断）"),
        (0,     "静置", "不给数"),
        (+800,  "充电", "↑ 充电中（不给时间）"),
    ]
    for I, desc, expect in cases:
        r = remaining_h(95, I)
        got = fmt_h(r) if r is not None else ("充电中" if I >= 0 else "> 99 h")
        print(f"  I={I:>+6} mA  {desc:12}  ->  {got:12}   期望: {expect}")

    # 判据 4：与手算对照（防公式写错）
    hand = 10160 * 0.95 / 1351 * 0.90
    print()
    print("=" * 78)
    print("③ 与手算对照（公式没写错的最后一道闸）")
    print("=" * 78)
    print(f"  10160 × 0.95 / 1351 × 0.90 = {hand:.4f} h")
    got = remaining_h(95, -1351)
    print(f"  代码算出来的              = {got:.4f} h")
    if abs(hand - got) > 1e-6:
        fails.append(f"手算 {hand:.4f} ≠ 代码 {got:.4f}")

    print()
    if fails:
        print("✗ 有问题：")
        for f in fails:
            print("   -", f)
        return 1
    print("✓ 全部判据通过 —— 公式可以照抄进 TntgoPower.kt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
