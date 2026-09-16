#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性诊断：真实台账里有没有「连续两条样本逐字相同」。

## 为什么这个数要紧

`TntgoBklProfile.refreshCurve()` 的节流键是 `"<条数>#<末条样本>"`。
台账是**环形**保留 `KEEP` 条 ⇒ 装满后**条数恒为 KEEP**。
于是只要**新追加的那条样本与上一拍逐字相同**，键就**不变** ⇒ 提前 return。

此时有两种情形，**必须分开**：

| 情形 | 台账内容 | 不重算对不对 |
|---|---|---|
| 全部 KEEP 条逐字相同 | **真的没变** | ✅ 对（曲线本来就一样） |
| 缓冲里还有别的档位的老样本 | **轮转掉了老样本 ⇒ 内容变了** | ⛔ **错**：档位计数会漂，曲线已陈旧 |

⇒ 所以只要「连续两条逐字相同」出现过，且当时缓冲是**混合**的，这个洞就是**活的**。

本脚本**只报事实**（照 `watch_bkl_rejection.py` 的纪律②）：给出相邻相同的次数、
游程长度、缓冲是否混合。**怎么判读由人决定。**

用法：
    python scripts/_diag_dup_run.py .ref/c1/_hb_t2.txt
"""

import sys
import os

# ★ 控制台是 GBK ⇒ 直印中文/箭头会 UnicodeEncodeError（本脚本第一次跑就崩在最后一行）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:                                                          # noqa: BLE001
    pass

if len(sys.argv) < 2:
    print("用法: python scripts/_diag_dup_run.py <台账样本串文件>")
    sys.exit(2)

p = sys.argv[1]
with open(p, encoding="utf-8") as f:
    raw = f.read().strip()

parts = [x for x in (y.strip() for y in raw.split(",")) if x]
n = len(parts)

# 相邻逐字相同
adj = [i for i in range(1, n) if parts[i] == parts[i - 1]]

# 游程
runs = []
cur = 1
for i in range(1, n):
    if parts[i] == parts[i - 1]:
        cur += 1
    else:
        if cur > 1:
            runs.append(cur)
        cur = 1
if cur > 1:
    runs.append(cur)

print("文件        : {}".format(os.path.basename(p)))
print("样本条数    : {}".format(n))
print("去重后取值  : {}".format(len(set(parts))))
print("相邻逐字相同: {} 处 / {} 个相邻对".format(len(adj), max(n - 1, 0)))
print("连续游程>1  : {}".format(sorted(runs, reverse=True)))
if adj:
    print("  前 10 处位置与取值：")
    for i in adj[:10]:
        print("    idx={:<4} value={}".format(i, parts[i]))
print("首 3 条     : {}".format(parts[:3]))
print("末 3 条     : {}".format(parts[-3:]))

# 缓冲是否"混合"：按 (ui,mcu) 去重后是不是多于 1 个档
levels = sorted({tuple(x.split(":")[:2]) for x in parts})
print("出现过的(ui,mcu)档位数: {} -> {}".format(len(levels), levels[:12]))

# 结论性事实（不是判读）：把"洞是否可能触发"用两个布尔量摆出来
mixed = len(levels) > 1
print()
print("★ 事实①：缓冲是否混合（档位 >1）      = {}".format(mixed))
print("★ 事实②：是否出现过连续两条逐字相同  = {}".format(bool(adj)))
print("★ 两者同时为真 ⇒ 节流键「条数#末条」在那些时刻"
      "**无法感知轮转**（内容变了但键没变）")
