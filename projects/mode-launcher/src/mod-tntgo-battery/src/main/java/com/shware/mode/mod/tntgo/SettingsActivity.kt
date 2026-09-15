package com.shware.mode.mod.tntgo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * ★ **TNT GO 电量 · 设置**（任务 AQ · AQ6）。
 *
 * 用户 2026-09-14 §3：「可以去做**字体大小的缩放设置**（做到 mod 设置里面）」
 *
 * ## 三块内容
 *
 * | # | 块 | 干什么 |
 * |---|---|---|
 * | 1 | **字号缩放** | 滑杆，7 档；改完**卡片立即重建**生效 |
 * | 2 | **容量自学习** | 显示当前采用的容量、已学段数、原始估计值；一个**复位**按钮 |
 * | 3 | **卡片位置** | 一个「复位到右下角」按钮（拖到找不回来时的救命入口） |
 *
 * ## 为什么设置改动能"立即生效"
 *
 * 卡片由 [TntgoBatteryService] 每 30 s 轮询时**顺带比对字号**：
 * 变了就**整块重建卡片**（比逐项比对省事且不会漏 —— 卡片很小，重建开销可忽略）。
 *
 * ⚠️ 本类与 Service **同进程**（manifest 里都写了 `android:process=":mod_tntgobat"`）
 * ⇒ 读的是**同一份 SharedPreferences**，不需要任何 IPC。
 */
class SettingsActivity : Activity() {

    private lateinit var fontValue: TextView
    private lateinit var capValue: TextView
    private lateinit var capDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#F2EDE4"))
        }

        // ── 标题
        root.addView(TextView(this).apply {
            text = "TNT GO 电量 · 设置"
            setTextColor(Color.parseColor("#16202B"))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        // ══════════════════════════════ ① 字号缩放
        root.addView(section("① 字号缩放"))
        root.addView(TextView(this).apply {
            text = "改完卡片会立即重建，不用重开组件。"
            setTextColor(Color.parseColor("#5A6673"))
            textSize = 12f
        })

        fontValue = TextView(this).apply {
            setTextColor(Color.parseColor("#2E7D6F"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(fontValue)

        root.addView(SeekBar(this).apply {
            max = TntgoConfig.FONT_STEPS.size - 1
            progress = TntgoConfig.FONT_STEPS.indexOf(
                TntgoConfig.FONT_STEPS.minByOrNull { kotlin.math.abs(it - TntgoConfig.fontScale(this@SettingsActivity)) }
                    ?: TntgoConfig.DEF_FONT
            ).coerceAtLeast(0)

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = TntgoConfig.FONT_STEPS[p]
                    TntgoConfig.setFontScale(this@SettingsActivity, v)
                    fontValue.text = "当前：%.2f×".format(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) = Toast
                    .makeText(this@SettingsActivity, "字号已改，卡片下一次刷新会重建", Toast.LENGTH_SHORT)
                    .show()
            })
        })
        fontValue.text = "当前：%.2f×".format(TntgoConfig.fontScale(this))

        // ══════════════════════════════ ② 容量自学习
        root.addView(section("② 容量自学习"))
        root.addView(TextView(this).apply {
            text = "容量从网络资料取了先验值（10160 mAh，【不是实测】）。\n" +
                "mod 会在【每次纯放电】里用库仑计数反推真实容量，学够 2 段后自动切换。\n" +
                "★ 学出来的值和我们的电流读数【自洽】 —— 电流若有系统性偏差会被抵消。"
            setTextColor(Color.parseColor("#5A6673"))
            textSize = 12f
            setLineSpacing(0f, 1.2f)
        })

        capValue = TextView(this).apply {
            setTextColor(Color.parseColor("#2E7D6F"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }
        capDetail = TextView(this).apply {
            setTextColor(Color.parseColor("#5A6673"))
            textSize = 11f
            setLineSpacing(0f, 1.2f)
        }
        root.addView(capValue)
        root.addView(capDetail)

        root.addView(btn("复位容量学习") {
            TntgoCapacity(this).reset()
            refreshCapacity()
            Toast.makeText(this, "已复位，回到先验 10160 mAh", Toast.LENGTH_SHORT).show()
        })

        // ══════════════════════════════ ③ 卡片位置
        root.addView(section("③ 卡片位置"))
        root.addView(TextView(this).apply {
            text = "长按卡片进入拖动模式（边框会变琥珀色），松手即保存。\n" +
                "拖到找不回来的位置时，用下面这个按钮复位。"
            setTextColor(Color.parseColor("#5A6673"))
            textSize = 12f
            setLineSpacing(0f, 1.2f)
        })
        root.addView(btn("复位到右下角") {
            TntgoConfig.clearPos(this)
            Toast.makeText(this, "已复位；重启组件后生效（或拖动一下就回默认）", Toast.LENGTH_LONG).show()
        })

        // ══════════════════════════════ 说明
        root.addView(section("关于那几个数字"))
        root.addView(TextView(this).apply {
            text = "· 功耗 = |电流| × 电压（电池侧实测）\n" +
                "  ⚠️ 【含对外供电】 —— TNT GO 输送给手机的那部分也算在里面。\n" +
                "  为什么扣不掉：端口侧的电流电压读不到（AT 口的 ADC 寄存器恒为 0、\n" +
                "  充电 IC 无响应、手机 sysfs 被 SELinux 拒绝）。详见计划书 AQ §2。\n\n" +
                "· 可用时间 = 容量 × 电量 / |电流| × 0.90（保守系数）\n" +
                "  电流取最近 5 次的中位数，避免单次跳变把数字甩来甩去。\n\n" +
                "· 温度 = +BATCG 第 5 字段 ÷ 10"
            setTextColor(Color.parseColor("#93A0AC"))
            textSize = 11f
            setLineSpacing(0f, 1.25f)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshCapacity()
    }

    private fun refreshCapacity() {
        val cap = TntgoCapacity(this)
        val f = cap.fusion
        // ★★ N9：三种态各有各的说法 —— 用户必须能看出"为什么是现在这个数"
        capValue.text = when {
            f == null ->
                "当前采用：≈ %.0f mAh（先验值，未校准 %d/%d 段）"
                    .format(cap.capacityMah, cap.segmentCount, TntgoCapacity.MIN_SEGMENTS)
            f.converged ->
                "当前采用：≈ %.0f mAh（已学习 %d 段）".format(f.mah, f.count)
            else ->
                "当前采用：≈ %.0f mAh（⚠️ 样本未收敛，取其中最保守的一段）".format(f.mah)
        }
        val s = cap.samples()
        capDetail.text = when {
            s.isEmpty() ->
                "还没有积累到有效放电段。\n" +
                    "★ 一段要满足：全程放电、电量掉 ≥5%、历时 ≥10 分钟（★ 中途断档不再作废整段）。"
            f == null ->
                "最近 %d 段学到：%s mAh\n".format(s.size, s.joinToString(" / ") { "%.0f".format(it) }) +
                    "★ 攒够 %d 段才切换 —— 2 个点时「中位数」会退化成均值，抗不了离群。"
                        .format(TntgoCapacity.MIN_SEGMENTS)
            f.converged ->
                "最近 %d 段学到：%s mAh\n".format(s.size, s.joinToString(" / ") { "%.0f".format(it) }) +
                    "取中位数 ≈ %.0f mAh；离散度 %.1f%%（≤ %.0f%% 视为收敛）"
                        .format(f.medianMah, f.dispersion * 100, TntgoCapacity.DISPERSION_MAX * 100)
            else ->
                "最近 %d 段学到：%s mAh\n".format(s.size, s.joinToString(" / ") { "%.0f".format(it) }) +
                    "⚠️ 离散度 %.0f%% 超过 %.0f%% ⇒ 样本互相打架，改取最小值。\n".format(
                        f.dispersion * 100, TntgoCapacity.DISPERSION_MAX * 100) +
                    "★ 此时**不退回先验** —— 先验 %.0f mAh 已知偏高，退回去会把可用时间算长。"
                        .format(TntgoCapacity.PRIOR_MAH)
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#16202B"))
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }
}
