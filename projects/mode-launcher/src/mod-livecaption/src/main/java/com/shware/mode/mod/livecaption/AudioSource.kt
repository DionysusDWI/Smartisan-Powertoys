package com.shware.mode.mod.livecaption

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log

/**
 * ★★ 音源 —— **两条路，语义完全不同**。
 *
 * | 路 | 拿到的声音 | 授权 | 对应什么 |
 * |---|---|---|---|
 * | [Kind.PLAYBACK] | **手机正在播放的媒体音频** | `MediaProjection`（每次会话要授） | ★ Android「实时字幕」用的就是这路 |
 * | [Kind.MIC] | 麦克风里的环境声（**默认**） | `RECORD_AUDIO`（授一次长期有效） | "现场收音转字幕" |
 *
 * ## ★★ 音频源类型（[SourceType]）—— 直接影响识别质量
 *
 * | 类型 | 特点 |
 * |---|---|
 * | [SourceType.VOICE_RECOGNITION] | ★ **为语音识别调过**（通常带降噪/AGC，且**关掉部分音效**）—— 默认选它 |
 * | [SourceType.MIC] | 原始主麦 |
 * | [SourceType.CAMCORDER] | 通常**指向摄像头那侧**的麦 |
 * | [SourceType.UNPROCESSED] | 不做任何处理（有则用） |
 *
 * ## ★★★ 输入设备（[pickDevice]）—— 这才是"降环境噪声"的正解
 *
 * **Android 不给普通 app 多通道麦阵**（`AudioDeviceInfo` 里那些立体声掩码是**混合过**的），
 * ⇒ **app 层做不了真正的波束成形**。
 *
 * **但坚果上有个更好的办法**：TNT GO 是 **USB 音频设备且自带麦克风**
 * （`AUDIO_DEVICE_IN_USB_HEADSET`）—— **它的位置天然就在你想要的方向上**。
 * ⇒ 用 `setPreferredDevice()` **直接选它**，比拿手机麦算波束成形干净得多。
 *
 * ⚠️ `setPreferredDevice` 只是**建议**，系统可以不采纳 ——
 * 所以 [routedDeviceName] 会**回读 `getRoutedDevice()`**，把实际用的是哪个报出来（能看能验）。
 */
class AudioSource(private val context: Context) {

    enum class Kind(val key: String, val label: String) {
        /** ★ 默认：麦克风 —— 免授权、任何 app 都不受限 */
        MIC("mic", "麦克风"),
        /** 进阶：抓系统内播放的声音（受 targetSdk≥29 规则约束） */
        PLAYBACK("playback", "媒体播放");

        companion object {
            fun of(s: String?) = entries.firstOrNull { it.key == s } ?: MIC
        }
    }

    /** 录音源类型 —— 见类注释 */
    enum class SourceType(val key: String, val label: String, val source: Int) {
        VOICE_RECOGNITION("voice", "语音识别优化（推荐）", MediaRecorder.AudioSource.VOICE_RECOGNITION),
        MIC("mic", "主麦克风（原始）", MediaRecorder.AudioSource.MIC),
        CAMCORDER("camcorder", "摄像侧麦克风", MediaRecorder.AudioSource.CAMCORDER),
        UNPROCESSED("raw", "不做处理（若有）", MediaRecorder.AudioSource.UNPROCESSED);

        companion object {
            fun of(s: String?) = entries.firstOrNull { it.key == s } ?: VOICE_RECOGNITION
        }
    }

    companion object {
        private const val TAG = "ModeMod/Caption"

        /** ★ 云端要的采样率 */
        const val SAMPLE_RATE = 16000

        /** 每次推 100 ms —— 太大会让字幕"一跳一跳"，太小则请求过密 */
        const val FRAME_BYTES = SAMPLE_RATE / 10 * 2   // 16bit 单声道

        /**
         * ★★★ **按设备实际支持的采样率开流**（2026-09-12 查出的关键 bug）。
         *
         * ## 为什么必须这样（TNT GO 的麦为什么"选得上但没声音"）
         *
         * 实测 `dumpsys media.audio_policy` 里 TNT GO 的输入档案：
         *
         * ```
         * Device 7:  id: 95   type: AUDIO_DEVICE_IN_USB_HEADSET
         *   tag name: USB-Audio - Smartisan TNT go
         *   Profiles: Profile 0: [dynamic format]
         *     sampling rates: 48000          ← ★★★ 只此一个
         * ```
         *
         * 而旧代码把 [SAMPLE_RATE]（**16000**）**写死**传给 `AudioRecord`。
         * 向一个只支持 48 kHz 的 USB 输入要 16 kHz 流：
         * **路由建得起来**（`setPreferredDevice` 返回 true、`getRoutedDevice()` 也确认了、
         * `dumpsys` 里状态是 `Active`）—— **但读出来永远是全零**。
         *
         * ⚠️ 这个现象极具误导性：**所有"选没选上"的检查都通过**，
         * 所以之前（W §10.3）把它误判成"这个麦本身送出来是静音"，就放弃了。
         * **真相是采样率不匹配。**
         *
         * ⇒ 修法：**问设备支持什么率，就开什么率**，再在本地**降采样**回 16 kHz 传云端。
         */
        private fun pickSampleRate(dev: AudioDeviceInfo?): Int {
            val rates = dev?.sampleRates ?: return SAMPLE_RATE
            if (rates.isEmpty()) return SAMPLE_RATE          // 空数组 = dynamic format，任意率都行
            if (rates.contains(SAMPLE_RATE)) return SAMPLE_RATE
            // ★ 优先选 16k 能【整除】的（降采样是整数倍：简单、无累积误差）
            rates.filter { it % SAMPLE_RATE == 0 }.minOrNull()?.let { return it }
            return SAMPLE_RATE                               // 实在没有 ⇒ 仍按 16k 试（设备也许会自己重采样）
        }

        /** 列出**真正的麦克风类**输入设备（排除通话回授 / FM / 虚拟输入等） */
        fun listMics(context: Context): List<AudioDeviceInfo> {
            val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
                // ⚠️ `TYPE_BACK_MIC` 是**隐藏常量**，公开 SDK 里没有 —— 不能写。
                //   实测坚果把两个内置麦都报成 `TYPE_BUILTIN_MIC`，
                //   靠公开的 `getAddress()`("bottom"/"back") 区分。
                when (it.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    -> true
                    else -> false
                }
            }
        }
    }

    private var record: AudioRecord? = null

    /**
     * ★ 实际开流的采样率（可能被设备抬高，如 USB 麦只支持 48000）。
     *
     * [SAMPLE_RATE] 是**送给云端**的率；[actualRate] 是**从硬件读**的率。
     * 两者不等时，[read] 内部会降采样。
     */
    var actualRate: Int = SAMPLE_RATE
        private set

    /** 降采样倍数（[actualRate] / [SAMPLE_RATE]）；1 = 不需要降 */
    private var ratio = 1

    /** 降采样用的原始字节缓冲（懒分配） */
    private var rawBuf = ByteArray(0)

    /** 实际路由到哪个设备（读 [AudioRecord.getRoutedDevice]）—— 用来验证 `setPreferredDevice` 有没有被采纳 */
    val routedDeviceName: String
        get() = record?.routedDevice?.let { "${it.productName}(id=${it.id})" } ?: "（无）"

    fun start(
        kind: Kind,
        projection: MediaProjection?,
        sourceType: SourceType = SourceType.VOICE_RECOGNITION,
        preferred: AudioDeviceInfo? = null,
    ): Result<Unit> = runCatching {
        stop()

        // ★★★ 先问设备支持什么采样率 —— 见 [pickSampleRate]
        val wantRate = if (kind == Kind.MIC) pickSampleRate(preferred) else SAMPLE_RATE
        ratio = if (wantRate > SAMPLE_RATE && wantRate % SAMPLE_RATE == 0) wantRate / SAMPLE_RATE else 1
        actualRate = if (ratio > 1) wantRate else SAMPLE_RATE

        val minBuf = AudioRecord.getMinBufferSize(
            actualRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, FRAME_BYTES * ratio * 8)

        val rec = when (kind) {
            Kind.MIC -> AudioRecord(
                sourceType.source,
                actualRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )

            Kind.PLAYBACK -> {
                val mp = projection
                    ?: throw IllegalStateException("播放捕获要 MediaProjection —— 还没授权（去设置里点「授权播放捕获」）")
                val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setBufferSizeInBytes(bufSize)
                    .build()
            }
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException(
                "AudioRecord 初始化失败（state=${rec.state}）—— " +
                        "多半是没授权，或本 ROM 不支持这条路"
            )
        }

        // ★ 只在麦克风模式下指定输入设备（播放捕获没有"设备"可选）
        if (kind == Kind.MIC && preferred != null) {
            val ok = rec.setPreferredDevice(preferred)
            Log.i(
                TAG,
                "setPreferredDevice(${preferred.productName} id=${preferred.id}) → $ok" +
                        "（该设备支持率 = ${preferred.sampleRates.joinToString().ifEmpty { "dynamic(任意)" }}）"
            )
        }

        record = rec
        rec.startRecording()
        Log.i(
            TAG,
            "✓ 音源已开：${kind.label} / ${sourceType.label}  " +
                    "硬件 ${actualRate}Hz → 送云端 ${SAMPLE_RATE}Hz（降采样 1/${ratio}）单声道 PCM16" +
                    "｜实际路由 = ${routedDeviceName}"
        )
    }

    /**
     * ★ 阻塞读**一片 16 kHz 的 PCM**。返回写入 `buf` 的字节数（0 或负值 = 没读到 / 出错）。
     *
     * ⚠️ 硬件率高于 16 kHz 时（如 TNT GO 的 48 kHz），内部**先读原始再降采样**，
     * 所以**调用方拿到的永远是 16 kHz** —— 上层代码无需关心。
     */
    fun read(buf: ByteArray): Int {
        val r = record ?: return 0
        if (ratio == 1) return runCatching { r.read(buf, 0, buf.size) }.getOrDefault(0)

        // 需要 ratio 倍的原始字节
        val need = buf.size * ratio
        if (rawBuf.size < need) rawBuf = ByteArray(need)
        val n = runCatching { r.read(rawBuf, 0, need) }.getOrDefault(0)
        if (n <= 0) return 0

        // 每 ratio 个采样取平均（顺带起个简易抗混叠的低通作用，比直接抽点干净）
        val outSamples = n / 2 / ratio
        var o = 0
        for (i in 0 until outSamples) {
            var acc = 0
            val base = i * ratio * 2
            for (k in 0 until ratio) {
                val idx = base + k * 2
                acc += ((rawBuf[idx + 1].toInt() shl 8) or (rawBuf[idx].toInt() and 0xFF)).toShort().toInt()
            }
            val v = acc / ratio
            buf[o++] = (v and 0xFF).toByte()
            buf[o++] = ((v shr 8) and 0xFF).toByte()
        }
        return o
    }

    fun stop() {
        runCatching {
            record?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            record?.release()
        }
        record = null
    }
}
