package com.shware.mode.mod.brightness

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * ★ 设置 / 自检界面（任务 AI）。
 *
 * 它同时是**这个 mod 的体检台** —— 因为这个 mod 有**三道人工门**，任何一道没过都会
 * 表现成"按键没反应"，而那是最难查的一类失败：
 *
 * | # | 门 | 怎么过 |
 * |---|---|---|
 * | 1 | Android `SYSTEM_ALERT_WINDOW` appop | `deploy.sh` 自动授（第 1 个按钮可复查） |
 * | 2 | ★ **Smartisan 悬浮窗授权** | 手机管理 → 权限管理 → TNT GO 亮度 → 悬浮窗（**只能手点**） |
 * | 3 | ★★ **无障碍服务** | 本界面的「打开无障碍设置」（**只能手点**） |
 * | 4 | USB 设备授权 | 系统弹窗点「允许」（建议勾「默认」） |
 *
 * ## ★「量程对账」按钮（任务 AI4）
 *
 * `.paper/07` 记的是 **9~2000**（OP 实测 + 官方公式），用户 2026-09-13 实测说 **1~1000**。
 * **两个数都不是定论。** 这个按钮直接把 1 / 100 / 500 / 1000 依次发下去再回读，
 * 用**设备自己的回声**把量程钉死。
 */
class SettingsActivity : Activity(), BrightnessCore.Listener {

    companion object {
        private const val TAG = "ModeMod/Bright"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bkl-probe").apply { isDaemon = true }
    }

    private lateinit var statusView: TextView
    private lateinit var probeView: TextView
    private lateinit var rangeView: TextView

    /** ★ 显示器能力（HDR / 广色域 / 位深）—— 开机算一次就够 */
    private var displayCapability: String = ""

    private val ticker = object : Runnable {
        override fun run() {
            render(BrightnessCore.current())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BrightnessCore.attach(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title("★ TNT GO 亮度 · 自检台"))

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#DDDDDD"))
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(statusView)

        root.addView(section("① 三道人工门（adb 授不了，只能手点）"))
        root.addView(button("打开「无障碍」设置 → 启用「TNT GO 亮度键」") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { toast("打不开无障碍设置：${it.message}") }
        })
        root.addView(button("请求 USB 设备授权") {
            BrightnessCore.attach(this)
            val bkl = TntgoBkl(this)
            when {
                bkl.findDevice() == null -> toast("没找到 TNT GO（先把它接上）")
                bkl.hasPermission() -> toast("已经有 USB 权限了")
                else -> { bkl.resetPermissionRequested(); bkl.requestPermission(); toast("请看系统弹窗，点「允许」") }
            }
        })

        root.addView(section("② 手动试（不按键也能验通路）"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("暗一档") { BrightnessCore.nudge(this, -1) }, weight())
        row.addView(button("亮一档") { BrightnessCore.nudge(this, +1) }, weight())
        root.addView(row)
        root.addView(button("重新读取设备当前亮度（裸发 AT+BKL 查询）") {
            BrightnessCore.refresh()
        })

        root.addView(section("③ ★ 量程对账（任务 AI4）—— 已裁决「9~2000」，这里是边界复核"))
        rangeView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#90A4AE"))
            text = "★ 已裁决：量程 = 9~2000（2000 目视确认最亮；3000 被设备回 +ERROR=100 拒收）。\n" +
                    "点下面的按钮，依次发 1 / 9 / 1000 / 1999 / 2000 / 2001 / 2048 / 2500 并回读。\n" +
                    "判读：★ 「+ERROR=」＝设备【明确拒绝】(超量程)，与「回读值」是完全不同的两种回应。\n" +
                    "⚠️ 背光变化不进 framebuffer ⇒ 截图看不出，这一步只能证明 MCU 收下了值。"
        }
        root.addView(button("★ 开始量程对账（边界复核）") { runRangeProbe() })

        root.addView(section("④ ★★ 上限阶梯：2000 之上还有更亮的档吗？（只能靠眼睛）"))
        root.addView(TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#90A4AE"))
            text = "✅ 已确认：2000 明显比 1000 亮 ⇒ 量程至少到 2000（＝出厂公式的上限）。\n" +
                    "⚠️ 但回读只能证明「MCU 收下了值」，证明不了「灯真的更亮」（背光不进 framebuffer）。\n" +
                    "★ 依次按下面几个按钮，盯着 TNT 屏看灯有没有变化 —— 这一步只能人眼判。"
        })
        val ladder = intArrayOf(1000, 2000, 3000, 4000, 5000)
        var ladderRow: LinearLayout? = null
        ladder.forEachIndexed { idx, v ->
            if (idx % 3 == 0) {
                ladderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                root.addView(ladderRow)
            }
            ladderRow!!.addView(button("设 $v") { setRaw(v) }, weight())
        }

        root.addView(section("⑤ ★ 显示能力（HDR / 广色域）—— 问系统自己要"))
        displayCapability = describeDisplays()
        root.addView(TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#B0BEC5"))
            text = displayCapability
        })
        root.addView(TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#78909C"))
            text = "判读：HDR=false / 空的 HDR 类型表 ⇒ 系统认为这块屏【不支持 HDR】。\n" +
                    "（HdrCapabilities 由 SurfaceFlinger 从【EDID 的 HDR 静态元数据块】解析而来）"
        })

        root.addView(section("⑥ ★ 交互：短按 / 长按（任务 AJ）"))
        root.addView(TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#90A4AE"))
            text = "短按 = 立即跳一档（保留段落感）。\n" +
                    "长按 = 按下后超过「长按阈值」仍按着 ⇒ **从那一刻起**按【时间】线性匀速调节（不加速）。\n" +
                    "★ 实测：按住时设备一个事件都不发（只有按下/松开各一个），所以能可靠区分短按与长按。"
        })

        addSlider(
            root, "长按阈值",
            Prefs.LONG_MS_MIN, Prefs.LONG_MS_MAX, Prefs.longPressMs(this),
            { "$it ms" }
        ) { Prefs.put(this, Prefs.KEY_LONG_MS, it) }

        addSlider(
            root, "无极调节速度",
            Prefs.RATE_MIN, Prefs.RATE_MAX, Prefs.rate(this),
            { "$it /秒" }
        ) { Prefs.put(this, Prefs.KEY_RATE, it) }

        addSlider(
            root, "短按步进",
            Prefs.STEP_MIN, Prefs.STEP_MAX, Prefs.step(this),
            { if (it == 0) "0（短按不跳档）" else "$it %" }
        ) { Prefs.put(this, Prefs.KEY_STEP, it) }

        // ★★ 线性域切换 —— 手感差别很大，所以留开关
        val domainBtn = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        fun refreshDomainBtn() {
            val d = Prefs.domain(this)
            domainBtn.text = if (d == Prefs.DOMAIN_UI) {
                "线性域：★ 感知(UI) 匀速 —— 全程一样快（点此切到 BKL）"
            } else {
                "线性域：BKL 匀速 —— 感知上「前快后慢」（点此切到 UI）"
            }
        }
        refreshDomainBtn()
        domainBtn.setOnClickListener {
            Prefs.putDomain(
                this,
                if (Prefs.domain(this) == Prefs.DOMAIN_UI) Prefs.DOMAIN_BKL else Prefs.DOMAIN_UI
            )
            refreshDomainBtn()
        }
        root.addView(domainBtn)
        root.addView(TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#78909C"))
            text = "⚠️ 两者差别很大：BKL 9→2000 里，**前 10% 的路程就走完了感知亮度的一半**\n" +
                    "（UI 50% 对应 MCU 只有 207）。⇒ 选 BKL 会感觉「一开始窜得快、后面拖得慢」；\n" +
                    "选 UI 才是真正意义上的「匀速」。默认给 BKL（按原话），但要手感好建议试 UI。"
        })

        // ★★ 模拟长按 —— 不碰键盘就能验证整条状态机（也方便以后回归）
        root.addView(TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
            text = "★ 模拟长按（走和真按键【完全同一条】代码路径）：先步进一档，" +
                    "到阈值后进入无极调节，2 秒后自动松开。"
        })
        val simRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        simRow.addView(button("模拟长按 2s（变暗）") { simulateHold(-1, 2000) }, weight())
        simRow.addView(button("模拟长按 2s（变亮）") { simulateHold(+1, 2000) }, weight())
        root.addView(simRow)
        val simRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        simRow2.addView(button("模拟短按（变暗）") { simulateTap(-1) }, weight())
        simRow2.addView(button("模拟短按（变亮）") { simulateTap(+1) }, weight())
        root.addView(simRow2)

        probeView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, pad / 2, 0, pad / 2)
            text = ""
        }
        root.addView(probeView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        BrightnessCore.addListener(this)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        BrightnessCore.removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBrightnessState(s: BrightnessCore.State) {
        handler.post { render(s) }
    }

    // ------------------------------------------------------------------ 渲染

    private fun render(s: BrightnessCore.State) {
        val bkl = TntgoBkl(this)
        val deviceOk = bkl.findDevice() != null

        statusView.text = buildString {
            append("当前亮度：")
            append(s.ui?.let { "$it%" } ?: "未知")
            append("   （回读 +BKL=").append(s.mcu?.toString() ?: "—").append("）\n")

            append("无障碍按键过滤器：")
            append(if (s.a11yConnected) "✓ 已连接" else "✗ 未连接（亮度键收不到）")
            append("\n")

            append("TNT GO 设备：")
            append(if (deviceOk) "✓ 在线" else "✗ 不在")
            append("   USB 权限：")
            append(if (bkl.hasPermission()) "✓ 有" else "✗ 没有")
            append("\n")

            append("已接住按键：").append(s.keyCount).append(" 次\n")
            append("状态：").append(healthLabel(s.health)).append("\n")
            if (s.note.isNotEmpty()) append("说明：").append(s.note)
        }
    }

    private fun healthLabel(h: BrightnessCore.Health): String = when (h) {
        BrightnessCore.Health.UNKNOWN -> "未开始"
        BrightnessCore.Health.PENDING -> "串口中…"
        BrightnessCore.Health.OK -> "✓ 正常"
        BrightnessCore.Health.NO_DEVICE -> "✗ 没插 TNT GO"
        BrightnessCore.Health.NO_PERMISSION -> "✗ 等 USB 授权"
        BrightnessCore.Health.BUSY -> "✗ 串口被占"
        BrightnessCore.Health.FAILED -> "✗ 失败"
    }

    // ------------------------------------------------------------------ 量程对账

    private fun runRangeProbe() {
        probeView.text = "开始…\n"
        io.execute {
            val bkl = TntgoBkl(this)
            if (bkl.findDevice() == null) {
                post("✗ 没找到 TNT GO —— 先把它接上")
                return@execute
            }
            if (!bkl.hasPermission()) {
                post("✗ 没有 USB 权限 —— 点上面那个按钮授权")
                return@execute
            }
            val sb = StringBuilder()
            // ★ 边界专测：下界 + 上界（2000 已被目视确认可用、3000 被设备拒收）
            val probes = intArrayOf(1, 9, 1000, 1999, 2000, 2001, 2048, 2500)
            for (v in probes) {
                val r = bkl.setRawMcu(v)
                // ★ 打印【实际发出去的】命令,而不是【打算发的】——
                //   第一版就是打印意图值,把"我自己夹成 1000"伪装成了"设备夹成 1000"。
                val sent = bkl.lastCommand.ifEmpty { "—（没发出去）" }
                val line = when (r) {
                    is TntgoBkl.Result.Ok ->
                        "打算 $v ｜ 实际发 `$sent` ｜ 回读 +BKL=${r.mcu}" +
                                if (r.mcu == v) "   ✓ 原值返回" else "   ⚠★ 设备改成了 ${r.mcu}"
                    is TntgoBkl.Result.Rejected ->
                        "打算 $v ｜ 实际发 `$sent` ｜ ★★ 设备拒绝：+ERROR=${r.code}   ← 超量程"
                    TntgoBkl.Result.NoDevice -> "打算 $v ｜ 设备不在"
                    TntgoBkl.Result.NoPermission -> "打算 $v ｜ 没权限"
                    is TntgoBkl.Result.Busy -> "打算 $v ｜ 串口忙：${r.why}"
                    is TntgoBkl.Result.Failed -> "打算 $v ｜ 失败：${r.why}"
                }
                Log.i(TAG, "量程对账 $line")
                sb.append(line).append("\n")
                post(sb.toString())
                Thread.sleep(300)
            }
            // 收尾：恢复到量程内的安全值，别把屏留在奇怪的状态
            val back = bkl.setRawMcu(2000)
            sb.append("\n收尾：已设回 `at+bkl=2000`  →  $back")
            post(sb.toString())
        }
    }

    // ------------------------------------------------------------------ 显示能力

    /**
     * ★★ 问系统：这块屏支不支持 HDR / 广色域。
     *
     * **为什么这个答案可信**：`Display.getHdrCapabilities()` 的值来自
     * **SurfaceFlinger → HWC → 显示器 EDID 的 HDR 静态元数据块**，
     * 也就是**显示器自己在 EDID 里申报的能力** —— 不是 Android 猜的。
     *
     * ⚠️ 注意区分两个 displayId：
     * - `100000` = **TNT 虚拟屏**（`hdrCapabilities = null`，因为它是虚拟的、没有物理 EDID）
     * - `4`      = **真正的 HDMI/DP 接收端**（TNT GO 的面板就挂在它后面）⇒ **要看这个**
     */
    private fun describeDisplays(): String {
        val dm = getSystemService(DisplayManager::class.java) ?: return "（拿不到 DisplayManager）"
        return dm.displays.joinToString("\n") { d ->
            val hc = d.hdrCapabilities
            val types = runCatching {
                hc?.supportedHdrTypes?.joinToString("/") { hdrName(it) }.orEmpty()
            }.getOrDefault("")
            val w = d.mode?.physicalWidth ?: 0
            val h = d.mode?.physicalHeight ?: 0
            "· id=${d.displayId}「${d.name}」${w}×${h}\n" +
                    "    HDR=${d.isHdr}  类型=[${types.ifEmpty { "无" }}]   广色域=${d.isWideColorGamut}\n" +
                    "    ★ " + hdrLuminance(hc)
        }
    }

    /**
     * ★★★ 从 EDID 的 **HDR 静态元数据块**里读**尼特范围**。
     *
     * | 字段 | 含义 |
     * |---|---|
     * | `getDesiredMaxLuminance` | **峰值亮度**(nit) —— 这才是"HDR 能到多亮"的正解 |
     * | `getDesiredMaxAverageLuminance` | 帧平均亮度上限(通常 ≈ 峰值 × 0.5) |
     * | `getDesiredMinLuminance` | **黑场下限**(nit) —— 越小越好 |
     *
     * ⚠️ 用**反射**调：这几个 getter 在 A10 的公开 SDK 里**不一定可见**，
     * 直接写会编译不过；反射失败就如实显示 `?`，**不猜**。
     * ⚠️ 值为 `0` 的含义 = **显示器没申报这一项**（不是"亮度为 0"）。
     */
    private fun hdrLuminance(hc: Display.HdrCapabilities?): String {
        if (hc == null) return "HdrCapabilities = null（虚拟屏没有物理 EDID）"
        fun call(name: String): String = runCatching {
            val v = Display.HdrCapabilities::class.java.getMethod(name).invoke(hc) as Float
            if (v <= 0f) "$v（未申报）" else "$v"
        }.getOrElse { "?" }
        return "EDID 申报亮度(nit)：峰值=" + call("getDesiredMaxLuminance") +
                "  平均上限=" + call("getDesiredMaxAverageLuminance") +
                "  黑场=" + call("getDesiredMinLuminance")
    }

    private fun hdrName(t: Int): String = when (t) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DolbyVision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "type$t"
    }

    /**
     * 一个「标题 + 当前值 + 滑杆」的小控件（任务 AJ 的设置项用）。
     *
     * ⚠️ 参数名故意不叫 `max` / `init` —— 会和 `SeekBar.max`、Kotlin 的 `init` 撞。
     */
    private fun addSlider(
        root: LinearLayout,
        title: String,
        minV: Int,
        maxV: Int,
        initV: Int,
        fmt: (Int) -> String,
        onChange: (Int) -> Unit,
    ) {
        val pad = (6 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "$title：${fmt(initV)}"
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, pad, 0, 0)
        }
        val sb = SeekBar(this).apply {
            max = maxV - minV
            progress = (initV - minV).coerceIn(0, max)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + minV
                tv.text = "$title：${fmt(v)}"
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) { /* 不用 */ }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { /* 已经实时存了 */ }
        })
        root.addView(tv)
        root.addView(sb)
    }

    private fun post(text: String) = handler.post { probeView.text = text }

    /**
     * ★★ 模拟一次长按 —— **走和真按键完全同一条代码路径**
     * （[BrightnessCore.onKeyDown] → 阈值到点 → 无极调节 → [BrightnessCore.onKeyUp]）。
     *
     * 为什么要有它：★ **真按键只能人按**（`input keyevent` 到不了无障碍按键过滤器，任务 AI 实测），
     * ⇒ 没有这个入口，**整条状态机就无法自动化验证、也没法回归**。
     */
    private fun simulateHold(dir: Int, ms: Long) {
        Log.i(TAG, "▶ 模拟长按 dir=$dir ${ms}ms")
        BrightnessCore.onKeyDown(this, dir)
        handler.postDelayed({ BrightnessCore.onKeyUp() }, ms)
    }

    /** 模拟一次短按（按下后立刻松开，远小于阈值） */
    private fun simulateTap(dir: Int) {
        Log.i(TAG, "▶ 模拟短按 dir=$dir")
        BrightnessCore.onKeyDown(this, dir)
        handler.postDelayed({ BrightnessCore.onKeyUp() }, 60)
    }

    /** A/B 对账：发一个原始 MCU 值（**绕过曲线**,用于人眼判"灯有没有真的变"） */
    private fun setRaw(v: Int) {
        io.execute {
            val bkl = TntgoBkl(this)
            val r = bkl.setRawMcu(v)
            val sent = bkl.lastCommand.ifEmpty { "—" }
            val msg = when (r) {
                is TntgoBkl.Result.Ok ->
                    "A/B 设 `$sent`  →  回读 +BKL=${r.mcu}" +
                            if (r.mcu == v) "   ✓" else "   ⚠★ 设备改成了 ${r.mcu}"
                is TntgoBkl.Result.Rejected ->
                    "A/B 打算设 $v → 实际发 `$sent` ｜ ★★ 设备拒绝：+ERROR=${r.code}" +
                            "（★ 超出量程 ${TntgoBkl.MCU_MIN}~${TntgoBkl.MCU_MAX}，灯【没有】变）"
                else -> "A/B 设 $v 失败：$r"
            }
            Log.i(TAG, msg)
            post(msg + "\n（回读只证明 MCU 收下了值；灯有没有变，请看屏幕）")
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#FFD54F"))
        setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#80CBC4"))
        setPadding(0, (18 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
