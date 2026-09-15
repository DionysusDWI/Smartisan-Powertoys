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

/**
 * ★★★★★ **档位功耗对照**（任务 AK7）—— 补齐最后一个未知数。
 *
 * ## 问题
 *
 * 任务 AK6 发现 `POWER COLLAPSE = 1`（`0x40C00000`）能把突发负载从 789 ms 压到 **269 ms**，
 * 但它**禁止 CPU 电源关断** ⇒ ⚠️ **空闲时必然更费电**。这个代价**必须量化**，
 * 否则"强力档"就是个只报收益不报代价的功能。
 *
 * ## 设计
 *
 * **交替 2 轮**（off → 均衡 → 强力 → off → 均衡 → 强力），各 60 秒：
 * - 交替可以**抵消系统漂移**（任务 AK4 的教训：单次测量同一件事能差 6 倍）
 * - **空闲**（不加负载）—— 这正是电源关断代价显形的场景
 * - 屏幕常亮固定（本 Activity 自己 `KEEP_SCREEN_ON`）
 *
 * ## 数据源
 *
 * `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`（µA，放电为负）
 * —— ★ 公开 API、零权限，任务 AK4 实测**给真值**
 * （而 `/sys/class/power_supply/` 下连 shell 都被 SELinux 挡）。
 *
 * ## ⚠️ 前提
 * **手机必须在放电**（`AC powered: false`）。
 */
class TierPowerActivity : Activity() {

    companion object {
        private const val TAG = "TierPower"
        private const val SVC = "vendor.perfservice"
        private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"
        private const val TX_LOCK_RELEASE_HANDLER = 2
        private const val TX_LOCK_ACQUIRE = 4

        private const val PHASE_MS = 60_000L
        private const val SAMPLE_MS = 2_000L
        private const val LOCK_MS = 80_000

        private const val OP_BIG_MAX = 0x40800000
        private const val OP_LITTLE_MAX = 0x40800100
        private const val OP_PRIME_MAX = 0x40800200
        private const val OP_PWR_COLLAPSE = 0x40C00000

        /** 均衡档：只抬频率下限 */
        private val BALANCED = intArrayOf(OP_BIG_MAX, 2419, OP_LITTLE_MAX, 1785, OP_PRIME_MAX, 2956)

        /** 强力档：＋ 禁止电源关断 */
        private val STRONG = BALANCED + intArrayOf(OP_PWR_COLLAPSE, 1)
    }

    private lateinit var out: TextView
    private val sb = StringBuilder()
    private val bm by lazy { getSystemService(BATTERY_SERVICE) as BatteryManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (14 * resources.displayMetrics.density).toInt()
        out = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#DDDDDD"))
            setBackgroundColor(Color.parseColor("#FF101010"))
            setPadding(pad, pad, pad, pad)
            typeface = Typeface.MONOSPACE
        }
        setContentView(ScrollView(this).apply { addView(out) })
        Thread { runCompare() }.start()
    }

    private fun line(s: String) {
        Log.i(TAG, s)
        runOnUiThread { sb.append(s).append('\n'); out.text = sb.toString() }
    }

    private data class P(val name: String, val meanMa: Double, val maxMa: Double, val minMa: Double, val n: Int)

    private fun runCompare() {
        line("========== 档位功耗对照（空闲 · 交替 2 轮 · 各 60s）==========")
        line("电量 ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%  ·  屏幕常亮固定")
        line("")

        val seq = listOf(
            Triple("① 无锁   ", null, 0),
            Triple("② 均衡   ", BALANCED, 0),
            Triple("③ 强力   ", STRONG, 0),
            Triple("① 无锁   ", null, 1),
            Triple("② 均衡   ", BALANCED, 1),
            Triple("③ 强力   ", STRONG, 1),
        )

        val acc = HashMap<String, ArrayList<Double>>()
        for ((name, list, round) in seq) {
            var handle = -1
            if (list != null) {
                handle = acquire(LOCK_MS, list)
                if (handle < 0) { line("$name ✗ 拿锁失败"); continue }
            }
            Thread.sleep(2500)

            val samples = ArrayList<Int>()
            val end = System.currentTimeMillis() + PHASE_MS
            while (System.currentTimeMillis() < end) {
                val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (ua != Int.MIN_VALUE) samples.add(ua)
                Thread.sleep(SAMPLE_MS)
            }
            if (handle >= 0) release(handle)
            Thread.sleep(1500)

            val ma = samples.map { -it / 1000.0 }
            val mean = ma.average()
            val t = batteryTempC()
            line("%s 第%d轮  均值 %6.1f mA  峰 %5.0f  谷 %5.0f  n=%2d  电池 %.1f°C".format(
                name, round + 1, mean, ma.max(), ma.min(), samples.size, t))
            acc.getOrPut(name) { ArrayList() }.add(mean)
        }

        line("")
        line("========== 汇总（两轮均值）==========")
        val base = acc["① 无锁   "]?.average() ?: 0.0
        for (k in listOf("① 无锁   ", "② 均衡   ", "③ 强力   ")) {
            val v = acc[k] ?: continue
            val m = v.average()
            line("%s  均值 %6.1f mA   两轮 %s   ⇒ 相对无锁 %+6.1f mA (%+.1f%%)  ≈ %+.0f mW".format(
                k, m, v.joinToString("/") { "%.0f".format(it) },
                m - base, (m - base) / base * 100, (m - base) * 3.85))
        }
        line("")
        line("DONE")
    }

    private fun batteryTempC(): Double = try {
        registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra("temperature", 0)?.div(10.0) ?: 0.0
    } catch (t: Throwable) { 0.0 }

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
