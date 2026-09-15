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
 * ★★★★★ **软解功耗对照**（任务 AN · 第 2 步）。
 *
 * ## 要回答什么
 *
 * AN 已实测：**AV1 软解**时性能模式 **+35.8%**，且收益几乎全部来自
 * `POWER COLLAPSE = 1`（禁止电源关断），而不是抬频。
 *
 * ⇒ ★ **但那个收益的【代价】是多少？** 软解本来就满载 + 禁止电源关断，
 * 功耗与发热必然上升。**必须量化，否则档位推荐没有依据。**
 *
 * ## 设计
 *
 * | 相位 | 负载 | 频率锁 |
 * |---|---|---|
 * | ① 无锁 | 持续 AV1 软解 | 无 |
 * | ② 均衡 | 持续 AV1 软解 | 三簇 MAX_FREQ |
 * | ③ 强力 | 持续 AV1 软解 | MAX_FREQ ＋ 禁止电源关断 |
 *
 * ★ **交替 2 轮**（①②③①②③）抵消热漂移。
 * ★ 每相位 **90 秒**，每 2 秒采一次：
 *   - `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`（µA，放电为负）—— 任务 AK4 验证过的公开 API
 *   - 电池温度（`ACTION_BATTERY_CHANGED` 的 `temperature`）
 *   - SoC 温度（`/sys/class/thermal` 最大值，**排除 `lmh-*`**）
 * ★ 同时统计**解码帧数** —— 这样能算出「**每帧能耗**」，而不只是"更费电"。
 *
 * ## ⚠️ 前提
 * **手机必须在放电**（`AC powered: false`）—— 插着充电器时充电控制器会调节，测不出消耗差异。
 */
class DecodePowerActivity : Activity() {

    companion object {
        private const val TAG = "DecodePower"

        private const val SVC = "vendor.perfservice"
        private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"
        private const val TX_LOCK_RELEASE_HANDLER = 2
        private const val TX_LOCK_ACQUIRE = 4

        private const val VIDEO = "/sdcard/Android/data/com.shware.perfprobe/files/av1test.mp4"
        private const val PHASE_MS = 90_000L
        private const val SAMPLE_MS = 2_000L
        private const val TIMEOUT_US = 10_000L

        private val LOCK_BALANCED = intArrayOf(
            0x40800000, 2419, 0x40800100, 1785, 0x40800200, 2956
        )
        private val LOCK_STRONG = intArrayOf(
            0x40800000, 2419, 0x40800100, 1785, 0x40800200, 2956, 0x40C00000, 1
        )
    }

    private lateinit var out: TextView
    private val sb = StringBuilder()
    private val bm by lazy { getSystemService(BATTERY_SERVICE) as BatteryManager }

    @Volatile private var stop = false

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

    override fun onDestroy() { stop = true; super.onDestroy() }

    private fun line(s: String) {
        Log.i(TAG, s)
        runOnUiThread { sb.append(s).append('\n'); out.text = sb.toString() }
    }

    private data class P(
        val name: String, val meanMa: Double, val frames: Int,
        val mAh: Double, val dSoC: Double, val dBat: Double, val perFrameUAh: Double,
    )

    private fun runCompare() {
        line("========== 软解功耗对照（AV1 软解持续负载）==========")
        val plugged = isPlugged()
        line("电源: ${if (plugged) "★ 正在充电 ⇒ 测不准，请拔掉！" else "放电中 ✓"}")
        line("视频: $VIDEO  (存在=${File(VIDEO).exists()})")
        line("相位 ${PHASE_MS / 1000}s × 3 配置 × 2 轮，交替")
        line("")

        val seq = listOf(
            Triple("无锁", null, 0), Triple("均衡", LOCK_BALANCED, 0), Triple("强力", LOCK_STRONG, 0),
            Triple("无锁", null, 1), Triple("均衡", LOCK_BALANCED, 1), Triple("强力", LOCK_STRONG, 1),
        )
        val acc = HashMap<String, ArrayList<P>>()
        for ((name, lock, round) in seq) {
            val r = phase(name, lock, round)
            if (r != null) acc.getOrPut(name) { ArrayList() }.add(r)
        }

        line("")
        line("========== 汇总（两轮均值）==========")
        line("%-6s %9s %9s %9s %9s %9s".format("配置", "平均电流", "解码帧", "耗电", "每帧", "SoCΔ"))
        for (k in listOf("无锁", "均衡", "强力")) {
            val v = acc[k] ?: continue
            line("%-6s %7.0f mA %9.0f %7.2f mAh %7.1f µAh %7.1f°C".format(
                k, v.map { it.meanMa }.average(), v.map { it.frames.toDouble() }.average(),
                v.map { it.mAh }.average(), v.map { it.perFrameUAh }.average(),
                v.map { it.dSoC }.average(),
            ))
        }
        val base = acc["无锁"]?.map { it.meanMa }?.average() ?: 0.0
        val basePf = acc["无锁"]?.map { it.perFrameUAh }?.average() ?: 0.0
        line("")
        for (k in listOf("均衡", "强力")) {
            val v = acc[k] ?: continue
            val ma = v.map { it.meanMa }.average()
            val pf = v.map { it.perFrameUAh }.average()
            line("  $k 相对无锁: 电流 %+.0f mA (%+.1f%%)   每帧能耗 %+.1f%%".format(
                ma - base, (ma - base) / base * 100, (pf - basePf) / basePf * 100))
        }
        line("")
        line("DONE")
    }

    private fun phase(name: String, lock: IntArray?, round: Int): P? {
        line("──── 相位 $name（第${round + 1}轮，${PHASE_MS / 1000}s）────")
        var handle = -1
        if (lock != null) {
            handle = acquire(200_000, lock)
            if (handle < 0) { line("  ✗ 拿锁失败"); return null }
            Thread.sleep(2000)
        }

        stop = false
        val frames = java.util.concurrent.atomic.AtomicInteger(0)
        val decoder = Thread {
            while (!stop) {
                val n = decodeOnce(VIDEO)
                if (n > 0) frames.addAndGet(n) else Thread.sleep(200)
            }
        }.apply { priority = Thread.MAX_PRIORITY; start() }

        Thread.sleep(3000)      // 让负载稳定
        val samples = ArrayList<Int>()
        val soc0 = socTempC(); val bat0 = batteryTempC()
        val end = System.currentTimeMillis() + PHASE_MS
        while (System.currentTimeMillis() < end) {
            val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (ua != Int.MIN_VALUE) samples.add(ua)
            Thread.sleep(SAMPLE_MS)
        }
        val soc1 = socTempC(); val bat1 = batteryTempC()

        stop = true
        decoder.interrupt()
        decoder.join(3000)
        if (handle >= 0) release(handle)

        val ma = samples.map { -it / 1000.0 }
        val mean = ma.average()
        val f = frames.get()
        val mAh = mean * (PHASE_MS / 1000.0) / 3600.0
        val perFrame = if (f > 0) mAh * 1000.0 / f else 0.0     // µAh/帧
        line("  %.0f mA ｜ %d 帧 ｜ %.2f mAh ｜ %.1f µAh/帧 ｜ SoC %.1f→%.1f°C ｜ 电池 %.1f→%.1f°C".format(
            mean, f, mAh, perFrame, soc0, soc1, bat0, bat1))
        Thread.sleep(4000)
        return P(name, mean, f, mAh, soc1 - soc0, bat1 - bat0, perFrame)
    }

    // ------------------------------------------------------------------ 解码

    /** 全量解一遍；返回帧数（-1 = 失败） */
    private fun decodeOnce(path: String): Int {
        val ex = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        var st: android.graphics.SurfaceTexture? = null
        var surface: android.view.Surface? = null
        return try {
            ex.setDataSource(path)
            var track = -1
            var fmt: android.media.MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(android.media.MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    track = i; fmt = f; break
                }
            }
            if (track < 0 || fmt == null) return -1
            ex.selectTrack(track)
            codec = android.media.MediaCodec.createDecoderByType(
                fmt.getString(android.media.MediaFormat.KEY_MIME)!!
            )
            // ★ 必须给 Surface —— 软解器不支持 ByteBuffer 输出（AN 已踩）
            st = android.graphics.SurfaceTexture(0)
            st.setDefaultBufferSize(
                fmt.getInteger(android.media.MediaFormat.KEY_WIDTH),
                fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT)
            )
            surface = android.view.Surface(st)
            codec.configure(fmt, surface, null, 0)
            codec.start()

            val info = android.media.MediaCodec.BufferInfo()
            var frames = 0; var inDone = false; var outDone = false
            while (!outDone && !stop) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (oi >= 0) {
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    frames++
                    codec.releaseOutputBuffer(oi, true)
                }
            }
            frames
        } catch (t: Throwable) {
            Log.w(TAG, "decode 失败: ${t.javaClass.name}: ${t.message}")
            -1
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { surface?.release() }; runCatching { st?.release() }
            runCatching { ex.release() }
        }
    }

    // ------------------------------------------------------------------ 读数

    private fun isPlugged(): Boolean = try {
        val i = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (t: Throwable) { false }

    private fun batteryTempC(): Double = try {
        (registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra("temperature", 0) ?: 0) / 10.0
    } catch (t: Throwable) { 0.0 }

    /** SoC 温度：全 zone 最大值，★ 排除 `lmh-*`（那是限值恒 75，会盖掉真实温度） */
    private fun socTempC(): Double {
        var max = 0.0
        try {
            for (z in File("/sys/class/thermal").listFiles() ?: return 0.0) {
                if (!z.name.startsWith("thermal_zone")) continue
                val n = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: continue
                if (n.startsWith("lmh")) continue
                val raw = runCatching { File(z, "temp").readText().trim().toInt() }.getOrNull() ?: continue
                if (raw < 1000 || raw > 200000) continue
                val c = raw / 1000.0
                if (c > max) max = c
            }
        } catch (t: Throwable) { }
        return max
    }

    // ------------------------------------------------------------------ binder

    private fun getBinder(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, SVC) as? IBinder
    } catch (t: Throwable) { null }

    private fun acquire(d: Int, list: IntArray): Int {
        val b = getBinder() ?: return -1
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeInt(d); data.writeIntArray(list)
            b.transact(TX_LOCK_ACQUIRE, data, reply, 0); reply.readException(); reply.readInt()
        } catch (t: Throwable) { -1 } finally { reply.recycle(); data.recycle() }
    }

    private fun release(h: Int): Int {
        if (h < 0) return -1
        val b = getBinder() ?: return -1
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeInt(h)
            b.transact(TX_LOCK_RELEASE_HANDLER, data, reply, 0); reply.readException(); reply.readInt()
        } catch (t: Throwable) { -1 } finally { reply.recycle(); data.recycle() }
    }
}
