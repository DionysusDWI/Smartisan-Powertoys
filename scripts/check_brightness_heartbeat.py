#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★★ **亮度心跳的排程点 · 结构回归闸**（任务 AR12 故障复盘）

## 它防的是哪一类 bug

2026-09-15 实机故障：`files/tntgo_brightness.state` 的 `ts` **停住 10 分钟**，
电量侧每轮判「亮度未知」，**而 logcat 里一行异常都没有**。

根因：心跳的排程写在

```kotlin
if (root == null) {
    ...
    handler.removeCallbacks(heartbeat)
    handler.postDelayed(heartbeat, HEARTBEAT_MS)
}
```

里面，而 **`install -r` 重启进程后，无障碍服务会先于 `onStartCommand` 连上**，
走 `setA11y → refresh → notifyListeners → publishBrightness` 把状态文件**写了一次**
⇒ 时间戳看起来正常（恰好落进有效窗口），但**心跳从未被排程**。

## 为什么必须有这道闸（而不是"以后小心点"）

| 为什么 | 说明 |
|---|---|
| ★ **运行时看不出来** | 没有日志、没有异常；症状只出现在**另一个进程**（电量侧说"亮度未知"） |
| ★★ **`publishBrightness` 的成功会让判据失效** | "文件被写过一次" ≠ "发布者在心跳" —— 一次性的写**冒充**了心跳 |
| ★★★ **它只会被"重装/重启"触发** | 平时开发（改完代码手点启动）几乎不会命中，**回归时也不会** |

⇒ 所以判据只能是**结构性的**：**排程点不许在 `if (root == null)` 分支里**。

## 判据（三条，都必须成立）

1. `heartbeat` 这个 Runnable 被 `postDelayed` 排程过（**且**有 `removeCallbacks`，保证幂等）
2. ★ **该排程语句不在 `if (root == null)` 的语句块内**
3. ★ 心跳**自己会留日志**（否则"静默停摆"还会再来）

## 用法

```bash
python scripts/check_brightness_heartbeat.py            # 查真源码
python scripts/check_brightness_heartbeat.py --selftest # 用合成的三份源码证伪本闸
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
SRC = os.path.join(
    WS, "projects", "mode-launcher", "src", "mod-tntgo-brightness",
    "src", "main", "java", "com", "shware", "mode", "mod", "brightness",
    "BrightnessModService.kt",
)


def strip_comments(text):
    """去掉行注释与块注释。★ 判据扫的是**代码**，注释里出现的同样字串不算数
    （否则那段"踩坑记录"注释会把闸自己骗过去 —— 这是本工作区踩过的老坑）。"""
    out = []
    in_block = False
    for line in text.splitlines():
        s = line
        if in_block:
            if "*/" in s:
                s = s.split("*/", 1)[1]
                in_block = False
            else:
                continue
        s = re.sub(r"/\*.*?\*/", "", s)
        if "/*" in s:
            s = s.split("/*", 1)[0]
            in_block = True
        s = re.sub(r"//.*$", "", s)
        out.append(s)
    return "\n".join(out)


def check(text):
    """返回 `(ok: bool, findings: [str], facts: dict)`"""
    code = strip_comments(text)
    findings = []

    # ── 判据 1：排程存在 + 幂等
    has_post = "postDelayed(heartbeat" in code
    has_remove = "removeCallbacks(heartbeat)" in code
    if not has_post:
        findings.append("★ 找不到 `postDelayed(heartbeat…)` ⇒ 心跳根本没被排程")
    if not has_remove:
        findings.append("找不到 `removeCallbacks(heartbeat)` ⇒ 多次 onStartCommand 会叠加出多条心跳链")

    # ── 判据 2：★★ 排程语句不在 `if (root == null)` 块内
    lines = code.splitlines()
    guard = None
    for i, ln in enumerate(lines):
        if re.search(r"if\s*\(\s*root\s*==\s*null\s*\)", ln):
            guard = i
            break
    inside_guard = False
    if guard is not None and has_post:
        # 从 `if (root == null) {` 开始做花括号配平，看排程行落在不在里面
        depth = 0
        started = False
        for j in range(guard, len(lines)):
            seg = lines[j]
            if "postDelayed(heartbeat" in seg:
                if depth > 0:
                    inside_guard = True
                break
            depth += seg.count("{") - seg.count("}")
            if not started:
                if "{" in seg:
                    started = True
                else:
                    break          # `if (…)` 后面没跟 `{` ⇒ 单语句形式，交给下面兜底
    if inside_guard:
        findings.append(
            "★ 心跳排程 **在 `if (root == null)` 块内** ⇒ 正是 2026-09-15 那个故障的形态："
            "进程重启后无障碍先连上、`root` 非空 ⇒ **排程被跳过**；"
            "而状态文件已被写过一次 ⇒ 新鲜度判据**看不出**心跳不在")

    # ── 判据 3：心跳自己留日志
    m = re.search(r"private val heartbeat = object : Runnable \{(.*?)\n    \}", code, re.S)
    body = m.group(1) if m else ""
    has_log = bool(re.search(r"Log\.(i|w|e)\(", body))
    if not has_log:
        findings.append("★ 心跳体内**没有任何日志** ⇒ 停摆将是**静默**的（无法从日志发现）")

    facts = {"post": has_post, "remove": has_remove,
             "in_guard": inside_guard, "logs": has_log}
    return (not findings), findings, facts


GOOD = '''
class S {
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private val heartbeat = object : Runnable {
        override fun run() {
            val why = BrightnessCore.republish()
            Log.i(TAG, "心跳：$why")
            handler.postDelayed(this, 30000)
        }
    }
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, 30000)
        if (root == null) {
            attachOverlay(0)
        }
        return START_STICKY
    }
}
'''

# ★ 故障形态：排程在 `if (root == null)` 里面（原样复刻线上那份代码）
BAD_INSIDE_GUARD = '''
class S {
    private val heartbeat = object : Runnable {
        override fun run() {
            Log.i(TAG, "心跳")
            handler.postDelayed(this, 30000)
        }
    }
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (root == null) {
            attachOverlay(0)
            handler.removeCallbacks(heartbeat)
            handler.postDelayed(heartbeat, 30000)
        }
        return START_STICKY
    }
}
'''

# ★ 另一种失效：排程压根没有
BAD_NO_SCHEDULE = '''
class S {
    private val heartbeat = object : Runnable {
        override fun run() { Log.i(TAG, "心跳") }
    }
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (root == null) { attachOverlay(0) }
        return START_STICKY
    }
}
'''

# ★ 静默形态：排程对、但心跳不吭声
BAD_SILENT = '''
class S {
    private val heartbeat = object : Runnable {
        override fun run() {
            BrightnessCore.republish()
            handler.postDelayed(this, 30000)
        }
    }
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, 30000)
        return START_STICKY
    }
}
'''


def selftest():
    cases = [
        ("① 修好后的形态（排程在 guard 外 + 有日志）⇒ 必须通过", GOOD, True),
        ("② ★ 原故障形态（排程在 `if (root == null)` 内）⇒ 必须报出", BAD_INSIDE_GUARD, False),
        ("③ 压根没排程 ⇒ 必须报出", BAD_NO_SCHEDULE, False),
        ("④ 排程对但心跳静默 ⇒ 必须报出", BAD_SILENT, False),
    ]
    print("=" * 72)
    print("check_brightness_heartbeat · 自检（合成源码）")
    print("=" * 72)
    bad = 0
    for name, src, want in cases:
        ok, findings, _ = check(src)
        good = ok == want
        bad += 0 if good else 1
        print("  {} {}".format("✓" if good else "✗", name))
        if not good:
            print("      ★ 期望 {}，实得 {}；findings={}".format(want, ok, findings))
        elif not ok:
            for f in findings:
                print("      └─ {}".format(f[:100]))
    print("-" * 72)
    if bad:
        print(f"✗ 自检失败 {bad}/{len(cases)}")
        return 1
    print(f"✓ 自检通过 {len(cases)}/{len(cases)} —— 四种形态都能被分开 ⇒ 闸**有牙**")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        sys.exit(selftest())

    if not os.path.exists(SRC):
        print(f"✗ 找不到源码：{SRC}")
        sys.exit(2)
    with open(SRC, encoding="utf-8") as f:
        text = f.read()

    ok, findings, facts = check(text)
    print("=" * 72)
    print("AR12 · 亮度心跳排程点 · 结构回归闸")
    print("=" * 72)
    print(f"源码：{os.path.relpath(SRC, WS)}")
    print(f"  postDelayed 排程 .......... {'✓' if facts['post'] else '✗'}")
    print(f"  removeCallbacks（幂等） ... {'✓' if facts['remove'] else '✗'}")
    print(f"  ★ 排程在 `if (root==null)` 内 ... {'✗ **是**（就是那个 bug）' if facts['in_guard'] else '✓ 否'}")
    print(f"  心跳自带日志 ............... {'✓' if facts['logs'] else '✗'}")
    print("-" * 72)
    if ok:
        print("✓ 通过 —— 心跳的排程不再依赖 `root == null`，且停摆会在日志里显形")
        sys.exit(0)
    for f in findings:
        print(f"✗ {f}")
    sys.exit(1)


if __name__ == "__main__":
    main()
