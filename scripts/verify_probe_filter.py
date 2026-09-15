#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证：把 `dumpsys activity services` 过滤成两类行之后，**解析结果不变**。

## 为什么要单独验

`ModRuntime.probeAll` 改成在设备侧 `grep` 之后，传回来的不再是完整块结构，
而是**只留** `ServiceRecord{…}` 与 `isForeground=true` 两类行。
解析器依赖"`isForeground` 紧跟它自己的 `ServiceRecord`"这个**顺序**性质 ——
本脚本把 Kotlin 的 `parseServiceRecords` 逻辑原样搬过来，
对**过滤前**与**过滤后**两份真实 dump 各跑一遍，逐条比对。
"""
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.join(ROOT, "toolchain", "android-sdk", "platform-tools", "adb.exe")
SERIAL = os.environ.get("ANDROID_SERIAL", "")

RX_SVC = re.compile(r"ServiceRecord\{.*?\s([\w.]+)/([\w.$]+)\}")


def sh(cmd):
    out = subprocess.run([ADB, "-s", SERIAL, "shell", cmd],
                         capture_output=True)
    return out.stdout.decode("utf-8", "replace")


def parse(dump):
    """Kotlin `ModRuntime.parseServiceRecords` 的逐行对照实现。"""
    res = {}
    pkg = cls = None
    fg = False

    def flush():
        nonlocal pkg, cls, fg
        if pkg is not None and cls is not None:
            res[f"{pkg}/{cls}"] = fg
        pkg = cls = None
        fg = False

    for line in dump.splitlines():
        m = RX_SVC.search(line)
        if m:
            flush()
            pkg = m.group(1)
            cls = m.group(2)
            if cls.startswith("."):
                cls = pkg + cls
            continue
        if pkg is not None and "isForeground=true" in line:
            fg = True
    flush()
    return res


def main():
    full = sh("dumpsys activity services")
    filt = sh("dumpsys activity services | grep -E 'ServiceRecord|isForeground=true'")

    print(f"过滤前 {len(full):>7} 字节 / {full.count(chr(10)):>5} 行")
    print(f"过滤后 {len(filt):>7} 字节 / {filt.count(chr(10)):>5} 行")

    a, b = parse(full), parse(filt)
    print(f"\n解析出 {len(a)} / {len(b)} 条 ServiceRecord")

    only_a = set(a) - set(b)
    only_b = set(b) - set(a)
    diff = {k for k in set(a) & set(b) if a[k] != b[k]}

    if only_a:
        print(f"✗ 只在过滤前出现的 {len(only_a)} 条：{sorted(only_a)[:5]}")
    if only_b:
        print(f"✗ 只在过滤后出现的 {len(only_b)} 条：{sorted(only_b)[:5]}")
    if diff:
        print(f"✗ isForeground 不一致的 {len(diff)} 条：")
        for k in sorted(diff)[:8]:
            print(f"    {k}: 前={a[k]} 后={b[k]}")

    # 我们自己的几个 mod 在不在、判得对不对（这是实际用途）
    print("\n本应用的组件：")
    for k in sorted(x for x in b if x.startswith("com.shware.mode/")):
        print(f"  {k}  isForeground={b[k]}")

    ok = not (only_a or only_b or diff)
    print(f"\n{'✓ 过滤前后解析结果完全一致' if ok else '✗ 有差异，过滤掉了不该掉的行'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
