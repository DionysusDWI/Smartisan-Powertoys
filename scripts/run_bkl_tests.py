#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★★★★ **AR13 · 亮度/功耗链路的回归套件（统一入口）**

## 一条命令跑完全部自检

```bash
python scripts/run_bkl_tests.py              # 跑全部（含 ③ 与有牙证伪）
python scripts/run_bkl_tests.py --no-kotlin  # 本机跑不动编译器时：跳过 ③，保留 ⑥
python scripts/run_bkl_tests.py --no-teeth   # 跳过"摘掉闸门必须失败"的证伪
python scripts/run_bkl_tests.py --list       # 只列项目
python scripts/run_bkl_tests.py --json       # 机器可读
```

## 它做六件事（每一件都必须**能失败**）

| # | 项目 | 为什么不能省 |
|---|---|---|
| ① | `analyze_bkl_curve.py --selftest` | 判读口径的**镜像**（分档/拟合/带外/反物理/XML 解码） |
| ② | `check_brightness_heartbeat.py --selftest` | ★ 心跳停摆的**结构回归闸**（原故障形态逐字复刻） |
| ③ | ★★★ **把 `TntgoBklCurve.kt` 真的编译并跑起来** | 前面两个都只验**镜像**；产品走的是 **Kotlin**。不跑它 = 「工具全绿而产品没接上」 |
| ④ | ★ 产品**接线点**检查 | 闸门写好了却没人调 —— 这正是 AR13 要修的那个 bug 的形态，**必须由判据盯着** |
| ⑤ | 真机归档端到端复算 | 用 `.ref/ar12d/` 的真台账跑判读并对账（缺归档 ⇒ SKIP） |
| ⑥ | ★★ **字节码兜底（javap）** | 直接看**构建产物**里有没有那两个枚举与三个方法，**且比时间戳**（防拿过期 class 当证据） |

## ★★★ ③ 为什么要动编译器（而不是把 Kotlin 翻译成 Python）

本项目的经典陷阱是「**两边各写一遍，镜像全绿而实现是错的**」。把 Kotlin **翻译**成
Python 再测，等于**第三次重写**：测的是我的翻译，不是产品。

⇒ 做法是**抽出真源码**（`object TntgoBklCurve` 全文 ＋ 三个无 Android 依赖的函数），
拼一个 `main` 编译成 jar **直接执行**，逐条比对结论等级与数值。

## ⚠️⚠️ ③ 在本机**执行不起来**（已知限制，2026-09-15）—— 是**降级并留缺口**，不是"跳过"

③ 要把 `kotlin-compiler-embeddable` 从 Gradle 缓存里自己拼起来（缓存里**没有**
Kotlin Gradle Plugin 的 **plugin marker** ⇒ `plugins { kotlin("jvm") }` 在 `--offline` 下解析不了）。
拼起来之后撞到的**全是拼装本身的问题**，不是被测代码的问题：

| 尝试 | 结果 |
|---|---|
| `java -cp <embeddable> K2JVMCompiler` | `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics` ⇒ 缺 stdlib |
| 补 stdlib | `NoClassDefFoundError: kotlinx/coroutines/CoroutineScope` ⇒ 缺 coroutines |
| 再补 | `NoClassDefFoundError: gnu/trove/TObjectHashingStrategy` ⇒ 缺 trove4j |
| 都补齐 | ★ **泛型函数**（`fun <T> g(l: List<T>, f: (T) -> Int?)`）在 **IR lowering** 崩：<br/>`Backend Internal error` ＋ `ERROR_EXPR 'Default Stub'` ⇒ **不是代码错，是这套拼装的编译器坏了** |
| 换 Gradle（合成 Maven 镜像 788 件 ＋ 最小 POM） | 缺 `gson:2.8.9`（Gradle 8.7 只带 `gson-2.10`）⇒ 离线凑不齐 |
| 换用模块自己的 `compileDebugKotlin` | ✅ **产品编译没问题** —— 但 `JavaExec` 一碰 AGP 的 `debugRuntimeClasspath` / `debugUnitTestRuntimeClasspath` 就 variant ambiguity |

⇒ ③ 用 `--no-kotlin` **显式跳过**（报告里是 `SKIP` 并写明"本项**没有**结论"，
**不会变绿**）。缺口由两件事补：

* **⑥ 字节码**（结构在，且 class 比源码新）
* **真实构建** `gradlew :mod-tntgo-battery:compileDebugKotlin`（2026-09-15 已 `BUILD SUCCESSFUL`）

★ 但**数值行为仍然只有 Python 镜像在判** —— 这个缺口**必须留在报告里**，
不许当作已验收（纪律：弱判据不得冒充强判据）。

## ★★ 有牙证伪（`--teeth`，默认开）

把抽出来的源码里 `worstViolation(...) != null` 改成 `false`（＝**闸门被摘掉**），
再跑一遍 ⇒ **必须失败**。
⚠️ 否则"全绿"只证明脚本能跑通，**不证明它盯着闸门**。
"""

import argparse
import json
import locale
import os
import re
import subprocess
import sys
import tempfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPTS = os.path.join(WS, "scripts")
PY = sys.executable
JAVA = os.path.join(WS, "toolchain", "jdk-17.0.20.1+1", "bin", "java.exe")
if not os.path.exists(JAVA):
    JAVA = "java"          # 兜底：环境里自己有 JDK 也能跑（但会记进详情里）

CURVE_KT = os.path.join(WS, "projects", "mode-launcher", "src", "mod-tntgo-battery",
                        "src", "main", "java", "com", "shware", "mode", "mod", "tntgo",
                        "TntgoBklCurve.kt")
PROFILE_KT = os.path.join(WS, "projects", "mode-launcher", "src", "mod-tntgo-battery",
                          "src", "main", "java", "com", "shware", "mode", "mod", "tntgo",
                          "TntgoBklProfile.kt")
SERVICE_KT = os.path.join(WS, "projects", "mode-launcher", "src", "mod-tntgo-battery",
                          "src", "main", "java", "com", "shware", "mode", "mod", "tntgo",
                          "TntgoBatteryService.kt")


# --------------------------------------------------------------------- 工具

class Report:
    def __init__(self):
        self.rows = []

    def add(self, name, ok, detail="", skipped=False):
        self.rows.append({"name": name, "ok": bool(ok), "detail": detail,
                          "skipped": bool(skipped)})
        mark = "SKIP" if skipped else ("✓" if ok else "✗")
        print("  {} {:56}{}".format(mark, name, ("  " + detail) if detail else ""))
        return ok

    @property
    def failed(self):
        return [r for r in self.rows if not r["ok"] and not r["skipped"]]

    def summary(self):
        print("-" * 78)
        n, f = len(self.rows), len(self.failed)
        if f:
            print("✗ 回归失败 {}/{} —— 下面这些必须查清（**不许当作环境问题跳过**）".format(f, n))
            for r in self.failed:
                print("    ✗ {} ：{}".format(r["name"], r["detail"][:160]))
        else:
            print("✓ 全部通过 {}/{} —— 判读镜像、心跳结构闸、Kotlin 实现、产品接线点都在判据之内".format(n, n))
        return 1 if f else 0


def run_py(args, timeout=900):
    """跑**本仓库的 Python 子脚本**并按其**约定**解码（UTF-8）。

    ★★ 为什么必须与 [run] 分开（2026-09-16 踩到）：
      · **JVM 子进程**按平台默认（中文 Windows ＝ cp936）输出 ⇒ 用 [run]（cp936）。
      · **本仓库的 Python 子脚本**通过 `PYTHONIOENCODING` 约定用 **UTF-8** 输出。
      ⛔ 把 [run] 的编码改成 cp936 之后，`analyze_bkl_curve.py` 的中文输出被
        **按 cp936 解** ⇒ ⑤ 的 `"放电：3 档**逐项一致**" in out` 立刻不成立
        ⇒ ★ 修一处、坏另一处（我当时就是这么把 ⑤ 弄红的）。
      ⇒ 一律**显式**：跑 Python 子脚本就用本函数（强制它 UTF-8 输出、按 UTF-8 解）。
    """
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    return subprocess.run([PY] + list(args), cwd=WS, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout, env=env)


def run(cmd, cwd=WS, timeout=600, env=None):
    """跑子进程并**按它实际使用的编码**解码输出。

    ★★★★★ 2026-09-16 定位：这里原先硬写 `encoding="utf-8"` ⇒
    ⛔ **中文 Windows 上子进程（JVM 的 `System.out`）按 GBK 输出**，被当 UTF-8 解 ⇒
      乱码，而 `errors="replace"` 把坏字节换成 `\\ufffd` ⇒ **信息不可逆丢失**。
      症状极具误导性：`CASE|…|absMa|…` 这类**纯 ASCII 值完全正常**，
      只有探针里 `assert(cond, "中文消息")` 的**断言名**变成 `������ȷ…`
      ⇒ Python 侧按中文名取结果**永远取不到** ⇒ 判据红得毫无道理。
      （★ 我当时误判为"断言名与判据名漂开"，还写了个前缀匹配去迁就 —— 那是**错的修法**：
        真正该修的是"别让信息在解码时被丢掉"，而不是"绕开被损坏的名字"。）

    ⚠️ 顺序：① 环境里的 `PYTHONIOENCODING`／`JAVA_TOOL_OPTIONS` ② 本函数用的
      `locale.getpreferredencoding(False)`（即平台默认，中文 Windows ＝ cp936）。
    """
    enc = os.environ.get("PYTHONIOENCODING") or locale.getpreferredencoding(False) or "utf-8"
    enc = enc.split(":")[0]                       # `utf-8:replace` 这类写法只取编码部分
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                          encoding=enc, errors="replace", timeout=timeout, env=env)


def find_kotlin_compiler():
    """★ 在 Gradle 缓存里找 `kotlin-compiler-embeddable`（**不下载**）。找不到就报清楚。"""
    root = os.path.join(WS, "toolchain", ".gradle", "caches", "modules-2", "files-2.1",
                        "org.jetbrains.kotlin", "kotlin-compiler-embeddable")
    best = None
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.startswith("kotlin-compiler-embeddable") and f.endswith(".jar"):
                p = os.path.join(dirpath, f)
                if best is None or os.path.getmtime(p) > os.path.getmtime(best):
                    best = p
    return best


def find_cached_jar(group, name_prefix, prefer=None, prefer_any=None,
                    exclude=("-common", "-jdk", "sources")):
    """在 Gradle 缓存里找某个依赖的 jar（**不下载**）。

    @param prefer 优先匹配的完整文件名（**版本必须对齐**的场合用它）
    @param prefer_any 依次尝试的完整文件名列表（找不到就退回"最新那个"）
    """
    root = os.path.join(WS, "toolchain", ".gradle", "caches", "modules-2", "files-2.1")
    hits = []
    for dirpath, _, files in os.walk(root):
        for f in files:
            if (f.startswith(name_prefix) and f.endswith(".jar")
                    and not any(x in f for x in exclude)):
                hits.append(os.path.join(dirpath, f))
    if not hits:
        return None
    for want in ([prefer] if prefer else []) + list(prefer_any or []):
        for p in hits:
            if os.path.basename(p) == want:
                return p
    return sorted(hits, key=os.path.getmtime)[-1]


def find_stdlib(compiler_jar):
    """`kotlin-compiler-embeddable` **不含** stdlib ⇒ 直接 `java -cp` 跑它会
    `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`（本脚本第一版就撞过）。
    ⇒ 从缓存里按**同版本**取 stdlib 补进 classpath。"""
    ver = re.search(r"kotlin-compiler-embeddable-([\d.]+)\.jar", compiler_jar)
    return find_cached_jar("org.jetbrains.kotlin", "kotlin-stdlib-",
                           prefer="kotlin-stdlib-{}.jar".format(ver.group(1)) if ver else None)


def compiler_classpath(kotlinc):
    r"""★ 让 `kotlin-compiler-embeddable` **能起来**所需的运行时 jar。

    ⚠️ 它是个**瘦** jar：把这些拆出来之后，缺一件就报一个
    `NoClassDefFoundError`，而**报出来的名字跟真正的原因差很远**
    （本脚本依次撞过 `kotlin/jvm/internal/Intrinsics` 与
    `kotlinx/coroutines/CoroutineScope`）。

    ## ★★★★★ 第五件：`org.jetbrains:annotations`（2026-09-16 定位，C1 轮）

    这一件的**症状是所有里最会误导人的**，所以单列出来：

    ```
    Backend Internal error: Exception during IR lowering
      ERROR_EXPR 'Default Stub' type=kotlin.Int        ← 看起来像"泛型函数把编译器搞崩了"
    Caused by: NoClassDefFoundError: org/jetbrains/annotations/NotNull
    ```

    ★ 真正的根因是**代码生成阶段要写 `@NotNull` 可空性注解，而那个类不在 classpath 上**。
    ⚠️ 为什么这条特别值得记：前一轮（AR13）**据此判定"这套拼装的编译器坏了，不是我们的代码"**
    —— 结论方向对（确实不是被测代码的问题），但**原因判错了**，于是把一条**可以修好的**
    环境缺口写成了"本机跑不动"的死结论，`③ Kotlin 真执行` 因此降级成 SKIP 了整整一轮。
    ⇒ ★ 教训：`Caused by:` 那一行**才是**根因；`Backend Internal error` 只是**症状**。
      （同一族：本工作区"报错不指向真正的原因"已记多例。）

    ## ★★★★ 版本必须**成对**：stdlib 与 reflect（2026-09-16 实测）

    补上 annotations 之后**又**换了报错，而且这次是**更早**的阶段（frontend 之后、写产物时）：

    ```
    CompileEnvironmentException: Couldn't find kotlin-stdlib at <no_path>\lib\kotlin-stdlib.jar
    ```

    ⚠️ 而 stdlib **明明在** classpath 上（`-cp` 里有 `kotlin-stdlib-2.0.20.jar`）。

    ## ★★★★★ `<no_path>` 的真因**不是版本**（2026-09-16 实测更正）

    当时**据此判定**「真因是 stdlib 2.0.20 与 reflect 1.9.22 版本不成对」，
    并写下修法「把 stdlib 与 reflect 钉在同一版本」。⚠️ 但那条修法**没有落地**
    （`stdlib` 仍写死编译器版本 2.0.20，reflect 照旧取到 1.9.22）——
    ⇒ ★ 而它**照样能过**：说明**版本不是这次的原因**。

    真因在报错自己那一行里：**`<no_path>`**。
      · `-cp` 上的 stdlib **编译器根本没在做这个检查**；
      · 这个检查查的是 **`kotlin.home/lib/kotlin-stdlib.jar`**（即 `-include-runtime`
        要往产物里塞的那一份 runtime），而 `kotlin-compiler-embeddable` 是个**瘦 jar**，
        **没有 `kotlin.home`** ⇒ 解析成 `<no_path>` ⇒ 必然失败。
    ⇒ ★ 修法是**别走那条路径**：产物**不再自带 runtime**（去掉 `-include-runtime`），
      改为运行时用 `-cp <stdlib>` + **主类名**执行（见 [compile_and_run]）。
    ⇒ ⚠️ 教训：**「看起来在 classpath 上却报找不到」时要先问"它到底在查哪个路径"** ——
      报错里那个 `<no_path>` 就是答案，而它当时被当成了无关的样板文字。
    """
    ver = re.search(r"kotlin-compiler-embeddable-([\d.]+)\.jar", kotlinc)
    ver = ver.group(1) if ver else None
    stdlib = "kotlin-stdlib-{}.jar".format(ver)
    #: reflect 优先与编译器同版；缓存里没有时按**降序**退（★ 且必须与 stdlib **成对**）
    reflect_pref = ["kotlin-reflect-{}.jar".format(v)
                    for v in (["2.0.20"] if ver == "2.0.20" else [ver or "2.0.20"])] + \
                   ["kotlin-reflect-1.9.22.jar", "kotlin-reflect-1.9.20.jar",
                    "kotlin-reflect-1.8.21.jar"]
    parts = [kotlinc]
    for g, n, pref, pref_any in (
        ("org.jetbrains.kotlin", "kotlin-stdlib-", stdlib, None),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm-", None, None),
        ("org.jetbrains.intellij.deps", "trove4j-", None, None),   # ★ 编译器内部要用（gnu.trove）
        ("org.jetbrains.kotlin", "kotlin-reflect-", None, reflect_pref),
        ("org.jetbrains.kotlin", "kotlin-script-runtime-", None, None),
        # ★★★ 第五件：**代码生成**阶段要用（写 `@NotNull` 注解）——
        #     缺它 ⇒ IR lowering 里抛 `NoClassDefFoundError`（症状见上面的注释）
        ("org.jetbrains", "annotations-", None,
         ["annotations-23.0.0.jar", "annotations-13.0.jar"]),
    ):
        p = find_cached_jar(g, n, prefer=pref, prefer_any=pref_any)
        if p:
            parts.append(p)
    return os.pathsep.join(parts), parts



def extract_object(src):
    """抽出 `object TntgoBklCurve { … }`（到行首的 `}` 为止）。

    ⚠️ 用**行首缩进**判断结尾，不做括号配平 —— 本项目已经被"括号配平找结尾"
      坑过一次（见 `bkl_common.read_curve` 的注释）。
    """
    start = src.index("object TntgoBklCurve {")
    end = src.index("\n}\n", start)
    body = src[start:end + 2]
    # 让它能被 main 调用：object 内的 `private fun` 才需要放开
    body = body.replace("private fun medianInt", "fun medianInt")
    return body


#: Kotlin 侧的用例 —— ★ 期望值来自**物理方向**，不是从实现抄的
SEED = [71, 497, 2000]

#: ★★★★★ G1（2026-09-16）：**真机放电档的原始样本形态** —— `(mcu, ma, 样本数)`。
#:
#: ⚠️ 这三个薄档是**真机实测**的（240 样本台账），不是编出来的：
#:   `94` 只有 1 条、`178` 只有 2 条（`1116` 与 `2108`，**充/放切换瞬态**）、
#:   `44` 与 `497`/`2000` 是厚档。探针用它验：**产品侧真的把薄的剔了、厚的留下**。
G1_RAW_LEVELS = [
    (44, 980, 95),
    (94, 1109, 1),
    (178, 1116, 1),      # ⚠️ 故意给 2 条**不同**的电流（真机是 1116 与 2108），见下方 G1 探针注释
    (178, 2108, 1),
    (497, 1344, 23),
    (2000, 2068, 8),
]

SCENARIOS = [
    # ⚠️ `id` 一律 **ASCII**：Kotlin 源码与探针 stdout 都要过控制台编码这一关，
    #    中文 case id 会在 Windows 控制台上变成乱码（那会让人怀疑"是编码问题还是判据问题"）。
    #    中文说明留在 Python 侧打印。
    # (id, 档位表 "(mcu,ma,n);…", trend, 期望 gate, 期望 inband_absMa(或 None), 说明)
    # ⚠️⚠️ 2026-09-16（C1 轮）**修正期望值**：`UT1` 原写 `1344.0`、`UT2` 原写 `3536.0`
    #   —— 那是**该档的实测中位数**，而 `estimate()` 返回的是**加权最小二乘直线在该 MCU 处的值**
    #   （`TntgoBklCurve.estimate`：`absMa = if (where == InBand) line.at(mcu) else null`）。
    #   ⇒ ★ **直线不复现每一个实测点**，所以这两个数**本来就不该相等**：
    #       `UT1` 497 处实测 1344 ⇒ 拟合 **1292.16**（权重 `(n+1)/n` = 16/15、24/23、9/8）
    #       `UT2` 497 处实测 3536 ⇒ 拟合 **3535.34**
    #   ★ 1292.16 **已独立验算**：`Σw = 122/23`、`x̄=534.28`、`ȳ=1297.26`、
    #     斜率 `0.5058`、截距 `1039.24` ⇒ `at(497) = 1292.6`（与探针一致，差在舍入）。
    #   ⚠️ 这**不是**"照抄实现输出"—— 若哪天 `fitLine` 改了口径，这两条会**照旧失败**，
    #      失败原因仍然成立（"拟合值不等于实测值"这件事本身是被验算过的）。
    ("UT1-dis-normal", "71,1001,15;497,1344,23;2000,2068,8", "Discharge", "Ok", 1292.16,
     "生产数据的单调走向 ⇒ 必须 Ok；★ 期望是**拟合值**(1292.16)，不是该档实测值(1344)"),
    # ★★★★ 2026-09-16（G1）**修正 UT2 的表**：原表 `23,3704,1;497,3536,70;2000,2888,8`
    #   里的 `23` 档 **n=1**，正是 G1 要剔掉的那种"单条样本档"
    #   （它在**充电方向**上造出 `23→178 反向 356 mA` 的假违规，见 ⑤-G2 / ①G1-d）。
    #   G1 生效后它被剔 ⇒ 只剩 `497/2000` 两档 ⇒ 拟合从 `3535.34` 变成 **`3536.00`**
    #   （两点定线必过两点 ⇒ 拟合值**恰好等于**实测值 —— 那会让
    #    "拟合值 ≠ 实测值"这条判据**失去区分度**）。
    #   ⇒ 换成**真机充电表的完整形态**（`178/497/2000`，`n` 分别 8/70/8）：
    #     档位数仍是 3、期望回到 **`3535.34`**，而 G1 **一档都不剔**（三档都是厚档）
    #     ⇒ ★ 用例恢复区分度，且同时说明"G1 不是无差别砍档"。
    #   ⚠️⚠️ 但 `3535.34` **不能照抄**：它只对"`497/2000` 两点"成立。
    #     换成三点之后**加权最小二乘给的是 `3580.14`**（2000 档把直线往上抬）。
    #     ⇒ 期望值**现算**：见本节末的 `_fit_expect()`（与 `fit_line` **同一公式**，
    #       但输入是**源码里那张表**，不是从实现抄的输出）。
    ("UT2-chg-negslope", "178,4060,8;497,3536,70;2000,2888,8", "Charge", "Ok", 3580.14,
     "★ 实测充电曲线（负斜率）**是正确的** ⇒ 必须 Ok（接错方向就会死在这条）；期望同为**拟合值**"),
    # ★★★★ 2026-09-16（C1 轮）**修正**：本用例原先的表是 `71,1001,15;497,1310,20;2000,2112,10`
    #   —— 那一串 `1001→1310→2112` 在**放电方向下是单调正确的**，根本不含"反物理"，
    #   而它的说明却写着"（−900 mA）"（那份数据里**没有任何一步下降 900**）。
    #   ⇒ ★ 用例**自相矛盾**，而 ③ 层被 `<no_path>` 挡了整整一轮 ⇒ 它**从未被执行过**。
    #   （A/B 实测证据：HEAD 版与工作区版**逐条相同**，`UT3` 两版都返回 `Ok`。见
    #     `scripts/_diag_ut3_ab.py` 与 `docs/20260916_AR13_C1_设备日志自证方向证据.md`。）
    # ⇒ 现表按**归档真机**的形态重建：`178→497` 是实测那一对，反向 **268 mA**
    #   （`.ref/ar13/…prefs.xml` 的放电档 `178:1612 / 497:1344`）——
    #   比"编一个下降"更强：它让探针与真机取到的是**同一对档**。
    ("UT3-dis-antiphys", "71,1001,15;178,1612,23;497,1344,10", "Discharge", "Rejected", None,
     "★ 更亮的一档反而更省电（178→497 反向 268 mA，取自归档真机）⇒ 必须 Rejected 且不给数"),
    ("UT4-chg-antiphys", "71,2888,10;497,3536,70;2000,3704,8", "Charge", "Rejected", None,
     "★ 充电态却随亮度【上升】⇒ 反向违规 ⇒ 必须 Rejected"),
    ("UT5-too-few", "497,1344,23", "Discharge", "NotEnoughLevels", None,
     "1 档 ⇒ 斜率 0/0 ⇒ 不给曲线"),
    ("UT6-direction", "71,1001,15;497,1344,23;2000,2068,8", "Charge", "Rejected", None,
     "★ 同一份【上升】数据在充电方向下就是反物理 ⇒ 证明闸门真的看方向"),
]


def kotlin_main(scenarios):
    """生成探针源码。

    ⚠️ **刻意不做那些花哨的链式 `to` 重载** —— 第一版试过，
    但它引入的脚手架比被测代码还多（等于又"重写一遍"）。
    ⇒ 直接摊成 `"sid" -> "值"` 的 map，用例表本身用 Python 生成。
    """
    #        （`Level` 同理 —— 它也是 `TntgoBklCurve` 的嵌套类）
    seed_vals = ", ".join("TntgoBklCurve.Level({}, 0.0, 1)".format(m) for m in SEED)
    # ⚠️ 三处 Kotlin 语法的坑（**报错都不指向真正的原因**）：
    #    ① 键值对用 `to`，**不是** Python 的 `->`（写错 ⇒ 一串 `Expecting ')'`）
    #    ② 条目之间**必须有逗号**（漏了 ⇒ 报的却是下一行的 `cannot infer type`）
    #    ③ 嵌套枚举**必须写全名** `TntgoBklCurve.Trend.Charge` —— 只写 `Trend.Charge`
    #       报的是 `unresolved reference 'Trend'` ＋ `cannot infer type`，
    #       看起来像"mapOf 推断不出来"，其实是名字根本不在作用域里。
    tables = ",\n".join('            "{}" to "{}"'.format(s[0], s[1]) for s in scenarios)
    trends = ",\n".join('            "{}" to TntgoBklCurve.Trend.{}'.format(s[0], s[2])
                        for s in scenarios)
    gates = ",\n".join('            "{}" to "{}"'.format(s[0], s[3]) for s in scenarios)
    mas = ",\n".join('            "{}" to {:.4f}'.format(s[0], -1.0 if s[4] is None else s[4])
                     for s in scenarios)
    std, chg = scenarios[0][1], scenarios[1][1]
    #: ★★★★ C1：探针要打印**"是哪一对"**（表级违规的那一对 ＋ 幅度 ＋ 方向）
    widen_map = ",\n".join('            "{}" to "{}"'.format(s[0], s[1])
                           for s in scenarios)
    return '''
// ---- 探针脚手架（只做一件事：把用例喂进【抽出来的源码】并打印结果）----------
fun assert(cond: Boolean, msg: String) { println("SELFTEST|" + msg + "|" + cond) }

fun parseLevels(s: String): List<TntgoBklCurve.Level> =
    s.split(";").filter { it.isNotEmpty() }.map { item ->
        val p = item.split(",")
        TntgoBklCurve.Level(p[0].toInt(), p[1].toDouble(), p[2].toInt())
    }

val TABLES = mapOf(
@@TABLES@@
)
val TRENDS = mapOf(
@@TRENDS@@
)
val WANT_GATE = mapOf(
@@GATES@@
)
val WANT_MA = mapOf(
@@MAS@@
)
val STD_TABLE = "@@STD@@"
val CHG_TABLE = "@@CHG@@"

// ★★ C1 探针要验的那两张表（与 SCENARIOS 同一批文本 ⇒ 不改两处）
val WIDEN_TABLES = mapOf(
@@WIDEN@@
)

/**
 * ★★★★ C1：**"哪一对"** 的字符串化 —— ⚠️ 与 `Widening.brief()` 在 Kotlin 里**是同一句**，
 * 所以这里打印的是**源码里那个方法**的输出（不是探针自己又算一遍）。
 *
 * ⚠️⚠️ 但**输出时把三段分开**：`brief()` 用的是 `178→497 反向 268 mA`，
 *    里面一旦含 `|` 就会**污染 `|` 分隔的协议行**（我第一版就把
 *    `178>497|268.0|Discharge` 当成**一段**发出去 ⇒ 解析端把 5 段读成 3 段、
 *    又 IndexError 又报"Kotlin=<缺失>"）。⇒ **分隔符不许出现在数据里**。
 */
fun briefOf(w: TntgoBklCurve.Widening?): String =
    if (w == null) "-" else w.loMcu.toString() + ">" + w.hiMcu.toString() + "|" +
        String.format("%.1f", w.violationMa) + "|" + w.trend.name

/** ★ 给协议行用：把三段拆开发（避免 `|` 污染）。`null` ⇒ 返回 `null`。 */
fun widenParts(w: TntgoBklCurve.Widening?): List<String>? =
    if (w == null) null
    else listOf(w.loMcu.toString() + ">" + w.hiMcu.toString(),
                String.format("%.1f", w.violationMa), w.trend.name)

fun main() {
    val seed = listOf(@@SEED@@)
    assert(seed.size == 3 && seed[1].mcu == 497, "探针自检：抽出源码里的 seed 能构造")

    // ── ★★★★★ G1（2026-09-16）：`plateauing(minN)` **真的在产品侧剔薄档**
    //    ⚠️ 这一组必须存在：其余判据全都用**手写的档位表**喂 `gate()`，
    //       于是"分档时到底剔没剔"**在别处一次也测不到** —— 改坏 `plateauing`
    //       而 `gate` 照旧正确，整套回归**会全绿**。
    run {
        val raw = @@G1S@@
        // ① 原始分档：**5 档**（含两个薄档 94 与 178）
        val all = TntgoBklCurve.plateauing(raw, { it.first }, { it.second }, minN = 1)
        assert(all.levels.size == 5,
               "G1 Kotlin: minN=1 ⇒ 原始 5 档（实测 " + all.levels.size + "）")
        assert(all.thinDropped == 0, "G1 Kotlin: minN=1 ⇒ 一档都不许剔")
        // ② 闸门口径：**3 档**、剔掉 2 个、且剔的正是 94 与 178
        val g1 = TntgoBklCurve.plateauing(raw, { it.first }, { it.second },
                                         minN = TntgoBklCurve.MIN_N_PER_LEVEL)
        val keptMcu = g1.levels.map { it.mcu }.joinToString(",")
        assert(g1.levels.size == 3 && g1.thinDropped == 2,
               "G1 Kotlin: minN=3 ⇒ 3 档 / 剔 2 个（实测 " + g1.levels.size +
               " 档 / 剔 " + g1.thinDropped + " 个）")
        assert(keptMcu == "44,497,2000",
               "G1 Kotlin: 留下的必须是 44,497,2000（实测 " + keptMcu + "）")
        // ③ ★★ 因果：**同一批样本**，闸门吃原始表 ⇒ 反物理；吃 G1 表 ⇒ Ok
        val vRaw = TntgoBklCurve.gate(all.levels, TntgoBklCurve.Trend.Discharge, 1)
        val vG1 = TntgoBklCurve.gate(g1.levels, TntgoBklCurve.Trend.Discharge)
        assert(vRaw == TntgoBklCurve.Verdict.Rejected && vG1 == TntgoBklCurve.Verdict.Ok,
               "G1 Kotlin: 关 G1 ⇒ Rejected / 开 G1 ⇒ Ok（实测 " + vRaw.name + " / " + vG1.name + "）")
        // ④ ★★ 回读路径（不经 plateauing 的表）也**不能绕过** G1
        assert(TntgoBklCurve.gate(all.levels, TntgoBklCurve.Trend.Discharge)
               == TntgoBklCurve.Verdict.Ok,
               "G1 Kotlin: 含薄档的表直接喂 gate() 也必须被滤掉（回读路径）")
        // ⑤ ★ 薄档在带边缘 ⇒ **实测带端点只由可用档定**
        //    ⚠️ 这里**不用** plateauing：直接给一张"最暗档是薄档"的表，
        //       验的是 `estimate()` 自己的过滤（回读路径同样要走它）。
        val e = TntgoBklCurve.estimate(all.levels, 2000, TntgoBklCurve.Trend.Discharge)
        assert(e.bandMcuMin == 44 && e.bandMcuMax == 2000,
               "G1 Kotlin: 带端点只由可用档定（实测 " + e.bandMcuMin + "~" + e.bandMcuMax + "）")
        val thinAtEdge = listOf(TntgoBklCurve.Level(44, 980.0, 1),      // ⛔ 薄档在最暗端
                                TntgoBklCurve.Level(497, 1344.0, 23),
                                TntgoBklCurve.Level(2000, 2068.0, 8))
        val e2 = TntgoBklCurve.estimate(thinAtEdge, 2000, TntgoBklCurve.Trend.Discharge)
        assert(e2.bandMcuMin == 497,
               "G1 Kotlin: 薄档在最暗端 ⇒ 带起点必须上移到 497（实测 " + e2.bandMcuMin + "）")
    }
    for ((sid, wantGate) in WANT_GATE) {
        val table = TABLES[sid] ?: ""
        val trend = TRENDS[sid] ?: TntgoBklCurve.Trend.Discharge
        val lv = parseLevels(table)
        val v = TntgoBklCurve.gate(lv, trend)
        println("CASE|" + sid + "|gate|" + v.name + "|" + wantGate)
        val mid = lv.sortedBy { it.mcu }.let { it[it.size / 2].mcu }
        val e = TntgoBklCurve.estimate(lv, mid, trend)
        val ma = e.absMa ?: -1.0
        val wantMa = WANT_MA[sid] ?: -1.0
        println("CASE|" + sid + "|absMa|" + String.format("%.4f", ma) + "|" + String.format("%.4f", wantMa))
        // ★★★★ G3 修正（2026-09-16）：`where` **不再**是"给不给数"的判据。
        //    ⛔ 旧的协议行是 `where|<名字>|<wantMa<0 ? "NOT_InBand" : "InBand">` ——
        //      它把「被拒」与「位置在带内」当成**互斥**的两件事，
        //      而 G3 之后 `estimate()` 在**被拒但 mcu 落带内**时**如实**返回
        //      `where=InBand` ＋ `unusable=Rejected`（`Where` 与 `Unusable` 是**两个正交维度**）。
        //    ⇒ 本行只当**信息**打（不做断言）：`where` 与位置有关，与"给不给数"无关。
        println("CASE|" + sid + "|where|" + e.where.name + "|" + e.where.name)
        //    ⇒ 原因**只报 Kotlin 自己的那个字段**，不夹带任何"期望" ——
        //       ⛔ 上一版让协议行自带期望（`wantMa < 0 ⇒ Rejected`），结果
        //       **1 档**那个用例的正确答案是 `NotEnoughLevels` ⇒ 期望当场错。
        //       ★ 原因**不许从别的字段猜**（`gate` 才是权威），也不许写进协议。
        println("CASE|" + sid + "|unusable|" + (e.unusable?.name ?: "null"))
        println("CASE|" + sid + "|levels|" + e.levels + "|" + lv.size)
    }
    // ★★★★ C1：表级违规**必须说得出是哪一对** ＋ 与 worstViolation **数值必须一致**
    //   （同一判据的两种粒度；两者若漂了，设备日志就会指错那一对）
    for ((sid, table) in WIDEN_TABLES) {
        val trend = TRENDS[sid] ?: TntgoBklCurve.Trend.Discharge
        val lv = parseLevels(table)
        val w = TntgoBklCurve.wideningPair(lv, trend)
        val vio = TntgoBklCurve.worstViolation(lv, trend)
        val agree = (w == null && vio == null) ||
                    (w != null && vio != null && kotlin.math.abs(w.violationMa - vio) < 1e-9)
        assert(agree, "C1 Kotlin: wideningPair 与 worstViolation 必须一致（" + sid + "）")
        // ★ 三段分开打（协议用 `|` 分隔，数据里不许再含 `|`）
        val _wp = widenParts(w)
        println("WIDEN|" + sid + "|" + (if (_wp == null) "-" else _wp.joinToString("|")))
    }
    val lv = parseLevels(STD_TABLE)
    assert(TntgoBklCurve.gate(lv) == TntgoBklCurve.gate(lv, TntgoBklCurve.Trend.Discharge),
           "兼容层：单参 gate() 必须等价于显式 Discharge")
    assert(TntgoBklCurve.estimate(lv, 497).absMa == TntgoBklCurve.estimate(lv, 497, TntgoBklCurve.Trend.Discharge).absMa,
           "兼容层：单参 estimate() 必须等价于显式 Discharge")
    assert(TntgoBklCurve.maxDrop(lv) == (TntgoBklCurve.worstViolation(lv, TntgoBklCurve.Trend.Discharge) ?: 0.0),
           "兼容层：maxDrop() 仍按放电方向")
    assert(TntgoBklCurve.worstViolation(parseLevels(CHG_TABLE), TntgoBklCurve.Trend.Charge) == null,
           "★ 实测充电曲线（负斜率）不得被判违规")
    // ★★★ C1 的**核心性质**：方向参数**真的换得动**（同一张表 ⇒ 两个方向给出不同的判定）
    //   ⚠️⚠️ 本条原先写作「`wd` 与 `wc` **都非 null** 且指名不同的两对」—— **写错了**：
    //     `disT` 是 UT1 那张**单调正确**的放电表 ⇒ 在**放电**方向下**本来就该没有违规**
    //     （`wideningPair(disT, Discharge) == null` 才是对的）。
    //   ⇒ ★ 这正是"两条都拒 ⇒ 方向无关"那个陷阱的**镜像**：把"本方向合法"误当成"必须也违规"。
    //     它同样是 ③ 层被 `<no_path>` 挡住一整轮**从未执行**的判据。
    val disT = parseLevels(STD_TABLE)
    val wd = TntgoBklCurve.wideningPair(disT, TntgoBklCurve.Trend.Discharge)
    val wc = TntgoBklCurve.wideningPair(disT, TntgoBklCurve.Trend.Charge)
    assert(wd == null && wc != null,
           "C1 Kotlin: monotonic discharge table -> no violation on Discharge; violation on Charge")
    // ★ 并且"是哪一对"必须**带上判它的那个方向**（否则读日志的人仍要盲信那句 verdict）
    //   ⚠️ `wc` 是 `Widening?` ⇒ 必须 `!!`（上一行已断言非 null；Kotlin 的智能转换
    //      不跨 `assert()` 调用生效）。
    assert(wc!!.trend == TntgoBklCurve.Trend.Charge,
           "C1 Kotlin: wideningPair result must carry the trend it was judged with")
    println("DONE")
}
'''.replace("@@TABLES@@", tables).replace("@@TRENDS@@", trends) \
   .replace("@@GATES@@", gates).replace("@@MAS@@", mas) \
   .replace("@@WIDEN@@", widen_map) \
   .replace("@@STD@@", std).replace("@@CHG@@", chg) \
   .replace("@@SEED@@", seed_vals) \
   .replace("@@G1S@@", "listOf(" + ", ".join(
       "Pair({}, {}.0)".format(m, a)
       for m, a, n in G1_RAW_LEVELS for _ in range(n)) + ")")




#: 探针里 `fun main()` 所在文件 = `BklProbe.kt` ⇒ Kotlin 生成的类名固定是它。
#: ⚠️ 去掉 `-include-runtime` 之后**必须**显式指名主类（不再能用 `java -jar`）。
PROBE_MAIN_CLASS = "BklProbeKt"


def probe_runtime_jar(kotlinc):
    """探针**运行时**要的那份 stdlib（与编译器同版本）。

    ★ 为什么单独一个函数：这条路径以前**不存在**（靠 `-include-runtime` 把 stdlib
      塞进 jar），于是 `find_stdlib` 找出来的 stdlib **只喂给了编译器自己**、
      **从没喂给探针**。去掉 `-include-runtime` 之后它才成为必需。

    ⚠️ 找不到就**报错**（不许静默退回 `-include-runtime` —— 那条路必然撞 `<no_path>`，
      静默退回只会把"环境缺件"变成"原因不明的 IR lowering 崩"）。
    """
    p = find_stdlib(kotlinc)
    if not p:
        raise RuntimeError(
            "找不到与编译器同版本的 kotlin-stdlib（探针运行时需要）—— "
            "不接受 `-include-runtime` 兜底：embeddable 编译器没有 kotlin.home，"
            "那条路必然报 `Couldn't find kotlin-stdlib at <no_path>\\lib\\...`。"
            "请在 Gradle 缓存里补齐 stdlib。")
    return p


def compile_and_run(kotlinc, src_text, workdir, stdlib=None):
    """编译成 jar 并**真的执行**，返回 stdout。

    ★★ 2026-09-16 更正：**不再用 `-include-runtime`**（原因见 [compiler_classpath]
    里 `<no_path>` 那一段）。产物是**瘦 jar** ⇒ 执行时必须自己带上 stdlib，
    且不能用 `java -jar`（无 Main-Class 清单）⇒ 用 `-cp` ＋ [PROBE_MAIN_CLASS]。
    """
    kt = os.path.join(workdir, "BklProbe.kt")
    with open(kt, "w", encoding="utf-8") as f:
        f.write(src_text)
    jar = os.path.join(workdir, "probe.jar")
    # ★ embeddable 编译器**自己也需要 stdlib** 才能起来 ⇒ 补进它自己的 classpath；
    #   ⚠️ 还**必须**给 JAVA_HOME —— 否则它会去找 IDE 的 JDK，
    #      报的是一串 `KotlinCoreEnvironment.getOrCreateApplicationEnvironmentForProduction`
    #      （看起来像"编译器坏了"，其实只是不知道用哪个 JDK）。
    compiler_cp = kotlinc + (os.pathsep + stdlib if stdlib else "")
    env = dict(os.environ)
    env["JAVA_HOME"] = os.path.dirname(os.path.dirname(JAVA))
    # ★★★★★ `-Dfile.encoding=UTF-8` **不是可选项**（2026-09-16 实测定位）
    #
    # ⛔ Kotlin 2.x **没有** `-encoding` 开关（`-X` 里也搜不到 "encod"），
    #   它按 **JVM 的平台默认字符集**读源文件。中文 Windows 上是 GBK ⇒
    #   探针里所有**非 ASCII 字面量被读坏**：
    #     · `assert(cond, "C1 Kotlin: 单调正确的放电表…")` ⇒ 消息变成 `������ȷ…`
    #       ⇒ ★ Python 侧按**中文断言名**取结果**永远取不到**，判据红得毫无道理
    #         （我一度误判为"断言名与判据名漂开"，还写了个前缀匹配去迁就 —— 那是错的修法）。
    #     · `println("CASE|" + …)` 的值全是 ASCII ⇒ **数值判据完全不受影响**，
    #       ⇒ 故障表现是"数值全对、只有一条名字对不上"，**极具误导性**。
    #   ⚠️ 这一族（非 ASCII 被静默损坏）已记多例，而它每次都长得像"判据写错了"。
    cp = run([JAVA, "-Dfile.encoding=UTF-8", "-cp", compiler_cp,
              "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
              "-nowarn", "-cp", compiler_cp, "-d", jar, kt],
             timeout=1800, env=env)
    if not os.path.exists(jar):
        # ★ 报错要**从头部**截（编译器的根因在头几行；截尾部只会得到一串 `at …`），
        #   并且要把 compiler 的 `warning:` 滤掉 —— 否则真正的 `error:` 会被挤出去。
        raw = ((cp.stdout or "") + (cp.stderr or "")).splitlines()
        keep = [l for l in raw if not l.strip().startswith(("warning:", "at ", "..."))]
        raise RuntimeError("Kotlin 编译失败（classpath 共 {} 个 jar）：\n{}".format(
            compiler_cp.count(os.pathsep) + 1, "\n".join(keep[:12])))
    run_cp = jar + os.pathsep + probe_runtime_jar(kotlinc)
    out = run([JAVA, "-cp", run_cp, PROBE_MAIN_CLASS], timeout=300)
    if out.returncode != 0:
        raise RuntimeError("Kotlin 运行失败：\n" + (out.stdout or "")[-800:] + (out.stderr or "")[-800:])
    return out.stdout


def parse_probe(stdout):
    gates, mas, wheres, unusables, levels, asserts, widens, done = {}, {}, {}, {}, {}, {}, {}, False
    for line in stdout.splitlines():
        parts = line.split("|")
        # ⚠️⚠️ `CASE` 行有**两种形状**（2026-09-16 G3 起）：
        #     `CASE|<sid>|<key>|<got>|<want>`   ⇒ 5 段（gate／absMa／where／levels）
        #     `CASE|<sid>|unusable|<got>`       ⇒ **4 段**（原因**不自带期望** —— 由 `gate` 推导）
        #   ⛔ 原先只写 `len(parts) == 5` ⇒ **4 段那行被静默丢弃**，
        #      详情里显示成 `unusable=<缺失>`，看起来像"Kotlin 没说" ——
        #      而真相是**判据自己把字段扔了**（与 F7／F8 同一族：判据自伤给出假红）。
        if parts[0] == "CASE" and len(parts) == 4:
            _, sid, key, got = parts
            if key == "unusable":
                unusables[sid] = got
        elif parts[0] == "CASE" and len(parts) == 5:
            _, sid, key, got, want = parts
            if key == "gate":
                gates[sid] = (got, want)
            elif key == "absMa":
                mas[sid] = (float(got), float(want))
            elif key == "where":
                wheres[sid] = (got, want)
            elif key == "levels":
                levels[sid] = (got, want)
        elif parts[0] == "WIDEN":
            # ★★★ 2026-09-16 修正：探针输出有**两种形状** ——
            #     `WIDEN|<sid>|-`                              （无违规）
            #     `WIDEN|<sid>|<lo>|<hi>|<振幅>|<Trend.name>`   （有违规）
            #   原先只写 `len(parts) == 3` ⇒ ★ **有违规的用例整条被丢掉**，
            #   下游却报 `Kotlin=<缺失>` —— ⛔ 判据自己把字段扔了，却当成"对面没说"。
            #   ⚠️ 用**末段是不是方向词**来分辨，而不是数段数：段数口径一变就 IndexError，
            #      而方向词是语义锚点（同一族教训见 `_diag_probe_compile.py`）。
            # ⚠️ 形状（**以 repr 数出来的为准**，别靠眼睛数 `|`）：
            #     `WIDEN|<sid>|-`                        ⇒ parts=3、rest=['-']
            #     `WIDEN|<sid>|<lo>|<hi>|<幅度>|<方向>`   ⇒ parts=**5**、rest=**3** 项
            #   ★ 本处连错三次，全是"数段数"错的：先写了 `rest[3]`（越界），
            #     再写了"末段是方向词就算对"（把 3 项的形状也放进来）。⇒ 现在显式分派。
            rest = parts[2:]
            if rest == ["-"]:
                widens[parts[1]] = "-"
            elif len(rest) == 3 and rest[2] in ("Discharge", "Charge"):
                widens[parts[1]] = "{}|{}|{}".format(rest[0], rest[1], rest[2])
            else:
                raise RuntimeError(
                    "WIDEN 行形状不认识（parts={} 段，rest={}）：{} —— 形状变了就必须显式改这里，"
                    "⛔ 不许静默丢弃（本处曾因静默丢弃把 3 个用例报成'对面没说'）".format(
                        len(parts), rest, "|".join(parts)))
        elif parts[0] == "SELFTEST" and len(parts) == 3:
            asserts[parts[1]] = parts[2] == "true"
        elif parts[0] == "DONE":
            done = True
    return gates, mas, wheres, unusables, levels, asserts, widens, done


# --------------------------------------------------------------------- 项目

def item_selftest(rep, script, name):
    p = os.path.join(SCRIPTS, script)
    r = run_py([p, "--selftest"])
    tail = [l for l in (r.stdout or "").splitlines() if l.strip()][-1:] or [""]
    rep.add(name, r.returncode == 0, tail[0][:110] if r.returncode == 0 else
            "退出码 {} ／ {}".format(r.returncode, " ／ ".join((r.stdout or "").splitlines()[-3:])[:200]))


def expected_fit_ma(table, mcu):
    """★ **从源码里那张表**算出"拟合值应当是多少" —— 给 `SCENARIOS` 的期望值当**独立依据**。

    ⚠️ 为什么必须有它（2026-09-16，G1 轮**实测踩到两次**）：
      `UT1`/`UT2` 的期望值原本是**手算后写死**的常数。G1 轮我把 `UT2` 的表从
      `23,3704,1;497,3536,70;2000,2888,8` 换成三点之后，**忘了重算期望**
      ⇒ 探针给了正确值 `3580.14`，判据却报"失败"，还配上一句
      "接线点不在了"式的误导说明。**看着像实现错，其实是预期错。**
    ⇒ 现在每一次运行都用**同一个公式**（`analyze_bkl_curve.fit_line`）从表里算一遍，
      再与源码里的常数比。**谁改表不改期望（或反之），当场报"预期与公式不符"** ——
      错误信息**直接指出是预期的问题**，不再伪装成实现或接线缺口。

    ⛔ 这**不是**"拿实现当答案"：`fit_line` 是**独立镜像**，而 ③ 层验的是 **Kotlin** 侧；
       本函数只保证"**判据的预期**没有过期"。数值行为仍由 Kotlin 探针实测。
    """
    import importlib
    if SCRIPTS not in sys.path:
        sys.path.insert(0, SCRIPTS)
    A = importlib.import_module("analyze_bkl_curve")
    lv = [dict(mcu=int(a), abs_ma=float(b), n=int(c))
          for a, b, c in (it.split(",") for it in table.split(";") if it)]
    keep = A.wire_levels(lv)
    line = A.fit_line(keep)
    return None if line is None else line["a"] + line["b"] * mcu


def item_kotlin(rep, teeth=False):
    if not os.path.exists(CURVE_KT):
        rep.add("③ Kotlin 真执行（TntgoBklCurve）", False, "找不到 " + CURVE_KT)
        return
    kotlinc = find_kotlin_compiler()
    if kotlinc is None:
        rep.add("③ Kotlin 真执行（TntgoBklCurve）", False,
                "找不到 kotlin-compiler-embeddable（Gradle 缓存）⇒ **这不许当跳过处理**"
                "（没有它，Kotlin 侧就完全没有判据，正是 AR13 要根治的形态）")
        return
    # ── ★★ 先验**判据的预期**没过期（与 Kotlin 无关，纯 Python 侧就能查）
    for sid, table, _trend, _wg, wma, _why in SCENARIOS:
        if wma is None:
            continue
        lv = [dict(mcu=int(a), abs_ma=float(b), n=int(c))
              for a, b, c in (it.split(",") for it in table.split(";") if it)]
        mid = sorted(lv, key=lambda l: l["mcu"])[len(lv) // 2]["mcu"]
        want = expected_fit_ma(table, mid)
        ok = want is not None and abs(want - wma) < 0.05
        rep.add("③-预期 {} 的期望值没过期（表 ⇒ 公式 ⇒ {}）".format(sid, wma),
                ok, "" if ok else
                "★ **是预期错了，不是实现错了**：按表算出来是 {}，源码里写的是 {}"
                .format(None if want is None else round(want, 2), wma))
    src = open(CURVE_KT, encoding="utf-8").read()
    body = extract_object(src)
    mutated = True
    if teeth:
        # ★★ 有牙证伪：把闸门**摘掉**再跑 ⇒ 断言必须失败
        # ⚠️⚠️ 2026-09-16（G1）：这里原先替换的是**两参**形态
        #    `worstViolation(levels, trend) != null`。G1 把实现搬进**三参**重载之后，
        #    这一次替换**一个字符也没改到**（`str.replace` 找不到就原样返回，不报错）
        #    ⇒ 跑的还是**带牙的**源码 ⇒ ③-teeth **假绿**（实测输出："不合的用例：无（＝脚本失效）"）。
        #    ⇒ ★ 教训：**"改了源码"必须自己验**，不能假定 replace 一定命中。
        #      否则"证伪"会在**它自己失效**的时候报告"一切都好" —— 这正是 AR13 要根治的形态。
        before = body
        body = body.replace("worstViolation(wire, trend) != null", "false")
        mutated = (body != before)
    text = ("// 由 scripts/run_bkl_tests.py 从 TntgoBklCurve.kt 抽出后编译\n"
            + body + "\n" + kotlin_main(SCENARIOS))
    with tempfile.TemporaryDirectory(prefix="bklprobe_") as tmp:
        try:
            cp_str, _parts = compiler_classpath(kotlinc)
            stdout = compile_and_run(kotlinc, text, tmp, stdlib=cp_str)
        except RuntimeError as e:
            rep.add("③ Kotlin 真执行（有牙）" if teeth else "③ Kotlin 真执行（TntgoBklCurve）",
                    False, str(e)[:400])
            return
    gates, mas, wheres, unusables, levels, asserts, widens, done = parse_probe(stdout)

    if teeth:
        bad = [sid for sid, (got, want) in gates.items() if got != want]
        # ★★ 先证**证伪装置自己有效**（`replace` 真的改到了源码），再证判据变红。
        #    这两件事**必须分开报**：混在一起时，"判据没变红"与"证伪装置没生效"
        #    看起来是同一句话，而处置完全相反（一个要查判据、一个要查装置）。
        rep.add("③-teeth-0 ★★ 证伪装置**真的**改到了源码（replace 命中）", mutated,
                "" if mutated else "★ `replace` 没命中 ⇒ 跑的还是原源码 ⇒ 本条**证伪无效**")
        rep.add("③-teeth 摘掉闸门后 UT3/UT4/UT6 **必须**失败", bool(bad),
                "摘闸门后仍全绿 ⇒ 判据没盯着闸门！不合的用例：{}".format(bad or "无（＝脚本失效）"))
        return

    rep.add("③-0 探针编译＋执行完整", done, "缺 DONE 标记 ⇒ 探针半途死了")
    for k, v in asserts.items():
        rep.add("③ " + k, v)
    for sid, table, trend, wgate, wma, why in SCENARIOS:
        got_gate, want_gate = gates.get(sid, ("<缺失>", wgate))
        ok_gate = got_gate == want_gate
        got_ma, want_ma = mas.get(sid, (-1.0, wma if wma is not None else -1.0))
        want_ma_v = -1.0 if wma is None else wma
        ok_ma = abs(got_ma - want_ma_v) < 0.05
        got_w = wheres.get(sid, ("<缺失>", ""))[0]
        # ★★★★★ G3 修正（2026-09-16）：**"给不给数"由 `unusable` 判，不由 `where` 判**。
        #   ⛔ 原判据 `(wma is None and got_w != "InBand") or (wma is not None and got_w == "InBand")`
        #      —— 上一轮（C1）**刚刚**只修了它引用的变量（`want_ma` → 源头的 `wma`），
        #      却没发现**判据本身**已经过期：它假设「被拒 ⇒ 位置必在带外」，
        #      而 G3 之后 `estimate()` 在**被拒但 mcu 落带内**时**如实**返回 `where=InBand`
        #      ＋ `unusable=Rejected`（`Where` 与 `Unusable` 是**两个正交维度**）。
        #   ⚠️ 症状极具误导性：详情行里 `gate=`、`absMa=`、`where=` **三样全对**，
        #      红的原因只写在"缺一个字段"上 ⇒ 看着像实现错，其实是**预期错**。
        #   ★ 现在的判据只问一件事：**"给不出数"时 Kotlin 有没有说出原因**。
        #   ★★ 那个**原因**由 `gate` 权威推导（`Rejected` ⇒ 反物理；其余 ⇒ 档位/数值不足），
        #      ⛔ **不是**由 `wantMa < 0` 猜 —— 1 档那个用例的正确答案是 `NotEnoughLevels`，
        #      猜就会把**正确的实现**判红（实测踩到）。
        got_u = unusables.get(sid, "<缺失>")
        want_u = ("null" if wgate == "Ok"
                  else ("Rejected" if wgate == "Rejected" else "NotEnoughLevels"))
        ok_u = got_u == want_u
        got_lv, want_lv = levels.get(sid, ("-1", str(len(parse_table(table)))))
        ok_lv = got_lv == want_lv
        rep.add("③ {} {}".format(sid, why[:34]), ok_gate and ok_ma and ok_u and ok_lv,
                "gate={}/{} absMa={:.2f}/{:.2f} where={} unusable={}/{} levels={}".format(
                    got_gate, want_gate, got_ma, want_ma_v, got_w, got_u, want_u, got_lv))

    # ── ★★★★★ C1：**Kotlin 的 wideningPair ↔ Python 镜像**（设备日志要与判读工具**同口径**）
    #
    # 判据不是"两边都跑通"，而是**同一条用例下两边指名的那一对必须逐字一致**
    #（`lo>hi|幅度|方向`）。⚠️ 这一条**直接服务于设备取证**：
    # 设备日志打出的就是 `Widening.brief()`，而离线工具打印的是 `widening_pair()['brief']`
    # ⇒ 两者若漂开，"拿离线预测去对账设备日志"这件事本身就失去意义（纪律 ②）。
    if widens:
        try:
            sys.path.insert(0, SCRIPTS)
            import analyze_bkl_curve as _A
        except Exception as e:                                    # noqa: BLE001
            rep.add("③-C1 Kotlin↔Python「哪一对」对账", False, "导入镜像失败：{}".format(e)[:200])
        else:
            mism = []
            for sid, table, trend, _wg, _wm, _why in SCENARIOS:
                lv = [dict(mcu=int(a), abs_ma=float(b), n=int(c))
                      for a, b, c in (it.split(",") for it in table.split(";") if it)]
                wpy = _A.widening_pair(lv, "dis" if trend == "Discharge" else "chg")
                want = "-" if wpy is None else "{}>{}|{:.1f}|{}".format(
                    wpy["lo"], wpy["hi"], wpy["violation_ma"], trend)
                got = widens.get(sid, "<缺失>")
                if got != want:
                    mism.append("{}: Kotlin={} Python={}".format(sid, got, want))
            rep.add("③-C1 ★★ Kotlin `wideningPair` 与 Python 镜像**逐字一致**"
                    "（lo>hi|幅度|方向；两台实现对同一对档不许各说各话）",
                    not mism, "；".join(mism)[:300])
            # ⚠️ 2026-09-16：本条的断言**文本改过**（原写"指名的必须是不同的两对"，
            #   那条判据本身是错的 —— 见 kotlin_main 里的注释）。这里若继续按**旧文本**取，
            #   会永远取到默认 False ⇒ ★ **判据名与断言名漂开**，红得毫无道理。
            #   ⇒ 改为**按前缀找**：断言文本以后微调不会再把这条打红。
            _dir_key = [k for k in asserts if k.startswith("C1 Kotlin: monotonic discharge table")]
            rep.add("③-C1 ★★ 方向换得动：同一张表两个方向指名的**必须是不同的两对**",
                    bool(_dir_key) and all(asserts[k] for k in _dir_key),
                    "logcat 里放电/充电各自打出不同的对 ⇒ 读日志的人**不必**信任那句 verdict"
                    + ("" if _dir_key else "（⚠️ 找不到该断言 ⇒ 断言名与判据名已漂开）"))


def parse_table(t):
    return [1 for _ in t.split(";") if _]


#: 编译产出的 class（★ 两处都找 —— AGP 增量编译后 `tmp/kotlin-classes` 可能已被清掉，
#: 而真正给 APK 用的那份在 `intermediates/runtime_library_classes_dir/…`）
CLASS_DIRS = [
    os.path.join(WS, "projects", "mode-launcher", "src", "mod-tntgo-battery", "build",
                 "tmp", "kotlin-classes", "debug"),
    os.path.join(WS, "projects", "mode-launcher", "src", "mod-tntgo-battery", "build",
                 "intermediates", "javac", "debug", "classes"),
    os.path.join(WS, "projects", "mode-launcher", "src", "mod-tntgo-battery", "build",
                 "intermediates", "runtime_library_classes_dir", "debug",
                 "bundleLibRuntimeToDirDebug"),
]

#: ★ 兜底判据的期望（**与方法名同源，改实现就要改这里**）
JAVAP_EXPECT = {
    "TntgoBklCurve$Trend": ["Discharge", "Charge"],
    "TntgoBklCurve$Verdict": ["Ok", "NotEnoughLevels", "Rejected"],
    "TntgoBklCurve": ["gate", "worstViolation", "wideningPair", "estimate"],
    # ★★ ⑨-B（2026-09-16）：曲线的新鲜度键**必须是一个字段**（`lastBuiltKey`）。
    #    ⛔ 守的形态：节流只看**样本条数** —— 而台账是环形保留 240 条 ⇒ 装满后条数恒定
    #       ⇒ `refreshCurve` 永远提前 return ⇒ **`gate()` 再也不执行**（实测静默失效 8 分钟以上）。
    #    ★ 这一条是**字节码级**的：源码里有这个字**不等于**它编进了产品（AR13 的纪律）。
    "TntgoBklProfile": ["lastBuiltKey", "refreshCurve"],
}

#: 每个 class 的**新鲜度基准源文件** —— ⑨-B 的字段在 `TntgoBklProfile.kt` 里，
#: 拿 `CURVE_KT` 的 mtime 去比会**高估新鲜度**（改了 Profile 没重构建也能"通过"）。
JAVAP_SRC = {
    "TntgoBklProfile": "PROFILE_KT",
}


def javap(path):
    jdk = os.path.join(WS, "toolchain", "jdk-17.0.20.1+1", "bin", "javap.exe")
    exe = jdk if os.path.exists(jdk) else "javap"
    return run([exe, "-p", "-classpath", os.path.dirname(path),
                os.path.basename(path)[:-6].replace(os.sep, ".")])


def javap_curve(path):
    """★ 对 `TntgoBklCurve.class` 反汇编**字节码**（`-c`）——
    看方法里**有没有真的读 `Trend` 字段**（`javap -p` 只看得到签名，看不到分支）。"""
    jdk = os.path.join(WS, "toolchain", "jdk-17.0.20.1+1", "bin", "javap.exe")
    exe = jdk if os.path.exists(jdk) else "javap"
    return run([exe, "-p", "-c", "-classpath", os.path.dirname(path),
                os.path.basename(path)[:-6].replace(os.sep, ".")], timeout=120)


def find_class(cls):
    """在构建产物里找某个 class（★ 用 **glob 递归找**，不写死包路径）。"""
    import glob as _glob
    for root in CLASS_DIRS:
        if not os.path.isdir(root):
            continue
        hits = _glob.glob(os.path.join(root, "**", cls + ".class"), recursive=True)
        if hits:
            return hits[0]
    return None


def item_bytecode(rep):
    """★★★ **兜底判据（不需要网络、不需要拼编译器）**：直接看【编译产物】。

    为什么不满足于读源码：源码里"有 `Rejected` 这个字"**不等于它编进了产品**，
    也不等于方法真的存在。`javap` 读的是 **class 文件** ——
    那是"构建真的产出过这份实现"的**独立证据**（AR13 的纪律：验证闸门要查**调用点**）。

    ★★ 并且要**比时间戳**：`build/` 里的 class 完全可能是**上一次构建**留下的
    （AGP 的 Kotlin 增量编译**不会删掉**已经消失的嵌套类 —— 实测磁盘上 21:40 的
    `TntgoBklCurve$Gate.class` 与 23:33 的 `$Trend.class` 并存）。
    ⇒ 若 class 比源码旧，"字节码里有它"就是**过期证据**，必须报出来而不是放行。

    ⚠️ 这一条**不能**替代 ③（真执行）。它只证明「结构在」，
       **不证明数值行为对** —— 两件事必须分开报，否则会变成"用弱判据冒充强判据"。
    """
    if not os.path.isdir(CLASS_DIRS[0]):
        rep.add("⑥ 字节码兜底（javap）", True,
                "build/ 里没有 Kotlin 产物（未构建过）⇒ 跳过", skipped=True)
        return
    for cls, members in JAVAP_EXPECT.items():
        p = find_class(cls)
        if p is None:
            rep.add("⑥ 字节码里有 {}".format(cls), False,
                    "找不到 {}.class ⇒ 未构建过（或构建失败）".format(cls))
            continue
        # ★★ ⑨-B：新鲜度必须拿**这个 class 自己的源文件**去比 ——
        #    `TntgoBklProfile.class` 拿 `TntgoBklCurve.kt` 的 mtime 比会**高估新鲜度**。
        ref_kt = globals().get(JAVAP_SRC.get(cls, ""), CURVE_KT)
        src_mtime = os.path.getmtime(ref_kt) if os.path.exists(ref_kt) else 0
        fresh = os.path.getmtime(p) >= src_mtime
        r = javap(p)
        out = (r.stdout or "") + (r.stderr or "")
        missing = [m for m in members if m not in out]
        rep.add("⑥ 字节码 {} 含 {}{}".format(cls.split("$")[-1], "/".join(members),
                                            "" if fresh else "（⚠ 比源码旧）"),
                not missing and fresh,
                ("缺 {} ".format(missing) if missing else "")
                + ("" if fresh else "★ class 比源码旧 ⇒ 这是**过期证据**，先重新构建"))
    rep.add("⑥ 字节码兜底只证『结构在』，**不证数值行为**（数值由 ③ 负责）", True,
            "见本函数的注释")



def find_curve_class():
    """★ 找 `TntgoBklCurve.class` 本体。

    ⚠️ `CLASS_DIRS[0]`（`build/tmp/kotlin-classes/debug`）在**增量编译之后可能已被清掉**，
    而真正给 APK 用的那份在 `build/intermediates/runtime_library_classes_dir/…`。
    ⇒ 递归搜**两处都找**，不写死一个（§8 纪律：同名不同物、同一件事多处说法，要现取现验）。
    """
    import glob as _glob
    for root in CLASS_DIRS:
        if not os.path.isdir(root):
            continue
        hits = _glob.glob(os.path.join(root, "**", "TntgoBklCurve.class"), recursive=True)
        if hits:
            return max(hits, key=os.path.getmtime)
    return None


def _javap_method_region(out, sig):
    """从 `javap -c` 输出里切出**一个方法**的字节码区段（到下一个方法头为止）。

    ⚠️ 不做括号配平（本项目被"配平找结尾"坑过）—— 只按 `public/private/static …(` 认边界。

    @return `(start, region)`；`start is None` ⇒ 这个方法**不在产物里**
    """
    lines = out.splitlines()
    start = next((i for i, l in enumerate(lines) if sig in l), None)
    region = []
    if start is not None:
        for l in lines[start + 1:]:
            if re.match(r"\s+(public|private|static)\s.*\(", l):
                break
            region.append(l)
    return start, region


def item_direction_evidence(rep):
    """★★★★★ **AR13-G2：闸门「按方向判」的证据链**（只读，不碰设备）。

    ## 为什么单独立一项

    A8 取证（真机「拒一次」）**不能**证明闸门**看方向**：那一轮放电表与充电表**都被拒**，
    而「两条都拒」与「方向无关」是**两件不同的事**。G2 要的正是把这两件事分开。

    ## 原 G2 判据为什么必须改（2026-09-16 实测）

    原话是「**同一批数据换 `Trend` 必须合法**」。它在**单调数据**上成立，
    而**真机归档是非单调的** ⇒ 两个方向**各自都能找到违规的一对**（实测是两对**不同**的档：
    放电 `178→497` ／ 充电 `497→2000`）⇒ 原判据在真机上**假失败**。

    ⇒ 换成与数据形状**无关**的性质（逐对方向互斥），并把它写进 ①（`analyze_bkl_curve` ⑮–⑲）。
    本项做的是**独立复核**：不跑那个脚本，直接对**已提交夹具**算一遍。

    ## 三层证据（每一层都要能失败）

    | 层 | 判据 | 被谁盯住 |
    |---|---|---|
    | **真机数据** | 每一对相邻档**恰好一个方向**违规（`min == 0`） | 本项 ＋ ①⑮ |
    | **换得动** | 把真机的一对档**调过来**，违规方向必须**跟着翻** | 本项 ＋ ①⑰ |
    | **字节码** | `worstViolation` 里**真的**有 `Trend` 字段比较（＋一元取负） | 本项 ＋ ⑥ |

    ⚠️ **仍然不能**由此说「方向参数在**设备上**被区分性验过」：产品把
    `Trend.Discharge` / `Trend.Charge` **写死**在 `refreshCurve()` 里，**没有外部开关** ⇒
    设备侧要换方向只能改产品代码（见证据文档 §「设备侧为什么做不到」）。
    """
    # ── 层 1＋2：真机夹具
    fix = os.path.join(SCRIPTS, "fixtures", "20260916_ar13_g2_direction_ledger.xml")
    if not os.path.exists(fix):
        rep.add("⑤-G2 真机夹具（方向区分性）", False,
                "★ 找不到 {} ⇒ **不许静默跳过**（`.ref/` 被 gitignore，夹具就是为此提交的）"
                .format(os.path.relpath(fix, WS)))
    else:
        # ⚠️ 这里**只做一次** `%r` 替换：正文里还有 `'{}'.format(...)`，
        #    `.format()` 会**二次解析**那些花括号（第一次实现就踩到 `IndexError`）。
        code = (
            "import sys, os\n"
            "sys.path.insert(0, %r)\n"
            "from bkl_common import read_ledger\n"
            "import analyze_bkl_curve as A\n"
            "xml = open(%r, encoding='utf-8').read()\n"
            "s, st = read_ledger(xml)\n"
            # ★★ G1（2026-09-16）：**两张表都要** —— `raw_*` 含薄档，`dis/chg` 是闸门真正吃的。
            "raw_dis = A.plateaus([x for x in s if not x['chg']], min_n=1)\n"
            "raw_chg = A.plateaus([x for x in s if x['chg']], min_n=1)\n"
            "dis = A.plateaus([x for x in s if not x['chg']])\n"
            "chg = A.plateaus([x for x in s if x['chg']])\n"
            "thin_dropped = (len(raw_dis) - len(dis))\n"
            "bad = 0\n"
            "for lv in (dis, chg):\n"
            "    p = A.pairwise_violation(lv)\n"
            "    bad += len(p['both_violating'])\n"
            "    if not p['pairs']:\n"
            "        bad += 1\n"
            # ⚠️ decisive 一律取**原始表**：G1 之后的表是单调的 ⇒ 逐对判据在它上面无从判定
            "k = A.pairwise_violation(raw_dis)['decisive']\n"
            "up = [dict(mcu=k['lo'], abs_ma=1000.0, n=5),\n"
            "      dict(mcu=k['hi'], abs_ma=1000.0 + k['delta'], n=5)]\n"
            "dn = [dict(mcu=k['lo'], abs_ma=1000.0 + k['delta'], n=5),\n"
            "      dict(mcu=k['hi'], abs_ma=1000.0, n=5)]\n"
            "flip = (A.worst_violation(up, 'dis') is None and A.worst_violation(up, 'chg') is not None\n"
            "        and A.worst_violation(dn, 'dis') is not None and A.worst_violation(dn, 'chg') is None)\n"
            "# ★★ 下面的集合一律**经闸门自己的判读**算出来（check_mode），\n"
            "#    不许用脚本侧重算的 delta —— 那样是自证，摘掉 trend 它照样绿（实测踩过）。\n"
            # ⚠️⚠️ 用**原始表**枚举相邻对：G1 之后的表是单调的 ⇒ 每一对**两个方向都合法**
            #     ⇒ `dis_set` 变空、`covered` 变 0 ⇒ 这条判据会**假失败**（本轮实测踩到）。
            #     它要证的性质（"每一对恰好判给一个方向"）**只能在含薄档的表上看**。
            "allpairs = []\n"
            "for i in range(len(raw_dis) - 1):\n"
            "    allpairs.append(((raw_dis[i]['mcu'], raw_dis[i+1]['mcu']),\n"
            "                     [raw_dis[i], raw_dis[i+1]]))\n"
            "dis_set = set()\n"
            "chg_set = set()\n"
            "for key, pair in allpairs:\n"
            "    vd = A.check_mode(pair, 'dis')[0]\n"
            "    vc = A.check_mode(pair, 'chg')[0]\n"
            "    if vd == '反物理':\n"
            "        dis_set.add(key)\n"
            "    if vc == '反物理':\n"
            "        chg_set.add(key)\n"
            "overlap = len(dis_set & chg_set)\n"
            "covered = len(dis_set ^ chg_set)\n"
            # ★★★★ G2（2026-09-16 更正第二处）：性质**不是**"每一对都恰好判给一个方向"。
            #    真机原始放电表 4 对里**只有 1 对**会违纪（`178→497`，且是在**充电**方向下），
            #    另外 3 对（`44→94`、`94→178`、`497→2000`）**两个方向都合法**的正常上升。
            #    ⇒ 要求 `covered == len(allpairs)` **在真机上必假**（本轮实测：covered=1 / 4 对）。
            #    ⚠️ 这正是"判据写成了一个**只在特定数据形状下成立**的性质"的第二次踩坑
            #       （第一次是"换方向必须合法"）。⇒ 钉**与数据形状无关**的那一条：
            #       **没有任何一对在两个方向下同时违纪**。
            "no_shared = True\n"
            "for i in range(len(raw_dis) - 1):\n"
            "    pr = [raw_dis[i], raw_dis[i+1]]\n"
            "    if (A.check_mode(pr, 'dis')[0] == '反物理'\n"
            "            and A.check_mode(pr, 'chg')[0] == '反物理'):\n"
            "        no_shared = False\n"
            "for i in range(len(raw_chg) - 1):\n"
            "    pr = [raw_chg[i], raw_chg[i+1]]\n"
            "    if (A.check_mode(pr, 'dis')[0] == '反物理'\n"
            "            and A.check_mode(pr, 'chg')[0] == '反物理'):\n"
            "        no_shared = False\n"
            "split_ok = (overlap == 0 and no_shared)\n"
            "print('DISJOINT|{}|{}|{}|{}'.format(len(dis_set), len(chg_set), split_ok, len(allpairs)))\n"
            # ★★★ G1 的**因果**行（原先那条"两个方向 worst 必须不同"在 G1 之后会**假绿**：
            #     两个方向都是 None ⇒ 不相等 ⇒ 看着"通过"，其实什么都没证）。
            #    ⇒ 改成**同一张表、同一方向**：关 G1 有 268，开 G1 变 None。
            #      这一行只可能因为 **G1 真的生效**而变真。
            "print('G1DIR|{}|{}'.format(A.worst_violation(raw_dis, 'dis'),\n"
            "                           A.worst_violation(dis, 'dis')))\n"
            # ★★ G2 的**真机**读数（两条腿的幅度与各自指名的那一对）——
            #    ⚠️ 原来这里打的 `DIFFERS` 拿的是**过滤后**的表 ⇒ G1 之后两边都是 None
            #       ⇒ 判据恒假（`None != None` 为假），而它**曾经真的绿过**（那时是 268 / 724）。
            #    ⇒ 改成打**原始表**上两个方向的真实读数，判据再从这两个数出发。
            "print('ORIENT|{}|{}|{}|{}'.format(\n"
            "    A.worst_violation(raw_dis, 'dis'), A.worst_violation(raw_chg, 'chg'),\n"
            "    (A.widening_pair(raw_dis, 'dis') or {}).get('brief', '-'),\n"
            "    (A.widening_pair(raw_chg, 'chg') or {}).get('brief', '-')))\n"
            "print('G2|{}|{}|{}|{}|{}'.format(len(s), st['legacy_no_mcu'], len(dis), len(chg), bad))\n"
            "print('THIN|{}'.format(thin_dropped))\n"
            "print('FLIP|{}'.format(flip))\n"
        ) % (SCRIPTS, fix)
        # ⚠️ 本段只打印 ASCII 标记（`G2|`/`DISJOINT|`/…）⇒ 编码无关；
        #    但仍走 run_py，保持"Python 子脚本一律 UTF-8"这一条**没有例外**。
        r = run_py(["-c", code])
        out = r.stdout or ""
        g2 = flip = None
        n_dis_pairs = n_chg_pairs = split_ok = differs = None
        gap = None
        g1dir = None
        thin = None
        orient = None
        for line in out.splitlines():
            if line.startswith("G2|"):
                _, n, legacy, nd, nc, bad = line.split("|")
                g2 = (int(n), int(legacy), int(nd), int(nc), int(bad))
            elif line.startswith("FLIP|"):
                flip = line.split("|")[1] == "True"
            elif line.startswith("THIN|"):
                thin = int(line.split("|")[1])
            elif line.startswith("G1DIR|"):
                _, a, b = line.split("|")
                g1dir = (None if a == "None" else float(a),
                         None if b == "None" else float(b))
            elif line.startswith("ORIENT|"):
                _, a, b, ba, bb = line.split("|")
                orient = (None if a == "None" else float(a),
                          None if b == "None" else float(b), ba, bb)
            elif line.startswith("DISJOINT|"):
                _, a, b, c, np_ = line.split("|")
                n_dis_pairs, n_chg_pairs, split_ok = int(a), int(b), c == "True"
                n_pairs = int(np_)
            elif line.startswith("DIFFERS|"):
                _, d, e = line.split("|")
                differs, gap = d == "True", float(e)
        ok_fields = g2 == (240, 24, 3, 3, 0)
        rep.add("⑤-G2 真机台账方向互斥（240 样本 / **G1 后** 3 放电档 / 3 充电档 / 同时违规 0 对）",
                ok_fields, "实测 {}".format(g2) if not ok_fields else "")
        rep.add("⑤-G2 ★★ 调换真机一对档位 ⇒ 违规方向必须跟着翻", bool(flip),
                "" if flip else "闸门不看方向（或夹具坏了）⇒ G2 不成立")
        # ★★★★ G1（2026-09-16）：**这一条是 G1 的证据**，而且是**只可能因 G1 生效而变真**的形态。
        # ⚠️ 原先那条「同一张表在两个方向下的 worst_violation 必须不同」在 G1 之后会**假绿**：
        #    G1 之后放电表在两个方向下都是 `None` ⇒ `None != None` 为假…
        #    ⚠️ 更糟的是它曾经**真的**绿过：那时放电方向 268、充电方向 724。
        #    ⇒ 换成**同方向、同数据、只切 G1 开关**的对比，这一条**不可能**被别的原因弄绿。
        rep.add("⑤-G1 ★★ 真机台账：**关 G1 ⇒ 放电违规 268 mA；开 G1 ⇒ 无违规**"
                "（同一批样本、同一方向 ⇒ 唯一变量就是 G1）",
                g1dir is not None and abs(g1dir[0] - 268.0) < 0.5 and g1dir[1] is None,
                "实测 关={} ／ 开={}".format(g1dir[0] if g1dir else None,
                                            g1dir[1] if g1dir else None))
        rep.add("⑤-G1 ★★ 真机台账薄档剔除数 = 放电 2（`94` n=1 ／ `178` n=2）",
                thin == 2, "实测 thin_dropped={}".format(thin))
        # ★★ 这两条都**经闸门自己的判读**（`check_mode`）算，不是脚本侧重算 delta。
        #    ⚠️ 第一版用了重算的 delta ⇒ **摘掉 trend 它照样绿**（假绿，实测踩到）。
        #    ⚠️⚠️ 枚举的是**原始表**的相邻对（含薄档）：G1 之后的表是单调的
        #        ⇒ 每一对**两个方向都合法** ⇒ 这条判据会假失败（本轮实测踩到）。
        rep.add("⑤-G2 ★★ 闸门**从不在两个方向下同时判一对违纪**（无重叠；且逐对实测无共享）",
                bool(split_ok),
                "判给放电 {} 对 ／ 判给充电 {} 对（相对 {} 对相邻档）／ 交集 {}".format(
                    n_dis_pairs, n_chg_pairs, n_pairs,
                    "0 ⇒ 方向真的分开了" if split_ok else "★ 有重叠 ⇒ 方向不敏感"))
        # ★★★★ G2（2026-09-16 更正）：原先这条查的是「同一张表在两个方向下 worst_violation
        #   必须不同」。⚠️ 它**有两个问题**：
        #   ① G1 之后它恒假（过滤后的表两边都是 None）——**本轮实测踩到**；
        #   ② 更根本的是：它在**真机数据**上给的两个数（放电 268 ／ 充电 724）**不是一对**——
        #      268 来自**放电表**的 `178→497`，724 来自**放电表在充电方向下**的 `497→2000`，
        #      而充电表在**它自己方向**下最差的是 `23→178` 的 **356**。
        #   ⇒ 改成直接钉**两条腿各自的真实读数**：放电表 268（`178→497`）、
        #      充电表 356（`23→178`），且**两条腿指名的不是同一对**。
        rep.add("⑤-G2 ★★ 真机台账两条腿的违规读数（放电表 `178→497` = 268 mA ／ "
                "充电表 `23→178` = 356 mA，**不是同一对**）",
                orient is not None and orient[0] is not None and orient[1] is not None
                and abs(orient[0] - 268.0) < 0.5 and abs(orient[1] - 356.0) < 0.5
                and orient[2].startswith("178→497") and orient[3].startswith("23→178")
                and orient[2] != orient[3],
                "实测 放电表={} ／ 充电表={}（{}；{}）".format(
                    orient[0] if orient else None, orient[1] if orient else None,
                    orient[2] if orient else "-", orient[3] if orient else "-"))

    # ── 层 3：字节码里**真的**有方向分支（⑥ 只查了名字，没查分支）
    p = find_curve_class()
    if p is None:
        rep.add("⑤-G2 字节码里 worstViolation 有 Trend 方向分支", False,
                "找不到 TntgoBklCurve.class ⇒ 未构建过")
    else:
        r = javap_curve(p)
        out = (r.stdout or "") + (r.stderr or "")
        for label, sig in (("worstViolation", "Double worstViolation(java.util.List"),
                           ("wideningPair", "TntgoBklCurve$Widening wideningPair(java.util.List")):
            start, region = _javap_method_region(out, sig)
            body = "\n".join(region)
            has_trend = "TntgoBklCurve$Trend.Discharge" in body
            has_neg = " dneg" in body
            # ★ C1 的那一条同时要求"哪一对"这个结构真的在产物里（返回类型就是它）
            extra = (label != "wideningPair") or ("TntgoBklCurve$Widening" in out)
            rep.add("⑤-G2 字节码 {} 里读 Trend 字段并取负{}".format(
                        label, "（C1：并返回 Widening 这一对）" if label == "wideningPair" else ""),
                    start is not None and has_trend and has_neg and extra,
                    "Trend 字段={} dneg={} Widening={} 区段 {} 行".format(
                        has_trend, has_neg, extra, len(region)))


def item_wiring(rep):
    """★★ 产品**接线点** —— AR13 那个 bug 的形态就是"函数写好了没人调",必须由判据盯着。"""
    checks = []
    if os.path.exists(PROFILE_KT):
        s = open(PROFILE_KT, encoding="utf-8").read()
        s_flat = re.sub(r"\s+", " ", s)
        checks.append(("④ Profile 用【放电】方向判放电曲线",
                       "gate(c.discharging, TntgoBklCurve.Trend.Discharge)" in s_flat))
        checks.append(("④ Profile 用【充电】方向判充电曲线",
                       "gate(c.charging, TntgoBklCurve.Trend.Charge)" in s_flat))
        checks.append(("④ 判读等级有 Rejected 分支（否则日志会说'没问题'）",
                       "Rejected ->" in s))
        # ── ★★★★ C1（2026-09-16）：日志**必须说得出"是哪一对"**
        #    理由不是"多打日志"：只报 verdict ⇒「放电被拒 ＋ 充电被拒」会被读成「方向无关」，
        #    而真相是两个方向各自拒了**不同**的一对。这一条**从判据上**堵住那个歧义。
        vt = re.search(r"private fun verdictText\((.*?)\n    \}", s, re.S)
        checks.append(("④-C1 verdictText 收的是【方向 Trend】而不是一个名字"
                       "（否则日志无法自证方向）",
                       bool(vt) and "trend: TntgoBklCurve.Trend" in vt.group(1)))
        # ★ 反向判据：`Rejected ->` 那一段里**必须真的**调用了 wideningPair
        rej = re.search(r"Verdict\.Rejected\s*->\s*\{(.*?)\n            \}", s, re.S)
        checks.append(("④-C1 Rejected 分支**真的**调用了 wideningPair（不是日志处重算 Δ）",
                       bool(rej) and "wideningPair(" in rej.group(1)))
        checks.append(("④-C1 日志里带上了那一对的 brief() 与违规动词",
                       bool(rej) and "brief()" in rej.group(1) and "badVerb()" in rej.group(1)))
        # ★★ G1：建表时**必须显式传门槛**，且**不许写死数字**（写死就会与 Curve 里的常量漂开）。
        checks.append(("④-G1 Profile 建表时**显式**传 G1 门槛（引常量，不写死数字）",
                       "minN = TntgoBklCurve.MIN_N_PER_LEVEL" in s_flat))
        checks.append(("④-G1 Profile 用的是 `plateauing`（能同时拿到\"剔了几档\"）",
                       "TntgoBklCurve.plateauing(" in s_flat))
        checks.append(("④-G1 日志里**报出**被剔的档数（否则读日志的人分不清\"只测到 2 档\""
                       "与\"测到 5 档、2 档太薄\"）",
                       "thinDropped" in s and "G1 剔除" in s))
        # ── ★★★★★ ⑨-B（2026-09-16）：**曲线的新鲜度键不能只看条数**
        #    ⛔ 守的形态：`total == lastBuiltCount` ＋ 环形保留 240 条 ⇒ 台账装满后
        #       **条数恒定** ⇒ 永远提前 return ⇒ `gate()` 再也不执行（实测静默失效 8 分钟以上，
        #       而且**一行异常日志都没有**，卡片照常渲染一个几十分钟前的快照）。
        #    ★ 这是纪律 ⑨「文件里有一次写入 ≠ 发布者在心跳」的**同族**：
        #       失效方式同样是"让一切看起来正常"。
        checks.append(("④-⑨-B 节流用一个**显式的新鲜度键字段**（`lastBuiltKey`）",
                       "lastBuiltKey" in s))
        checks.append(("④-⑨-B 判定用的是那个键本身（`key == lastBuiltKey`），"
                       "不是拿条数单独判",
                       "key == lastBuiltKey" in s_flat))
        # ★ 反向守卫：退回"只比条数"的旧写法必须**当场变红**
        #   （否则这一层只是一句注释，改了没人发现）
        old_throttle = "total == lastBuiltCount" in s
        checks.append(("④-⑨-B 旧的「只看条数」节流**已不在**"
                       "（若它回来了，说明修法被回退）", not old_throttle))
        # ── ★★★★★ ㊲（2026-09-16）：**"条数 ＋ 末条样本"也还不够**
        #    ⛔ 守的形态：环形**每拍挤掉一条老样本**，而"新追加的那条与上一拍逐字相同"时
        #       **键不变** ⇒ 内容变了、键没变 ⇒ 曲线不重算、闸门静默地不执行。
        #       ★ **真机已触发**（240 条里 1 处相邻逐字相同，而缓冲是混合的、4 个档位）。
        checks.append(("④-㊲ refreshCurve 的新鲜度键 = **台账全部内容**"
                       "（全串；只取末条 ⇒ 环形挤掉老样本时判不出来）",
                       "raw.joinToString(\",\")" in s_flat))
        checks.append(("④-㊲ 旧的「条数 ＋ 末条样本」形态**已不在**"
                       "（若它回来了，说明修法被回退）",
                       "raw.lastOrNull()" not in s))
        #    ★★ 上面两条是**结构核验**。结构核验有个已知弱点：它只证明"代码长这样"，
        #       不证明"这个键真的有分辨力"。⇒ 再补一条**可执行的模型**。
        #       ⚠️ 这是**判据应当具备的性质**的可执行表述 —— 受测实现是否具备它，
        #          仍由上面两条保证。（两种判据都不完美，所以两种都做。）
        ring_prev = ["A", "A", "B", "X"]
        ring_now = ["A", "B", "X", "X"]        # 环形轮转：挤掉一条 A、追加一条 X
        _old_key = lambda r: "{}#{}".format(len(r), r[-1] if r else "")
        _new_key = lambda r: ",".join(r)
        checks.append(("④-㊲ **模型**：环形轮转 ＋ 末条样本相同 ⇒ 旧键「条数 ＋ 末条样本」"
                       "**漏判**（这就是真机上触发过的形态）",
                       _old_key(ring_prev) == _old_key(ring_now) and ring_prev != ring_now))
        checks.append(("④-㊲ **模型**：同一个轮转，新键「全部内容」**必须**判出变化",
                       _new_key(ring_prev) != _new_key(ring_now)))
    else:
        checks.append(("④ 找得到 TntgoBklProfile.kt", False))
    if os.path.exists(SERVICE_KT):
        s = open(SERVICE_KT, encoding="utf-8").read()
        s_flat = re.sub(r"\s+", " ", s)
        checks.append(("④ 卡片查询显式传【放电】方向",
                       "estimate(curves.discharging, bkl.mcu, TntgoBklCurve.Trend.Discharge)" in s_flat))
        # ── ★★★★★ G3（2026-09-16）：卡片必须**分开说**「曲线被拒」与「该处未测」
        #    ⛔ 守的形态：**给不出数的原因被压成一个 `where != InBand`** ⇒
        #       「没测过」（该等）与「测了但不可信」（该重采）在卡片上长得一样。
        #    这与 AR13 的 `Gate` 放不下"反物理"是**同族**（纪律 ⑳）。
        pnt = re.search(r"private fun powerNoteText\((.*?)\n    \}", s, re.S)
        pnt_body = pnt.group(1) if pnt else ""
        checks.append(("④-G3 卡片**先看 `unusable`（原因）再看 `where`（位置）**"
                       "—— 顺序反了就等于没分",
                       "est.unusable != null" in pnt_body
                       and pnt_body.find("est.unusable != null") < pnt_body.find("when (est.where)")))
        checks.append(("④-G3 卡片有**两句不同**的话：`★ 曲线被拒（走向反物理）` / `★ 尚无实测档位`",
                       "★ 曲线被拒（走向反物理）" in pnt_body
                       and "★ 尚无实测档位" in pnt_body))
        # ★ 反向判据：那句**会误导成"没测过"**的旧注释留在代码里，说明改的人没做完
        checks.append(("④-G3 旧的「AR13 已知的表述缺口（未修）」注释**已不在**"
                       "（留着说明改动没做完）",
                       "已知的表述缺口" not in s))
    else:
        checks.append(("④ 找得到 TntgoBatteryService.kt", False))
    if os.path.exists(CURVE_KT):
        s = open(CURVE_KT, encoding="utf-8").read()
        s_flat = re.sub(r"\s+", " ", s)
        # ★★ G3：结论的**取值域**里必须真有"给不出数的原因"这一维
        #    （否则 `estimate()` 只能把它压回 `Where` ⇒ 卡片又无从分辨）
        checks.append(("④-G3 `Estimate` 有 `unusable` 这一维（★ 不是靠调用处 if 硬塞）",
                       "val unusable: Unusable?" in s_flat))
        checks.append(("④-G3 `Unusable` 同时有 `Rejected` 与 `NotEnoughLevels`"
                       "（两个原因对应**相反的动作**，不许合并）",
                       re.search(r"enum class Unusable \{(.*?)\n    \}", s, re.S) is not None
                       and "Rejected," in re.search(r"enum class Unusable \{(.*?)\n    \}", s, re.S).group(1)
                       and "NotEnoughLevels," in re.search(r"enum class Unusable \{(.*?)\n    \}", s, re.S).group(1)))
        # ★★★ 跨语言一致：镜像里那句卡片文案必须与 Kotlin **逐字**相同。
        #    ⛔ 这一条防的是"两份实现各写一遍文案"⇒ 用户看到的与离线复现的漂开。
        #    （纪律 ②/⑪：两边各写一遍的东西，必须有判据盯着它们相等）
        #
        # ⚠️⚠️ **写这一条时踩到的坑（值得留痕）**：
        #    第一版写成 `"…" in s and "…" in ms`，而当时 `s` 已经被**上一块**赋成了
        #    `TntgoBklCurve.kt`（这一块自己重赋了 `s`）⇒ **拿曲线文件去比卡片文案**，
        #    当然为假。★ 而它**红得像真缺口**：文案明明两边都在。
        #    ⇒ 正解：**要哪一个文件就显式读哪一个**，不要复用块间流动的 `s`。
        #    （与 ④-G1 那次"引用另一个文件的 s_flat"是**同一个坑的第二次**）
        mirror = os.path.join(SCRIPTS, "analyze_bkl_curve.py")
        if os.path.exists(mirror):
            ms = open(mirror, encoding="utf-8").read()
            svc = open(SERVICE_KT, encoding="utf-8").read() if os.path.exists(SERVICE_KT) else ""
            checks.append(("④-G3 ★★ 卡片文案在 Kotlin 与 Python 镜像里**逐字一致**"
                           "（`★ 曲线被拒（走向反物理）`）",
                           "★ 曲线被拒（走向反物理）" in svc
                           and "★ 曲线被拒（走向反物理）" in ms))
        # ⚠️⚠️ 2026-09-16（G1）：本条**必须**自己算 `s_flat`。
        #    这一块里 `s` 是 **TntgoBklCurve.kt**，而 `s_flat` 在上面 **Profile** 那一块里
        #    被赋过值 ⇒ 直接引用它查的会是**另一个文件**（Python 不报错，只会静默查错文件）。
        #    本轮 ④-G1 两条就是这么红的 —— 判据**看着像在查 Curve**，其实在查 Profile。
        # ★ G3 之后：块**开头**也已经自己算过一遍 `s_flat`；
        #   这里**保留**那句赋值（幂等，且是给"以后有人在中间插一段"留的护栏）。
        s_flat = re.sub(r"\s+", " ", s)
        # ★ 反向判据：`gate()` 内部**必须**调用闸门（不能只是"写了 worstViolation"）
        #    ⚠️⚠️ G1（2026-09-16）：这里原先钉的是**两参** `gate(levels, trend)` 的实现体。
        #        G1 之后两参版变成了**兼容重载**（一行转发、体内当然没有 worstViolation）
        #        ⇒ 判据当场变红。这正是"接线点判据**必须跟着签名走**"的教训：
        #        签名一改，判据就在**看着像真缺口**地假失败。
        #    ⇒ 改钉**真正实现**的那个（带 `minN`），并**同时**要求兼容重载转发给它。
        gate_body = re.search(r"fun gate\(levels: List<Level>, trend: Trend, minN: Int\): Verdict \{(.*?)\n    \}",
                              s, re.S)
        checks.append(("④ gate() 函数体里**真的**调用了 worstViolation",
                       bool(gate_body) and "worstViolation" in gate_body.group(1)))
        checks.append(("④-G1 gate() 里**真的**过了 G1 门槛（wireLevels）",
                       bool(gate_body) and "wireLevels" in gate_body.group(1)))
        checks.append(("④-G1 两参 gate() 是**兼容重载**且转发给带 minN 的那个",
                       "gate(levels, trend, MIN_N_PER_LEVEL)" in s_flat))
        est_body = re.search(r"fun estimate\(levels: List<Level>, mcu: Int, trend: Trend, minN: Int\): Estimate \{(.*?)\n        val where",
                             s, re.S)
        checks.append(("④ estimate() 先过 gate 再拟合",
                       bool(est_body) and "gate(" in est_body.group(1)))
        checks.append(("④-G1 estimate() **只对 G1 之后的表**拟合（wireLevels）",
                       bool(est_body) and "wireLevels" in est_body.group(1)))
    else:
        checks.append(("④ 找得到 TntgoBklCurve.kt", False))
    for name, ok in checks:
        rep.add(name, ok, "" if ok else "接线点不在了 ⇒ 闸门又会变回'没人调用的函数'")


def item_realdata(rep):
    """★ 真机归档数据的端到端复算（文件在 .ref/,被 gitignore ⇒ 缺了算 SKIP,不算失败）。"""
    xml = os.path.join(WS, ".ref", "ar12d", "tntgo_battery.xml")
    if not os.path.exists(xml):
        rep.add("⑤ 真机归档复算（.ref/ar12d）", True, "归档不在本机 ⇒ 跳过（不算失败）",
                skipped=True)
        return
    r = run_py([os.path.join(SCRIPTS, "analyze_bkl_curve.py"), "--dump-xml", xml])
    out = r.stdout or ""
    ok = (r.returncode == 0
          and "放电：3 档**逐项一致**" in out
          and "充电：3 档**逐项一致**" in out
          and "可拟合" in out)
    rep.add("⑤ 真机归档：放电可拟合 ＋ 充放电对账逐项一致", ok,
            "" if ok else "退出码 {} ／ 关键行缺失 ⇒ Kotlin 与 Python 已经漂了".format(r.returncode))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--no-teeth", action="store_true", help="跳过'摘掉闸门必须失败'的证伪")
    ap.add_argument("--no-kotlin", action="store_true",
                    help="跳过 ③（本机跑不动 Kotlin 编译器时的**显式**降级；"
                         "跳过了就必须靠 ⑥ 字节码 ＋ 真实构建补上，不许当作'通过'）")
    args = ap.parse_args()

    if args.list:
        print("① analyze_bkl_curve --selftest")
        print("② check_brightness_heartbeat --selftest")
        print("③ TntgoBklCurve.kt 真编译真执行（6 个用例 × gate/数值/带内/档位数）")
        print("③-teeth 摘掉闸门 ⇒ 必须失败")
        print("④ 产品接线点（Profile / Service / gate 函数体 / estimate 函数体）")
        print("⑤ 真机归档端到端复算（缺归档则 SKIP）")
        print("⑤-G2 方向区分性证据链（真机夹具逐对互斥 ＋ 调换档位必须翻面 ＋ 字节码里有 Trend 分支）")
        print("⑥ 字节码兜底（javap 读编译产物；只证结构在，不证数值）")
        return 0

    print("=" * 78)
    print("AR13 · 亮度/功耗链路 回归套件")
    print("=" * 78)
    rep = Report()
    print("\n【镜像自检】")
    item_selftest(rep, "analyze_bkl_curve.py", "① analyze_bkl_curve --selftest")
    item_selftest(rep, "check_brightness_heartbeat.py", "② check_brightness_heartbeat --selftest")
    print("\n【Kotlin 实现（真编译真执行，不是翻译）】")
    if args.no_kotlin:
        rep.add("③ Kotlin 真执行（TntgoBklCurve）", True,
                "★ 被 --no-kotlin 显式跳过 ⇒ 本项**没有**结论；请以 ⑥ ＋ 真实构建补上",
                skipped=True)
    else:
        item_kotlin(rep)
    print("\n【产品接线点】")
    item_wiring(rep)
    print("\n【真机归档】")
    item_realdata(rep)
    print("\n【G2 方向区分性（AR13 核心改法的证据链）】")
    item_direction_evidence(rep)
    print("\n【字节码兜底（不依赖编译器有无）】")
    item_bytecode(rep)
    if not args.no_teeth and not args.no_kotlin:
        print("\n【有牙证伪：把闸门摘掉，判据必须红】")
        item_kotlin(rep, teeth=True)

    code = rep.summary()
    if args.json:
        print(json.dumps(rep.rows, ensure_ascii=False, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
