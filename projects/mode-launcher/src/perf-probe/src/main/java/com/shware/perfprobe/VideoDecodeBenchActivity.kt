package com.shware.perfprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.nio.ByteBuffer

/**
 * ★★★★★ **视频解码基准**（任务 AN）—— 直接量「解完整个视频要多久」。
 *
 * ## 为什么要自己写（**前两种方法都失败了**）
 *
 * | 方法 | 为什么不行 |
 * |---|---|
 * | `dumpsys media.metrics` 差分 | ★ **记录会过期丢弃**（`Records Discarded … by Expiration`）<br/>实测 `latency.n` 会**减少** ⇒ **不是单调计数器，无法差分** |
 * | 播放整个视频量墙钟 | 播放器会**丢帧维持音画同步** ⇒ 还是 30 秒，量不出解码能力 |
 *
 * ⇒ ★ **自己驱动 MediaCodec**:同一个文件、同一个解码器、**尽快解完所有帧**，
 *   量**总耗时**与**解码 fps**。这是确定性的、可控的。
 *
 * ## 用法
 *
 * 通过 Intent extra 传文件路径（默认 `/sdcard/Movies/av1test.mp4`），
 * 输出用 `ByteBuffer` 模式（**不需要 Surface**，纯解码能力）。
 *
 * ## 判读
 *
 * | 现象 | 含义 |
 * |---|---|
 * | **解码 fps 提高** | 性能模式对软解有用 |
 * | **无变化** | 解码器已经吃满，抬频率帮不上（或瓶颈在解码器内部） |
 */
class VideoDecodeBenchActivity : Activity() {

    companion object {
        private const val TAG = "VidDecode"

        private const val SVC = "vendor.perfservice"
        private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"
        private const val TX_LOCK_RELEASE_HANDLER = 2
        private const val TX_LOCK_ACQUIRE = 4

        private const val DEFAULT_FILE = "/sdcard/Android/data/com.shware.perfprobe/files/av1test.mp4"
        private const val TIMEOUT_US = 10_000L

        /** 性能模式（强力档）：三簇 MAX_FREQ ＋ 禁止电源关断 */
        private val LOCK_STRONG = intArrayOf(
            0x40800000, 2419, 0x40800100, 1785, 0x40800200, 2956, 0x40C00000, 1
        )

        /** ★ 均衡档：只抬频率下限 */
        private val LOCK_BALANCED = intArrayOf(
            0x40800000, 2419, 0x40800100, 1785, 0x40800200, 2956
        )

        /** ★ 对照：只禁电源关断，不抬频（看 PC 单独有没有用） */
        private val LOCK_PC_ONLY = intArrayOf(0x40C00000, 1)
    }

    private lateinit var out: TextView
    private val sb = StringBuilder()

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

    private fun line(s: String) {
        Log.i(TAG, s)
        runOnUiThread { sb.append(s).append('\n'); out.text = sb.toString() }
    }

    private data class R(val frames: Int, val ms: Long, val codec: String) {
        val fps: Double get() = if (ms <= 0) 0.0 else frames * 1000.0 / ms
    }

    private fun runBench() {
        val path = intent?.getStringExtra("file") ?: DEFAULT_FILE
        line("========== 视频解码基准（任务 AN）==========")
        line("文件: $path")
        line("")

        // 先探一次,拿到解码器名
        val probe = decodeOnce(path)
        if (probe == null) { line("✗ 解码失败（文件不存在或不支持）"); line("DONE"); return }
        line("解码器: ${probe.codec}")
        line("基准轮: ${probe.frames} 帧")
        line("")

        // ★ 交替 3 轮（无锁 / 有锁），抵消漂移
        line("交替 3 轮（无锁 / 有锁，每轮全量解码一遍）：")
        val offs = ArrayList<R>()
        val ons = ArrayList<R>()
        for (r in 1..3) {
            val a = decodeOnce(path) ?: continue
            offs.add(a)
            line("  第${r}轮  无锁  ${a.frames} 帧 / ${a.ms} ms  =  %.1f fps".format(a.fps))

            val h = acquire(120_000, LOCK_STRONG)
            Thread.sleep(2000)          // 让锁生效
            val b = decodeOnce(path) ?: continue
            release(h)
            ons.add(b)
            line("  第${r}轮  有锁  ${b.frames} 帧 / ${b.ms} ms  =  %.1f fps   (handle=$h)".format(b.fps))
            Thread.sleep(30_000)        // 等锁过期，避免污染下一轮"无锁"
        }

        line("")
        line("========== 汇总 ==========")
        val oa = offs.map { it.fps }.average()
        val na = ons.map { it.fps }.average()
        line("  无锁 均值: %.1f fps   （各轮 %s）".format(oa, offs.joinToString("/") { "%.1f".format(it.fps) }))
        line("  有锁 均值: %.1f fps   （各轮 %s）".format(na, ons.joinToString("/") { "%.1f".format(it.fps) }))
        line("  ⇒ 提升 %+.1f%%".format((na - oa) / oa * 100))
        line("")

        // ★★★★ 第二轮：拆解是哪个 opcode 在起作用（决定推荐哪一档）
        line("════════ 第二轮：拆解 opcode（交替 2 轮）════════")
        line("无锁 / 只禁电源关断 / 均衡(只抬频) / 强力(抬频+禁PC)")
        line("")
        val cfgs = listOf(
            Triple("无锁        ", null, ""),
            Triple("只禁PC      ", LOCK_PC_ONLY, "0x40C00000=1"),
            Triple("均衡(只抬频)", LOCK_BALANCED, "三簇 MAX_FREQ"),
            Triple("强力(抬频+PC)", LOCK_STRONG, "MAX_FREQ + PC"),
        )
        val acc = HashMap<String, ArrayList<Double>>()
        for (r in 1..2) {
            for ((name, list, _) in cfgs) {
                var h = -1
                if (list != null) { h = acquire(120_000, list); Thread.sleep(2000) }
                val x = decodeOnce(path)
                if (h >= 0) release(h)
                if (x != null) {
                    acc.getOrPut(name) { ArrayList() }.add(x.fps)
                    line("  第${r}轮  $name  %.1f fps".format(x.fps))
                }
                Thread.sleep(if (list != null) 32_000 else 2_000)
            }
        }
        line("")
        line("---- 拆解汇总 ----")
        val base = acc["无锁        "]?.average() ?: 1.0
        for ((n, v) in acc) {
            line("  %-14s %.1f fps   相对无锁 %+.1f%%   各轮 %s".format(
                n, v.average(), (v.average() - base) / base * 100,
                v.joinToString("/") { "%.1f".format(it) }))
        }
        line("")
        line("════════ 第三轮：★ 分布对照（质疑「轻量更慢」是不是噪声）════════")
        line("前两轮里 轻量 的各轮是 20.5 / 36.2 —— ★ 波动 76%。")
        line("⇒ 要判断它是不是【真的】比无锁慢，必须比【分布】而不是比均值。")
        line("")
        line("交替 5 轮 × 3 配置（无锁 / 轻量 / 只禁PC），每轮测一次完整解码的耗时：")
        val cfgs3 = listOf(
            Triple("无锁  ", null, ""),
            Triple("轻量  ", LOCK_BALANCED, "MAX_FREQ"),
            Triple("只禁PC", LOCK_PC_ONLY, "PC=1"),
        )
        val acc3 = HashMap<String, ArrayList<Double>>()
        for (r in 1..5) {
            for ((name, list, _) in cfgs3) {
                var h = -1
                if (list != null) { h = acquire(120_000, list); Thread.sleep(2000) }
                val x = decodeOnce(path)
                if (h >= 0) release(h)
                if (x != null) {
                    acc3.getOrPut(name) { ArrayList() }.add(x.fps)
                    line("  第${r}轮  $name  %5.1f fps".format(x.fps))
                }
                Thread.sleep(if (list != null) 32_000 else 2_000)
            }
        }
        line("")
        line("---- 分布对照 ----")
        for ((n, v) in acc3) {
            val sorted = v.sorted()
            line("  %-8s n=%d  均值 %.1f  最小 %.1f  最大 %.1f  极差 %.1f  各值 %s".format(
                n, v.size, v.average(), sorted.first(), sorted.last(),
                sorted.last() - sorted.first(),
                v.joinToString("/") { "%.1f".format(it) }))
        }
        val w = acc3["无锁  "] ?: emptyList()
        val l = acc3["轻量  "] ?: emptyList()
        if (w.isNotEmpty() && l.isNotEmpty()) {
            val overlapLow = maxOf(w.min(), l.min())
            val overlapHigh = minOf(w.max(), l.max())
            line("")
            line("  ★ 无锁 区间 [%.1f, %.1f]   轻量 区间 [%.1f, %.1f]".format(
                w.min(), w.max(), l.min(), l.max()))
            if (overlapLow <= overlapHigh) {
                line("  ⇒ ★★ 两者【区间重叠】([%.1f, %.1f]) ⇒ 「轻量更慢」【不能成立】，是噪声".format(
                    overlapLow, overlapHigh))
            } else {
                line("  ⇒ ★★ 两者【区间不重叠】⇒ 「轻量更慢」是【真实效应】")
            }
        }
        line("")
        line("DONE")
    }

    /** 全量解码一遍；返回帧数与耗时 */
    private fun decodeOnce(path: String): R? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var st: android.graphics.SurfaceTexture? = null
        var surface: android.view.Surface? = null
        return try {
            ex.setDataSource(path)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)

            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)

            /*
             * ★★★ 必须给一个 Surface —— 2026-09-13 踩过：
             * 传 `null`（ByteBuffer 输出模式）会抛 **`IllegalStateException`**，
             * 因为 `c2.android.av1.decoder` 这类软解器**只支持 Surface 输出**。
             *
             * 用 `SurfaceTexture(0)` 造一个**离屏** Surface —— 不需要任何界面，
             * 而且正好是"纯解码能力"的度量（渲染开销不计入）。
             */
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            st = android.graphics.SurfaceTexture(0)
            st.setDefaultBufferSize(w, h)
            surface = android.view.Surface(st)

            codec.configure(fmt, surface, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var frames = 0
            var inputDone = false
            var outputDone = false
            val t0 = System.currentTimeMillis()

            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val buf: ByteBuffer = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (oi >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    frames++
                    // ★ Surface 模式必须 render=true，否则解码器可能不推进
                    codec.releaseOutputBuffer(oi, true)
                }
            }
            val ms = System.currentTimeMillis() - t0
            R(frames, ms, codec.name)
        } catch (t: Throwable) {
            // ★ 上一版只打 message（是 null）⇒ 看不到真因 ⇒ 这版打全堆栈
            Log.w(TAG, "decodeOnce 失败: ${t.javaClass.name}: ${t.message}")
            Log.w(TAG, Log.getStackTraceString(t))
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
            runCatching { st?.release() }
            runCatching { ex.release() }
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
