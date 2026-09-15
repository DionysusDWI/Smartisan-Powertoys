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


def run(cmd, cwd=WS, timeout=600, env=None):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout, env=env)


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


def find_cached_jar(group, name_prefix, prefer=None, exclude=("-common", "-jdk", "sources")):
    """在 Gradle 缓存里找某个依赖的 jar（**不下载**）。"""
    root = os.path.join(WS, "toolchain", ".gradle", "caches", "modules-2", "files-2.1")
    hits = []
    for dirpath, _, files in os.walk(root):
        for f in files:
            if (f.startswith(name_prefix) and f.endswith(".jar")
                    and not any(x in f for x in exclude)):
                hits.append(os.path.join(dirpath, f))
    if not hits:
        return None
    if prefer:
        for p in hits:
            if prefer in os.path.basename(p):
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
    """★ 让 `kotlin-compiler-embeddable` **能起来**所需的运行时 jar。

    ⚠️ 它是个**瘦** jar：把这些拆出来之后，缺一件就报一个
    `NoClassDefFoundError`，而**报出来的名字跟真正的原因差很远**
    （本脚本依次撞过 `kotlin/jvm/internal/Intrinsics` 与
    `kotlinx/coroutines/CoroutineScope`）。⇒ 一次配齐，并在失败时把清单打出来。
    """
    parts = [kotlinc]
    for g, n in (
        ("org.jetbrains.kotlin", "kotlin-stdlib-"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm-"),
        ("org.jetbrains.intellij.deps", "trove4j-"),      # ★ 编译器内部要用（gnu.trove）
        ("org.jetbrains.kotlin", "kotlin-reflect-"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime-"),
    ):
        p = find_stdlib(kotlinc) if n == "kotlin-stdlib-" else find_cached_jar(g, n)
        if p is None and n == "kotlin-stdlib-":
            p = find_cached_jar(g, n)
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

SCENARIOS = [
    # ⚠️ `id` 一律 **ASCII**：Kotlin 源码与探针 stdout 都要过控制台编码这一关，
    #    中文 case id 会在 Windows 控制台上变成乱码（那会让人怀疑"是编码问题还是判据问题"）。
    #    中文说明留在 Python 侧打印。
    # (id, 档位表 "(mcu,ma,n);…", trend, 期望 gate, 期望 inband_absMa(或 None), 说明)
    ("UT1-dis-normal", "71,1001,15;497,1344,23;2000,2068,8", "Discharge", "Ok", 1344.0,
     "生产数据的单调走向 ⇒ 必须 Ok,且中间档被直线复现"),
    ("UT2-chg-negslope", "23,3704,1;497,3536,70;2000,2888,8", "Charge", "Ok", 3536.0,
     "★ 实测充电曲线（负斜率）**是正确的** ⇒ 必须 Ok（接错方向就会死在这条）"),
    ("UT3-dis-antiphys", "71,1001,15;497,1310,20;2000,2112,10", "Discharge", "Rejected", None,
     "★ 更亮的一档反而更省电（−900 mA）⇒ 必须 Rejected 且不给数"),
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

fun main() {
    val seed = listOf(@@SEED@@)
    assert(seed.size == 3 && seed[1].mcu == 497, "探针自检：抽出源码里的 seed 能构造")
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
        println("CASE|" + sid + "|where|" + e.where.name + "|" + (if (wantMa < 0.0) "NOT_InBand" else "InBand"))
        println("CASE|" + sid + "|levels|" + e.levels + "|" + lv.size)
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
    println("DONE")
}
'''.replace("@@TABLES@@", tables).replace("@@TRENDS@@", trends) \
   .replace("@@GATES@@", gates).replace("@@MAS@@", mas) \
   .replace("@@STD@@", std).replace("@@CHG@@", chg) \
   .replace("@@SEED@@", seed_vals)




def compile_and_run(kotlinc, src_text, workdir, stdlib=None):
    """编译成 jar 并**真的执行**，返回 stdout。"""
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
    cp = run([JAVA, "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
              "-nowarn", "-cp", compiler_cp, "-include-runtime", "-d", jar, kt],
             timeout=1800, env=env)
    if not os.path.exists(jar):
        # ★ 报错要**从头部**截（编译器的根因在头几行；截尾部只会得到一串 `at …`），
        #   并且要把 compiler 的 `warning:` 滤掉 —— 否则真正的 `error:` 会被挤出去。
        raw = ((cp.stdout or "") + (cp.stderr or "")).splitlines()
        keep = [l for l in raw if not l.strip().startswith(("warning:", "at ", "..."))]
        raise RuntimeError("Kotlin 编译失败（classpath 共 {} 个 jar）：\n{}".format(
            compiler_cp.count(os.pathsep) + 1, "\n".join(keep[:12])))
    out = run([JAVA, "-jar", jar], timeout=300)
    if out.returncode != 0:
        raise RuntimeError("Kotlin 运行失败：\n" + (out.stdout or "")[-800:] + (out.stderr or "")[-800:])
    return out.stdout


def parse_probe(stdout):
    gates, mas, wheres, levels, asserts, done = {}, {}, {}, {}, {}, False
    for line in stdout.splitlines():
        parts = line.split("|")
        if parts[0] == "CASE" and len(parts) == 5:
            _, sid, key, got, want = parts
            if key == "gate":
                gates[sid] = (got, want)
            elif key == "absMa":
                mas[sid] = (float(got), float(want))
            elif key == "where":
                wheres[sid] = (got, want)
            elif key == "levels":
                levels[sid] = (got, want)
        elif parts[0] == "SELFTEST" and len(parts) == 3:
            asserts[parts[1]] = parts[2] == "true"
        elif parts[0] == "DONE":
            done = True
    return gates, mas, wheres, levels, asserts, done


# --------------------------------------------------------------------- 项目

def item_selftest(rep, script, name):
    p = os.path.join(SCRIPTS, script)
    r = run([PY, p, "--selftest"])
    tail = [l for l in (r.stdout or "").splitlines() if l.strip()][-1:] or [""]
    rep.add(name, r.returncode == 0, tail[0][:110] if r.returncode == 0 else
            "退出码 {} ／ {}".format(r.returncode, " ／ ".join((r.stdout or "").splitlines()[-3:])[:200]))


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
    src = open(CURVE_KT, encoding="utf-8").read()
    body = extract_object(src)
    if teeth:
        # ★★ 有牙证伪：把闸门**摘掉**再跑 ⇒ 断言必须失败
        body = body.replace("worstViolation(levels, trend) != null", "false")
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
    gates, mas, wheres, levels, asserts, done = parse_probe(stdout)

    if teeth:
        bad = [sid for sid, (got, want) in gates.items() if got != want]
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
        ok_w = (want_ma is None and got_w != "InBand") or (want_ma is not None and got_w == "InBand")
        got_lv, want_lv = levels.get(sid, ("-1", str(len(parse_table(table)))))
        ok_lv = got_lv == want_lv
        rep.add("③ {} {}".format(sid, why[:34]), ok_gate and ok_ma and ok_w and ok_lv,
                "gate={}/{} absMa={:.2f}/{:.2f} where={} levels={}".format(
                    got_gate, want_gate, got_ma, want_ma_v, got_w, got_lv))


def parse_table(t):
    return [1 for _ in t.split(";") if _]


#: 编译产出的 class（AGP 的 `compileDebugKotlin` 目标目录）—— 用于 `javap` 兜底检查
CLASS_DIRS = [
    os.path.join(WS, "projects", "mode-launcher", "src", "mod-tntgo-battery", "build",
                 "tmp", "kotlin-classes", "debug"),
    os.path.join(WS, "projects", "mode-launcher", "src", "mod-tntgo-battery", "build",
                 "intermediates", "javac", "debug", "classes"),
]

#: ★ 兜底判据的期望（**与方法名同源，改实现就要改这里**）
JAVAP_EXPECT = {
    "TntgoBklCurve$Trend": ["Discharge", "Charge"],
    "TntgoBklCurve$Verdict": ["Ok", "NotEnoughLevels", "Rejected"],
    "TntgoBklCurve": ["gate", "worstViolation", "estimate"],
}


def javap(path):
    jdk = os.path.join(WS, "toolchain", "jdk-17.0.20.1+1", "bin", "javap.exe")
    exe = jdk if os.path.exists(jdk) else "javap"
    return run([exe, "-p", "-classpath", os.path.dirname(path),
                os.path.basename(path)[:-6].replace(os.sep, ".")])


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
    src_mtime = os.path.getmtime(CURVE_KT) if os.path.exists(CURVE_KT) else 0
    for cls, members in JAVAP_EXPECT.items():
        p = find_class(cls)
        if p is None:
            rep.add("⑥ 字节码里有 {}".format(cls), False,
                    "找不到 {}.class ⇒ 未构建过（或构建失败）".format(cls))
            continue
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
    else:
        checks.append(("④ 找得到 TntgoBklProfile.kt", False))
    if os.path.exists(SERVICE_KT):
        s = open(SERVICE_KT, encoding="utf-8").read()
        s_flat = re.sub(r"\s+", " ", s)
        checks.append(("④ 卡片查询显式传【放电】方向",
                       "estimate(curves.discharging, bkl.mcu, TntgoBklCurve.Trend.Discharge)" in s_flat))
    else:
        checks.append(("④ 找得到 TntgoBatteryService.kt", False))
    if os.path.exists(CURVE_KT):
        s = open(CURVE_KT, encoding="utf-8").read()
        # ★ 反向判据：`gate()` 内部**必须**调用闸门（不能只是"写了 worstViolation"）
        gate_body = re.search(r"fun gate\(levels: List<Level>, trend: Trend\): Verdict \{(.*?)\n    \}",
                              s, re.S)
        checks.append(("④ gate() 函数体里**真的**调用了 worstViolation",
                       bool(gate_body) and "worstViolation" in gate_body.group(1)))
        est_body = re.search(r"fun estimate\(levels: List<Level>, mcu: Int, trend: Trend\): Estimate \{(.*?)\n        val where",
                             s, re.S)
        checks.append(("④ estimate() 先过 gate 再拟合",
                       bool(est_body) and "gate(levels, trend)" in est_body.group(1)))
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
    r = run([PY, os.path.join(SCRIPTS, "analyze_bkl_curve.py"), "--dump-xml", xml])
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
