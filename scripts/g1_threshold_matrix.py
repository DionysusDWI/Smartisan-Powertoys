# -*- coding: utf-8 -*-
"""★ G1 口径选型：**用真机台账**在候选口径下逐一算，而不是拍脑袋定 K。

## 为什么要这个脚本（而不是直接在 Kotlin 里改个常数）

G1 的验收是**双向**的：

| 方向 | 判据 |
|---|---|
| **不该杀** | 去掉薄档后，两条曲线**走向必须与物理一致**（可拟合） |
| **不许修哑** | 闸门对**厚档**的真违规**必须仍然拒**（否则就是把闸门改哑了） |

⇒ 只要有一个方向没数据，就会滑向"调常数直到曲线好看"——那正是**把判据迁就数据**。
本脚本两个方向都算，并把**每一条被剔除的档**连同原因打印出来。

## 用法

    python scripts/g1_threshold_matrix.py                      # 两个真机夹具都算
    python scripts/g1_threshold_matrix.py --xml <path>          # 只算一份

★ 只读，不碰设备、不写任何文件。
"""
import argparse
import io
import os
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import analyze_bkl_curve as A            # noqa: E402
from bkl_common import read_ledger       # noqa: E402


def mad(v):
    m = A.median(v)
    if m is None:
        return None
    return A.median([abs(x - m) for x in v])


def level_spread(levels, i, samples):
    """档 i 的（min, max, MAD）—— ★ 要靠**原始样本**算，`Level` 只存中位数。"""
    return None


def samples_by_level(samples, levels, key="mcu"):
    """把原始样本按**已定档位**归桶（复刻 plateaus 的判据：与簇内最小值差 ≤ tolerance）。

    ⚠️ 这里**不重写**分档算法：直接按 `plateaus()` 已给出的档位 MCU 就近归属，
    只为算档内离散；分档本身仍由产品同款 `plateaus()` 决定。
    """
    tol = A.TOLERANCE_MCU
    out = []
    for lv in levels:
        lo, hi = lv["mcu"] - tol, lv["mcu"] + tol
        bucket = [s for s in samples if s["mcu"] is not None and lo <= s["mcu"] <= hi]
        out.append(bucket)
    return out


def spread_of(bucket, chg):
    """档内 |I| 的离散（用**绝对电流**，与 Level.abs_ma 同量纲）。"""
    v = [abs(s["ma"]) for s in bucket]
    if not v:
        return (None, None, None)
    return (min(v), max(v), mad(v))


def triage(levels, buckets, min_n):
    """按 `n >= min_n` 剔除薄档，返回 (可用档, [(被剔档, 原因)])。"""
    keep, drop = [], []
    for lv, b in zip(levels, buckets):
        if lv["n"] < min_n:
            rng = None
            if b:
                v = [abs(s["ma"]) for s in b]
                rng = max(v) - min(v)
            drop.append((lv, "n={} < {}".format(lv["n"], min_n), rng))
        else:
            keep.append(lv)
    return keep, drop


def check(levels, trend):
    """★ 走**闸门自己的判读**（`check_mode`），不重算 delta —— 重算就是自证。

    ⚠️ `check_mode` 返回 **3** 元组 `(verdict, why, facts)`（不是 2 个）。
    """
    verdict, why, facts = A.check_mode(levels, trend)
    return verdict, why, facts


def run_one(path, label, ks=(1, 2, 3, 4, 5)):
    xml = open(path, encoding="utf-8").read()
    samples, st = read_ledger(xml)
    print("=" * 76)
    print("★ {}".format(label))
    print("  文件 {}  （{} B）".format(os.path.relpath(path), os.path.getsize(path)))
    print("  样本 {} 条（旧格式 {} 条）".format(st["total"], st["legacy_no_mcu"]))
    print("=" * 76)

    # ⚠️ 显式 `min_n=1`：本脚本要拿**含薄档的原始表**，自己再逐级筛 —— 复现门槛矩阵
    dis_all = A.plateaus([s for s in samples if not s["chg"]], min_n=1)
    chg_all = A.plateaus([s for s in samples if s["chg"]], min_n=1)
    dis_b = samples_by_level([s for s in samples if not s["chg"]], dis_all)
    chg_b = samples_by_level([s for s in samples if s["chg"]], chg_all)

    for name, levels, buckets, trend in (("放电", dis_all, dis_b, "dis"),
                                         ("充电", chg_all, chg_b, "chg")):
        print("\n【{}】原始 {} 档 —— 逐档样本数 / 档内离散".format(name, len(levels)))
        print("      MCU    n     中位|I|   档内 min~max     档内跨度   MAD")
        for lv, b in zip(levels, buckets):
            lo, hi, m = spread_of(b, trend)
            if lo is None:
                print("  {:>7} {:>4} {:>10.0f}   （无样本）".format(lv["mcu"], lv["n"], lv["abs_ma"]))
            else:
                print("  {:>7} {:>4} {:>10.0f}   {:>7.0f}~{:<7.0f} {:>8.0f} {:>6.0f}"
                      .format(lv["mcu"], lv["n"], lv["abs_ma"], lo, hi, hi - lo, m or 0))

    print("\n" + "-" * 76)
    print("★ 门槛矩阵：每一种 K 下，两条曲线各自的判读（★ 走闸门自己的 check_mode）")
    print("-" * 76)
    print("   K | 放电：可用档数 → 判读       | 充电：可用档数 → 判读")
    for k in ks:
        cells = []
        for name, levels, buckets, trend in (("放电", dis_all, dis_b, "dis"),
                                             ("充电", chg_all, chg_b, "chg")):
            keep, drop = triage(levels, buckets, k)
            verdict, why, facts = check(keep, trend)
            w = facts.get("widening")
            detail = verdict
            if w:
                detail += "（{}→{} 反向 {:.0f}）".format(w["lo"], w["hi"], w["violation_ma"])
            cells.append("{:>2} 档 → {}".format(len(keep), detail))
        print("  {:>2} | {:<28} | {}".format(k, cells[0], cells[1]))

    # ── 负例守门：删掉薄档之后，**真正该拒的**还拒不拒
    print("\n" + "-" * 76)
    print("★ 负例守门（不许把闸门修哑）：厚档真违规必须**仍然被拒**")
    print("-" * 76)
    neg = [
        ("放电·厚档反物理（2000 比 497 更省电）",
         [dict(mcu=497, abs_ma=1344.0, n=23), dict(mcu=2000, abs_ma=2100.0, n=8)], "dis"),
        ("充电·厚档反物理（2000 比 497 更耗电）",
         [dict(mcu=497, abs_ma=3536.0, n=70), dict(mcu=2000, abs_ma=3100.0, n=8)], "chg"),
    ]
    for label_, lv, tr in neg:
        # 构造一条**违反物理**的厚档表：把亮档的中位数挪到违规一侧
        bad = [dict(x) for x in lv]
        if tr == "dis":
            bad[1]["abs_ma"] = bad[0]["abs_ma"] - 300.0     # 更亮却更省电
        else:
            bad[1]["abs_ma"] = bad[0]["abs_ma"] + 300.0     # 更亮却更耗电
        v, why, f = check(bad, tr)
        w = f.get("widening")
        print("  {:<36} → {}{}".format(
            label_, v,
            "（{}→{} 反向 {:.0f}）".format(w["lo"], w["hi"], w["violation_ma"]) if w else ""))
    return samples, st, dis_all, chg_all, dis_b, chg_b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--xml", action="append", default=None)
    a = ap.parse_args()
    if a.xml:
        for p in a.xml:
            run_one(p, os.path.basename(p))
        return 0
    ws = os.path.dirname(HERE)
    fixtures = [
        (os.path.join(HERE, "fixtures", "20260916_ar13_g2_direction_ledger.xml"),
         "已提交的 G2 真机夹具（240 样本 —— 与 CHANGELOG 记录同一份）"),
        (os.path.join(ws, ".ref", "ar12d", "20260916_bkl_ledger_g1.xml"),
         "现场活台账（刚 adb pull —— 含更新样本）"),
        (os.path.join(ws, ".ref", "ar12d", "tntgo_battery.xml"),
         "AR12d 归档夹具（235 样本 —— 用户提到的『3 档 71/497/2000』就是它）"),
    ]
    for p, label in fixtures:
        if not os.path.exists(p):
            print("⛔ 缺文件：{}（跳过）".format(p))
            continue
        run_one(p, label)
    return 0


if __name__ == "__main__":
    sys.exit(main())
