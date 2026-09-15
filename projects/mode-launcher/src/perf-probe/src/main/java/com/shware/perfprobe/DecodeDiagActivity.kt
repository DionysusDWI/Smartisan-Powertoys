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
 * ★★★★★ **「只抬频为何更慢」机理诊断**（任务 AN · 第 3 步）。
 *
 * ## 要解释的悖论
 *
 * 用 AV1 软解做基准（全量解 901 帧）：
 * ```
 * 无锁          31.2 fps
 * 轻量(只抬频)   28.3 fps   ← ★ 比【什么都不做】还慢！
 * 只禁PC        40.3 fps
 * 强力(抬频+PC)  42.3 fps
 * ```
 *
 * - ❌ **"热降频"假说已被推翻**：轻量档 55–58°C 比强力档 60–62°C **更凉**却更慢。
 * - ★ 新假说：**抬频把小核垫高后，调度器不再把解码线程迁到大核**
 *   （EAS 看到"小核容量够用"就不上迁）。
 *
 * ## 关键观测量：**`time_in_state` —— CPU 实际交付了多少周期**
 *
 * `/sys/devices/system/cpu/cpuN/cpufreq/stats/time_in_state` **每核可读**（实测）。
 *
 * | 派生指标 | 含义 |
 * |---|---|
 * | `Σ(驻留 × 频率)` | ★★ **总交付周期数** —— 这才是"干活的量" |
 * | `Σ驻留` | 该簇真正**被使用**了多久 |
 * | `total_trans` | ★ 频率**切换次数** —— 高 = 频率在抖 |
 *
 * ⇒ ★★ 若轻量档的**总周期数反而更低**，就解释了它为何更慢。
 *
 * ## 配置对比
 *
 * | # | 配置 | 目的 |
 * |---|---|---|
 * | ① | 无锁 | 基线 |
 * | ② | ★ **只抬大核**（`0x40800000=2419`） | **隔离变量**：只动大核，看是不是"小核被垫高"的锅 |
 * | ③ | 三簇全抬（＝轻量档） | 复现悖论 |
 * | ④ | 强力（＋禁 PC） | 正解 |
 */
class DecodeDiagActivity : Activity() {

    companion object {
        private const val TAG = "DecodeDiag"

        private const val SVC = "vendor.perfservice"
        private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"
        private const val TX_LOCK_RELEASE_HANDLER = 2
        private const val TX_LOCK_ACQUIRE = 4

        private const val VIDEO = "/sdcard/Android/data/com.shware.perfprobe/files/av1test.mp4"
        private const val PHASE_MS = 60_000L
        private const val TIMEOUT_US = 10_000L

        private val BIG_ONLY = intArrayOf(0x40800000, 2419)
        private val ALL_FREQ = intArrayOf(0x40800000, 2419, 0x40800100, 1785, 0x40800200, 2956)
        private val STRONG = ALL_FREQ + intArrayOf(0x40C00000, 1)

        /** 三簇的代表核 + 该簇在 `time_in_state` 里的核 */
        private val CLUSTER_CORES = intArrayOf(0, 4, 7)
        private val CLUSTER_NAMES = arrayOf("小核", "大核", "超大核")
    }

    private lateinit var out: TextView
    private val sb = StringBuilder()

    @Volatile private var stop = false

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
        Thread { runDiag() }.start()
    }

    override fun onDestroy() { stop = true; super.onDestroy() }

    private fun line(s: String) {
        Log.i(TAG, s)
        runOnUiThread { sb.append(s).append('\n'); out.text = sb.toString() }
    }

    /** 单簇快照：按频率分档的驻留计数（ticks） */
    private data class Snap(val byFreq: Map<Long, Long>, val trans: Long) {
        val total: Long get() = byFreq.values.sum()
        /** ★ 总交付周期（MHz·tick） */
        val cycles: Double get() = byFreq.entries.sumOf { it.key.toDouble() * it.value }
        val avgMhz: Double get() = if (total == 0L) 0.0 else cycles / total / 1000.0
    }

    private fun snap(core: Int): Snap {
        val f = File("/sys/devices/system/cpu/cpu$core/cpufreq/stats/time_in_state")
        val m = HashMap<Long, Long>()
        runCatching {
            f.readLines().forEach { ln ->
                val p = ln.trim().split(Regex("\\s+"))
                if (p.size >= 2) m[p[0].toLong()] = p[1].toLong()
            }
        }
        val t = runCatching {
            File("/sys/devices/system/cpu/cpu$core/cpufreq/stats/total_trans").readText().trim().toLong()
        }.getOrDefault(0L)
        return Snap(m, t)
    }

    private data class Res(
        val name: String, val fps: Double, val frames: Int,
        val totalTicks: LongArray, val avgMhz: DoubleArray,
        val cycles: DoubleArray, val trans: LongArray, val online: Int,
    )

    private fun runDiag() {
        line("========== 「只抬频为何更慢」机理诊断 ==========")
        line("观测量：每核 time_in_state（★ 总交付周期 = Σ驻留×频率）")
        line("相位 ${PHASE_MS / 1000}s × 4 配置")
        line("")

        val cfgs = listOf(
            Triple("① 无锁", null, 0),
            Triple("② 只抬大核", BIG_ONLY, 0),
            Triple("③ 三簇全抬(轻量)", ALL_FREQ, 0),
            Triple("④ 强力(+禁PC)", STRONG, 0),
        )
        val res = ArrayList<Res>()
        for ((name, lock, _) in cfgs) {
            val r = phase(name, lock)
            if (r != null) res.add(r)
        }

        line("")
        line("══════════ 汇总 ══════════")
        line("%-18s %8s %10s %10s %10s %8s %6s".format("配置", "fps", "小核MHz", "大核MHz", "超大核MHz", "总切换", "在线"))
        for (r in res) {
            line("%-18s %8.1f %10.0f %10.0f %10.0f %8d %6d".format(
                r.name, r.fps, r.avgMhz[0], r.avgMhz[1], r.avgMhz[2],
                r.trans.sum(), r.online))
        }

        val base = res.firstOrNull() ?: return
        line("")
        line("---- 相对无锁 ----")
        for (r in res.drop(1)) {
            val cy = r.cycles.sum(); val cy0 = base.cycles.sum()
            val tk = r.totalTicks.sum(); val tk0 = base.totalTicks.sum()
            line("%-18s fps %+.1f%% ｜ ★总交付周期 %+.1f%% ｜ 有效用时 %+.1f%%".format(
                r.name, (r.fps - base.fps) / base.fps * 100,
                (cy - cy0) / cy0 * 100,
                (tk - tk0).toDouble() / tk0 * 100))
        }
        line("")
        line("---- 各簇承担的工作比例（按交付周期）----")
        for (r in res) {
            val tot = r.cycles.sum()
            line("%-18s 小核 %4.1f%% ｜ 大核 %4.1f%% ｜ 超大核 %4.1f%%".format(
                r.name, r.cycles[0] / tot * 100, r.cycles[1] / tot * 100, r.cycles[2] / tot * 100))
        }
        line("")
        line("DONE")
    }

    private fun phase(name: String, lock: IntArray?): Res? {
        line("──── $name（${PHASE_MS / 1000}s）────")
        var h = -1
        if (lock != null) {
            h = acquire(200_000, lock)
            if (h < 0) { line("  ✗ 拿锁失败"); return null }
            Thread.sleep(2000)
        }
        stop = false

        val s0 = Array(3) { snap(CLUSTER_CORES[it]) }
        val frames = java.util.concurrent.atomic.AtomicInteger(0)
        val dec = Thread {
            while (!stop) {
                val n = decodeOnce(VIDEO)
                if (n > 0) frames.addAndGet(n) else Thread.sleep(200)
            }
        }.apply { priority = Thread.MAX_PRIORITY; start() }

        Thread.sleep(3000)
        val f0 = frames.get()
        val t0 = System.currentTimeMillis()
        val ss0 = Array(3) { snap(CLUSTER_CORES[it]) }
        Thread.sleep(PHASE_MS)
        val ss1 = Array(3) { snap(CLUSTER_CORES[it]) }
        val elapsed = System.currentTimeMillis() - t0
        val f1 = frames.get()
        stop = true
        dec.interrupt(); dec.join(3000)
        if (h >= 0) release(h)

        val ticks = LongArray(3); val cyc = DoubleArray(3); val mhz = DoubleArray(3); val tr = LongArray(3)
        for (i in 0 until 3) {
            val byF = HashMap<Long, Long>()
            for ((k, v) in ss1[i].byFreq) {
                val d = v - (ss0[i].byFreq[k] ?: 0L)
                if (d > 0) byF[k] = d
            }
            val s = Snap(byF, ss1[i].trans - ss0[i].trans)
            ticks[i] = s.total; cyc[i] = s.cycles; mhz[i] = s.avgMhz; tr[i] = s.trans
        }
        val fps = (f1 - f0) * 1000.0 / elapsed
        val online = runCatching {
            File("/sys/devices/system/cpu/online").readText().trim()
        }.getOrDefault("?")
        line("  %.1f fps（%d 帧 / %.0f ms）".format(fps, f1 - f0, elapsed.toDouble()))
        for (i in 0 until 3) {
            line("    %-4s 均值 %.0f MHz ｜ 驻留 %d ticks ｜ 交付 %.0f Mcyc ｜ 切换 %d".format(
                CLUSTER_NAMES[i], mhz[i], ticks[i], cyc[i] / 1e6, tr[i]))
        }
        line("    在线核: $online")
        Thread.sleep(4000)
        return Res(name, fps, f1 - f0, ticks, mhz, cyc, tr, 0)
    }

    // ------------------------------------------------------------------ 解码

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
            st = android.graphics.SurfaceTexture(0)
            st.setDefaultBufferSize(
                fmt.getInteger(android.media.MediaFormat.KEY_WIDTH),
                fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT)
            )
            surface = android.view.Surface(st)
            codec.configure(fmt, surface, null, 0)
            codec.start()
            val info = android.media.MediaCodec.BufferInfo()
            var fr = 0; var inD = false; var outD = false
            while (!outD && !stop) {
                if (!inD) {
                    val ii = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val b = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(b, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inD = true
                        } else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (oi >= 0) {
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outD = true
                    fr++; codec.releaseOutputBuffer(oi, true)
                }
            }
            fr
        } catch (t: Throwable) {
            Log.w(TAG, "decode 失败: ${t.javaClass.name}: ${t.message}"); -1
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { surface?.release() }; runCatching { st?.release() }
            runCatching { ex.release() }
        }
    }

    // ------------------------------------------------------------------ binder

    private fun getBinder(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, SVC) as? IBinder
    } catch (t: Throwable) { null }

    private fun acquire(d: Int, list: IntArray): Int {
        val b = getBinder() ?: return -1
        val da = Parcel.obtain(); val re = Parcel.obtain()
        return try {
            da.writeInterfaceToken(DESCRIPTOR); da.writeInt(d); da.writeIntArray(list)
            b.transact(TX_LOCK_ACQUIRE, da, re, 0); re.readException(); re.readInt()
        } catch (t: Throwable) { -1 } finally { re.recycle(); da.recycle() }
    }

    private fun release(h: Int): Int {
        if (h < 0) return -1
        val b = getBinder() ?: return -1
        val da = Parcel.obtain(); val re = Parcel.obtain()
        return try {
            da.writeInterfaceToken(DESCRIPTOR); da.writeInt(h)
            b.transact(TX_LOCK_RELEASE_HANDLER, da, re, 0); re.readException(); re.readInt()
        } catch (t: Throwable) { -1 } finally { re.recycle(); da.recycle() }
    }
}
