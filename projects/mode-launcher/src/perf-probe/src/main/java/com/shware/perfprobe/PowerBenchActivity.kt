package com.shware.perfprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * ★★★★★ **功耗基准**（任务 AK4）—— 量化"性能模式"到底多费多少电。
 *
 * ## 为什么把整件事放进 app 里做
 *
 * 试过的两条路都不行：
 * 1. ❌ **`dumpsys batterystats` 的 `Discharge`** —— **跳变式更新**（粒度 5–20 mAh），
 *    145 秒窗口只有 ~12 mAh ⇒ **测到的是量化噪声**，甚至得出"加锁更省电"的荒谬结论
 * 2. ⚠️ 之前想用 shell 脚本控制相位 + 另一处采样 ⇒ **时间戳要对齐**，多一层出错面
 *
 * ★ **正解**：用 **`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`（µA，放电为负）**
 * —— 这是**公开 API**，而且本 ROM 实测**给真值**（而 `/sys/class/power_supply/` 下
 * 连 shell 都被 SELinux 挡）。
 *
 * ⇒ 采样 / 施加锁 / 造负载**全在本 Activity 里**，没有对齐问题。
 *
 * ## 四个相位（各 90 秒）
 *
 * | 相位 | 负载 | 频率锁 | 含义 |
 * |---|---|---|---|
 * | ① `off-idle` | 无 | 无 | 基线 |
 * | ② **`on-idle`** | 无 | **有** | ★★ **纯代价**（CPU 被钉高频却什么都不干） |
 * | ③ `off-load` | 8 线程 | 无 | 每单位电干多少活 |
 * | ④ `on-load` | 8 线程 | **有** | 同上，带锁 |
 *
 * ## ⚠️ 前提
 * - **手机必须在放电**（`AC powered: false`）—— 插着充电器时充电控制器会调节，测不出差异
 * - 屏幕状态固定（本 Activity 自己 `KEEP_SCREEN_ON`）
 */
class PowerBenchActivity : Activity() {

    companion object {
        private const val TAG = "PowerBench"

        private const val SVC = "vendor.perfservice"
        private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"
        private const val TX_LOCK_RELEASE_HANDLER = 2
        private const val TX_LOCK_ACQUIRE = 4

        /** 每个相位时长（毫秒） */
        private const val PHASE_MS = 90_000L

        /**
         * ★ 空闲 A/B 的相位时长。
         * 用**交替四相位**（off/on/off/on）＋ 平均来抵消系统漂移 ——
         * 单次测量实测能差到 6 倍（175/181 vs 167/208）。
         */
        private const val IDLE_PHASE_MS = 60_000L

        /** 采样间隔 */
        private const val SAMPLE_MS = 2_000L

        /** 施加锁的时长（要盖住相位） */
        private const val LOCK_MS = 120_000

        /** 资源 opcode：三簇 CPUBOOST_MAX_FREQ，值 = MHz */
        private val LOCK_MAX = intArrayOf(0x40800000, 2419, 0x40800100, 1785, 0x40800200, 2956)

        /** 负载线程数（8 核） */
        private const val LOAD_THREADS = 8

        /** ★ 定量工作：每个线程要跑的运算次数（固定值 ⇒ 各相位干的活一样多） */
        private const val WORK_PER_THREAD = 6_000_000_000L
    }

    private lateinit var out: TextView
    private val sb = StringBuilder()
    private val bm by lazy { getSystemService(BATTERY_SERVICE) as BatteryManager }

    @Volatile private var stopLoad = false

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

        Thread { runBench() }.start()
    }

    override fun onDestroy() {
        stopLoad = true
        super.onDestroy()
    }

    private fun line(s: String) {
        Log.i(TAG, s)
        runOnUiThread {
            sb.append(s).append('\n')
            out.text = sb.toString()
        }
    }

    // ------------------------------------------------------------------ 基准

    private fun runBench() {
        line("========== 功耗基准（BatteryManager.CURRENT_NOW）==========")
        line("相位时长 ${PHASE_MS / 1000}s ／ 采样 ${SAMPLE_MS}ms ／ 负载 ${LOAD_THREADS} 线程")

        val temp0 = batteryTempC()
        line("起始电池温度 ${"%.1f".format(temp0)}°C   起始电量 ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")

        // ★★★ 空闲 A/B —— **交替四相位**（off/on/off/on）
        //   为什么：前两轮只各测一次（175/181 与 167/208），**同一件事差了 6 倍**
        //   ⇒ 单次测量被系统漂移主导。交替 + 平均才能抵消线性漂移。
        val i1 = phase("idle-off #1", load = false, lock = false, durMs = IDLE_PHASE_MS)
        val i2 = phase("idle-on  #1", load = false, lock = true, durMs = IDLE_PHASE_MS)
        val i3 = phase("idle-off #2", load = false, lock = false, durMs = IDLE_PHASE_MS)
        val i4 = phase("idle-on  #2", load = false, lock = true, durMs = IDLE_PHASE_MS)

        val idleOff = (i1.meanMa + i3.meanMa) / 2
        val idleOn = (i2.meanMa + i4.meanMa) / 2

        line("")
        line("════════ 空闲 A/B（交替四相位，各 ${IDLE_PHASE_MS / 1000}s）════════")
        line("  off: %.0f / %.0f mA  ⇒ 平均 %.0f mA".format(i1.meanMa, i3.meanMa, idleOff))
        line("  on : %.0f / %.0f mA  ⇒ 平均 %.0f mA".format(i2.meanMa, i4.meanMa, idleOn))
        line("  ★★ 空闲代价 = %+.0f mA  (%+.1f%%)  ≈ %+.0f mW".format(
            idleOn - idleOff, (idleOn - idleOff) / idleOff * 100, (idleOn - idleOff) * 3.85))
        line("  （两组组内差：off %.0f mA，on %.0f mA —— 越小越可信）".format(
            kotlin.math.abs(i1.meanMa - i3.meanMa), kotlin.math.abs(i2.meanMa - i4.meanMa)))

        line("")
        line("════════ 第二轮：★【定量工作】对比 ════════")
        line("⚠️ 第一轮的负载相位两组**干的活不一样**（空转循环，锁让 CPU 跑得更快）")
        line("   ⇒ 那个 −247 mA 不可采信。改成**同样的活，看谁省电**。")

        // ★ 交替顺序（无锁→有锁→无锁→有锁）以平均掉热漂移
        val w1 = fixedWork("work-off #1", lock = false)
        val w2 = fixedWork("work-on  #1", lock = true)
        val w3 = fixedWork("work-off #2", lock = false)
        val w4 = fixedWork("work-on  #2", lock = true)

        val offMean = (w1.energyMah + w3.energyMah) / 2
        val onMean = (w2.energyMah + w4.energyMah) / 2
        val offTime = (w1.seconds + w3.seconds) / 2
        val onTime = (w2.seconds + w4.seconds) / 2

        line("")
        line("══════════ 最终结论 ══════════")
        line("【空闲代价】交替四相位 = %+.0f mA ／ %+.1f%%  ≈ %+.0f mW".format(
            idleOn - idleOff, (idleOn - idleOff) / idleOff * 100, (idleOn - idleOff) * 3.85))
        line("")
        line("【定量工作】同样 %d 亿次运算 × %d 线程（★ 剔除首轮 JIT 预热）".format(
            WORK_PER_THREAD / 100_000_000, LOAD_THREADS))
        line("  无锁 #1 %.1fs/%.2fmAh   #2 %.1fs/%.2fmAh".format(w1.seconds, w1.energyMah, w3.seconds, w3.energyMah))
        line("  有锁 #1 %.1fs/%.2fmAh   #2 %.1fs/%.2fmAh".format(w2.seconds, w2.energyMah, w4.seconds, w4.energyMah))
        line("  含首轮：无锁 %.1fs/%.2fmAh   有锁 %.1fs/%.2fmAh  ⇒ 耗时 %+.1f%%  耗电 %+.1f%%".format(
            offTime, offMean, onTime, onMean,
            (onTime - offTime) / offTime * 100, (onMean - offMean) / offMean * 100))
        line("  只比 #2：无锁 %.1fs/%.2fmAh   有锁 %.1fs/%.2fmAh  ⇒ 耗时 %+.1f%%  耗电 %+.1f%%".format(
            w3.seconds, w3.energyMah, w4.seconds, w4.energyMah,
            (w4.seconds - w3.seconds) / w3.seconds * 100,
            (w4.energyMah - w3.energyMah) / w3.energyMah * 100))
        line("")
        line("DONE")
    }

    private data class W(val name: String, val seconds: Double, val meanMa: Double, val energyMah: Double)

    /**
     * ★★★ **定量工作**相位 —— 每个线程跑**固定次数**的运算，
     * 测「跑完这些活花了多久 + 用了多少电」。
     *
     * 这才是能回答"值不值"的测法：**同样多的活，锁是更省还是更费**。
     */
    private fun fixedWork(name: String, lock: Boolean): W {
        line("")
        line("──── $name（锁=$lock，每线程 ${WORK_PER_THREAD / 100_000_000} 亿次）────")

        var handle = -1
        if (lock) {
            handle = acquire(LOCK_MS, LOCK_MAX)
            line("  施加锁 → handle=$handle")
        }
        Thread.sleep(2500)

        val samples = java.util.Collections.synchronizedList(ArrayList<Int>())
        // 别让 JIT 把整个循环优化掉（`@Volatile` 不能用在局部变量上 ⇒ 用 AtomicInteger）
        val dummy = java.util.concurrent.atomic.AtomicInteger(0)

        val start = System.currentTimeMillis()
        val threads = (0 until LOAD_THREADS).map {
            Thread {
                var x = 1
                var i = 0L
                while (i < WORK_PER_THREAD) {
                    x = x * 31 + 1
                    x = x xor (x shr 7)
                    i++
                }
                dummy.addAndGet(x)
            }.apply { priority = Thread.MAX_PRIORITY; start() }
        }

        // 主线程一边等一边采样
        val sampler = Thread {
            while (threads.any { it.isAlive }) {
                val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (ua != Int.MIN_VALUE) samples.add(ua)
                Thread.sleep(SAMPLE_MS)
            }
        }.apply { start() }

        threads.forEach { it.join() }
        val elapsed = (System.currentTimeMillis() - start) / 1000.0
        sampler.join(3000)
        if (handle >= 0) release(handle)

        val meanMa = if (samples.isEmpty()) 0.0 else samples.map { -it / 1000.0 }.average()
        val mah = meanMa * elapsed / 3600.0
        line("  耗时 %.1f s ／ 采样 %d 个 ／ 平均 %.0f mA ／ 耗电 %.2f mAh".format(
            elapsed, samples.size, meanMa, mah))
        return W(name, elapsed, meanMa, mah)
    }

    private data class R(
        val name: String, val meanMa: Double, val maxMa: Double, val minMa: Double,
        val meanMw: Double, val dTempC: Double, val samples: Int,
    )

    private fun phase(name: String, load: Boolean, lock: Boolean, durMs: Long = PHASE_MS): R {
        line("")
        line("──── 相位 $name（负载=$load 锁=$lock 时长=${durMs / 1000}s）────")

        var handle = -1
        if (lock) {
            handle = acquire(LOCK_MS, LOCK_MAX)
            line("  施加锁 → handle=$handle")
            if (handle < 0) line("  ⚠️ 拿锁失败，本相位不可信")
        }
        Thread.sleep(3000)      // 让锁生效 / 状态稳定

        var threads = emptyList<Thread>()
        if (load) {
            stopLoad = false
            threads = (0 until LOAD_THREADS).map {
                Thread {
                    var x = 1
                    while (!stopLoad) { x = x * 31 + 1; x = x xor (x shr 7) }
                }.apply { priority = Thread.MAX_PRIORITY; start() }
            }
            line("  已起 $LOAD_THREADS 个负载线程")
        }

        val t0 = batteryTempC()
        val samples = ArrayList<Int>()
        val end = System.currentTimeMillis() + PHASE_MS
        while (System.currentTimeMillis() < end) {
            val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (ua != Int.MIN_VALUE) samples.add(ua)
            Thread.sleep(SAMPLE_MS)
        }
        val t1 = batteryTempC()

        stopLoad = true
        threads.forEach { it.join(500) }
        if (handle >= 0) release(handle)

        val t1b = System.currentTimeMillis()

        // ★ 电流为负（放电）⇒ 取负号变成"消耗"
        val consumption = samples.map { -it / 1000.0 }     // mA
        val mean = consumption.average()
        val volts = 3.85                                    // 标称工作电压（`dumpsys battery` 实测 4.03–4.21V）
        line("  采样 ${samples.size} 个；电流 均值 %.0f mA  峰值 %.0f mA  最低 %.0f mA".format(
            mean, consumption.max(), consumption.min()))
        line("  电池温度 %.1f → %.1f °C".format(t0, t1))
        Thread.sleep(1)
        Log.i(TAG, "PHASE_END $name ${t1b}")

        return R(name, mean, consumption.max(), consumption.min(), mean * volts, t1 - t0, samples.size)
    }

    private fun batteryTempC(): Double {
        val i = intent
        return try {
            val t = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra("temperature", 0) ?: 0
            t / 10.0
        } catch (e: Throwable) { 0.0 }
    }

    // ------------------------------------------------------------------ binder

    private fun getBinder(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, SVC) as? IBinder
    } catch (t: Throwable) {
        Log.w(TAG, "拿 binder 失败: ${t.message}"); null
    }

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
