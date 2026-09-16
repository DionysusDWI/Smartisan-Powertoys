#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★★★ **监看「真被拒」现场**（C1 的正面证据 ＋ G3 的卡片文案取证）

## 为什么需要它（不是"顺手写的看门狗"）

C1 与 G3 各自**还缺同一次现场**：

| 缺口 | 要什么 |
|---|---|
| **C1** | 设备日志里那行 `Rejected` **必须说出"是哪一对"**，且与离线工具对**同一份台账**算出的那一对**逐字一致**（判据 C1-b） |
| **G3** | 卡片的 `@ 亮度 N% ★ 曲线被拒（走向反物理）` 那一句 —— **形态与「该处未测」不同** |

★ 这两件事**只能等它自然发生**：

- ⛔ **不可**用"临时关掉 G1"制造拒绝 —— 那会把 G1 的成果掩盖掉（＝ 把判据改哑）。
- ⛔ **不可**等"薄档违规" —— G1 之后薄档**不进判据**，它根本不会打响闸门。

⇒ 唯一正当的路子：**等台账里出现一对【厚档】真违规**（两档都 `n >= 3` 且真的反向）。

## ★★★ 三条纪律（决定了这个脚本怎么写）

### ① 判读一律**调用真判据**，自己不算 `Δ`

违规那一对由 `analyze_bkl_curve.widening_pair()` 产出；"是不是反物理"由 `check_mode()` 判。
⛔ 监看脚本**自己再实现一遍受测逻辑**就只能自证（G2 §5.1 那个假绿的教训）。

### ② **只报事实，不报"好不好"**（`watch_bkl_sampling.py` 的既有纪律）

本脚本报的是：档位、条数、哪一对、幅度、日志原文、台账快照路径。
"这意味着什么"留在 `analyze_bkl_curve.py` 与判读环节 ——
**判据如果跟着监看一起演进，监看就会说谎**。

### ③ ★★★★ **先检查"闸门到底有没有在跑"，再等它打响**

这条是本脚本存在的**第二个理由**，来自 2026-09-16 实测：台账是**环形**保留 240 条，
而曲线重算的节流键原来是**样本条数** ⇒ 台账装满后条数恒为 240
⇒ **`refreshCurve` 永远提前 return** ⇒ `gate()` **再也不执行**
⇒ 此时"等一个拒绝"是**等一个永远不会发生的事件**，而且**屏幕上什么都不像坏了**。

⇒ 所以每轮都必须自问：**心跳还在跳吗？台账还在长吗？**
   （`bkl.curve` 的 `ts` 也要跟着长 —— 那是"闸门真的跑了"的直接证据。）

## 用法

```bash
python scripts/watch_bkl_rejection.py                # 每 20 s 一轮，发现厚档违规即取证并停
python scripts/watch_bkl_rejection.py --precheck     # 只看一眼当前状态，不循环
python scripts/watch_bkl_rejection.py --minutes 240  # 最多跑 4 小时
```

★ 本轮**不替用户决定**"要不要长跑"：`--minutes` 是显式上限，到点就停并留下日志。

日志与证据：`.ref/c1/<时间戳>_拒一次监看.log` ＋ `.ref/c1/<时间戳>_厚档违规/`
"""

import argparse
import datetime as dt
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bkl_common import read_ledger, read_curve, adb                         # noqa: E402
from analyze_bkl_curve import (plateaus, check_mode,                        # noqa: E402
                              MIN_N_PER_LEVEL)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:                                                          # noqa: BLE001
    pass

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(WS, ".ref", "c1")

#: ★ 心跳的新鲜度窗口（与 `TntgoState.VALID_MS` 同量级：心跳 ×3）
HEARTBEAT_VALID_MS = 90_000
#: ★ 台账/曲线的"应该在长"判据：两条样本之间约 30 s ⇒ 给 3 倍余量
STALE_MS = 100_000


def now_str():
    return dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def device_now_ms():
    r = adb("shell", "date +%s%3N")
    try:
        return int((r.stdout or "").strip())
    except ValueError:
        return None


def read_heartbeat():
    """读亮度心跳状态文件（`ui/mcu/ts`）—— ★ 它是"闸门有没有在跑"的第一个前提。"""
    r = adb("shell", "run-as com.shware.mode cat "
                     "/data/data/com.shware.mode/files/tntgo_brightness.state")
    kv = {}
    for line in (r.stdout or "").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            kv[k.strip()] = v.strip()
    try:
        kv["ts"] = int(kv.get("ts", "0"))
    except ValueError:
        kv["ts"] = 0
    return kv


def snapshot():
    """★ 一次读数 ⇒ 纯数据 dict（**不打印**，便于 `--precheck` 与自检复用）。"""
    dev_ms = device_now_ms()
    hb = read_heartbeat()
    xml_text = adb("shell", "run-as com.shware.mode cat "
                            "/data/data/com.shware.mode/shared_prefs/tntgo_battery.xml").stdout
    samples, stats = read_ledger(text=xml_text)
    curve = read_curve(text=xml_text)

    dis_all = plateaus([s for s in samples if not s["chg"]], min_n=1)
    chg_all = plateaus([s for s in samples if s["chg"]], min_n=1)
    dis = plateaus([s for s in samples if not s["chg"]])
    chg = plateaus([s for s in samples if s["chg"]])

    out = {
        "dev_ms": dev_ms,
        "hb": hb,
        "total": len(samples),
        "legacy": stats["legacy_no_mcu"],
        "bad": stats["bad"],
        "newest": samples[-1] if samples else None,
        "curve": curve,
        "dis_all": dis_all, "chg_all": chg_all,   # 含薄档（诊断用）
        "dis": dis, "chg": chg,                   # ★ G1 之后的可用档（判据只用这张）
        "xml": xml_text,
    }
    # ★ 两个方向的判读**一律经真判据**（不自己算 Δ）
    for key, lv, trend in (("dis", dis, "dis"), ("chg", chg, "chg")):
        v, why, facts = check_mode(lv, trend)
        out[key + "_verdict"] = v
        out[key + "_why"] = why
        out[key + "_pair"] = facts.get("widening")
        # ★ 关掉 G1 ⇒ 若这时才出现违规，说明它**正是薄档造成的**（G1 刚刚救过一次）
        v1, _w1, f1 = check_mode(lv, trend, min_n=1)
        out[key + "_pair_minN1"] = f1.get("widening")
        out[key + "_verdict_minN1"] = v1
    return out


def thick_violations(s):
    """★ **厚档真违规**：一对相邻档**两边都是可用档**（`n >= MIN_N_PER_LEVEL`）却反向。

    ⚠️ 定义**只用真判据的产物**（`check_mode` 给的 `widening`），
    而"两边都是厚档"由 `plateaus(min_n=MIN_N_PER_LEVEL)` 出的表保证 ——
    **不在这里另写一遍过滤**（那会变成"两边各写一遍"）。
    """
    out = []
    for key in ("dis", "chg"):
        p = s.get(key + "_pair")
        if p is None:
            continue
        lv = {l["mcu"]: l["n"] for l in s[key]}
        out.append({
            "trend": key,
            "pair": p,
            "lo_n": lv.get(p["lo"]), "hi_n": lv.get(p["hi"]),
        })
    return out


def health_notes(s):
    """★ 报出"闸门到底有没有在跑"的两条前提 —— **只报事实**。"""
    notes = []
    hb_age = None
    if s["dev_ms"] and s["hb"].get("ts"):
        hb_age = (s["dev_ms"] - s["hb"]["ts"]) / 1000.0
        if hb_age * 1000 > HEARTBEAT_VALID_MS:
            notes.append("★ 亮度心跳已 stale {:.0f}s（窗口 {}s）⇒ 新样本**不会进台账**"
                         .format(hb_age, HEARTBEAT_VALID_MS // 1000))
    else:
        notes.append("★ 读不到心跳状态文件 ⇒ 亮度组件可能没跑")
    c = s["curve"]
    if c and c.get("ts") and s["dev_ms"]:
        c_age = (s["dev_ms"] - c["ts"]) / 1000.0
        if c_age * 1000 > STALE_MS:
            notes.append("★ 盘上曲线已 {:.0f}s 没更新 ⇒ **闸门可能根本没在执行**"
                         "（`bkl.curve` 只在重算时写）".format(c_age))
    if c and c.get("legacy") is not None and c["legacy"] != s["legacy"]:
        notes.append("★ 盘上曲线 `legacy`={} ≠ 台账现值 {} ⇒ **盘上那份是旧快照**"
                     .format(c["legacy"], s["legacy"]))
    return notes, hb_age


def render(s, notes, thick):
    lines = []
    n = s["newest"]
    state = "无样本" if n is None else ("★充电" if n["chg"] else "放电")
    lines.append("[{}] 台账 {} 条（旧格式 {}）｜此刻 {} ma={}｜心跳 {}".format(
        dt.datetime.now().strftime("%H:%M:%S"), s["total"], s["legacy"], state,
        n["ma"] if n else "-",
        "-" if s["hb"].get("ts") is None else "ok"))
    for key, label in (("dis", "放电"), ("chg", "充电")):
        lv = s[key]
        if not lv:
            lines.append("    【{}】★ 0 个**可用**档（G1 之后）".format(label))
            continue
        detail = "／".join("{}:{:.0f}(n{})".format(l["mcu"], l["abs_ma"], l["n"]) for l in lv)
        lines.append("    【{}】可用 {} 档 ⇒ {}｜{}".format(
            label, len(lv), s[key + "_verdict"], detail))
        p = s[key + "_pair"]
        if p:
            lines.append("        ★★ 打响的那一对：{}（{} n={} ／ {} n={}）".format(
                p["brief"], p["lo"], {l['mcu']: l['n'] for l in lv}.get(p["lo"]),
                p["hi"], {l['mcu']: l['n'] for l in lv}.get(p["hi"])))
    if thick:
        lines.append("    ★★★ 厚档真违规 {} 处 ⇒ **这就是 C1/G3 要的现场**".format(len(thick)))
    else:
        lines.append("    厚档真违规：无（薄档违规不算 —— G1 之后它们不进判据）")
    for x in notes:
        lines.append("    " + x)
    return "\n".join(lines)


def capture(s, thick, out_dir):
    """★ 取证：把**同一时刻**的台账快照 ＋ 盘上曲线 ＋ 设备日志一起落盘。

    ⚠️ **台账快照必须与日志一起存** —— C1-b 要的是"日志里那一对
    与离线工具对**同一份**台账算出的那一对逐字一致"，
    而台账**每 30 s 就在变**（环形保留 240 条）⇒ 事后回读的台账**不是**当时那份。
    """
    os.makedirs(out_dir, exist_ok=True)
    p_xml = os.path.join(out_dir, "ledger_snapshot.xml")
    with open(p_xml, "w", encoding="utf-8") as f:
        f.write(s["xml"])
    p_txt = os.path.join(out_dir, "offline_verdict.txt")
    with open(p_txt, "w", encoding="utf-8") as f:
        f.write("采集时刻（本机）：{}\n".format(now_str()))
        f.write("采集时刻（设备 ms）：{}\n".format(s["dev_ms"]))
        f.write("台账 {} 条，旧格式 {}\n".format(s["total"], s["legacy"]))
        f.write("盘上 curve：{}\n\n".format(s["curve"]))
        for key, label in (("dis", "放电"), ("chg", "充电")):
            f.write("【{}】verdict={}\n    why={}\n".format(label, s[key + "_verdict"],
                                                           s[key + "_why"]))
            f.write("    可用档：{}\n".format(
                "／".join("{}:{:.1f}(n{})".format(l["mcu"], l["abs_ma"], l["n"]) for l in s[key])))
            f.write("    含薄档：{}\n".format(
                "／".join("{}:{:.1f}(n{})".format(l["mcu"], l["abs_ma"], l["n"])
                          for l in s[key + "_all"])))
            p = s[key + "_pair"]
            f.write("    ★ 离线算出的那一对：{}\n".format("-" if not p else p["brief"]))
            f.write("    （关掉 G1 时那一对：{}）\n".format(
                "-" if not s[key + "_pair_minN1"] else s[key + "_pair_minN1"]["brief"]))
        f.write("\n★ 厚档真违规：{}\n".format(thick))
    # ★ 设备日志：`-T` 用**设备本地时间**（与上面同一时钟）
    ts = time.strftime("%m-%d %H:%M:%S.000", time.localtime())
    r = adb("shell", "logcat -d -v time -t '{}'".format(ts))
    p_log = os.path.join(out_dir, "device_logcat.txt")
    with open(p_log, "w", encoding="utf-8") as f:
        f.write(r.stdout or "")
    return [os.path.relpath(p, WS) for p in (p_xml, p_txt, p_log)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--interval", type=int, default=20)
    ap.add_argument("--minutes", type=float, default=0, help="0 = 跑到 Ctrl-C")
    ap.add_argument("--precheck", action="store_true")
    ap.add_argument("--stop-on-thick", action="store_true", default=True)
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)

    print("=" * 74)
    print("C1/G3 · 监看「真被拒」现场（只报事实）")
    print("  要等的是：一对相邻档**两边都 n >= {}** 却真的反向".format(MIN_N_PER_LEVEL))
    print("  ⛔ 薄档违规不算（G1 之后它们不进判据）；⛔ 不许关掉 G1 去制造")
    print("=" * 74)

    if args.precheck:
        s = snapshot()
        thick = thick_violations(s)
        notes, _ = health_notes(s)
        print(render(s, notes, thick))
        return 0

    log_path = os.path.join(OUT_DIR, dt.datetime.now().strftime("%Y%m%d_%H%M%S") + "_拒一次监看.log")
    print("日志 → {}\n".format(os.path.relpath(log_path, WS)))

    started = dt.datetime.now()
    last_newest = None
    with open(log_path, "w", encoding="utf-8") as f:
        f.write("# C1/G3 拒一次监看　{}　interval={}s\n".format(now_str(), args.interval))
        while True:
            try:
                s = snapshot()
                thick = thick_violations(s)
                notes, _ = health_notes(s)
                text = render(s, notes, thick)
            except Exception as e:                                        # noqa: BLE001
                s, thick, text = None, [], "[{}] ✗ 读数失败：{}: {}".format(
                    dt.datetime.now().strftime("%H:%M:%S"), type(e).__name__, e)
            print(text, flush=True)
            f.write(text + "\n")
            f.flush()

            # ★ 台账"没在长"也要报 —— 否则会静默地等一个不会发生的事件
            if s and last_newest is not None and s["newest"] == last_newest:
                msg = "    ⚠ 台账末条**未变**（{}）—— 没有新样本就不会有新的判读".format(
                    last_newest)
                print(msg, flush=True)
                f.write(msg + "\n")
                f.flush()
            if s:
                last_newest = s["newest"]

            if s and thick and args.stop_on_thick:
                d = os.path.join(OUT_DIR, dt.datetime.now().strftime("%Y%m%d_%H%M%S") + "_厚档违规")
                files = capture(s, thick, d)
                tail = "\n★★★ 抓到厚档真违规 ⇒ 已取证：\n" + \
                       "\n".join("    " + x for x in files) + \
                       "\n★ 下一步：C1-b 要拿 device_logcat.txt 里那一对与 offline_verdict.txt 逐字对账"
                print(tail, flush=True)
                f.write(tail + "\n")
                f.close()
                return 0

            if args.minutes and (dt.datetime.now() - started).total_seconds() >= args.minutes * 60:
                print("\n★ 到 {} 分钟，停止（**没有**抓到厚档违规）。".format(args.minutes))
                break
            try:
                time.sleep(args.interval)
            except KeyboardInterrupt:
                print("\n★ 手动停止。")
                break
    return 0


if __name__ == "__main__":
    sys.exit(main())
