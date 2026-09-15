package com.shware.mode.mod.perfmode

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * ★★★★ 性能模式的控制台（任务 AK）。
 *
 * ## 它是什么
 *
 * 把 **QTI perf HAL 的 `perfLockAcquire`** 暴露成一个开关：
 * **开启后 CPU 频率下限被抬起**（实测空闲时大核 `710400→1804800`、超大核 `825600→2956800`，
 * 突发负载纯计算快 **19.3%**）。
 *
 * ## 三条安全设计（都能在这个界面上看到）
 *
 * | 设计 | 界面上的体现 |
 * |---|---|
 * | **自动过期** | 「总时长」滑杆 + 状态行的**剩余时间** |
 * | **过热兜底** | 「温度上限」滑杆 + 状态行的**当前 SoC 温度** |
 * | ★★ **崩溃自愈** | 说明文字：**锁只有 45 秒寿命，每 15 秒续期** ⇒ 进程死了最多 45 秒自动恢复 |
 *
 * ## ⚠️ 必须诚实告知的代价
 *
 * **抬起频率下限 = 更费电 + 更热。** 本界面把这件事写在明面上，不用模糊措辞。
 */
class SettingsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var freqView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var detailView: TextView
    private var tierGroup: android.widget.RadioGroup? = null

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (14 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "⚡ 性能模式"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFD54F"))
        })
        root.addView(TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#90A4AE"))
            text = "通过 QTI perf HAL 抬起 CPU 频率下限。\n" +
                    "★ 不需要 root：走的是 vendor.perfservice 的 binder（公开 Parcel API 手搓事务）。"
        })

        // ---------- 开关 ----------
        root.addView(section("① 开关"))
        toggleBtn = Button(this).apply {
            textSize = 15f
            isAllCaps = false
            setOnClickListener { onToggle() }
        }
        root.addView(toggleBtn)

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#DDDDDD"))
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(statusView)

        freqView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding(0, pad / 4, 0, pad / 2)
        }
        root.addView(freqView)

        // ---------- 参数 ----------
        root.addView(section("② 档位（★ 代价差别很大，看清楚再选）"))
        tierGroup = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        for (t in PerfLock.Tier.entries) {
            tierGroup!!.addView(android.widget.RadioButton(this).apply {
                id = t.ordinal + 1000
                text = "${t.label} —— ${t.desc}"
                textSize = 12f
                isChecked = Prefs.tier(this@SettingsActivity) == t.key
                setOnClickListener {
                    Prefs.putStr(this@SettingsActivity, Prefs.KEY_TIER, t.key)
                    PerfModeEngine.tier = t
                }
            })
        }
        root.addView(tierGroup)
        root.addView(TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#FFAB91"))
            text = "★★★★ 实测基准是【AV1 软解视频】（最贴近真实重负载 · 全量解 901 帧）：\n" +
                    "   无锁   24.1 fps（稳定地慢，区间 20.5–25.5）\n" +
                    "   只禁PC 42.9 fps（★ 稳定快，极差仅 0.5）\n" +
                    "   ★★ 强力(抬频+禁PC) 44.4 fps（+71%）\n" +
                    "   ⚠️ 轻量(只抬频) 双峰：43.4/43.4/20.6/20.7/43.5\n" +
                    "\n" +
                    "\n" +
                    "⚠️ 机理【仍然不明】：三个假说都被直接测量排除了\n" +
                    "   · 热降频 — 温度不支持（轻量更凉却更慢）\n" +
                    "   · 线程在运行队列等待 — schedstat 四配置无差异\n" +
                    "   · CPU 从深 C-state 恢复慢 — cpuidle 次数无差异\n" +
                    "\n" +
                    "★ 突发负载实测（60 轮纯计算）：无锁 1081 ms ｜ 轻量 789 ｜ 强力 269\n" +
                    "★ 空闲功耗实测：无锁 176 mA ｜ 轻量 173（≈0）｜ 强力 236（+60 mA）\n" +
                    "★ ★★ 满载功耗实测：功耗 +74%，但解码产出 +79%\n" +
                    "   ⇒ 【每帧能耗 −3.7%】—— 满载时反而是【能效正收益】\n" +
                    "★ 算账：+60 mA × 15 分钟 ≈ 0.4% 电量 ⇒ 短时开很划算\n" +
                    "\n" +
                    "⚠️ 对【H.264 硬解】视频无用（硬解时 CPU 92% 空闲）\n" +
                    "⚠️ 对【应用冷启动】无用（IO/加载受限，实测在噪声内）\n" +
                    "\n" +
                    "★ 为什么【默认改成强力】：轻量档表现【双峰、不可预期】；\n" +
                    "  强力档每次都稳定在 44 fps。\n" +
                    "⚠️ 机理仍然不明：热降频 / 运行队列等待 / 深 C-state 三个假说均已被直接测量排除。"
        })

        root.addView(section("③ 参数（改动立即生效，仅对下次开启）"))
        addSlider(root, "总时长", Prefs.MIN_MINUTES, Prefs.MAX_MINUTES, Prefs.minutes(this), { "$it 分钟" }) {
            Prefs.put(this, Prefs.KEY_MINUTES, it)
        }
        addSlider(root, "温度上限", Prefs.MIN_TEMP, Prefs.MAX_TEMP, Prefs.tempLimit(this), { "$it °C" }) {
            Prefs.put(this, Prefs.KEY_TEMP_LIMIT, it)
        }

        // ---------- 说明 ----------
        root.addView(section("④ 安全设计（三道闸门）"))
        root.addView(TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#B0BEC5"))
            text = "① 自动过期：到「总时长」自动释放。\n" +
                    "② 过热兜底：每次续期读一次 SoC 温度，超过上限立刻退出。\n" +
                    "★★ 崩溃自愈：锁【只有 45 秒寿命】，每 15 秒续期一次\n" +
                    "   ⇒ 进程崩了 / 被杀了，**最多 45 秒后频率自己回到正常**，\n" +
                    "     不需要任何残留清理。这是本功能最要紧的一条安全属性。\n\n" +
                    "⚠️ 代价（不粉饰）：抬起频率下限 = 更费电 + 更热。\n" +
                    "   所以默认只有 15 分钟、温度上限 65°C。"
        })

        // ---------- 预设 ----------
        root.addView(section("⑤ 资源 opcode（已实测确证）"))
        detailView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#78909C"))
            text = "⚠️ opcode 顺序是【大核 → 小核 → 超大核】，不是「小/大/超大」\n" +
                    PerfLock.PRESET_PERFORMANCE.toList().chunked(2)
                        .joinToString("\n") { "  0x%08X -> %d".format(it[0], it[1]) } +
                    "\n\n（0x40800000/0100/0200 = 大/小/超大核 CPUBOOST_MAX_FREQ，值 = MHz）" +
                    "\n★ 这个顺序曾经读反过：大核一直在被请求 1785 而不是 2419，" +
                    "\n  表现是「请求 2419 只生效 1804800」。2026-09-13 修正后大核到 2419200。" +
                    "\n★ 值语义：向上取整到该簇最近的可用档位（1000→1056000，1500→1612800）"
        }
        root.addView(detailView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun onToggle() {
        if (PerfModeEngine.state == PerfModeEngine.State.ON) {
            PerfModeEngine.stop("手动关闭")
            toast("已关闭")
        } else {
            val min = Prefs.minutes(this)
            PerfModeEngine.start(min, Prefs.tempLimit(this).toFloat(), PerfLock.Tier.of(Prefs.tier(this)))
            toast("已开启 $min 分钟")
        }
        render()
    }

    private fun render() {
        val on = PerfModeEngine.state == PerfModeEngine.State.ON
        toggleBtn.text = if (on) "■ 关闭性能模式" else "▶ 开启性能模式（${Prefs.minutes(this)} 分钟）"
        statusView.text = PerfModeEngine.statusText()
        statusView.setTextColor(
            when (PerfModeEngine.state) {
                PerfModeEngine.State.ON -> Color.parseColor("#81C784")
                PerfModeEngine.State.ERROR -> Color.parseColor("#EF9A9A")
                PerfModeEngine.State.OFF -> Color.parseColor("#B0BEC5")
            }
        )
        freqView.text = readFreqs()
    }

    /**
     * 读三簇实时频率 —— **app 能读 `/sys`**（任务 T 实测）。
     * ★ 这就是"锁有没有生效"最直观的证据。
     */
    private fun readFreqs(): String {
        val names = arrayOf("policy0 小核", "policy4 大核", "policy7 超大核")
        val sb = StringBuilder("实时频率（kHz）：\n")
        names.forEachIndexed { i, n ->
            val f = runCatching {
                File("/sys/devices/system/cpu/cpufreq/policy${listOf(0, 4, 7)[i]}/scaling_cur_freq")
                    .readText().trim()
            }.getOrNull() ?: "—"
            sb.append("  %-12s %s\n".format(n, f))
        }
        sb.append("  （空闲基线：小核随动 / 大核 710400 / 超大核 825600）")
        return sb.toString()
    }

    // ------------------------------------------------------------------ 小工具

    private fun addSlider(
        root: LinearLayout, title: String, minV: Int, maxV: Int, initV: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit,
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
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + minV
                tv.text = "$title：${fmt(v)}"
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) { /* 不用 */ }
            override fun onStopTrackingTouch(s: SeekBar?) { /* 已实时存 */ }
        })
        root.addView(tv); root.addView(sb)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#80CBC4"))
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
