package com.shware.mode.mod.livecaption

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * ★ 实时字幕的**设置 + 授权入口**。
 *
 * ## 为什么这个 mod 需要一个 Activity
 *
 * 两件事**只能在 Activity 里做**：
 * 1. **请求运行时权限**（`RECORD_AUDIO`）—— Service 起不了权限弹窗
 * 2. ★ **请求 `MediaProjection`**（播放捕获的授权）—— `createScreenCaptureIntent()` 必须 `startActivityForResult`
 *
 * ## 两个入口
 *
 * ① 手机应用列表里的「实时字幕」（本 Activity 带 `LAUNCHER`）
 * ② **宿主管理器的「设置」按钮** —— 走 [com.shware.mode.mod.ModContract.META_SETTINGS]（任务 V 刚做的）
 *
 * ## ⚠️ 要防 TNT 抓走（与宿主 MainActivity 同一套自愈）
 */
class SetupActivity : Activity() {

    private lateinit var cfg: CaptionConfig
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cur = windowManager.defaultDisplay.displayId
        if (cur != Display.DEFAULT_DISPLAY) {
            Log.w(TAG, "★ 设置界面被 TNT 纳管到 display $cur —— 重新以手机屏启动")
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            startActivity(intent, opts.toBundle())
            finish()
            return
        }

        cfg = CaptionConfig(this)
        setContentView(buildUi())
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): ScrollView {
        val d = resources.displayMetrics.density
        fun dp(n: Int) = (n * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }
        root += title("实时字幕")
        root += note(
            "抓音源 → 送 DashScope 实时识别 → 在 TNT 屏下方滚字幕。\n" +
                    "⚠️ 本 ROM 没有系统「实时字幕」服务（dumpsys captioning 找不到），" +
                    "所以本 mod 是**自己成为**实时字幕，不是劫持现成的。"
        )
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root += body

        return ScrollView(this).apply { addView(root) }
    }

    private fun rebuild() {
        val d = resources.displayMetrics.density
        fun dp(n: Int) = (n * d).toInt()
        body.removeAllViews()

        // ---- 状态 ----
        body += section("当前状态")
        body += note(
            "API Key   ${cfg.maskedKey()}\n" +
                    "模型      ${cfg.model}\n" +
                    "音源      ${cfg.source.label}\n" +
                    "麦克风权限 ${if (hasMic()) "✅ 已授" else "❌ 未授"}"
        )

        // ---- 授权按钮 ----
        body += section("授权")
        body += button("① 授权麦克风（RECORD_AUDIO）") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        body += button("② 授权播放捕获（实时字幕的原义）") { requestProjection() }
        body += note("播放捕获的授权是**每次会话一次**（系统要确认「正在录屏」）；麦克风是授一次长期有效。")

        // ---- 音源切换 ----
        body += section("音源")
        for (k in AudioSource.Kind.entries) {
            body += radio(if (k == AudioSource.Kind.MIC) "${k.label}（推荐·免授权）" else "${k.label}（进阶·受 targetSdk≥29 约束）",
                cfg.source == k) { cfg.source = k; rebuild() }
        }

        // ---- 音源类型 + 输入设备（只对麦克风有意义）----
        if (cfg.source == AudioSource.Kind.MIC) {
            body += section("录音源类型")
            for (t in AudioSource.SourceType.entries) {
                body += radio(t.label, cfg.sourceType == t) { cfg.sourceType = t; rebuild() }
            }

            body += section("输入设备")
            body += note(
                "★ Android **不给普通 app 多通道麦阵**（那些立体声掩码是混合过的）⇒ app 层**做不了波束成形**。\n" +
                        "手机只有 **2 个真麦**（底部 / 背部）。能做的就是**选对麦克风**。\n" +
                        "⚠️ **TNT GO 自带麦克风**（USB 音频）**选得上但实测送出来是静音** —— 想试可以选，但默认不用它。\n" +
                        "「自动」= 优先手机内置麦。"
            )
            body += radio("自动（优先手机内置麦）", cfg.isAutoDevice) {
                cfg.deviceId = -1; rebuild()
            }
            val mics = AudioSource.listMics(this)
            if (mics.isEmpty()) {
                body += note("（系统没列出可选输入设备）")
            }
            for (m in mics) {
                val label = "${m.productName}  [id=${m.id} ${typeName(m.type)} addr=${m.address}]"
                body += radio(label, !cfg.isAutoDevice && cfg.deviceId == m.id) {
                    cfg.deviceId = m.id; rebuild()
                }
            }
        }

        // ---- ★★ 语音起止闸门（省调用量）----
        body += section("语音起止闸门（省调用量）")
        body += note(
            "★ **模型**：只判断**语音什么时候开始、什么时候结束** ——\n" +
                    "**一有声音立即激活**，**停 ${cfg.vadIdleMs / 1000} 秒没有新语音就停止上传**。\n\n" +
                    "激活期间是**连静音一起传**的，这一点**是有意的**：\n" +
                    "云端 `server_vad` **靠静音判断「这句说完了」** —— 按帧把静音掐掉，\n" +
                    "它就会**永不断句**（上一版就是这么翻车的：实测 **106 条转写、0 条定稿**）。\n\n" +
                    "⇒ 省下的位置是**真正没人说话的大段空白**（> ${cfg.vadIdleMs / 1000} 秒），那才是该省的地方。"
        )
        body += CheckBox(this).apply {
            text = "启用（关掉 = 一直上传，最费但最保险）"
            textSize = 14f
            isChecked = cfg.vadEnabled
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, on -> cfg.vadEnabled = on; rebuild() }
        }
        if (cfg.vadEnabled) {
            // ★ 电平表 —— 一箭三雕：挑麦克风 / 定阈值 / 看有没有在传
            body += note("电平表（★ 对着它定**唤醒门限**：把门限放在**安静时底噪**上面一点点就行）")
            body += buildMeter()

            body += LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(Button(this@SetupActivity).apply {
                    text = "－1"
                    textSize = 13f
                    setOnClickListener { cfg.vadThreshold = cfg.vadThreshold - 1; rebuild() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@SetupActivity).apply {
                    text = "唤醒门限 ${cfg.vadThreshold}%"
                    textSize = 15f
                    gravity = android.view.Gravity.CENTER
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
                addView(Button(this@SetupActivity).apply {
                    text = "＋1"
                    textSize = 13f
                    setOnClickListener { cfg.vadThreshold = cfg.vadThreshold + 1; rebuild() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            body += note(
                "★ **门限调低一点没关系** —— 它只决定「**何时醒**」，不决定每一片传不传，\n" +
                        "**误激活的代价很便宜**（顶多多传一会儿，停 ${cfg.vadIdleMs / 1000} 秒没新语音就自动歇）。\n" +
                        "⚠️ 实测教训：门限设 4%、而**有语音时电平只有 2%** ⇒ 一直睡着，**有声音也不传**。"
            )

            body += LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(Button(this@SetupActivity).apply {
                    text = "－5s"
                    textSize = 13f
                    setOnClickListener { cfg.vadIdleMs = cfg.vadIdleMs - 5000; rebuild() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@SetupActivity).apply {
                    text = "停 ${cfg.vadIdleMs / 1000}s 就歇"
                    textSize = 15f
                    gravity = android.view.Gravity.CENTER
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
                addView(Button(this@SetupActivity).apply {
                    text = "＋5s"
                    textSize = 13f
                    setOnClickListener { cfg.vadIdleMs = cfg.vadIdleMs + 5000; rebuild() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            body += note("只要明显大于云端的 `silence_duration_ms`（我们下发成 500ms）就够，15 秒余量很宽裕。")
        }

        // ---- ★★★ 主动断句（段落硬上限）----
        body += section("主动断句（防止字幕成一条无限长段落）")
        body += note(
            "★ **为什么必须有**：闸门的「静音尾巴」只在**本地判出「语音结束」**时才发。\n" +
                    "但**视频/播客/音乐有背景音乐** ⇒ 电平一直高于门限 ⇒ 本地 VAD **永不休眠**\n" +
                    "⇒ 一分钟静音都没送过 ⇒ 云端 **永不断句**。\n" +
                    "**实测症状**：106 条转写、**0 条定稿**，字幕堆成密不透风的一大坨。\n" +
                    "⇒ 既然音频流由我们控制，就**按需制造静音**：推满上限就灌 800ms 数字静音，逼云端断句。\n" +
                    "代价：约 **8% 额外调用量**（800ms / 10s），换字幕按句滚动。"
        )
        body += CheckBox(this).apply {
            text = "启用主动断句"
            textSize = 14f
            isChecked = cfg.forceSegment
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, on -> cfg.forceSegment = on; rebuild() }
        }
        if (cfg.forceSegment) {
            body += LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(Button(this@SetupActivity).apply {
                    text = "－5s"
                    textSize = 13f
                    setOnClickListener { cfg.maxSegmentMs = cfg.maxSegmentMs - 5000; rebuild() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@SetupActivity).apply {
                    text = "段上限 ${cfg.maxSegmentMs / 1000}s"
                    textSize = 15f
                    gravity = android.view.Gravity.CENTER
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
                addView(Button(this@SetupActivity).apply {
                    text = "＋5s"
                    textSize = 13f
                    setOnClickListener { cfg.maxSegmentMs = cfg.maxSegmentMs + 5000; rebuild() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            body += note("上限小 ⇒ 字幕切得碎、更跟手；上限大 ⇒ 句子更完整、但一次要读的字更多。")
        }

        // ---- 模型 ----
        body += section("模型（DashScope realtime）")
        for (m in CaptionConfig.MODELS) {
            body += radio(m, cfg.model == m) { cfg.model = m; rebuild() }
        }

        // ---- ★★★ 落盘 + 显示窗口（长会话性能）----
        body += section("转写落盘 + 显示窗口（长会话不降速、内容不丢）")
        body += note(
            "★ **卡片只渲染末尾 N 字** —— 云端的 `text+stash` 是**当前这一整段的全文**，\n" +
                    "段落没断句时可以涨到几千字，而 `Partial` 事件**每秒来好几次**，\n" +
                    "每次都把全文塞进 TextView 重新排版 ⇒ **长会话下一直在做无谓的开销**。\n" +
                    "⇒ 只留末尾 N 字（卡片始终跟着**最新**的字走），**全文一个字不丢**地写进文件。"
        )
        body += CheckBox(this).apply {
            text = "把已定稿的转写实时写入文件（推荐）"
            textSize = 14f
            isChecked = cfg.saveTranscript
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, on -> cfg.saveTranscript = on; rebuild() }
        }
        body += note("⚠️ 只写【已定稿】的句子 —— 「识别中的临时文本」会被后续结果修正，写进去等于存错字。")
        if (CaptionService.logSummary.isNotEmpty()) {
            body += note("当前文件：\n${CaptionService.logSummary}")
        } else {
            body += note("（字幕服务没在跑，看不到当前文件）")
        }
        body += LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(Button(this@SetupActivity).apply {
                text = "－40"
                textSize = 13f
                setOnClickListener { cfg.showChars = cfg.showChars - 40; rebuild() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SetupActivity).apply {
                text = "显示末尾 ${cfg.showChars} 字"
                textSize = 15f
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
            addView(Button(this@SetupActivity).apply {
                text = "＋40"
                textSize = 13f
                setOnClickListener { cfg.showChars = cfg.showChars + 40; rebuild() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ---- 其它 ----
        body += section("其它")
        body += CheckBox(this).apply {
            text = "显示「识别中的临时文本」（关掉更稳，开着更实时）"
            textSize = 14f
            isChecked = cfg.showStash
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, on -> cfg.showStash = on }
        }

        // ---- 启停 ----
        body += section("字幕")
        body += button("▶ 开始字幕（挂到 TNT 屏）") {
            startServiceCompat(CaptionService::class.java.name)
        }
        body += button("■ 停止字幕") {
            stopService(Intent(this, CaptionService::class.java))
            toast("已停止")
        }
        body += note("也可以直接在启动器的 Mod 管理器里开关这个 mod。")
    }

    // ------------------------------------------------------------------ 授权

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
    }

    @Deprecated("A10 上用经典回调就够；换成 ActivityResult API 要引 androidx.activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJ) return
        if (resultCode != RESULT_OK || data == null) {
            toast("播放捕获被拒绝")
            return
        }
        // ★ MediaProjection 对象不能直接传 —— 但「授权结果 Intent」是 Parcelable，可以过 Intent，
        //   由 Service 自己 getMediaProjection(code, data)
        val i = Intent(this, CaptionService::class.java)
            .putExtra(CaptionService.EXTRA_MP_DATA_KEY, data)
            .putExtra(CaptionService.EXTRA_MP_CODE_KEY, resultCode)
        startServiceCompat(CaptionService::class.java.name, i)
        toast("播放捕获已授权")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            toast(if (hasMic()) "麦克风已授权" else "麦克风被拒绝")
            rebuild()
        }
    }

    // ------------------------------------------------------------------ 电平表

    private var meterFill: View? = null
    private var meterTrack: android.widget.LinearLayout? = null
    private var meterText: TextView? = null

    /** 电平表：每 200 ms 读一次服务发布的 [CaptionService.lastLevel] */
    private val meterTicker = object : Runnable {
        override fun run() {
            val lv = CaptionService.lastLevel
            val speech = CaptionService.lastIsSpeech
            val d = resources.displayMetrics.density
            val h = (10 * d).toInt()
            val track = meterTrack
            val fill = meterFill
            if (track != null && fill != null) {
                val lp = fill.layoutParams as android.widget.LinearLayout.LayoutParams
                lp.weight = lv.coerceAtLeast(1).toFloat()
                lp.height = h
                (track.getChildAt(1).layoutParams as android.widget.LinearLayout.LayoutParams).weight =
                    (100 - lv).coerceAtLeast(0).toFloat()
                fill.setBackgroundColor(
                    if (speech) 0xFF66BB6A.toInt() else 0xFF546E7A.toInt()
                )
                fill.requestLayout()
            }
            meterText?.text = "电平 ${lv}%   门限 ${cfg.vadThreshold}%   " +
                    (if (speech) "★ 判为语音（在传）" else "静音") +
                    "   本轮省下 ${CaptionService.savedSeconds}s   " +
                    "★ 云端已计费 ${String.format(java.util.Locale.US, "%.0f", CaptionService.billedSeconds)}s"
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this, 200)
        }
    }

    override fun onResume() {
        super.onResume()
        rebuild()
        android.os.Handler(android.os.Looper.getMainLooper()).post(meterTicker)
    }

    override fun onPause() {
        android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(meterTicker)
        super.onPause()
    }

    private fun buildMeter(): LinearLayout {
        val d = resources.displayMetrics.density
        val h = (10 * d).toInt()
        val radius = h / 2f

        meterTrack = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius; setColor(0x33000000)
            }
        }
        meterFill = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, h, 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius; setColor(0xFF546E7A.toInt())
            }
        }
        val rest = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, h, 99f)
        }
        meterTrack!!.addView(meterFill)
        meterTrack!!.addView(rest)

        meterText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, (6 * d).toInt(), 0, 0)
        }

        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(meterTrack)
            addView(meterText)
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun startServiceCompat(name: String, extra: Intent? = null) {
        val i = extra ?: Intent().setClassName(this, name)
        if (extra == null) i.setClassName(this, name)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }.onFailure { toast("启动失败：${it.message}") }
        toast("已启动，字幕会出现在 TNT 屏下方")
    }

    private fun title(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#37474F"))
        setPadding(0, (14 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
    }

    private fun note(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Color.parseColor("#546E7A"))
        setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        setOnClickListener { runCatching { onClick() }.onFailure { toast("出错：${it.message}") } }
    }

    private fun radio(label: String, checked: Boolean, onClick: () -> Unit) = CheckBox(this).apply {
        text = label
        textSize = 14f
        isChecked = checked
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** `AudioDeviceInfo.getType()` 的数字 → 好读的名字 */
    private fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB设备"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB耳麦"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙"
        else -> "type$t"
    }

    private operator fun LinearLayout.plusAssign(v: View) {
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private companion object {
        const val TAG = "ModeMod/CaptionCfg"
        const val REQ_MIC = 1001
        const val REQ_PROJ = 1002
    }
}
