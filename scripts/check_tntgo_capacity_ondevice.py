#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★★ TNT GO 容量融合 · **跨实现对账**（真机行为 vs 离线镜像）

## 为什么必须单独有这么一个脚本

`verify_tntgo_capacity.py` 把 `TntgoCapacity` **另写了一遍**（Python 镜像）。
这是必要的（能在宿主上快速跑），但它有一个**结构性盲区**：

> **镜像只能验证"我对算法的理解"，验证不了"我写的 Kotlin 是不是那个理解"。**

2026-09-15 就撞上了这个盲区，代价是一条**功能完全失效却全绿**的路径：

| | |
|---|---|
| Kotlin | `medianOf(list.map { abs(it - med) })` —— ★ **传进去的列表没排序** |
| Python | `_median(sorted(abs(x - med) for x in s))` —— 排了 |
| 结果 | 镜像**全绿**；真机上 `MAD` 恒为 **0** ⇒ `converged` 永远为真 ⇒ **「未收敛取最小样本」这条兜底永远不会触发** |
| 实机日志 | `容量=7217 mAh（已学习 5 段，取中位数，离散度 0.0%）` ← 实际应为 **0.97%** |

⇒ 本脚本拿**同一组盘上样本**，分别用两条实现算，再与**真机日志里那行字**对账。
**三方不一致就是 bug** —— 这正是上面那条路径唯一能被抓到的地方。

## 用法

```bash
python scripts/check_tntgo_capacity_ondevice.py            # 只对账（用日志里最近一行）
python scripts/check_tntgo_capacity_ondevice.py --restart  # 先重启 mod 拿一行新鲜的再对账
python scripts/check_tntgo_capacity_ondevice.py --restart --json
```

★ **`--restart` 会重启宿主 app** ⇒ 容量学习会记一次断档（约 95s）。
  这是**已知且被 N6a 正确吸收**的代价（段不作废，只把时长单独记账、估计偏保守）。
  ⇒ 不加 `--restart` 时，若样本在日志那行之后又滚动过，本脚本会**明说对不了账**，而不是假装通过。
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

WORKSPACE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(WORKSPACE, "scripts"))

import verify_tntgo_capacity as V  # noqa: E402  （镜像实现）

PKG = "com.shware.mode"
PREFS = f"/data/data/{PKG}/shared_prefs/tntgo_battery.xml"
LAUNCHER = f"{PKG}/.ui.HomeActivity"
TAG = "ModeMod/Tntgo"

# 真机日志里那行：`容量=7217 mAh（已学习 5 段，取中位数，离散度 0.0%）`
#             或：`容量更新为 7217 mAh（...）`
#         ★ 未收敛时括号里**还有一层括号**：
#           `容量=6270 mAh（⚠️ 已学习 3 段但未收敛（离散度 12.1% > 10%）⇒ 取最保守的一段）`
#         ⚠️ 所以**不能用 `[^）]*`** —— 那会停在**第一个** `）`，
#            把 note 截成 `⚠️ 已学习 3 段但未收敛（离散度 12% > 10%`，
#            于是「最保守」这条断言会**误报**（2026-09-15 实际踩到）。
#         ⇒ 用贪婪 `.*` 吃到本行**最后一个** `）`。
RE_CAP = re.compile(r"容量(?:更新为)?\s*=?\s*([\d.]+)\s*mAh（(.*)）")
RE_COUNT = re.compile(r"已学习\s*(\d+)\s*段")
RE_DISP = re.compile(r"离散度\s*([\d.]+)\s*%")

FAILED = []


def adb(*args, timeout=60):
    return subprocess.run(
        ["adb", *args], capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    )


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def check(name, ok, detail=""):
    print(f"  {'✓' if ok else '✗'} {name}" + (f" —— {detail}" if detail else ""))
    if not ok:
        FAILED.append(name)
    return ok


# ---------------------------------------------------------------------- 取数

def read_device_samples():
    """从真机 prefs 读 `cap.samples`（★ 与 mod 读的是**同一份盘上数据**）"""
    r = sh(f"run-as {PKG} cat {PREFS}")
    if r.returncode != 0 or not r.stdout:
        raise SystemExit(f"✗ 读不到 prefs（设备连了吗？）:\n{r.stderr}")
    m = re.search(r'name="cap\.samples">([^<]*)<', r.stdout)
    raw = (m.group(1) if m else "").strip()
    vals = [float(x) for x in raw.split(",") if x.strip()]
    return vals, raw


def read_device_log_line():
    """取 logcat 里**最近一条**带容量的行，连同它的时间戳"""
    r = adb("logcat", "-d", "-t", "3000", timeout=90)
    best = None
    for line in r.stdout.splitlines():
        if TAG not in line or "容量" not in line:
            continue
        m = RE_CAP.search(line)
        if not m:
            continue
        ts = line.split()[0:2]
        best = {
            "raw": line.strip(),
            "when": " ".join(ts),
            "mah": float(m.group(1)),
            "note": m.group(2),
        }
    return best


def wait_for_mod(wait_s=60):
    """等看门狗把 `:mod_tntgobat` 拉起来（**不重启**，只是等）"""
    import time
    for i in range(wait_s):
        time.sleep(1)
        if f"{PKG}:mod_tntgobat" in sh("ps -A").stdout:
            time.sleep(3)          # 等服务把启动日志写完
            print(f"  · mod 已起来（等了 {i + 1}s）")
            return True
    print(f"  ⚠️ 等了 {wait_s}s 没看到 :mod_tntgobat")
    return False


def restart_mod(wait_s=60):
    """重启宿主 app，让 mod 打出一行**新鲜的**启动日志。

    ★ 为什么要整个 app 重启：`onStartCommand` 里那行 `容量=` 在 `if (root == null)` 内，
      服务已在跑时再 `startForegroundService` **不会**重新打 —— 拿不到新行。
    """
    print(f"  · force-stop {PKG}")
    adb("shell", "am", "force-stop", PKG)
    print(f"  · 重新拉起 {LAUNCHER}（然后等看门狗拉起 mod，最多 {wait_s}s）")
    sh(f"am start -n {LAUNCHER}")
    return wait_for_mod(wait_s)


# ---------------------------------------------------------------------- 注入

# ★ AR §10.9 里**真实出现过**的三样本（最大相差 38%）。
#   期望：`MAD/中位数` = 12.14% > 10% ⇒ **未收敛** ⇒ 取**最小样本** 6270。
#   ⚠️ 刻意**不用**编造的数 —— 这条路径的行为要能对上历史事实。
GATE_VECTOR = [6270.2593, 7137.005, 8630.246]
GATE_EXPECT_MAH = 6270.2593
GATE_EXPECT_DISP_PCT = 12.1444


def _read_prefs_raw():
    return sh(f"run-as {PKG} cat {PREFS}").stdout


def _write_prefs(xml_text):
    """★ 用 base64 中转 —— 直接 `cat >` 会被引号/换行/编码咬到"""
    b = base64.b64encode(xml_text.encode("utf-8")).decode("ascii")
    return sh(f"run-as {PKG} sh -c 'echo {b} | base64 -d > {PREFS}'")


def _inject_samples(values):
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
           "<map>\n"
           f'    <string name="cap.samples">{",".join(str(v) for v in values)}</string>\n'
           "</map>\n")
    r = _write_prefs(xml)
    return r.returncode == 0


def verify_gate(args):
    """★★★ 在**真机**上走一遍「未收敛 ⇒ 取最小样本」那条分支。

    ## 为什么非要在真机走一遍

    2026-09-15 的教训：**离线镜像 ≠ Kotlin 实现**。
    离散度**算得对不对**已经在 `[4] 三方对账` 里验过了；
    但"算出来超标之后**走哪个分支**"是**另一段 Kotlin**，镜像管不着。
    ⇒ 注入一组已知会超标的真实样本，看真机是不是真的取最小值。

    ★ 全程**先备份、后还原** —— 注入会覆盖盘上的进行中段状态。
    """
    print("\n【注入式验证：未收敛分支】")
    print("[A] 备份盘上 prefs")
    backup = _read_prefs_raw()
    if "cap.samples" not in backup:
        print("     ✗ 备份内容不含 cap.samples，中止（不敢动）")
        FAILED.append("注入前备份")
        return
    print(f"     ✓ 已备份 {len(backup)} 字节")

    try:
        print(f"[B] 注入真实历史三样本 {GATE_VECTOR}")
        adb("shell", "am", "force-stop", PKG)
        if not _inject_samples(GATE_VECTOR):
            check("注入 prefs", False)
            return
        back = _read_prefs_raw()
        check("注入后盘上确实是这 3 个", "8630.246" in back, back.strip()[:80])

        print("\n[C] 重启取新鲜日志")
        sh(f"am start -n {LAUNCHER}")
        wait_for_mod(wait_s=60)
        line = read_device_log_line()
        if not line:
            check("拿到真机日志行", False, "logcat 里没有")
            return
        print(f"     [{line['when']}] {line['raw']}")

        print("\n[D] 判据")
        check("★ 容量 = 最小样本 6270（不是中位数 7137！）",
              abs(line["mah"] - GATE_EXPECT_MAH) <= 1.0,
              f"日志 {line['mah']:.0f} ／ 期望 {GATE_EXPECT_MAH:.0f}")
        check("★ 容量 ≠ 中位数（证明确实走了兜底分支）",
              abs(line["mah"] - 7137.005) > 100,
              "若等于 7137 说明闸门没生效")
        check("★★ 容量 < 先验 10160（绝不退回先验）",
              line["mah"] < V.PRIOR_MAH,
              f"{line['mah']:.0f} vs {V.PRIOR_MAH:.0f}")
        check("★ 日志标注了「未收敛」", "未收敛" in line["note"], line["note"])
        check("★ 日志标注了「最保守」", "最保守" in line["note"], line["note"])
        d = RE_DISP.search(line["note"])
        if d:
            check("离散度 ≈ 12.1%", abs(float(d.group(1)) - GATE_EXPECT_DISP_PCT) <= 0.1,
                  f"日志 {d.group(1)}%")
        else:
            check("日志里印了离散度", False, line["note"])
    finally:
        print("\n[E] 还原备份并重启")
        _write_prefs(backup)
        after = _read_prefs_raw()
        ok = "cap.samples" in after and "8630.246" not in after
        check("盘上已还原（注入值已消失）", ok, after.strip()[:90].replace("\n", " "))
        adb("shell", "am", "force-stop", PKG)
        sh(f"am start -n {LAUNCHER}")
        print("     · 已重新拉起（看门狗约 30s 内把 mod 带回来）")


# ---------------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--restart", action="store_true",
                    help="先重启宿主 app 拿一行新鲜日志（★ 会记一次断档，见文件头）")
    ap.add_argument("--json", action="store_true", help="以 JSON 输出结论")
    ap.add_argument("--verify-gate", action="store_true",
                    help="★★★ 注入一组已知超标的真实样本，验证「未收敛 ⇒ 取最小」"
                         "这条分支在真机上真的走通（含备份/还原）")
    args = ap.parse_args()

    print("=" * 70)
    print("TNT GO 容量融合 · 跨实现对账（真机日志 vs 离线镜像）")
    print("=" * 70)

    if args.verify_gate:
        verify_gate(args)
        print("\n" + "=" * 70)
        if FAILED:
            print(f"✗ {len(FAILED)} 条判据未通过：")
            for x in FAILED:
                print(f"   · {x}")
            print("=" * 70)
            sys.exit(1)
        print("✓ 未收敛分支在真机上走通")
        print("=" * 70)
        sys.exit(0)

    if args.restart:
        print("\n[0] 重启以取得新鲜日志")
        restart_mod()

    print("\n[1] 盘上样本")
    samples, raw = read_device_samples()
    print(f"     {raw}")
    print(f"     （{len(samples)} 个）")

    print("\n[2] 离线镜像算出的**应当值**")
    f = V._fusion_of(samples, buggy_n9=False)
    if f is None:
        print("     段数不足 3 ⇒ 真机应当显示「先验值，未校准」，本脚本无从对账")
        print("=" * 70)
        sys.exit(0)
    exp_mah = f.mah
    exp_disp_pct = f.dispersion * 100
    print(f"     容量 {exp_mah:.2f} mAh（median={f.median_mah:.2f} "
          f"min={f.min_mah:.2f} converged={f.converged}）")
    print(f"     离散度 {exp_disp_pct:.4f}%  ⇒ 日志里应印成 {exp_disp_pct:.1f}%")

    print("\n[3] 真机日志里那一行")
    line = read_device_log_line()
    if not line:
        print("     ⚠️ logcat 里没找到带「容量」的行")
        print("     ⇒ 加 --restart 重跑（mod 只有在**刚起来**时才打这行）")
        FAILED.append("真机日志")
    else:
        print(f"     [{line['when']}] {line['raw']}")

    if line:
        m = RE_COUNT.search(line["note"])
        d = RE_DISP.search(line["note"])
        got_count = int(m.group(1)) if m else None
        got_disp = float(d.group(1)) if d else None

        print("\n[4] 三方对账")
        check("段数一致", got_count == len(samples),
              f"日志 {got_count} ／ 盘上 {len(samples)}")
        check("容量一致（±1 mAh，日志按 %.0f 印）",
              abs(line["mah"] - exp_mah) <= 1.0,
              f"日志 {line['mah']:.0f} ／ 应当 {exp_mah:.0f}")
        if got_disp is None:
            check("日志里有离散度", False, line["note"])
        else:
            # 日志按 %.1f 印 ⇒ 容差放到 0.1
            check("★ 离散度一致（±0.1%，这是本条的核心）",
                  abs(got_disp - exp_disp_pct) <= 0.1,
                  f"日志 {got_disp:.1f}% ／ 应当 {exp_disp_pct:.1f}%")
            if got_disp == 0.0 and exp_disp_pct > 0.1:
                print("     ★★★ 日志离散度是 0 而应当非 0 —— "
                      "典型病征：`medianOf` 收到了**未排序**的偏差列表。")
        check("收敛判定一致", (got_disp is not None)
              and (got_disp <= V.DISPERSION_MAX * 100) == f.converged,
              f"日志 {got_disp}% vs 阈值 {V.DISPERSION_MAX * 100:.0f}%")

    print("\n" + "=" * 70)
    result = {"samples": samples, "expected_mah": exp_mah,
              "expected_dispersion_pct": exp_disp_pct,
              "device": line, "failed": FAILED}
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    if FAILED:
        print(f"✗ {len(FAILED)} 条对账未通过：")
        for x in FAILED:
            print(f"   · {x}")
        print("=" * 70)
        sys.exit(1)
    print("✓ 真机与离线镜像一致")
    print("=" * 70)
    sys.exit(0)


if __name__ == "__main__":
    main()
