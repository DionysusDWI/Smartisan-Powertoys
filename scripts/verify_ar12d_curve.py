#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AR12d:独立复算 亮度(MCU) -> |I| 曲线,并与 app 落盘的 bkl_curve.json 对账。

判据来源(全部来自源码,不采信任何摘要):
  TntgoBklCurve.kt
    plateaus()  L147-173  相邻聚类:按 MCU 升序,簇内以 lo(簇**最小值*)为基准,
                          `pts[i].first - lo > tolerance(40)` 或 `> bucketMcu(300)` 即新起一簇
    level()     L175-182  mcu = 簇内 MCU 的中位数(int);absMa = 簇内 |I| 的中位数;n = 簇内样本数
    fitLine()   L192-211  加权最小二乘,权重 w = n.coerceAtLeast(1);需 >= MIN_LEVELS(2) 档且 MCU 跨度 >= 1
    gate()      L214-215  仅 `levels >= 2 && fitLine != null`  -> Gate.Ok
    maxDrop()   L262-271  单调性自检函数 -- ★ 全仓库无调用点(见报告),不构成 gate
  TntgoBklProfile.kt
    samples()   L109-137  4 字段 = 当前格式;3 字段 = 旧格式(mcu=null,不参与)
    Sample.absMa L67       abs(currentMa)
    levelsText  L252       "mcu:%.1f:n"

只读输入,不做任何写回。
"""
import json
import statistics
from pathlib import Path

WS = Path(r"P:\Literature_Lab\Smartisan-TNT-Secondary")
SAMPLES = WS / ".ref" / "ar12d" / "bkl_samples.txt"
CURVE = WS / ".ref" / "ar12d" / "bkl_curve.json"

TOLERANCE_MCU = 40       # TntgoBklCurve.kt L297
MAX_BUCKET_MCU = 300     # TntgoBklCurve.kt L300
MIN_LEVELS = 2           # TntgoBklCurve.kt L128
V_TNTGO = 3.811          # 题面给定的 TNT GO 电压


# ----------------------------------------------------------------- 解析
def parse_samples(text):
    old, new = [], []
    for raw in (r.strip() for r in text.split(",")):
        if not raw:
            continue
        p = raw.split(":")
        if len(p) == 4:
            ui, mcu, ma, ch = (int(x) for x in p)
            new.append({"ui": ui, "mcu": mcu, "raw_ma": ma, "abs_ma": abs(ma), "chg": ch == 1})
        elif len(p) == 3:
            ui, ma, ch = (int(x) for x in p)
            old.append({"ui": ui, "raw_ma": ma, "abs_ma": abs(ma), "chg": ch == 1})
        else:
            raise SystemExit("unparsable record: %r" % raw)
    return old, new


# ------------------------------------------------- 聚类(TntgoBklCurve.plateaus)
def median_int(vals):
    s = sorted(vals)
    n = len(s)
    if n == 0:
        return 0
    # 源: (s[n/2-1] + s[n/2]) / 2  且类型是 Int -> 整数除法(截断)
    return s[n // 2] if n % 2 == 1 else (s[n // 2 - 1] + s[n // 2]) // 2


def median_float(vals):
    s = sorted(vals)
    n = len(s)
    if n == 0:
        return None
    return s[n // 2] if n % 2 == 1 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def plateaus(samples, mode="cluster_min"):
    """mode='cluster_min' = 源码口径(与簇最小值比);'prev' = 题面措辞(与上一个值比)。"""
    pts = sorted(((s["mcu"], float(s["abs_ma"])) for s in samples if s["abs_ma"] > 0.0),
                 key=lambda t: t[0])
    if not pts:
        return []
    out, start, lo = [], 0, pts[0][0]
    for i in range(1, len(pts) + 1):
        if i == len(pts):
            over = True
        elif mode == "cluster_min":
            over = (pts[i][0] - lo > TOLERANCE_MCU) or (pts[i][0] - lo > MAX_BUCKET_MCU)
        else:
            over = (pts[i][0] - pts[i - 1][0] > TOLERANCE_MCU)
        if over:
            sl = pts[start:i]
            out.append({"mcu": median_int([p[0] for p in sl]),
                        "absMa": median_float([p[1] for p in sl]),
                        "n": len(sl),
                        "mcu_min": min(p[0] for p in sl),
                        "mcu_max": max(p[0] for p in sl)})
            if i < len(pts):
                start, lo = i, pts[i][0]
    return out


# --------------------------------- 加权最小二乘(TntgoBklCurve.fitLine L192-211)
def fit_line(levels):
    if len(levels) < MIN_LEVELS:
        return None
    xs = [float(l["mcu"]) for l in levels]
    if max(xs) - min(xs) < 1.0:
        return None
    w = [float(max(l["n"], 1)) for l in levels]
    sw = sum(w)
    mx = sum(w[i] * levels[i]["mcu"] for i in range(len(levels))) / sw
    my = sum(w[i] * levels[i]["absMa"] for i in range(len(levels))) / sw
    sxx = sxy = 0.0
    for i in range(len(levels)):
        dx = levels[i]["mcu"] - mx
        sxx += w[i] * dx * dx
        sxy += w[i] * dx * (levels[i]["absMa"] - my)
    if sxx <= 1e-9:
        return None
    b = sxy / sxx
    return {"a": my - b * mx, "b": b}


def max_drop(levels):
    s = sorted(levels, key=lambda l: l["mcu"])
    if len(s) < 2:
        return None
    worst = 0.0
    for i in range(1, len(s)):
        d = s[i - 1]["absMa"] - s[i]["absMa"]
        if d > worst:
            worst = d
    return worst


def table(name, levels, line):
    print("  %s 档位表(%d 档):" % (name, len(levels)))
    print("    %-8s %-12s %-6s %-16s" % ("mcu", "absMa(mA)", "n", "cluster[mcu_min..max]"))
    for l in levels:
        print("    %-8d %-12.1f %-6d [%d..%d]" % (l["mcu"], l["absMa"], l["n"], l["mcu_min"], l["mcu_max"]))
    if line:
        print("    拟合 |I| = %.6f + %.9f * MCU" % (line["a"], line["b"]))
    else:
        print("    拟合 = None(档位不足 / 数值退化)")
    md = max_drop(levels)
    print("    maxDrop(反向跳变上界) = %s" % ("None" if md is None else "%.1f mA" % md))


def main():
    text = SAMPLES.read_text(encoding="utf-8")
    stored = json.loads(CURVE.read_text(encoding="utf-8"))
    old, new = parse_samples(text)

    print("=" * 78)
    print("E. 记录数")
    print("=" * 78)
    print("  3 字段(旧,忽略) = %d   4 字段(采用) = %d   合计 = %d" % (len(old), len(new), len(old) + len(new)))
    print("  按 flag 分:charging(flag=1) = %d   discharging(flag=0) = %d"
          % (sum(1 for s in new if s["chg"]), sum(1 for s in new if not s["chg"])))
    print("  4 字段中 abs_ma == 0 的条数(会被源 filter 丢掉) = %d"
          % sum(1 for s in new if s["abs_ma"] == 0))
    print("  3 字段中的 flag=1 条数 = %d(旧格式整条丢弃,与 flag 无关)"
          % sum(1 for s in old if s["chg"]))

    chg_s = [s for s in new if s["chg"]]
    dis_s = [s for s in new if not s["chg"]]

    print()
    print("=" * 78)
    print("A/B. 独立复算(源码口径:与簇最小值比,容差 40,兜底 300)")
    print("=" * 78)
    chg = plateaus(chg_s)
    dis = plateaus(dis_s)
    chg_line = fit_line(chg)
    dis_line = fit_line(dis)
    table("CHARGING", chg, chg_line)
    print()
    table("DISCHARGING", dis, dis_line)

    # 口径对照:题面措辞(与前一值比)是否给出不同分簇
    print()
    print("  [口径对照] 题面措辞'与上一个值比 > 40'是否改变结果?")
    for nm, ss in (("charging", chg_s), ("discharging", dis_s)):
        alt = plateaus(ss, mode="prev")
        same = [(l["mcu"], l["absMa"], l["n"]) for l in (chg if nm == "charging" else dis)] == \
               [(l["mcu"], l["absMa"], l["n"]) for l in alt]
        print("    %-12s 源码口径 == 前一值口径 ? %s" % (nm, same))
    # MCU 值的离散度(决定两口径何时会分歧)
    for nm, ss in (("charging", chg_s), ("discharging", dis_s)):
        vals = sorted(set(s["mcu"] for s in ss))
        gaps = [vals[i] - vals[i - 1] for i in range(1, len(vals))]
        print("    %-12s 出现的 distinct MCU = %s ; 相邻间距 = %s" % (nm, vals, gaps))

    print()
    print("=" * 78)
    print("C. 与 app 落盘曲线对账(bkl_curve.json)")
    print("=" * 78)
    print("  stored: %s" % json.dumps(stored, ensure_ascii=False))
    print("  stored.legacy = %s ; 我数出 3 字段条数 = %d" % (stored.get("legacy"), len(old)))

    all_ok = True
    for key, mine in (("chg", chg), ("dis", dis)):
        raw = stored.get(key, "")
        stored_lv = []
        for tok in raw.split(";"):
            if not tok.strip():
                continue
            m, a, n = tok.split(":")
            stored_lv.append({"mcu": int(m), "absMa": float(a), "n": int(n)})
        print("\n  [%s] stored %d 档 vs 复算 %d 档" % (key, len(stored_lv), len(mine)))
        for i in range(max(len(stored_lv), len(mine))):
            s = stored_lv[i] if i < len(stored_lv) else None
            r = mine[i] if i < len(mine) else None
            if s is None:
                print("    #%d  只存在于复算: mcu=%d median=%.1f n=%d" % (i, r["mcu"], r["absMa"], r["n"]))
                all_ok = False
                continue
            if r is None:
                print("    #%d  只存在于 stored: mcu=%d median=%.1f n=%d" % (i, s["mcu"], s["absMa"], s["n"]))
                all_ok = False
                continue
            ok = (s["mcu"] == r["mcu"]) and (abs(s["absMa"] - r["absMa"]) < 5e-4) and (s["n"] == r["n"])
            all_ok &= ok
            print("    #%d  stored=%d:%.1f:%d   复算=%d:%.1f:%d   %s"
                  % (i, s["mcu"], s["absMa"], s["n"], r["mcu"], r["absMa"], r["n"],
                     "MATCH" if ok else "*** MISMATCH ***"))
            if not ok:
                print("        差异: mcu %s | median %s | n %s"
                      % ("同" if s["mcu"] == r["mcu"] else "%d vs %d" % (s["mcu"], r["mcu"]),
                         "同" if abs(s["absMa"] - r["absMa"]) < 5e-4 else "%.1f vs %.1f" % (s["absMa"], r["absMa"]),
                         "同" if s["n"] == r["n"] else "%d vs %d" % (s["n"], r["n"])))
    legacy_ok = (stored.get("legacy") == len(old))
    all_ok &= legacy_ok
    print("\n  legacy 字段: %s" % ("MATCH" if legacy_ok else "*** MISMATCH ***"))
    print("  >>> C 总判定: %s" % ("PASS(逐条一致)" if all_ok else "*** FAIL(存在差异,见上) ***"))

    print()
    print("=" * 78)
    print("D. 用【放电线】在 MCU=71 处求值")
    print("=" * 78)
    a, b = dis_line["a"], dis_line["b"]
    i71 = a + b * 71.0
    print("  |I|(71) = %.6f + %.9f*71 = %.3f mA" % (a, b, i71))
    w_line = V_TNTGO * (i71 / 1000.0)
    w_inst = V_TNTGO * (1001.0 / 1000.0)
    print("  P(line @MCU=71) = 3.811 V * (%.3f/1000) = %.4f W  -> 四舍五入 %.1f W" % (i71, w_line, round(w_line, 1)))
    print("  P(瞬时读数 1001 mA) = 3.811 V * 1.001 A   = %.4f W  -> 四舍五入 %.1f W" % (w_inst, round(w_inst, 1)))
    print("  差值 = %.4f W" % (w_inst - w_line))
    print("  放电带 [%d..%d] ; MCU=71 %s"
          % (dis[0]["mcu"], dis[-1]["mcu"],
             "在带内(下沿,可插值)" if dis[0]["mcu"] <= 71 <= dis[-1]["mcu"] else "★带外"))

    print()
    print("=" * 78)
    print("门禁复算(TntgoBklCurve.gate L214-215)")
    print("=" * 78)
    print("  charging : levels=%d fit=%s -> %s"
          % (len(chg), chg_line is not None, "Ok" if (len(chg) >= MIN_LEVELS and chg_line) else "NotEnoughLevels"))
    print("  discharging: levels=%d fit=%s -> %s"
          % (len(dis), dis_line is not None, "Ok" if (len(dis) >= MIN_LEVELS and dis_line) else "NotEnoughLevels"))
    for nm, lv in (("charging", chg), ("discharging", dis)):
        md = max_drop(lv)
        print("  %s maxDrop = %s  (门禁是否用它:否 -- gate() 未调用 maxDrop)" % (nm, md))


if __name__ == "__main__":
    main()
