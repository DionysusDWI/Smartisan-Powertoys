#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★ TNT GO 串口**白名单**校验（任务 AS3b · 判据 4）

## ★★★ 本脚本与 `verify_tntgo_capacity.py` 的做法**故意不同**

那个脚本把 Kotlin **另写了一遍**（Python 镜像）——
2026-09-15 正是因此漏掉了一个致命 bug：**镜像全绿，而真机上功能是死的**
（`medianOf` 收到未排序列表 ⇒ MAD 恒 0 ⇒ 离散度闸门完全失效）。

⇒ 本脚本**不写镜像**，而是**从 `TntgoSerial.kt` 源码里把真实的正则抠出来**再测。
**测的是真家伙，不是我对它的理解。**

⚠️ 抠取用的是正则，所以若有人改了写法（比如换成 `Regex("...")` 非 raw string、
或把 `WHITELIST` 改名），本脚本会**报"抠不到"并退出 1** ——
★ **宁可吵，也不要静默地测了个空**（那正是 §9.7 那个"假 `--buggy`"的翻版）。

## 为什么这条判据重要

TNT GO 的 AT 台里有**会关机 / 重启 / 变砖**的命令：

```
AT+SHUTDOWN / AT+PWROFF / AT+REBOOT / AT+RESET / AT+RECOVERY / AT+UPGRADE
AT+FLASHWRITE / AT+OTPWRITE
```

白名单不是"锦上添花的输入检查" —— 它是**唯一**挡住"某个调用方手滑拼错命令字符串"
的东西。所以它必须**被证伪过一次**（下面 [DENY] 那一大串就是干这个的）。
"""

import argparse
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

WORKSPACE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(
    WORKSPACE, "projects", "mode-launcher", "src", "tntgo-serial",
    "src", "main", "java", "com", "shware", "mode", "tntgoserial", "TntgoSerial.kt",
)

# ── 期望放行 / 期望拦下 ────────────────────────────────────────────────
ALLOW = [
    "at+batcg",              # 电量查询
    "AT+BATCG",              # ★ 大小写不敏感
    "  at+batcg  ",          # ★ 前后空白应被 trim
    "at+bkl",                # 亮度查询（裸发安全）
    "at+bkl=0",
    "at+bkl=9",
    "at+bkl=2000",
    "at+bkl=6000",           # ★ 对账用的宽边界值 —— 由 MCU 自己用 +ERROR 拒绝
    "AT+BKL=800",
]

DENY = [
    # ① 会关机 / 重启 / 变砖的
    "at+shutdown", "at+pwoff", "at+reboot", "at+reset", "at+recovery",
    "at+upgrade", "at+flashwrite", "at+otpwrite",
    "AT+SHUTDOWN",
    # ② 别的 AT 命令（不是本工程该发的）
    "at+hall", "at+bq", "at+version", "at",
    # ③ ★★ 形状擦边：**这是白名单真正的价值所在**
    "at+bkl=",               # 空值
    "at+bkl=abc",
    "at+bkl=-1",
    "at+bkl=99999",          # 5 位
    "at+bkl=1.5",
    "at+bkl =800",           # 多了空格
    "at+bklx=800",           # 命令名被改
    "at+batcgx",
    # ④ ★★★ **注入**：想借一条合法命令夹带第二条
    "at+bkl=800\r\nat+shutdown",
    "at+bkl=800\nat+shutdown",
    "at+batcg;at+shutdown",
    "at+batcg at+shutdown",
    "at+bkl=800\x00",
]

FAILED = []


def check(name, ok, detail=""):
    print(f"  {'✓' if ok else '✗'} {name}" + (f" —— {detail}" if detail else ""))
    if not ok:
        FAILED.append(name)
    return ok


def extract_whitelist_text(src):
    """★ 从 Kotlin **源码文本**里抠出 `WHITELIST` 的真实正则（**不是抄一份**）"""
    m = re.search(r"private\s+val\s+WHITELIST\s*=\s*listOf\s*\((.*?)\n\s*\)", src, re.S)
    if not m:
        return None, "抠不到 `private val WHITELIST = listOf( ... )` 块"
    body = m.group(1)

    pats = re.findall(r'Regex\(\s*"""(.*?)"""\s*(?:,\s*([^)]*))?\)', body, re.S)
    if not pats:
        return None, 'WHITELIST 块里没找到 `Regex("""...""")`'

    out = []
    for pattern, opts in pats:
        flags = 0
        if opts and "IGNORE_CASE" in opts:
            flags |= re.IGNORECASE
        out.append((pattern, flags, pattern))
    return out, None


def extract_whitelist(path):
    """读文件版（`--self-test` 走 [extract_whitelist_text]，内存里变异）"""
    with open(path, encoding="utf-8") as f:
        return extract_whitelist_text(f.read())


def self_test(src_path):
    """★★ 证明本脚本**有牙** —— 在内存里把白名单放松一处，断言自己会红。

    ★ 为什么必须做：本工作区已经有过一次「用来自证的工具，自己没牙」
    （`--buggy` 长期全绿、`exit 0`，见 AR §9.7）。
    凡是用来自证的东西，**本身必须被证伪过一次**。
    """
    print("\n【自检】把白名单放松一处，看本脚本是否变红")
    with open(src_path, encoding="utf-8") as f:
        s = f.read()
    # 把"精确形状"放松成 `.*` —— 一次看起来无害的"简化"
    weakened = s.replace(r"at\+bkl=\d{1,4}", r"at\+bkl=.*")
    if weakened == s:
        print("  ✗ 变异没生效（源码里找不到 `at\\+bkl=\\d{1,4}`）⇒ 自检本身失效")
        return False

    pats, err = extract_whitelist_text(weakened)
    if pats is None:
        print(f"  ✗ 变异后抠不到白名单：{err}")
        return False

    compiled = [(re.compile(p, f), raw) for p, f, raw in pats]

    def allowed(cmd):
        c = cmd.strip()
        return any(rx.fullmatch(c) for rx, _ in compiled)

    leaked = [c for c in DENY if allowed(c)]
    if leaked:
        print(f"  ✓ 抓到 {len(leaked)} 条本不该放行的：")
        for c in leaked[:6]:
            print(f"      · {c!r}")
        print("  ⇒ 本脚本有牙")
        return True
    print("  ✗ 放松了白名单却一条都没抓到 ⇒ ★ 本脚本没有牙，不能信它")
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--source", default=SRC,
        help="★ 被测的 Kotlin 源文件。**默认就是真文件**；"
             "给一个变异副本即可验证本脚本【有牙】",
    )
    ap.add_argument(
        "--self-test", action="store_true",
        help="★★ 在内存里制造一个【放松了的白名单】，断言本脚本会红（证明有牙）",
    )
    args = ap.parse_args()
    src_path = args.source

    print("=" * 70)
    print("TNT GO 串口白名单 · 源码级校验（★ 抠真实正则，不写镜像）")
    print("=" * 70)
    print(f"被测源码：{os.path.relpath(src_path, WORKSPACE) if os.path.exists(src_path) else src_path}")

    if not os.path.exists(src_path):
        print(f"✗ 找不到源文件：{src_path}")
        sys.exit(1)

    if args.self_test:
        ok = self_test(src_path)
        print("=" * 70)
        sys.exit(0 if ok else 1)

    pats, err = extract_whitelist(src_path)
    if pats is None:
        print(f"✗ {err}")
        print("  ⚠️ 本脚本**故意**在这里失败而不是跳过 —— 抠不到就等于没测（见文件头）")
        sys.exit(1)

    print(f"\n[1] 从源码抠到 {len(pats)} 条白名单正则")
    for _, _, raw in pats:
        print(f"     · {raw}")

    # ★ Kotlin 的 `Regex.matches` 要求**整串匹配** ⇒ Python 用 `fullmatch`
    compiled = [(re.compile(p, f), raw) for p, f, raw in pats]

    def allowed(cmd):
        c = cmd.strip()
        return any(rx.fullmatch(c) for rx, _ in compiled)

    print(f"\n[2] 期望【放行】的 {len(ALLOW)} 条")
    for c in ALLOW:
        ok = allowed(c)
        if not ok:
            print(f"  ✗ 被误拦：{c!r}")
            FAILED.append(f"误拦 {c!r}")

    print(f"\n[3] 期望【拦下】的 {len(DENY)} 条")
    for c in DENY:
        ok = not allowed(c)
        if not ok:
            print(f"  ✗ 被放行：{c!r}  ← ★ 危险")
            FAILED.append(f"放行 {c!r}")

    print("\n[4] 分组小结")
    check("① 关机/重启/变砖类命令全部拦下",
          all(not allowed(c) for c in DENY[:9]), f"{len(DENY[:9])} 条")
    check("② 其它 AT 命令全部拦下",
          all(not allowed(c) for c in DENY[9:13]), f"{len(DENY[9:13])} 条")
    check("★★ ③ 形状擦边（空值/负数/超长/多余空格）全部拦下",
          all(not allowed(c) for c in DENY[13:22]), f"{len(DENY[13:22])} 条")
    check("★★★ ④ 注入（CRLF / 分号 / 空格夹带第二条命令）全部拦下",
          all(not allowed(c) for c in DENY[22:]), f"{len(DENY[22:])} 条")
    check("⑤ 合法命令确实能过（白名单没写死成一律拒绝）",
          all(allowed(c) for c in ALLOW), f"{len(ALLOW)} 条")

    print("\n" + "=" * 70)
    if FAILED:
        print(f"✗ {len(FAILED)} 条未通过：")
        for x in FAILED:
            print(f"   · {x}")
        print("=" * 70)
        sys.exit(1)
    print("✓ 白名单判据全部通过")
    print("=" * 70)
    sys.exit(0)


if __name__ == "__main__":
    main()
