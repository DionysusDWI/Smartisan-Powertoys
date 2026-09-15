package com.shware.mode.mod.livecaption

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.IntentFilter
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ★★★ MOD：**实时字幕** —— 抓音源 → 云端实时识别（DashScope）→ 在 TNT 屏上滚字幕。
 *
 * ## ⚠️ 先说清楚：这台机器**没有「系统实时字幕」可劫持**
 *
 * ```
 * $ dumpsys captioning  →  Can't find service: captioning
 * ```
 * 这个 ROM 根本没编进 Android 的 captioning 服务 ⇒ **没有对象可以 hook**。
 * ⇒ 本 mod **自己成为实时字幕**：抓**同一路音频**（Live Caption 用的就是媒体播放音频）
 * → 送云端 → 自己画。
 *
 * ## 数据流
 *
 * ```
 * AudioSource(16k mono PCM16, 每 100ms 一片)
 *   → AsrClient.append(base64)             [wss://dashscope…/api-ws/v1/realtime]
 *   → conversation.item.input_audio_transcription.text / .completed
 *   → 字幕卡片（text=已定稿 / stash=识别中的临时文本）
 * ```
 *
 * ## 契约四件套（照 [com.shware.mode.mod.ModContract] 抄，见 `mod-hello`）
 *
 * 1. `onStartCommand` 里 5 秒内 `startForeground`
 * 2. `createDisplayContext` 拿 WindowManager 3. `TYPE_APPLICATION_OVERLAY` 4. `onDestroy` 撤窗
 */
class CaptionService : Service() {

    private object C {
        const val META_TOUCH = "com.shware.mode.MOD_TOUCH"
        const val META_TARGET = "com.shware.mode.MOD_TARGET"
        const val TOUCH_SELF = "self"
        const val TARGET_PHONE = "phone"
    }

    companion object {
        private const val TAG = "ModeMod/Caption"
        private const val CHANNEL_ID = "mode_mod_caption"
        // ★ 2006（原来 2004）。★ 通知 ID 作用域是【包】不是进程 ——
        //   2004 与 mod-perfmode 撞了，两者同时跑会互相顶掉通知。
        //   全包分配表登记在 :app 的 ModContract「二·补」；
        //   ⚠️ mod 刻意不依赖 :app ⇒ 值只能手抄，改表不等于改这里。
        private const val NOTIF_ID = 2006
        private const val FIRST_VIRTUAL_DISPLAY_ID = 100000
        private const val CARD_WIDTH_DP = 420

        /** 自动重连最多试几次（指数退避 1s→2s→4s→8s→15s 封顶） */
        private const val MAX_RECONNECT = 6

        /**
         * ★★★ 到段落上限时**主动灌进去**的静音长度（ms）。
         *
         * **必须 > 服务端 `silence_duration_ms`**（本 mod 下发的是 500，见
         * `AsrClient.SILENCE_DURATION_MS`），留余量保证一定触发断句 ⇒ 800 / 500 = **1.6× 余量**。
         *
         * 代价：约 `800ms / 10s` = **8% 额外调用量**，换字幕按句滚动。
         */
        private const val INJECT_SILENCE_MS = 800

        /**
         * ★★ `AudioRecord` 看门狗的触发窗口数（每个心跳窗 = 5 秒）。
         *
         * **36 × 5 s = 3 分钟**。判据是峰值【**恰好为 0**】——
         * 真实环境噪声几乎不可能连续 3 分钟精确为 0，所以误判概率极低；
         * 而**误重建的代价也很小**（只是重开一次 `AudioRecord`，WS 不动）。
         */
        private const val ZERO_BEATS_TO_REBUILD = 36

        /**
         * ★ 已定稿那句话最多留多少字（正常 10s 一段远小于这个数）。
         *
         * 未定稿那一路的长度上限见 [CaptionConfig.showChars]（设置界面可调）。
         */
        private const val FINAL_MAX_CHARS = 120

        /**
         * ★ [SetupActivity] 把 **MediaProjection 的授权结果**塞进来。
         *
         * MediaProjection 对象本身不能过 Intent，但**授权结果的 `Intent` 是 Parcelable** ——
         * 可以由 Service 自己 `getMediaProjection(code, data)` 换出来。
         */
        const val EXTRA_MP_DATA_KEY = "mp_data"
        const val EXTRA_MP_CODE_KEY = "mp_code"

        // ---------------------------------------------------------------- ★★ 测试声源

        /**
         * ★★★ **让 mod 自己放一段音** —— 用于"某个麦克风到底能不能收声"这类验证。
         *
         * ## 为什么必须有（2026-09-12 踩坑得出）
         *
         * 验麦克风时**必须有受控声源**，而 **`adb` 放不了音**：
         * `am start -a VIEW -d file:///…wav` 只弹 `ResolverActivity`；
         * 走 MediaStore 的 `content://` **还是弹 Resolver，而且卡在前台不动**。
         *
         * ⚠️ 更坑的是**判"在不在放"也没有可靠信号**：
         * 「屏幕上有画面」不等于「扬声器在出声」；
         * `dumpsys audio` 里的 `state:started` **也是假的**
         * （实测有个 player 挂了半小时 `started`，其实早停了）。
         *
         * ⇒ 让 mod **自己当声源**：**同一个 app 既放又收**，
         * 声学上走的就是真实路径（扬声器 → 空气 → 麦克风）。
         *
         * ```bash
         * adb shell am start-foreground-service -n com.shware.mode.mod.livecaption/.CaptionService \
         *     --es play_wav /sdcard/Android/data/com.shware.mode.mod.livecaption/files/test.wav
         * ```
         *
         * ★ **主用它**：放**已知内容的语音** ⇒ **既看电平（有没有收到），又看字幕（收得对不对）**。
         */
        const val EXTRA_PLAY_WAV = "play_wav"

        /**
         * 兜底入口：放一段内置测试音（`ToneGenerator`），**不需要任何外部文件**。
         *
         * ```bash
         * adb shell am start-foreground-service -n …/.CaptionService --ez play_test true
         * ```
         *
         * ⚠️ 音是**纯音**，识别不出字 —— 只能验"麦克风活着没"，验不了识别质量。
         */
        const val EXTRA_PLAY_TEST = "play_test"

        // ---------------------------------------------------------------- ★ 电平（供设置界面显示）

        /**
         * 实时电平，**百分比 0–100**（PCM16 满量程 32767 的占比）。
         *
         * 用途**一箭三雕**：① 挑麦克风（哪个麦收得清楚）② 定 VAD 阈值（看底噪落在哪）
         * ③ 看有没有在传（服务停了就是 0）。
         */
        @Volatile
        var lastLevel: Int = 0

        /** 当前这一片算不算"有语音"（按 VAD 阈值判的）—— 设置界面画门限线用 */
        @Volatile
        var lastIsSpeech: Boolean = false

        /** 本轮 VAD 闸门**省下了多少秒**音频（没上传的） */
        @Volatile
        var savedSeconds: Int = 0

        /**
         * ★★ **云端已计费的音频秒数** —— 直接就是账单口径。
         *
         * ⚠️ 实测发现（2026-09-12）：服务端回推的 `usage.duration`
         * **是【整场会话累计】，不是【这一句的时长】** ——
         * 连续 10 条定稿的值单调递增（40.0 → 43.0 → 45.0 → … → 77.0）。
         *
         * ⇒ 别把它当"句子多长"用（原先日志里就标错了），它**就是花了多少钱**。
         * 这就是「省调用量」这件事**唯一可信的度量**：跟 [savedSeconds] 一对比就知道闸门值不值。
         */
        @Volatile
        var billedSeconds: Double = 0.0

        /** 转写文件的现状摘要（[CaptionLog.summary]）—— 供设置界面显示 */
        @Volatile
        var logSummary: String = ""
    }

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var doneView: TextView? = null
    private var liveView: TextView? = null

    private lateinit var cfg: CaptionConfig
    private lateinit var displayManager: DisplayManager
    private lateinit var audio: AudioSource
    private val handler = Handler(Looper.getMainLooper())

    /** 读音频的线程 —— 必须离开主线程（`AudioRecord.read` 是阻塞的） */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "caption-audio").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)

    private var asr: AsrClient? = null
    private var projection: MediaProjection? = null

    /** ★ 转写落盘（见 [CaptionLog]）—— 服务启动时开一个带时间戳的新文件 */
    private var log: CaptionLog? = null

    /** 上一句的定稿 */
    @Volatile private var lastFinal = ""

    /**
     * ★ 云端刚断完句（收到 `completed`）—— [pump] 看到就开新的一段。
     *
     * 跨线程：[onAsrEvent] 在主线程写，[pump] 在 io 线程读。
     */
    @Volatile private var segmentClosed = false

    private var touchMode = "none"
    private var target = "tnt"

    private var startedAt = 0L

    /** 连续多少次心跳是"全静音"（用来在卡片上提示原因） */
    private var silentBeats = 0

    /** 状态行最近一次的内容（1 秒的秒表 ticker 靠它重渲染，不改变语义） */
    private var lastStatus: String? = null
    private var lastSub: String? = null

    /** 重连计数（退避用） */
    private var reconnectAttempts = 0

    /** ★ 1 秒的秒表 —— 只刷新状态行（不碰字幕行） */
    private val statusTicker = object : Runnable {
        override fun run() {
            // 窗口还在才刷（`root` 是"挂上了没"的判据）
            if (root != null) lastStatus?.let { render(it, lastSub, null) }
            handler.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ 生命周期
    // ═══════════════════════════════════════════════════════════════════
    // ★ 图层控制（任务 AP / AP6）—— 响应宿主的 LAYER_CONTROL 广播
    //
    // 宿主**不拥有**别人的窗口（Android 的硬边界，不是没实现），
    // 所以它在图层管理页上按的「隐藏」只能是**请求**。这一段就是"听从这个请求"。
    // 契约见 .paper/plans/AP-图形化UI与对外API.md §4.3。
    //
    // ⚠️ 只改 visibility，**绝不 removeView** ——
    //    窗口的位置/大小由本 mod 自己管；removeView 之后再显示要重建窗口，
    //    代价大且容易留下错状态。
    //
    // ⚠️ `layerId` 必须和本模块 manifest 里的 `MOD_ID` **保持一致**。
    //    （宿主广播里带的 target 就是那个 id。改了一处忘了另一处，
    //      症状是"隐藏按钮点了没反应" —— 而不会报任何错。）
    // ═══════════════════════════════════════════════════════════════════

    private val layerId = "live.caption"
    private var layerRx: android.content.BroadcastReceiver? = null

    private fun registerLayerControl() {
        if (layerRx != null) return
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val target = i?.getStringExtra(EXTRA_LAYER_TARGET).orEmpty()
                if (target.isNotEmpty() && target != layerId) return   // 空 = 广播给所有
                val vis = when (i?.getStringExtra(EXTRA_LAYER_OP).orEmpty()) {
                    "hide" -> false
                    "show" -> true
                    "toggle" -> root?.visibility != android.view.View.VISIBLE
                    else -> return
                }
                root?.visibility =
                    if (vis) android.view.View.VISIBLE else android.view.View.GONE
                android.util.Log.i("ModeMod/Layer", "$layerId ← ${i?.getStringExtra(EXTRA_LAYER_OP)}（$vis）")
            }
        }
        ContextCompat.registerReceiver(
            this, rx, IntentFilter(ACTION_LAYER_CONTROL),
            // ★ F5 修复：宿主与 mod **同 UID 同 APK**（只是不同进程）⇒ 同道广播，
            //    用 NOT_EXPORTED 就够，**不需要**对外暴露这个 receiver。
            //    ⚠️ 与 BATTERY_CHANGED 不同 —— 那个是**系统**广播，必须 EXPORTED。
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        layerRx = rx
        android.util.Log.i("ModeMod/Layer", "$layerId 已登记图层控制监听")
    }

    private fun unregisterLayerControl() {
        layerRx?.let { runCatching { unregisterReceiver(it) } }
        layerRx = null
    }




    override fun onCreate() {

        registerLayerControl()
        super.onCreate()
        cfg = CaptionConfig(this)
        displayManager = getSystemService(DisplayManager::class.java)
        audio = AudioSource(this)
        touchMode = readOwnMeta(C.META_TOUCH).takeIf { it == C.TOUCH_SELF } ?: "none"
        target = readOwnMeta(C.META_TARGET).ifEmpty { "tnt" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        // ★ 如果这次带着 MediaProjection 的授权结果进来，先把它接住
        intent?.let { takeMediaProjection(it) }

        if (root == null) {
            attachOverlay(targetDisplayId())
            startedAt = System.currentTimeMillis()
            if (cfg.saveTranscript) log = CaptionLog(this)
            startPipeline()
            handler.postDelayed(statusTicker, 1000)   // ★ 让状态行的时长真的走起来
        }

        // ★★ 测试声源入口 —— 见 [EXTRA_PLAY_WAV] / [EXTRA_PLAY_TEST]
        //    放在 overlay 守卫【之后】：这样卡片已经挂上，播放状态能显示出来
        //    （服务已在跑时 `root != null`，同样能走到这里）
        intent?.getStringExtra(EXTRA_PLAY_WAV)?.let { playWav(it) }
        if (intent?.getBooleanExtra(EXTRA_PLAY_TEST, false) == true) playTestTone()

        return START_STICKY
    }

    override fun onDestroy() {

        unregisterLayerControl()
        running.set(false)
        handler.removeCallbacks(statusTicker)
        asr?.close(); asr = null
        audio.stop()
        runCatching { projection?.stop() }
        projection = null
        log?.close(); log = null
        logSummary = ""
        io.shutdownNow()
        runCatching { root?.let { wm?.removeViewImmediate(it) } }
        root = null; wm = null; statusView = null; doneView = null; liveView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 管线

    private fun startPipeline() {
        // ① 检查前置条件，缺什么就在卡片上说清楚（别静默失败）
        val needPermission = cfg.source == AudioSource.Kind.MIC &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            render("没授权麦克风", "去「实时字幕」设置里点一下授权", null)
            return
        }
        if (cfg.source == AudioSource.Kind.PLAYBACK && projection == null) {
            render("没授权播放捕获", "去「实时字幕」设置里点「授权播放捕获」", null)
            return
        }
        if (cfg.apiKey.isBlank()) {
            render("没配 API Key", "去设置里填 DashScope 的 key（或构建时注入 DASHSCOPE_API_KEY）", null)
            return
        }

        // ② 连云端
        render("正在连接云端…", "DashScope ${cfg.model}", null)
        val client = AsrClient(cfg.apiKey, cfg.model) { ev -> handler.post { onAsrEvent(ev) } }
        asr = client
        client.connect()

        // ③ 开音源 + 推流
        audio.start(cfg.source, projection, cfg.sourceType, cfg.resolveDevice(this))
            .onSuccess { running.set(true); io.execute { pump(client) } }
            .onFailure { render("音源打不开", it.message ?: "未知", null) }
    }

    /**
     * 读音频 → **过语音起止闸门** → 推流。**阻塞循环，跑在 [io] 线程**。
     *
     * ## ★★★★★ 模型（2026-09-12 重构，用户指定）
     *
     * **不是"逐帧看门限决定传不传"，而是"看语音的起止决定整段传不传"**：
     *
     * ```
     * 电平 ≥ 门限        ⇒ ★ 立即激活（当前若在休眠则唤醒，先补 300ms 预滚防切字头）
     * 激活期间           ⇒ ★ 【全传】—— 连静音一起传
     * 距上次语音 > 15s   ⇒ 休眠（停止上传，只维护预滚）
     * ```
     *
     * ### 为什么"静音也传"是有意的
     *
     * 云端是 `server_vad`，**它靠静音判断"这句说完了"**。
     * 旧设计（任务 W/X 的逐帧闸门）**按帧掐掉静音**，结果是：
     * 视频有背景音乐 ⇒ 电平恒高于门限 ⇒ 永不进休眠分支 ⇒
     * **那段本该喂给云端断句的静音一次都没发过** ⇒ **云端永不断句**
     * （实测症状：**106 条转写、0 条定稿**）。
     *
     * ### 新模型为什么明显更好
     *
     * | 好处 | 说明 |
     * |---|---|
     * | ★ **不再饿死云端** | 激活期间连静音一起传 ⇒ 云端正常断句 |
     * | ★ **门限不再是单点故障** | 门限只决定「**何时醒**」，不决定每一片传不传 ⇒ **误判可恢复** |
     * | ★ **省的地方更准** | 省下的是**真正没人说话的大段空白**（>15s） |
     * | ★ **行为可预期** | "说话就传，停 15 秒就歇" —— 用户能自己推断它该干什么 |
     *
     * > ⚠️ 实测教训：旧设计下门限设 4%，而**有语音时电平只有 2%** ⇒
     * > 闸门一直睡着，**有语音也不传**，卡片上还显示得像"没人说话"。
     * > ⇒ 新模型下门限默认降到 **2%**（**宁可敏感**，误激活很便宜）。
     *
     * ## ★★ 看门狗：`AudioRecord` 静默失效
     *
     * 实测抓到：跑 30 分钟后 `read()` **不报错、但返回全零**（重启服务才恢复）。
     * ⚠️ **旧设计让这个 bug 完全不可见** —— "闸门休眠"本来就是预期状态，
     * "麦克风死了"和"没人说话"在卡片上**长得一模一样**。
     * ⇒ 连续 [ZERO_BEATS_TO_REBUILD] 个心跳窗**峰值恰好为 0** ⇒ 自动重建音源。
     */
    private fun pump(client: AsrClient) {
        val buf = ByteArray(AudioSource.FRAME_BYTES)
        /** 预滚：醒来的瞬间补发最近这几片，避免把字头切掉 */
        val preroll = ArrayDeque<ByteArray>()
        val prerollFrames = 3                      // 300 ms
        val idleMs = cfg.vadIdleMs.toLong()        // ★ 多久没新语音就休眠

        var frames = 0L
        var skipped = 0L
        var injected = 0L                          // ★ 主动灌进去的静音片数
        var silentReads = 0L
        var peak = 0
        var lastBeat = System.currentTimeMillis()
        var lastPushAt = System.currentTimeMillis()  // 上次真的推了东西的时刻
        var active = false                         // 闸门开着（= 正在整段上传）？
        var flushPreroll = false                   // 刚醒：下一片前先补预滚
        var lastSpeechAt = 0L                      // ★ 上次判为"有语音"的时刻
        var segAudioFrames = 0L                    // ★ 本段已推的【真实音频】片数
        var degraded = false                       // ★ 卡片上现在是不是挂着警告
        var zeroBeats = 0                          // ★ 连续多少个心跳窗峰值恰好为 0
        var rebuilds = 0                           // ★ 音源重建过几次

        while (running.get()) {
            // ★ 云端断句了 ⇒ 开新的一段
            if (segmentClosed) {
                segmentClosed = false
                segAudioFrames = 0
            }

            val n = audio.read(buf)
            if (n <= 0) {
                silentReads++
                Thread.sleep(10)
                continue
            }

            // ⚠️ 必须 copy —— buf 会被下一轮复用，预滚队列里存的不能是同一个数组
            val frame = if (n == buf.size) buf.copyOf() else buf.copyOf(n)

            // 算这一片的电平（PCM16 小端，取绝对峰值）
            var p = 0
            var i = 0
            while (i + 1 < frame.size) {
                val v = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toInt()
                val a = if (v < 0) -v else v
                if (a > p) p = a
                i += 2
            }
            if (p > peak) peak = p
            val levelPct = p * 100 / 32767
            val nowMs = System.currentTimeMillis()

            lastLevel = levelPct
            val speech = levelPct >= cfg.vadThreshold
            lastIsSpeech = speech

            // ★ 语音开始 ⇒ 立即激活（新模型的核心）
            if (speech) {
                lastSpeechAt = nowMs
                if (!active) {
                    active = true
                    flushPreroll = true
                }
            }

            // ★ 语音结束 ⇒ 等 [CaptionConfig.vadIdleMs]（默认 15s）没新语音就休眠
            if (!cfg.vadEnabled) active = true                                  // 关掉闸门 = 一直传
            else if (active && nowMs - lastSpeechAt > idleMs) active = false

            if (client.isOpen) {
                if (active) {
                    if (flushPreroll) {
                        // ★ 刚醒：先把预滚补上，否则字头会被切掉
                        flushPreroll = false
                        while (preroll.isNotEmpty()) {
                            client.append(preroll.removeFirst()); frames++
                        }
                        preroll.clear()
                    }
                    // ★★ 【全传】—— 包括静音。
                    //    ⚠️ 这不是浪费：云端 `server_vad` **靠静音判断"这句说完了"**。
                    //    旧设计按帧掐掉静音 ⇒ 云端永不断句（实测 0 条定稿）。
                    client.append(frame); frames++
                    segAudioFrames++
                    lastPushAt = nowMs
                } else {
                    // ★ 休眠中：不上传，只维护预滚
                    flushPreroll = false
                    skipped++
                    preroll.addLast(frame)
                    if (preroll.size > prerollFrames) preroll.removeFirst()
                }

                // ★★★ 段落硬上限 ⇒ 主动灌静音，逼云端断句（见 [CaptionConfig.forceSegment]）
                //
                // 为什么需要：连续有声（背景音乐）时本地 VAD 永不休眠 ⇒ 上面那个
                // 「静音尾巴」分支从不进入 ⇒ 云端收不到静音 ⇒ 永不断句。
                // 实测症状：106 条转写 0 条定稿。⇒ 既然音频流由我们控制，就按需造静音。
                if (active && cfg.forceSegment && segAudioFrames * 100 >= cfg.maxSegmentMs) {
                    val zeros = ByteArray(AudioSource.FRAME_BYTES)
                    repeat(INJECT_SILENCE_MS / 100) {
                        client.append(zeros); frames++; injected++
                    }
                    lastPushAt = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "★ 到段落上限 ${cfg.maxSegmentMs} ms ⇒ 注入 ${INJECT_SILENCE_MS} ms 静音强制断句"
                    )
                    segAudioFrames = 0
                }
            }

            // ★ 心跳：分清"没读到音频"和"WS 断了" —— 这两种失败长得一模一样
            val now = System.currentTimeMillis()
            if (now - lastBeat >= 5000) {
                val peakPct = peak * 100 / 32767
                savedSeconds = (skipped / 10).toInt()
                val idleFor = if (lastSpeechAt == 0L) 0 else (now - lastSpeechAt) / 1000
                Log.i(
                    TAG,
                    "心跳：已推 $frames 片（${frames * 100} ms，其中注入 $injected 片）｜" +
                            "本段 ${segAudioFrames * 100} ms / 上限 ${if (cfg.forceSegment) "${cfg.maxSegmentMs} ms" else "关"}｜" +
                            "空闲 $idleFor s / 阈值 ${idleMs / 1000} s｜" +
                            "闸门省下 $savedSeconds s（${skipped} 片没传）｜" +
                            "空读 $silentReads 次｜峰值 $peakPct%｜电平 $lastLevel%（门限 ${cfg.vadThreshold}%）｜" +
                            "闸门=${if (active) "开" else "休眠"}｜重建 $rebuilds 次｜" +
                            "WS=${if (client.isOpen) "open" else "CLOSED"}"
                )

                // ★★★ 看门狗：AudioRecord 静默失效
                //
                // 实测抓到：跑 30 分钟后 `read()` **不报错、但返回全零**，重启服务才恢复。
                // ⚠️ 旧设计让这个 bug **完全不可见** —— "闸门休眠"本来就是预期状态，
                //    "麦克风死了"和"没人说话"在卡片上一模一样。
                //
                // 判据用【恰好为 0】而不是【很小】：真环境噪声几乎不可能连续 3 分钟精确为 0。
                if (peakPct == 0 && frames >= 0) {
                    zeroBeats++
                    if (zeroBeats >= ZERO_BEATS_TO_REBUILD) {
                        zeroBeats = 0
                        rebuilds++
                        Log.w(
                            TAG,
                            "★★ 连续 ${ZERO_BEATS_TO_REBUILD} 个心跳窗峰值【恰好为 0】" +
                                    "⇒ 判定 AudioRecord 已失效，重建音源（第 $rebuilds 次）"
                        )
                        val r = runCatching {
                            audio.stop()
                            audio.start(cfg.source, projection, cfg.sourceType, cfg.resolveDevice(this))
                        }
                        if (r.isFailure) Log.w(TAG, "重建音源失败：${r.exceptionOrNull()?.message}")
                        handler.post {
                            render(
                                "⚠️ 音源已自动重建",
                                "连续 ${ZERO_BEATS_TO_REBUILD * 5} 秒信号【恰好为 0】⇒ 判定麦克风句柄失效" +
                                        "（第 $rebuilds 次）",
                                null
                            )
                        }
                        degraded = true
                    }
                } else {
                    zeroBeats = 0
                }

                // ★★ 连续 10 秒全静音 ⇒ 在卡片上说清楚为什么（否则用户一头雾水）
                if (peakPct == 0 && frames > 0) {
                    silentBeats++
                    if (silentBeats == 2) {
                        degraded = true
                        handler.post {
                            render(
                                "⚠️ 抓到的全是静音",
                                if (cfg.source == AudioSource.Kind.PLAYBACK)
                                    "播放方多半【不允许被捕获】——Android 规则：targetSdk<29 的 app 默认只让系统抓（自带浏览器/音乐就是）。换 targetSdk≥29 的 app（如微信），或改用麦克风音源"
                                else "麦克风没收到声音（若确认有声音 ⇒ 等看门狗自动重建音源）",
                                null
                            )
                        }
                    }
                } else {
                    silentBeats = 0
                }

                // ★★ 空闲太久 ⇒ 必须说清楚"是没人说话还是门限太高"
                //    （否则又是一个"静默失败"：卡片一动不动，用户以为是没人在说）
                val sleepingMs = now - lastPushAt
                if (cfg.vadEnabled && !active && frames > 0 && sleepingMs >= idleMs + 5_000) {
                    degraded = true
                    handler.post {
                        render(
                            "⏸ 空闲中（停 ${idleMs / 1000}s 没新语音就歇）",
                            "近 ${sleepingMs / 1000}s 没上传（累计已省 ${savedSeconds}s）｜" +
                                    "当前电平 $lastLevel% vs 门限 ${cfg.vadThreshold}%。" +
                                    "若【有声音也不出字】⇒ 把门限调低（设置里有电平表对着调）",
                            null
                        )
                    }
                }

                // ★★★ 恢复正常 ⇒ **必须把状态行改回来**
                //    否则过期警告一直挂在卡片上 —— 界面说的状态和实际不一致，
                //    正是本项目反复踩的「静默失败/误导性提示」那一类。
                if (degraded && peakPct > 0) {
                    degraded = false
                    handler.post { render("● 识别中", "${cfg.model}  ${cfg.source.label}", null) }
                }

                peak = 0
                lastBeat = now
            }
        }
        Log.w(TAG, "pump 退出（running=false）共推 $frames 片")
    }

    private fun onAsrEvent(ev: AsrClient.Event) {
        // ★ 识别结果同时进 logcat —— 卡片不可见时（未授悬浮窗）也能远程验证
        when (ev) {
            is AsrClient.Event.Connected -> {
                reconnectAttempts = 0
                Log.i(TAG, "● 识别中：${ev.model}  ${cfg.source.label}")
                render("● 识别中", "${ev.model}  ${cfg.source.label}", null)
            }
            is AsrClient.Event.Partial -> {
                Log.i(TAG, "… [${ev.language}] ${ev.text}⟨${ev.stash}⟩")
                render(null, null, ev.text + ev.stash)
            }
            is AsrClient.Event.Final -> {
                // ⚠️ `ev.seconds` 是【整场会话累计】的计费秒数，不是这一句的时长
                ev.seconds?.let { billedSeconds = it }
                Log.i(
                    TAG,
                    "✓ [${ev.language}] ${ev.transcript}" +
                            "   （云端累计计费 ${String.format(Locale.US, "%.1f", billedSeconds)}s，" +
                            "闸门省下 ${savedSeconds}s）"
                )
                lastFinal = ev.transcript
                // ★★★ 实时落盘 —— 字幕只活在内存里的话，服务一停/一崩就全没了
                log?.append(ev.transcript)
                logSummary = log?.summary() ?: ""
                segmentClosed = true          // ★ 告诉 pump：开新的一段
                render(null, null, "")
            }
            is AsrClient.Event.Failed -> {
                Log.w(TAG, "✗ 云端出错：${ev.why}")
                scheduleReconnect(ev.why)
            }
            is AsrClient.Event.Closed -> {
                Log.w(TAG, "连接已关闭 code=${ev.code} ${ev.reason}")
                scheduleReconnect("code=${ev.code} ${ev.reason}")
            }
        }
    }

    /**
     * ★ **自动重连**（指数退避）。
     *
     * 为什么不重连不行：长连接会因网络切换 / 服务端回收而断，
     * 断了以后**卡片只是静止不动** —— 用户完全看不出来，会以为是"没人在说话"。
     * ⇒ 必须重连，而且**卡片上要说清楚在重连**。
     */
    private fun scheduleReconnect(why: String) {
        if (!running.get()) return
        if (reconnectAttempts >= MAX_RECONNECT) {
            render("✗ 重连已放弃", "试了 $MAX_RECONNECT 次：$why", null)
            return
        }
        reconnectAttempts++
        val delayMs = (1000L shl (reconnectAttempts - 1)).coerceAtMost(15_000L)
        Log.i(TAG, "★ ${delayMs}ms 后重连（第 $reconnectAttempts 次）：$why")
        render("正在重连…", "第 $reconnectAttempts 次，${delayMs / 1000}s 后：$why", null)
        handler.postDelayed({
            if (running.get()) asr?.connect()
        }, delayMs)
    }

    private fun takeMediaProjection(intent: Intent) {
        val data = intent.getParcelableExtra<Intent>(EXTRA_MP_DATA_KEY) ?: return
        val code = intent.getIntExtra(EXTRA_MP_CODE_KEY, 0)
        runCatching {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection?.stop()
            // ⚠️ 不要调 `MediaProjection.start(...)` ——
            //   它在 **compileSdk 35 上已被删除**（实测 `Unresolved reference 'start'`），
            //   而且**不需要**：实测音频捕获在没有 start 的情况下也能工作。
            projection = mpm.getMediaProjection(code, data)
            Log.i(TAG, "✓ 拿到 MediaProjection（播放捕获已授权）")
        }.onFailure { Log.w(TAG, "取 MediaProjection 失败：${it.message}") }
    }

    // ------------------------------------------------------------------ ★★ 测试声源

    /**
     * 放一段 WAV —— **主用的测试声源**，见 [EXTRA_PLAY_WAV]。
     *
     * ⚠️ 有一条**反直觉的风险**：我们的录音源类型若是 `VOICE_RECOGNITION`，
     * 系统可能开**回声消除（AEC）**，而**自己放给自己听**恰好是 AEC 最想消掉的东西
     * ⇒ 可能"放了但收不到"。**若结果异常，就去设置里把源类型换成
     * `主麦克风（原始）` 或 `不做处理` 再测一次。**
     */
    private fun playWav(path: String) {
        val f = java.io.File(path)
        if (!f.exists()) {
            Log.w(TAG, "✗ 测试音频不存在：$path")
            handler.post { render("✗ 测试音频不存在", path, null) }
            return
        }
        runCatching {
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                Log.i(TAG, "★ 测试音频播放完毕")
                runCatching { it.release() }
            }
            mp.setOnErrorListener { m, what, extra ->
                Log.w(TAG, "✗ 播放出错 what=$what extra=$extra")
                runCatching { m.release() }
                true
            }
            mp.prepare()
            mp.start()
            // ★ 把"在放什么、放多久、当前音量多少"全打出来 ——
            //   否则"没声音"到底是没放还是音量为 0，又分不清（又一处"静默失败"）
            Log.i(TAG, "♪ 测试音频开始：${f.name}  时长 ${mp.duration} ms  音量 ${currentVolume()}")
            handler.post { render("♪ 正在播放测试音频", "${f.name}  ${mp.duration / 1000}s  音量 ${currentVolume()}", null) }
        }.onFailure {
            Log.w(TAG, "✗ 播放测试音频失败：${it.message}")
            handler.post { render("✗ 播放失败", it.message ?: "未知", null) }
        }
    }

    /** 放一段内置测试音（不需要任何文件）—— 见 [EXTRA_PLAY_TEST] */
    private fun playTestTone(ms: Int = 8000) {
        runCatching {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90)
            tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ms)
            Log.i(TAG, "♪ 测试音开始（${ms} ms）  音量 ${currentVolume()}")
            handler.post { render("♪ 正在播放测试音", "${ms / 1000}s  音量 ${currentVolume()}", null) }
            handler.postDelayed({ runCatching { tg.release() } }, ms + 500L)
        }.onFailure { Log.w(TAG, "✗ 播放测试音失败：${it.message}") }
    }

    /** `STREAM_MUSIC` 的 `当前/最大` 音量 —— 附在播放日志里，排除"音量为 0"这种假故障 */
    private fun currentVolume(): String = runCatching {
        val am = getSystemService(android.media.AudioManager::class.java)
        "${am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}" +
                if (am.isStreamMute(android.media.AudioManager.STREAM_MUSIC)) "（静音）" else ""
    }.getOrDefault("?")

    // ------------------------------------------------------------------ overlay

    private fun targetDisplayId(): Int =
        if (target == C.TARGET_PHONE) Display.DEFAULT_DISPLAY else fallbackDisplayId()

    private fun fallbackDisplayId(): Int =
        displayManager.displays.maxByOrNull { it.displayId }?.displayId ?: Display.DEFAULT_DISPLAY

    private fun attachOverlay(displayId: Int) {
        val display = displayManager.getDisplay(displayId)
        if (display == null) { Log.w(TAG, "没有 display $displayId"); return }

        runCatching {
            val ctx = createDisplayContext(display)
            val w = ctx.getSystemService(WindowManager::class.java)
            val d = ctx.resources.displayMetrics.density
            // ★ 用【目标屏】的密度 —— `resources` 拿到的是【手机屏】的(2.5)，
            //   而 TNT 屏是 1.35 ⇒ 卡片会大 1.85 倍（2026-09-12 实测踩过）
            val card = buildCard(ctx)

            val p = WindowManager.LayoutParams(
                (CARD_WIDTH_DP * d).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                touchFlags(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                // 字幕放屏幕下方 —— 像电视字幕那样
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                x = 0
                y = (90 * d).toInt()   // 让开 TNT 任务栏（它是系统装饰层，在 overlay 之上）
            }

            w.addView(card, p)
            wm = w; root = card
            Log.i(TAG, "✓ overlay 已挂到 display $displayId  标志=0x${Integer.toHexString(touchFlags())}")
        }.onFailure { Log.e(TAG, "✗ 挂 overlay 失败：${it.message}", it) }
    }

    private fun buildCard(ctx: Context): LinearLayout {
        // ★ 用【目标屏】的密度 —— `resources` 拿到的是【手机屏】的(2.5)，
        //   而 TNT 屏是 1.35 ⇒ 卡片会大 1.85 倍（2026-09-12 实测踩过）
        val d = ctx.resources.displayMetrics.density
        val pad = (12 * d).toInt()

        statusView = TextView(ctx).apply {
            setTextColor(0xFF90A4AE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
        }
        doneView = TextView(ctx).apply {
            setTextColor(0xFFECEFF1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
        }
        liveView = TextView(ctx).apply {
            setTextColor(0xFFFFD54F.toInt())     // 琥珀 = "还在识别，会被修正"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            // ★★★ 关键：超长时**从开头截断**，保留【末尾】。
            //   默认（TruncateAt.END）是从末尾截 —— 于是段落越长，卡片上**永远停在最早那两行**，
            //   新字根本不出现，"实时"字幕变成不动的（2026-09-12 实测踩到）。
            ellipsize = android.text.TextUtils.TruncateAt.START
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 10 * d
            }
            addView(statusView); addView(doneView); addView(liveView)
        }
    }

    /** 状态行 / 上一句 / 当前句（传 null = 不动那一行） */
    private fun render(status: String?, sub: String?, live: String?) {
        // ★ 记下状态内容 —— 1 秒的秒表 ticker 靠它重渲染出"走动的时长"
        if (status != null) {
            lastStatus = status
            lastSub = sub
        }
        status?.let { s ->
            statusView?.text = buildString {
                append("★ 实时字幕   ").append(s)
                sub?.let { append("   ").append(it) }
                if (startedAt > 0) {
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    append(String.format(Locale.US, "   %d:%02d", sec / 60, sec % 60))
                }
            }
        }
        if (live != null) {
            // ★ 只留末尾 —— 见 [CaptionConfig.showChars]，配合 `TruncateAt.START`
            //   让卡片始终跟着【最新】的字走，且长会话下渲染量有上限。
            liveView?.text = if (cfg.showStash) live.takeLast(cfg.showChars) else ""
            doneView?.text = lastFinal.takeLast(FINAL_MAX_CHARS)
        }
    }

    // ------------------------------------------------------------------ 契约工具

    private fun touchFlags(): Int =
        if (touchMode == C.TOUCH_SELF) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

    private fun readOwnMeta(key: String): String = runCatching {
        packageManager.getServiceInfo(
            ComponentName(this, CaptionService::class.java), PackageManager.GET_META_DATA
        ).metaData?.getString(key)?.trim().orEmpty()
    }.getOrDefault("")

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MODE 组件", NotificationManager.IMPORTANCE_MIN)
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("实时字幕运行中")
            .setContentText("${cfg.source.label} → ${cfg.model}")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        // ⚠️ A10 不校验 FGS 类型；A14+ 用 microphone 类型要求已授 RECORD_AUDIO。
        //    两个版本都安全。
        //
        // ★★ 但【播放捕获】必须显式带上 MEDIA_PROJECTION ——
        //    `MediaProjectionManager.getMediaProjection()` 会检查调用方前台服务的类型，
        //    不匹配就抛：
        //      "Media projections require a foreground service of type
        //       ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION"
        //    （2026-09-12 实测踩到；manifest 里也要声明同名类型）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════
// ★ 图层控制契约的字符串常量（任务 AP / AP6）
//
// ⚠️ 这三个值**必须与宿主的 `LayerManager` 逐字一致**。
//    mod 刻意不依赖宿主的模块（"无共享 AAR、各抄各的契约字符串"是既定约定），
//    所以这份重复是【故意】的 —— 抄错的症状是"隐藏按钮点了没反应"，
//    ★ 而且**不会报任何错**。
//
// ★ 放在**文件级**而不是类里的 companion object：
//    本类已经有一个 companion object 了，Kotlin **每个类只允许一个**。
// ═══════════════════════════════════════════════════════════════════════════
private const val ACTION_LAYER_CONTROL = "com.shware.mode.action.LAYER_CONTROL"
private const val EXTRA_LAYER_OP = "com.shware.mode.extra.LAYER_OP"
private const val EXTRA_LAYER_TARGET = "com.shware.mode.extra.LAYER_TARGET"
