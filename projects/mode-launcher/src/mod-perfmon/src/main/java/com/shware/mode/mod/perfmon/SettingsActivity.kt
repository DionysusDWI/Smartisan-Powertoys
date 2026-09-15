package com.shware.mode.mod.perfmon

import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * ★ 本 mod 的**设置界面** —— 选卡片上显示哪些指标。
 *
 * ## 为什么设置放在 mod 自己这里（而不是启动器的管理器里）
 *
 * | 方案 | 取舍 |
 * |---|---|
 * | ★ **mod 自带 Activity** | **选它**：自包含、**零契约改动、零启动器改动** |
 * | 放进启动器的 MOD 管理器 | 要扩契约（`MOD_SETTINGS`）+ 改启动器 —— 列为将来项 |
 *
 * ## 两个入口
 *
 * 1. **手机应用列表里的「手机性能」**（本 Activity 带 `LAUNCHER` 类别）
 * 2. **卡片上双击**
 *
 * ## ⚠️ 要防 TNT 抓走
 *
 * 宿主 `MainActivity` 踩过这个坑：Smartisan 的 TNT 会把新起的 Activity
 * **自动纳管到 TNT 屏**（`SmartisanLaunch: … reason: pc mode`），导致界面被拉伸。
 * ⇒ 这里用同一套自愈：发现不在 `display 0` 就**以手机屏重新启动自己**。
 */
class SettingsActivity : Activity() {

    private lateinit var cfg: PerfConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 与宿主 MainActivity 同一套自愈逻辑（见其注释）
        val cur = windowManager.defaultDisplay.displayId
        if (cur != Display.DEFAULT_DISPLAY) {
            Log.w(TAG, "★ 设置界面被 TNT 纳管到 display $cur —— 重新以手机屏启动")
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            startActivity(intent, opts.toBundle())
            finish()
            return
        }

        cfg = PerfConfig(this)
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val d = resources.displayMetrics.density
        fun dp(n: Int) = (n * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root += TextView(this).apply {
            text = "手机性能 · 设置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }

        root += TextView(this).apply {
            text = "勾选要在卡片上显示的指标。改完立即生效（卡片会自动重建）。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(6), 0, dp(16))
        }

        root += sectionTitle("进度条", d)
        for (label in PerfMetrics.BAR_LABELS) root += checkRow(label)

        root += sectionTitle("文本", d)
        for (label in PerfMetrics.TEXT_LABELS) root += checkRow(label)

        // ---- 操作提示（长按拖动是隐式交互，必须写出来，否则没人知道）----
        root += TextView(this).apply {
            text = buildString {
                appendLine("卡片上的手势：")
                appendLine("  · 长按 → 进入拖动（边框变亮），拖到想要的位置松手")
                appendLine("  · 双击 → 打开本设置")
                appendLine()
                appendLine("⚠️ 卡片会吃掉它自己矩形内的点击（能拖就得收触摸）。")
                append("   挡住什么了就拖走。")
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#546E7A"))
            setPadding(0, dp(20), 0, 0)
        }

        // ---- 复位位置（拖丢了的时候用）----
        root += TextView(this).apply {
            text = "把卡片位置复位到右上角"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, dp(18), 0, 0)
            isClickable = true
            setOnClickListener {
                cfg.clearPos()
                cfg.bump()
                finish()
            }
        }

        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionTitle(s: String, d: Float) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#37474F"))
        setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt())
    }

    private fun checkRow(label: String) = CheckBox(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = cfg.isEnabled(label)
        setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        setOnCheckedChangeListener { _, on -> cfg.setEnabled(label, on) }
    }

    private operator fun LinearLayout.plusAssign(v: android.view.View) {
        addView(
            v,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private companion object {
        const val TAG = "ModeMod/PerfCfg"
    }
}
