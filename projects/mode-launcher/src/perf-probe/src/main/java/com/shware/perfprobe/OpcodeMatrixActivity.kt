package com.shware.perfprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * ★★★★★ **opcode 矩阵**（任务 AK6）—— 探索 `perfLockAcquire` 的其它资源 opcode。
 *
 * ## 为什么要"矩阵"而不是"单发观测"
 *
 * `0x40800000`（大核 MAX_FREQ）这类**频率**资源，效果能直接从 `scaling_cur_freq` 看出来。
 * 但 `SCHEDBOOST` / `CPUBW` / `LLCCBW` / `POWER COLLAPSE` 这些**看不到频率变化**
 * ⇒ ★ **必须用下游基准**（突发延迟）来判断有没有用。
 *
 * ⇒ 做法：**逐个叠加 opcode，每种组合跑同一个突发基准**。
 *
 * ## 矩阵（每组都跑两遍，取第二遍以避开 JIT 预热）
 *
 * | # | 组合 |
 * |---|---|
 * | ① | 无锁（基线） |
 * | ② | 仅 MAX_FREQ（当前预设） |
 * | ③ | + `SCHEDBOOST 0xFF` |
 * | ④ | + `CPUBW_MIN_FREQ 0xFF` |
 * | ⑤ | + `LLCCBW 0xFFFF` |
 * | ⑥ | + `POWER COLLAPSE 0x1`（⚠️ 可能影响功耗，最后测） |
 *
 * ## 顺带做一件重要的事：**测 app 身份能不能读 sysfs**
 *
 * shell 身份下 `/sys/class/devfreq` 与 `/sys/class/kgsl` 都被 **SELinux 挡死**。
 * 但 **app 的 SELinux 域（`untrusted_app`）和 shell 不同** —— 值得实测。
 * 若能读，就能直接观测 `CPUBW`/`LLCCBW` 有没有生效。
 */
class OpcodeMatrixActivity : Activity() {

    companion object {
        private const val TAG = "OpcodeMatrix"
        private const val SVC = "vendor.perfservice"
        private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"
        private const val TX_LOCK_RELEASE_HANDLER = 2
        private const val TX_LOCK_ACQUIRE = 4

        private const val LOCK_MS = 90_000
        private const val BURST_ROUNDS = 60
        private const val BURST_ITERS = 3_000_000
        private const val BURST_GAP_MS = 40L

        // ---- 资源 opcode（★ 顺序已实测确证：0x40800000 是【大核】）----
        private const val OP_BIG_MAX = 0x40800000
        private const val OP_LITTLE_MAX = 0x40800100
        private const val OP_PRIME_MAX = 0x40800200
        private const val OP_SCHEDBOOST = 0x43000000
        private const val OP_CPUBW = 0x41800000
        private const val OP_LLCCBW = 0x43400000
        private const val OP_PWR_COLLAPSE = 0x40C00000

        private val BASE_MAX = intArrayOf(OP_BIG_MAX, 2419, OP_LITTLE_MAX, 1785, OP_PRIME_MAX, 2956)
        private val WITH_SCHED = BASE_MAX + intArrayOf(OP_SCHEDBOOST, 0xFF)
        private val WITH_CPUBW = WITH_SCHED + intArrayOf(OP_CPUBW, 0xFF)
        private val WITH_LLCCBW = WITH_CPUBW + intArrayOf(OP_LLCCBW, 0xFFFF)
        private val WITH_PC = WITH_LLCCBW + intArrayOf(OP_PWR_COLLAPSE, 0x1)

        /** ★ 对照组：`POWER COLLAPSE = 0` —— 用来验证「值语义」（若与 ② 无差别 ⇒ 值确实有含义） */
        private val WITH_PC_ZERO = WITH_LLCCBW + intArrayOf(OP_PWR_COLLAPSE, 0x0)

        /** ★★ 精简版：只保留真正有用的两项（复现验证用） */
        private val MINIMAL = intArrayOf(OP_BIG_MAX, 2419, OP_LITTLE_MAX, 1785, OP_PRIME_MAX, 2956)
    }

    private lateinit var out: TextView
    private val sb = StringBuilder()
    private val bm by lazy { getSystemService(BATTERY_SERVICE) as BatteryManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (14 * resources.displayMetrics.density).toInt()
        out = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#DDDDDD"))
            setBackgroundColor(Color.parseColor("#FF101010"))
            setPadding(pad, pad, pad, pad)
            typeface = Typeface.MONOSPACE
        }
        setContentView(ScrollView(this).apply { addView(out) })
        Thread { runMatrix() }.start()
    }

    private fun line(s: String) {
        Log.i(TAG, s)
        runOnUiThread { sb.append(s).append('\n'); out.text = sb.toString() }
    }

    // ------------------------------------------------------------------ 矩阵

    private fun runMatrix() {
        line("========== opcode 矩阵（突发基准 60 轮）==========")
        line("")

        // ---- 先测 sysfs 可达性（app 身份 vs shell 身份）----
        probeSysfs()
        line("")

        // JIT 预热：先跑一遍扔掉
        line("JIT 预热中…")
        burstBench()
        line("预热完成")
        line("")

        val results = ArrayList<Triple<String, Long, Double>>()

        val configs = listOf(
            Triple("① 无锁（基线）", null, 0.0),
            Triple("② 仅 MAX_FREQ", BASE_MAX, 0.0),
            Triple("③ +SCHEDBOOST 0xFF", WITH_SCHED, 0.0),
            Triple("④ +CPUBW 0xFF", WITH_CPUBW, 0.0),
            Triple("⑤ +LLCCBW 0xFFFF", WITH_LLCCBW, 0.0),
            Triple("⑥ +POWER COLLAPSE 1", WITH_PC, 0.0),
        )

        for ((name, list, _) in configs) {
            var handle = -1
            if (list != null) {
                handle = acquire(LOCK_MS, list)
                if (handle < 0) { line("$name → ✗ 拿锁失败"); continue }
            }
            Thread.sleep(2000)

            // ★ 跑两遍，取第二遍（避开 JIT 与首跑开销）
            val p1 = burstBench()
            val p2 = burstBench()

            if (handle >= 0) release(handle)
            Thread.sleep(1500)

            val meanMa = currentMa()
            line("%-24s pass1=%4d ms  pass2=%4d ms   %.0f mA".format(name, p1, p2, meanMa))
            results.add(Triple(name, p2, meanMa))
        }

        line("")
        line("========== 汇总（按 pass2 排序，越小越快）==========")
        val base = results.firstOrNull()?.second ?: 1L
        results.sortedBy { it.second }.forEach { (n, t, ma) ->
            line("%-24s %4d ms   %+6.1f%%   %.0f mA".format(n, t, (base - t) * 100.0 / base, ma))
        }
        line("")

        // ★★★ 复现验证 —— POWER COLLAPSE 的跃升太大，必须交替重复几轮
        line("========== ★ 复现验证：② MAX_FREQ vs ⑥ +POWER COLLAPSE 1 ==========")
        line("（交替 3 轮，抵消漂移；另加 ⑦ PC=0 作【对照组】，验证值语义）")
        val seq = listOf(
            "② MAX_FREQ" to BASE_MAX,
            "⑥ +PC=1" to WITH_PC,
            "⑦ +PC=0(对照)" to WITH_PC_ZERO,
            "② MAX_FREQ" to BASE_MAX,
            "⑥ +PC=1" to WITH_PC,
            "⑦ +PC=0(对照)" to WITH_PC_ZERO,
            "② MAX_FREQ" to BASE_MAX,
            "⑥ +PC=1" to WITH_PC,
            "⑦ +PC=0(对照)" to WITH_PC_ZERO,
        )
        val acc = HashMap<String, ArrayList<Long>>()
        for ((name, list) in seq) {
            val h = acquire(LOCK_MS, list)
            if (h < 0) { line("$name ✗"); continue }
            Thread.sleep(1500)
            val t = burstBench()
            release(h)
            Thread.sleep(1200)
            acc.getOrPut(name) { ArrayList() }.add(t)
            line("  %-16s %4d ms".format(name, t))
        }
        line("")
        line("---- 复现汇总（均值）----")
        for ((n, v) in acc) {
            line("  %-16s 均值 %4d ms   各轮 %s".format(n, v.average().toLong(), v.joinToString("/")))
        }
        line("")
        line("DONE")
    }

    private fun burstBench(): Long {
        var compute = 0L
        repeat(BURST_ROUNDS) {
            val t0 = System.nanoTime()
            var x = 1
            for (i in 0 until BURST_ITERS) { x = x * 31 + i; x = x xor (x shr 7) }
            compute += System.nanoTime() - t0
            if (x == Int.MIN_VALUE) Log.d(TAG, "impossible")
            Thread.sleep(BURST_GAP_MS)
        }
        return compute / 1_000_000
    }

    private fun currentMa(): Double {
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (ua == Int.MIN_VALUE) 0.0 else -ua / 1000.0
    }

    /**
     * ★★ **app 身份读 sysfs** —— shell 被 SELinux 挡死的那些目录，app 能不能读？
     * （`untrusted_app` 与 `shell` 是**不同的 SELinux 域**，不能想当然。）
     */
    private fun probeSysfs() {
        line("---- app 身份 sysfs 可达性 ----")
        val probes = listOf(
            "/sys/class/devfreq",
            "/sys/class/kgsl/kgsl-3d0",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/devfreq/soc:qcom,cpu0-cpu-ddr-lat/cur_freq",
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq",
            "/sys/class/thermal/thermal_zone0/temp",
        )
        for (p in probes) {
            val f = File(p)
            val r = when {
                !f.exists() -> "ABSENT"
                f.isDirectory -> if (f.list() != null) "LS-OK  (${f.list()!!.size} 项)" else "LS-DEN"
                else -> try { "CAT-OK = ${f.readText().trim().take(20)}" } catch (t: Throwable) { "CAT-DEN" }
            }
            line("  %-52s %s".format(p, r))
        }
    }

    // ------------------------------------------------------------------ binder

    private fun getBinder(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, SVC) as? IBinder
    } catch (t: Throwable) { null }

    private fun acquire(durationMs: Int, list: IntArray): Int {
        val b = getBinder() ?: return -1
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(durationMs)
            data.writeIntArray(list)
            b.transact(TX_LOCK_ACQUIRE, data, reply, 0)
            reply.readException(); reply.readInt()
        } catch (t: Throwable) { -1 } finally { reply.recycle(); data.recycle() }
    }

    private fun release(handle: Int): Int {
        val b = getBinder() ?: return -1
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(handle)
            b.transact(TX_LOCK_RELEASE_HANDLER, data, reply, 0)
            reply.readException(); reply.readInt()
        } catch (t: Throwable) { -1 } finally { reply.recycle(); data.recycle() }
    }
}
