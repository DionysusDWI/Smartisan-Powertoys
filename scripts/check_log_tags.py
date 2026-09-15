#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★★ **日志 TAG 漂移检查** —— 让"脚本按 TAG 过滤"这件事不可能再静默失效

## 为什么需要它（2026-09-15 真实踩到，代价是 9 分钟的假结论）

AS3b 把 TNT GO 的设备 I/O 从 `ModeMod/Tntgo` 统一到 **`ModeMod/Serial`**，
而 `watch_tntgo_capacity.py` **仍按旧 TAG 过滤** ⇒ `raw=+BATCG` **一条都抓不到**
⇒ 监看连报 **9 分钟「无读数」**，而 mod 其实每 30 s 都在正常轮询。

★ **症状极具迷惑性**：看起来像"mod 停摆了"，实际是**仪器瞎了**。

★★ 更值得记的是：**同一个坑我当天已经踩过一次** ——
   `measure_serial_contention.sh` 因为同样的 TAG 改名误报「成功读到 = 0」，
   我修了那一个脚本，**却没有想到去查其它按 TAG 过滤的脚本**。

⇒ 本检查把这件事变成**机械可查**：
   ① 从 Kotlin 源码抽出所有日志 TAG 常量；
   ② 从 `scripts/` 抽出所有被当成 TAG 用的字面量（形如 `Mode*/Xxx`）；
   ③ **脚本里出现、但 Kotlin 里不存在的** ⇒ 报错。

## 判据

```bash
python scripts/check_log_tags.py          # 必须 exit 0
python scripts/check_log_tags.py --self-test   # 证明它有牙（注入一个假 TAG ⇒ 必须红）
```
"""

import argparse
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(WS, "projects", "mode-launcher", "src")
SCRIPTS = os.path.join(WS, "scripts")

# Kotlin: `private const val TAG = "ModeMod/Tntgo"` / `const val TAG = "..."` / `val TAG = "..."`
RE_KT_TAG = re.compile(r'\b(?:const\s+)?val\s+TAG\s*(?::\s*String\s*)?=\s*"([^"]+)"')

# 脚本里被当 TAG 用的字面量：本项目约定一律形如 `Mode.../Xxx`
# ⚠️ 刻意**不**匹配单独的 `Mode` —— 那会命中普通英文词。
RE_SCRIPT_TAGISH = re.compile(r'"(Mode[A-Za-z]*/[A-Za-z0-9_]+)"')

# 这些不是 logcat TAG，是 action / 组件名之类，白名单放行
WHITELIST = {
    "Mode/Shortcut",     # 若将来变成 action 名也仍会被 Kotlin 侧覆盖，这里只作兜底
}


def kotlin_tags():
    tags = set()
    for root, _dirs, files in os.walk(SRC):
        if os.sep + "build" + os.sep in root + os.sep:
            continue
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(root, fn)
            try:
                with open(p, encoding="utf-8", errors="replace") as f:
                    tags.update(RE_KT_TAG.findall(f.read()))
            except OSError:
                pass
    return tags


def script_literals():
    """{字面量: [脚本路径, ...]}"""
    found = {}
    for root, _dirs, files in os.walk(SCRIPTS):
        for fn in files:
            if not fn.endswith((".py", ".sh")):
                continue
            p = os.path.join(root, fn)
            try:
                with open(p, encoding="utf-8", errors="replace") as f:
                    text = f.read()
            except OSError:
                continue
            for m in RE_SCRIPT_TAGISH.finditer(text):
                found.setdefault(m.group(1), []).append(os.path.relpath(p, WS))
    return found


def run(extra_injected=None, quiet=False):
    kt = kotlin_tags()
    if extra_injected:
        kt = kt - {extra_injected}          # 自检：假装 Kotlin 侧没有这个 TAG

    lits = script_literals()
    orphans = {}
    for lit, where in sorted(lits.items()):
        if lit in WHITELIST:
            continue
        if lit not in kt:
            orphans[lit] = sorted(set(where))

    if not quiet:
        print("=" * 68)
        print("日志 TAG 漂移检查 —— 脚本引用的 logcat TAG 必须仍存在于 Kotlin 源码")
        print("=" * 68)
        print(f"\nKotlin 侧 TAG 常量 {len(kt)} 个：")
        for t in sorted(kt):
            print(f"  · {t}")
        print(f"\n脚本侧被当 TAG 用的字面量 {len(lits)} 个：")
        for t, w in sorted(lits.items()):
            mark = "✗ 已失效" if t in orphans else "✓"
            print(f"  {mark} {t}   ← {', '.join(sorted(set(w)))}")

    if orphans:
        print("\n" + "!" * 68)
        print("✗ 脚本引用了【Kotlin 里不存在】的日志 TAG —— 该过滤会静默失效：")
        for t, w in orphans.items():
            print(f"   · {t}   出现在：{', '.join(w)}")
        print("\n  ★ 症状：脚本报「无读数 / 0 次」而**被测对象其实一切正常**。")
        print("  ⇒ 改法：把脚本的 TAG 过滤更新为 Kotlin 侧现存的 TAG。")
        print("!" * 68)
        return 1

    if not quiet:
        print("\n" + "=" * 68)
        print("✓ 所有脚本 TAG 都仍存在于 Kotlin 源码中")
        print("=" * 68)
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true",
                    help="★ 证明本检查有牙：抽掉一个真实 TAG ⇒ 必须报错")
    args = ap.parse_args()

    if args.self_test:
        print("=== 自检：抽掉 `ModeMod/Serial` 后必须变红 ===")
        rc = run(extra_injected="ModeMod/Serial", quiet=True)
        if rc == 0:
            print("✗ 自检失败：TAG 不存在却没报错 ⇒ 本检查没有牙")
            sys.exit(1)
        print("✓ 自检通过：注入的失效 TAG 被抓到了\n")
        sys.exit(0)

    sys.exit(run())


if __name__ == "__main__":
    main()
