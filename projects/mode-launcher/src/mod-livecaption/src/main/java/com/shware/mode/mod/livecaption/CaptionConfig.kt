package com.shware.mode.mod.livecaption

import android.content.Context

/**
 * 实时字幕的本地配置。
 *
 * ⚠️ **API Key 的优先级**：设置界面里填的 > 构建时注入的 [BuildConfig.DASHSCOPE_API_KEY]。
 *
 * > 构建时注入的那份**在 APK 里**（见 `build.gradle.kts` 的注释）——
 * > 本项目不对外发布，可接受；但**别把 APK 发给别人**。
 */
class CaptionConfig(context: Context) {

    private val sp = context.getSharedPreferences("livecaption", Context.MODE_PRIVATE)

    companion object {
        /** 实测可用的 realtime 模型（见 [归档 08](../../../../../../../../.ref/Search-Results/08-realtime-asr.md)） */
        val MODELS = listOf(
            "qwen3-asr-flash-realtime",
            "qwen3-asr-flash-realtime-2026-02-10",
            "qwen3-livetranslate-flash-realtime",
        )

        private const val K_KEY = "apiKey"
        private const val K_MODEL = "model"
        private const val K_SRC = "source"
        private const val K_STASH = "showStash"
        private const val K_STYPE = "sourceType"
        private const val K_DEV = "deviceId"
        private const val K_VAD = "vadEnabled"
        private const val K_VAD_TH = "vadThreshold"
        private const val K_VAD_IDLE = "vadIdleMs"
        private const val K_FORCE_SEG = "forceSegment"
        private const val K_SEG_MS = "maxSegmentMs"
        private const val K_SAVE = "saveTranscript"
        private const val K_SHOW_CHARS = "showChars"
    }

    /** API Key：设置里填的优先，否则用构建时注入的 */
    var apiKey: String
        get() = sp.getString(K_KEY, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DASHSCOPE_API_KEY
        set(v) {
            sp.edit().apply { if (v.isBlank()) remove(K_KEY) else putString(K_KEY, v.trim()) }.apply()
        }

    /** 设置里**显式**填过 key 吗（用来在界面上区分"在用哪个"） */
    val hasOwnKey: Boolean get() = !sp.getString(K_KEY, null).isNullOrBlank()

    var model: String
        get() = sp.getString(K_MODEL, MODELS.first()) ?: MODELS.first()
        set(v) { sp.edit().putString(K_MODEL, v).apply() }

    /** 音源：麦克风（默认，免授权）/ 播放捕获（进阶，受 targetSdk≥29 规则约束） */
    var source: AudioSource.Kind
        get() = AudioSource.Kind.of(sp.getString(K_SRC, null))
        set(v) { sp.edit().putString(K_SRC, v.key).apply() }

    /** 录音源类型（语音识别优化 / 原始 / 摄像侧 / 不处理） */
    var sourceType: AudioSource.SourceType
        get() = AudioSource.SourceType.of(sp.getString(K_STYPE, null))
        set(v) { sp.edit().putString(K_STYPE, v.key).apply() }

    /**
     * 指定的输入设备 id；**-1 = 自动**。
     *
     * ★ 自动的取舍见 [resolveDevice]：**优先 TNT GO 的 USB 麦克风**。
     */
    var deviceId: Int
        get() = sp.getInt(K_DEV, -1)
        set(v) { sp.edit().putInt(K_DEV, v).apply() }

    /**
     * 解析出真正要用的输入设备。
     *
     * ## 自动策略：**优先手机内置麦**（2026-09-12 实测后定的）
     *
     * ```
     * 0. 用户在设置里显式选过设备  ⇒ 就用它
     * 1. 手机内置麦（TYPE_BUILTIN_MIC）
     * 2. 否则交给系统
     * ```
     *
     * ### ★★★ TNT GO 的 USB 麦：查清了两件事，但**结论仍是"不用它做默认"**
     *
     * 实测（同一房间、相隔数分钟、**同一把尺子**= `dumpsys media.audio_flinger` 的硬件 dB 表）：
     *
     * | 麦 | 硬件率 | dB 表峰值到达 | 直方图跨度 |
     * |---|---|---|---|
     * | **手机内置**（id=18） | 16000 | **-41.7 dB** | -36.8 ～ -72.8 |
     * | **TNT GO**（id=95） | 48000 | -51.3 dB | -51.1 ～ -69.5 |
     *
     * **① ★ 旧结论"送出来是静音"不准确** —— 两路**都不是数字静音**，
     * TNT GO 那路有真实波动（-51 ～ -69 dB）。
     *
     * **② ★★ 但发现并修掉了一个真 bug：采样率不匹配。**
     * 该 USB 输入的档案**只声明 48000 Hz**（`dumpsys media.audio_policy`），
     * 而旧代码把 16000 **写死** ⇒ 路由此前根本建不正确。
     * 已修：见 [AudioSource.pickSampleRate]（按设备支持率开流 + 本地降采样回 16k）。
     *
     * **③ 结论**：即便修好采样率，**TNT GO 的麦仍比内置麦低约 10 dB** ⇒ **不做默认**，
     * **保留在设置里可手动选**（想用随时用）。
     *
     * > ⚠️ **仍未定论**：两者的差异有多少是"麦本身弱"、多少是"当时扬声器没在放音"——
     * > 需要**受控声源**（正对着放一段已知语音）才能分清。**目前没有定论。**
     */
    fun resolveDevice(context: android.content.Context): android.media.AudioDeviceInfo? {
        val mics = AudioSource.listMics(context)
        deviceId.takeIf { it >= 0 }?.let { want ->
            mics.firstOrNull { it.id == want }?.let { return it }
        }
        // 自动：优先内置麦（坚果报 address=bottom 那个）
        mics.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { return it }
        return null   // 交给系统
    }

    /** 是否"自动" */
    val isAutoDevice: Boolean get() = deviceId < 0

    /** 是否显示"识别中的临时文本"（`stash`）—— 关掉更稳，开着更"实时" */
    var showStash: Boolean
        get() = sp.getBoolean(K_STASH, true)
        set(v) { sp.edit().putBoolean(K_STASH, v).apply() }

    /** key 的掩码展示（不泄漏全量） */
    fun maskedKey(): String {
        val k = apiKey
        return if (k.isBlank()) "（未设置）"
        else "${k.take(6)}…${k.takeLast(4)}  (${if (hasOwnKey) "设置里填的" else "构建时注入的"})"
    }

    // ------------------------------------------------------------------ ★ VAD 闸门（省调用量）

    /**
     * ★★ **空闲休眠**（默认开）—— 关掉就一直上传。
     *
     * ## 模型（2026-09-12 重构，用户指定）
     *
     * > 「只需要判断**什么时候语音开始、什么时候语音结束**，然后**开始时立即激活**，
     * > 　**结束后等待 15 秒没有新语音就停止上传**就可以了」
     *
     * ```
     * 电平 ≥ [vadThreshold]        ⇒ ★ 立即激活
     * 激活期间                     ⇒ ★ 【全传】—— 连静音一起传（云端靠它断句）
     * 距上次语音 > [vadIdleMs]     ⇒ 休眠（不上传，只维护预滚）
     * ```
     *
     * ⚠️ **旧的"逐帧门限"模型已被推翻**（任务 W/X 那版）：它按帧掐掉静音，
     * 导致云端 `server_vad` **永远收不到静音** ⇒ **永不断句**
     * （实测：106 条转写、**0 条定稿**）。详见 [CaptionService.pump] 的注释。
     */
    var vadEnabled: Boolean
        get() = sp.getBoolean(K_VAD, true)
        set(v) { sp.edit().putBoolean(K_VAD, v).apply() }

    /**
     * 判定"**有语音**"的门限，**百分比（0–100）**，对应 PCM16 满量程 32767 的占比。
     *
     * ## ★★ 默认从 4% 降到 2%（2026-09-12 重构）
     *
     * 新模型下门限**只决定"何时醒"**，不再决定"每一片传不传" ⇒
     * **误激活的代价很便宜**（顶多多传一会儿，15 秒没新语音就自动歇）。
     *
     * > ⚠️ 实测教训：旧模型下门限 4%，而**有语音时电平只有 2%**
     * > ⇒ 闸门一直睡着，**有声音也不传**，卡片上还显示得像"没人说话"。
     *
     * ⇒ **宁可敏感。** 想省调用量再往上调，用设置界面的电平表对着调。
     */
    var vadThreshold: Int
        get() = sp.getInt(K_VAD_TH, 2)
        set(v) { sp.edit().putInt(K_VAD_TH, v.coerceIn(0, 60)).apply() }

    /**
     * ★★ **停多久没新语音就停止上传**（ms，默认 15000）。
     *
     * 用户 2026-09-12 指定的模型：
     * > 「**开始时立即激活**，**结束后等待 15 秒没有新语音就停止上传**就可以了」
     *
     * ⚠️ 激活期间是**连静音一起传**的 —— 那是有意的：
     * 云端 `server_vad` **靠静音判断"这句说完了"**，掐掉静音会让它永不断句。
     *
     * ⇒ 这个值**只要明显大于云端的 `silence_duration_ms`（我们下发成 500ms）**就够，
     * 15 秒是很宽裕的余量。
     */
    var vadIdleMs: Int
        get() = sp.getInt(K_VAD_IDLE, 15000)
        set(v) { sp.edit().putInt(K_VAD_IDLE, v.coerceIn(3000, 120000)).apply() }

    /**
     * ★★★ **主动断句**（默认开）—— 段落硬上限，到点主动灌静音逼云端断句。
     *
     * ## 为什么必须有（2026-09-12 实测得出）
     *
     * 闸门的「静音尾巴」只在**本地 VAD 判出"语音结束"**时才发。
     * 但**连续有声**（视频/播客/音乐）下电平一直高于门限 ⇒ 本地 VAD **永不休眠**
     * ⇒ **一分钟静音都没送过** ⇒ 云端 `server_vad` **永不断句**。
     *
     * **实测症状**：106 条转写、**0 条定稿**，字幕成了一条无限增长的长段落。
     * ⚠️ 而「连续有声」恰恰是**实时字幕最主要的场景**。
     *
     * ⇒ 既然音频流由我们控制，就**按需制造静音**：推满 [maxSegmentMs] 就灌
     * [CaptionService.INJECT_SILENCE_MS] 的数字静音，逼云端断句。
     *
     * **代价**：约 `800ms / 10s` = **8% 额外调用量**，换字幕按句滚动。可关。
     */
    var forceSegment: Boolean
        get() = sp.getBoolean(K_FORCE_SEG, true)
        set(v) { sp.edit().putBoolean(K_FORCE_SEG, v).apply() }

    /** 段落硬上限（ms）—— 超过就主动注入静音强制断句 */
    var maxSegmentMs: Int
        get() = sp.getInt(K_SEG_MS, 10000)
        set(v) { sp.edit().putInt(K_SEG_MS, v.coerceIn(4000, 60000)).apply() }

    // ------------------------------------------------------------------ ★ 落盘 + 卡片显示窗口

    /**
     * ★★★ **把已定稿的转写实时写入文件**（默认开）。
     *
     * 见 [CaptionLog] —— 字幕只活在内存里的话，服务一停/一崩**全没了**。
     */
    var saveTranscript: Boolean
        get() = sp.getBoolean(K_SAVE, true)
        set(v) { sp.edit().putBoolean(K_SAVE, v).apply() }

    /**
     * ★★★ 卡片上**只渲染末尾多少个字**（默认 160）。
     *
     * ## 为什么必须有（用户 2026-09-12 提出）
     *
     * > 「只展示最新的多少字内容，以**避免时间长了之后实时字幕窗口性能下降**」
     *
     * 云端的 `text + stash` 是**当前这一整段**的全文。段落没断句时可以涨到几千字 ——
     * 每个 `Partial` 事件（**每秒好几次**）都把它整份塞进 `TextView` 重新排版，
     * 长会话下就是持续的无谓开销。
     *
     * ⇒ 只留末尾 N 字（配合 `TruncateAt.START`，卡片始终跟着**最新**的字走）。
     * 全文由 [CaptionLog] 承接，**一个字都不会丢**。
     */
    var showChars: Int
        get() = sp.getInt(K_SHOW_CHARS, 160)
        set(v) { sp.edit().putInt(K_SHOW_CHARS, v.coerceIn(40, 600)).apply() }
}
