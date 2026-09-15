package com.shware.perfprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * ★★★★★ **GPU 探针**（任务 AM）—— 摸清能不能用 QTI perf HAL 控制 GPU。
 *
 * ## 依据（**不是猜的**）
 *
 * `/vendor/etc/perf/commonresourceconfigs.xml` 就是 **opcode → 内核节点 的字典**：
 * ```
 * Major 0xA = GPU
 *   0x0 → /sys/class/kgsl/kgsl-3d0/default_pwrlevel
 *   0x1 → min_pwrlevel
 *   0x2 → max_pwrlevel
 *   0x3 → devfreq/min_freq
 *   0x4 → devfreq/max_freq
 *   0x5 / 0x6 → gpubw min / max
 *   ★ 0x7 → SPECIAL: gpu_disable_gpu_nap   ← CPU 版 POWER COLLAPSE 的 GPU 版
 * ```
 *
 * ## ⚠️ 难点：opcode 编码不确定
 *
 * `perfboostsconfig.xml` 用 `0x40xxxxxx` 形式，XML 里的 major 只有 `0x0`–`0xD`
 * ⇒ **两套编号不是直接对应的**（唯一吻合的锚点：`0x43000000` ↔ major 0x3 sched_boost）。
 *
 * ⇒ ★ **不猜，直接扫。** 候选把几种可能的移位都试一遍。
 *
 * ## ★★★ 下游观测量：`gpuclk`
 *
 * `/sys/class/kgsl/kgsl-3d0/gpuclk`（**app 可读**，实测 `257000000` = 空闲 257 MHz）
 * —— 任务 AK6 已证实：**SELinux 挡的是 `readdir`，不是 `read`**。
 *
 * | 现象 | 含义 |
 * |---|---|
 * | `gpuclk` **升高** | 频率下限被抬起（`min_pwrlevel` / `devfreq min_freq` 类） |
 * | `gpuclk` **被压低** | 封顶生效（`max_pwrlevel` 类） |
 * | 无变化 | 该 opcode 不生效，或编码不对 |
 *
 * ## ⚠️ 安全
 * 每个候选：**只发一对 opcode**、**时长 4 秒**、**立即回读**、**观察 CPU 频率有没有被误动**。
 */
class GpuProbeActivity : Activity() {

    companion object {
        private const val TAG = "GpuProbe"
        private const val SVC = "vendor.perfservice"
        private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"
        private const val TX_LOCK_RELEASE_HANDLER = 2
        private const val TX_LOCK_ACQUIRE = 4

        private const val GPUCLK = "/sys/class/kgsl/kgsl-3d0/gpuclk"
        private const val HOLD_MS = 4000

        /**
         * 候选 opcode。
         * ★ 每个都标了「来自哪条 XML 条目」与「试的哪种编码」——
         *   扫完就能反推编码规则，而不是只得到一个"能用/不能用"。
         */
        private val CANDIDATES: List<Triple<String, Int, Int>> = listOf(
            // 说明, opcode, 建议值
            Triple("0x4A000000 minor0<<16 default_pwrlevel", 0x4A000000, 1),
            Triple("0x4A000100 minor0<<8  default_pwrlevel", 0x4A000100, 1),
            Triple("0x4A001000 minor0<<12 default_pwrlevel", 0x4A001000, 1),
            Triple("0x4A010000 minor1<<16 min_pwrlevel", 0x4A010000, 1),
            Triple("0x4A010100 minor1<<8  min_pwrlevel", 0x4A010100, 1),
            Triple("0x4A011000 minor1<<12 min_pwrlevel", 0x4A011000, 1),
            Triple("0x4A020000 minor2<<16 max_pwrlevel", 0x4A020000, 1),
            Triple("0x4A030000 minor3<<16 devfreq min_freq", 0x4A030000, 500),
            Triple("0x4A040000 minor4<<16 devfreq max_freq", 0x4A040000, 500),
            Triple("0x4A070000 minor7<<16 disable_gpu_nap", 0x4A070000, 1),
            // 配置里真出现过的 0x428* 族
            Triple("0x42800000 配置里出现过", 0x42800000, 1),
            Triple("0x42804000 配置里出现过", 0x42804000, 1),
            Triple("0x42808000 配置里出现过", 0x42808000, 1),
            Triple("0x4281C000 配置里出现过", 0x4281C000, 1),
        )
    }

    private lateinit var out: TextView
    private val sb = StringBuilder()

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
        Thread { runScan() }.start()
    }

    private fun line(s: String) {
        Log.i(TAG, s)
        runOnUiThread { sb.append(s).append('\n'); out.text = sb.toString() }
    }

    private fun runScan() {
        line("========== GPU opcode 扫描（任务 AM）==========")
        line("下游观测: $GPUCLK")
        line("")

        // 可达性
        val f = File(GPUCLK)
        line("可达性: ${if (f.exists()) (runCatching { "读 OK = ${f.readText().trim()}" }.getOrElse { "存在但读不了" }) else "ABSENT"}")
        line("")
        if (!f.exists()) { line("✗ GPU 频率读不到 ⇒ 没有观测量，扫描无意义"); line("DONE"); return }

        val gpu0 = gpuClk()
        val base = freqSnapshot()
        line("基线: gpu=$gpu0  $base")
        line("")
        line("候选 opcode 扫描（每个 4 秒，只发一对）：")
        line("值 = MHz（freq 类）或 pwrlevel（level 类）—— 两者语义相反，故都试")
        line("")

        val hits = ArrayList<String>()
        for ((desc, opcode, value) in CANDIDATES) {
            val before = freqSnapshot()
            val g0 = gpuClk()
            val r = acquire(HOLD_MS, intArrayOf(opcode, value))
            Thread.sleep(1800)
            val g1 = gpuClk()
            val after = freqSnapshot()
            release(r)
            Thread.sleep(600)

            val changed = g0 != g1
            val cpuMoved = before != after
            val mark = when {
                changed -> "★★ GPU 变了!"
                r < 0 -> "✗ 被拒(handle<0)"
                else -> "—"
            }
            if (changed) hits.add(desc)
            line("0x%08X = %-5d  %-34s gpu: %s→%s  %s%s".format(
                opcode, value, desc.take(34),
                fmt(g0), fmt(g1), mark,
                if (cpuMoved) "  ⚠ CPU 也被动: $after" else ""
            ))
        }

        line("")
        line("========== 汇总 ==========")
        if (hits.isEmpty()) {
            line("★ 没有任何候选改变了 gpuclk")
            line("  ⇒ 要么编码不对（需要继续试别的移位），要么这些资源在本 ROM 上不生效")
            line("  ★ 注意：GPU 空闲时可能被电源关断 ⇒ 抬 min_pwrlevel 也未必立刻反映到 gpuclk")
            line("     下一步：**加 GPU 负载**再扫一遍（本探针未做）")
        } else {
            hits.forEach { line("★★ 命中: $it") }
        }

        // ★★★★ 第二轮：值扫描 —— 反推 pwrlevel → 频率 的阶梯
        line("")
        line("════════ 第二轮：值扫描（反推 pwrlevel → 频率 阶梯）════════")
        line("gpuclk 是【唯一】可读的 GPU 节点（其它 kgsl 节点全被 SELinux 挡）")
        line("⇒ 只能靠扫值来反推。")
        line("")
        for (op in intArrayOf(0x42800000, 0x42804000)) {
            line("---- opcode 0x%08X ----".format(op))
            val seen = LinkedHashMap<String, Int>()
            for (v in 0..10) {
                val r = acquire(3000, intArrayOf(op, v))
                Thread.sleep(1500)
                val g = gpuClk()
                release(r)
                Thread.sleep(400)
                line("  值 %-3d  →  gpu = %s   %s".format(v, fmt(g), if (r < 0) "(被拒)" else ""))
                seen[g] = v
            }
            line("  ⇒ 出现过的档位: ${seen.keys.joinToString(" ")}")
        }

        line("")
        line("DONE")
    }

    // ------------------------------------------------------------------ 读取

    private fun gpuClk(): String = runCatching { File(GPUCLK).readText().trim() }.getOrElse { "—" }

    private fun fmt(hz: String): String = runCatching {
        val v = hz.toLong()
        if (v > 1_000_000) "%dM".format(v / 1_000_000) else hz
    }.getOrElse { hz }

    /** 快照三簇 CPU 频率 —— 用来发现"误伤 CPU" */
    private fun freqSnapshot(): String = (0..7 step 4).joinToString(",") { p ->
        runCatching {
            File("/sys/devices/system/cpu/cpufreq/policy$p/scaling_cur_freq").readText().trim()
        }.getOrElse { "?" }
    }.let { "$it" }.replace(",", "/")

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
        if (handle < 0) return -1
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
