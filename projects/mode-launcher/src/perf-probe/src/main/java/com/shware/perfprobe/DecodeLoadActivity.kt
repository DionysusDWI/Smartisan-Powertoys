package com.shware.perfprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView

/**
 * ★★★★★ **纯解码负载**（任务 AN · 第 5 步）—— 给「唤醒延迟」假说找**直接证据**。
 *
 * ## 它只做一件事
 *
 * **按指定时长持续软解 AV1**，不打任何频率锁 ——
 * **锁由外部（adb shell 的 `service call`）控制**。
 *
 * 这样 shell 就能在负载期间读 `/proc/(media.swcodec 的 pid)/task/(tid)/schedstat`。
 *
 * ## 为什么这个观测量是「直接证据」
 *
 * `/proc/<pid>/schedstat` 的格式：
 * ```
 *   <运行时间 ns>   <★★在运行队列上【等待】的时间 ns>   <时间片数>
 * ```
 *
 * ★ **第 2 个字段就是"线程已经就绪、但还没被放到 CPU 上"的累计时间** ——
 * 这正是「唤醒延迟」的定义。
 *
 * **假说**：禁用 CPU 电源关断（`POWER COLLAPSE = 1`）⇒ 核不会深度断电
 * ⇒ **线程不需要等核被唤醒** ⇒ **第 2 个字段应该显著变小**。
 *
 * ## 用法（shell 侧）
 * ```bash
 * adb shell am start -n com.shware.perfprobe/.DecodeLoadActivity \
 *     --ei ms 40000          # 解 40 秒
 * # 负载期间从 shell 采样:
 * adb shell 'awk "{w+=\$2; r+=\$1; n++} END{print r, w, n}" /proc/$(pidof media.swcodec)/task/(tid)/schedstat'
 * ```
 */
class DecodeLoadActivity : Activity() {

    companion object {
        private const val TAG = "DecodeLoad"
        private const val VIDEO = "/sdcard/Android/data/com.shware.perfprobe/files/av1test.mp4"
        private const val TIMEOUT_US = 10_000L
        private const val DEFAULT_MS = 40_000L
    }

    @Volatile private var stop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#DDDDDD"))
            setBackgroundColor(Color.parseColor("#FF101010"))
            typeface = Typeface.MONOSPACE
            setPadding(40, 40, 40, 40)
        }
        setContentView(tv)

        val ms = intent?.getIntExtra("ms", DEFAULT_MS.toInt())?.toLong() ?: DEFAULT_MS
        Thread {
            Thread.sleep(1500)      // 给 shell 一点时间开始采样
            Log.i(TAG, "@@LOAD_START@@ ${System.currentTimeMillis()} 时长 ${ms}ms")
            runOnUiThread { tv.text = "解码负载中… ${ms / 1000}s" }
            val frames = java.util.concurrent.atomic.AtomicInteger(0)
            stop = false
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end && !stop) {
                val n = decodeOnce(VIDEO)
                if (n > 0) frames.addAndGet(n) else Thread.sleep(200)
            }
            Log.i(TAG, "@@LOAD_END@@ ${System.currentTimeMillis()} 共 ${frames.get()} 帧")
            runOnUiThread { tv.text = "完成：${frames.get()} 帧" }
            Thread.sleep(2500)
            finish()
        }.start()
    }

    override fun onDestroy() { stop = true; super.onDestroy() }

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
}
