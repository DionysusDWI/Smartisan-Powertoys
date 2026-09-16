#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性 A/B：**改动前**的 `TntgoBklCurve.kt`（HEAD 版）跑同一个探针，UT3 期望什么？

为什么必须做这个实验：③ Kotlin 真执行被 `<no_path>` 挡了整整一轮 ⇒
`UT3-dis-antiphys` 期望 `Rejected`、实得 `Ok` 这件事**可能是既有问题**，
也可能是本轮修改引入的。⛔ 只靠"读代码觉得等价"不算证据 —— 跑出来才算。

做法：`git show HEAD:<kt>` 取**未修改**的源码 → 用同一套脚手架编译执行 → 比 UT3。

用法：python scripts/_diag_ut3_ab.py
"""
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_bkl_tests as R  # noqa: E402


def _strip_c1(probe_src):
    """把探针源码里 **C1 新增的每一处**都删掉，只留 gate 相关。

    ★ 为什么要按【行】扫而不是切两段：C1 在这份脚手架里有**三处**落点
      （`WIDEN_TABLES` 声明、`briefOf()` 函数、`main()` 里的断言段），
      切两段会漏掉第三处 —— 而漏掉时报的是 `unresolved reference 'wideningPair'`，
      **看起来像"HEAD 版真的没有这东西"**，其实是裁剪没干净
      （第一次就死在这里，连错两轮）。⇒ 一律按行过滤，且**删完必须自查**。
    """
    out, drop, stop = [], False, None
    for line in probe_src.splitlines():
        if line.startswith("// ★★ C1 探针要验的那两张表"):
            drop, stop = True, "start"        # ① WIDEN_TABLES 声明块（含 briefOf）
            continue
        if line.startswith("fun main() {"):
            drop, stop = False, None          # 到 main 就停删
            out.append(line)
            continue
        if line.startswith("    // ★★★★ C1：表级违规"):
            drop, stop = True, "std"          # ② main 第一段 C1（到 STD_TABLE 结束）
            continue
        if line.startswith("    // ★★★ C1 的**核心性质**"):
            drop, stop = True, "done"         # ③ main 第二段 C1（到 println("DONE") 结束）
            continue
        if drop and stop == "std" and line.startswith("    val lv = parseLevels(STD_TABLE)"):
            drop, stop = False, None          # ★ 这一行本身要保留
        if drop and stop == "done" and line.strip() == 'println("DONE")':
            drop, stop = False, None          # ★ 同样要保留这一行
        if not drop:
            out.append(line)
    text = "\n".join(out)
    # ⚠️ 只能查**代码引用**（`TntgoBklCurve.wideningPair`），不能查裸词 `wideningPair`
    #    —— 探针的**注释里**就写着这个词（`// C1 Kotlin: wideningPair 与 worstViolation…`），
    #    裸词检查会对注释误报（第一次就是这么又假红一轮）。
    for bad in ("Widening", "TntgoBklCurve.wideningPair", "briefOf(", "WIDEN|", "WIDEN_TABLES"):
        assert bad not in text, "裁剪不彻底：仍残留 " + bad
    return text


def probe_ut3(build):
    """编译执行探针（只喂 UT3 那张表），返回 gate 判读字符串。"""
    src = build["src"]
    body = R.extract_object(src)
    text = "// probe\n" + body + "\n" + R.kotlin_main(R.SCENARIOS)
    kotlinc = R.find_kotlin_compiler()
    cp_str, _parts = R.compiler_classpath(kotlinc)
    with tempfile.TemporaryDirectory(prefix="bklab_") as tmp:
        stdout = R.compile_and_run(kotlinc, text, tmp, stdlib=cp_str)
    return R.parse_probe(stdout)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ws = os.path.dirname(here)
    cur = open(R.CURVE_KT, encoding="utf-8").read()

    print("=" * 72)
    print("A/B：改动前(HEAD) vs 改动后(工作区) —— 同一探针、同一批用例")
    print("=" * 72)

    tried = []
    # A：HEAD 版（未修改）
    try:
        cp = subprocess.run(["git", "show", "HEAD:" + os.path.relpath(R.CURVE_KT, ws).replace("\\", "/")],
                            cwd=ws, capture_output=True, text=True, encoding="utf-8", timeout=60)
        if cp.returncode == 0 and cp.stdout.strip():
            tried.append(("A 改动前 (HEAD)", cp.stdout))
        else:
            print("⚠️ 取 HEAD 版失败：", (cp.stderr or "")[:200])
    except Exception as e:                                        # noqa: BLE001
        print("⚠️ 取 HEAD 版异常：", e)

    tried.append(("B 改动后 (工作区)", cur))

    results = {}
    for name, src in tried:
        print("\n---- {} ----".format(name))
        # HEAD 版没有 wideningPair ⇒ 探针里 C1 的断言会让编译失败。
        # ⚠️ 这正是要点：不能为了让它编译而改探针（那就是改判据去迁就实现）。
        #    所以对 HEAD 版用**精简探针**：只问 gate，不问 C1。
        if "wideningPair" not in src:
            print("    ⚠️ 该版无 wideningPair ⇒ 用精简探针（只问 gate，不涉及 C1）")
            simple = _strip_c1(R.kotlin_main(R.SCENARIOS))
            try:
                body = R.extract_object(src)
                text = "// probe\n" + body + "\n" + simple
                kotlinc = R.find_kotlin_compiler()
                cp_str, _parts = R.compiler_classpath(kotlinc)
                with tempfile.TemporaryDirectory(prefix="bklab_") as tmp:
                    stdout = R.compile_and_run(kotlinc, text, tmp, stdlib=cp_str)
                gates = R.parse_probe(stdout)[0]
            except RuntimeError as e:
                print("    ✗ 编译/运行失败：", str(e)[:400])
                continue
        else:
            try:
                gates = probe_ut3({"src": src})[0]
            except RuntimeError as e:
                print("    ✗ 编译/运行失败：", str(e)[:400])
                continue

        for sid, table, trend, wgate, wma, why in R.SCENARIOS:
            got = gates.get(sid, ("<缺失>", wgate))[0]
            mark = "✓" if got == wgate else "✗"
            flag = ""
            if sid.startswith("UT3"):
                flag = "   ← ★ 争议点"
            print("    {} {} 实得={} 期望={}{}".format(mark, sid, got, wgate, flag))
        results[name] = gates

    print("\n" + "=" * 72)
    print("结论")
    print("=" * 72)
    keys = list(results.keys())
    if len(keys) == 2:
        a, b = results[keys[0]], results[keys[1]]
        diffs = [(sid, a.get(sid, ("?",))[0], b.get(sid, ("?",))[0])
                 for sid, *_ in R.SCENARIOS
                 if a.get(sid, ("?",))[0] != b.get(sid, ("?",))[0]]
        if diffs:
            print("★ 两版**行为不同**（⇒ 本轮的修改确实改变了行为）：")
            for sid, va, vb in diffs:
                print("    {} : 改动前={}  改动后={}".format(sid, va, vb))
        else:
            print("★★ 两版**逐条相同** ⇒ UT3 的 `Ok` **不是本轮引入的**，")
            print("   而是 ③ 被 <no_path> 挡住这一轮里**从未真正执行**的既有失败。")
    else:
        print("⚠️ 只跑成一版，无法比较：", keys)
    return 0


if __name__ == "__main__":
    sys.exit(main())
