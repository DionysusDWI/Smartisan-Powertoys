#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性诊断：把 run_bkl_tests 的探针**原样**编译，并把编译器输出**全文**留下来。

为什么要它：③ 的失败信息被 `keep[:12]` 截断了（那次截断是**为了**别让一串 `at …`
挤掉根因），但 IR lowering 崩的根因**恰好**在更深处 ⇒ 截断把最有用的那段丢了。

⚠️⚠️ 2026-09-16 更正：本脚本原先**自己抄了一遍编译器命令行**，并且抄的是**带
`-include-runtime` 的那一版** ⇒ 它**钉死了旧行为**、因此**验不了**对
`compile_and_run()` 的修改（实测：改完之后跑它，照样报 `<no_path>`）。
★ 这正是本项目反复记的那一族 —— **判据自己实现一遍受测逻辑 ⇒ 只能自证**。
⇒ 已改为**直接调用 `R.compile_and_run()`**（真路径），不再抄命令行。

用法：python scripts/_diag_probe_compile.py
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_bkl_tests as R  # noqa: E402

src = open(R.CURVE_KT, encoding="utf-8").read()
body = R.extract_object(src)
probe = R.kotlin_main(R.SCENARIOS)
text = "// probe\n" + body + "\n" + probe

kotlinc = R.find_kotlin_compiler()
print("[diag] kotlinc =", kotlinc)
cp_str, parts = R.compiler_classpath(kotlinc)
print("[diag] classpath jars =", len(parts))

work = os.path.join(tempfile.gettempdir(), "bklprobe_diag")
os.makedirs(work, exist_ok=True)
kt = os.path.join(work, "BklProbe.kt")
with open(kt, "w", encoding="utf-8") as f:
    f.write(text)
print("[diag] 探针源码 =", kt, os.path.getsize(kt), "bytes")

print("[diag] 运行时 stdlib =", R.probe_runtime_jar(kotlinc))
print("[diag] ---- 走真路径 R.compile_and_run() ----")
try:
    stdout = R.compile_and_run(kotlinc, text, work, stdlib=cp_str)
except RuntimeError as e:
    print("[diag] ✗ 失败：")
    print(str(e))
    # 编译器全文（如果有）也一并倒出来，便于看 Caused by
    log = os.path.join(work, "compiler_full.log")
    if os.path.exists(log):
        print("[diag] ---- compiler_full.log ----")
        with open(log, encoding="utf-8") as f:
            print(f.read())
    sys.exit(1)

lines = stdout.splitlines()
print("[diag] ✓ 编译＋执行成功，stdout", len(lines), "行")
for line in lines:
    if line.startswith(("CASE|", "WIDEN|", "SELFTEST|", "DONE")):
        print("   ", line[:200])

