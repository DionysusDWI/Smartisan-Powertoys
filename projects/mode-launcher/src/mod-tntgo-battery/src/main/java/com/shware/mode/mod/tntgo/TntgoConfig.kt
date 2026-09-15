package com.shware.mode.mod.tntgo

import android.content.Context

/**
 * ★ **本 mod 的本地配置**（`SharedPreferences`，mod 私有）。
 *
 * 存三件事：
 * 1. **卡片字号缩放**（用户 2026-09-14 §3 要求「做到 mod 设置里面」）
 * 2. **卡片位置**（长按拖动后落下的坐标）
 * 3. 容量学习的状态由 [TntgoCapacity] 管（同一个 prefs 文件，键前缀 `cap.`）
 *
 * ## ⚠️ 位置的两条纪律（都踩过）
 *
 * 1. ★★ **全程只用 `Gravity.TOP or Gravity.START`** ——
 *    亮度 mod 曾混用 `BOTTOM|CENTER_HORIZONTAL`（那种 gravity 下 `x` 是**居中偏移**）
 *    与 `TOP|START`（`x` 是**距左边缘**）⇒ 存出了 `(-145, 90)`，下次启动卡片被甩到屏外。
 * 2. ★ 存的是**窗口坐标 `params.x/y`**（相对内容区），**不是屏幕坐标** ——
 *    读回来直接赋给 `params` 即可自洽（两者差一个状态栏高度）。
 */
object TntgoConfig {

    private const val FILE = "tntgo_battery"

    // ------------------------------------------------------------------ 字号

    /** ★ 字号缩放倍率 */
    const val KEY_FONT = "ui.font.scale"

    /** 没设过 = 1.0（原始大小） */
    const val DEF_FONT = 1.0f

    /** 可选档位（设置界面做成滑杆 + 刻度） */
    val FONT_STEPS = listOf(0.8f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f)

    const val FONT_MIN = 0.7f
    const val FONT_MAX = 2.0f

    // ------------------------------------------------------------------ 位置

    const val KEY_POS_X = "ui.pos.x"
    const val KEY_POS_Y = "ui.pos.y"

    /** 没存过位置的哨兵值（和亮度 mod 用同一套约定） */
    const val POS_UNSET = Int.MIN_VALUE

    // ------------------------------------------------------------------ 卡片尺寸

    /** 卡片宽度 dp（`WRAP_CONTENT` 会随内容跳，定宽更稳） */
    const val CARD_WIDTH_DP = 190

    /** 默认位置：右下角，离底边留出 TNT 任务栏**
     *
     * ⚠️ `y` 要够大 —— **TNT 任务栏是系统装饰层，在 overlay 之上**，
     * 压上去的话任务栏会盖住卡片底边（[Q1 §9.1](../plans/Q1-Mod框架.md) 层次表）。
     */
    const val DEFAULT_BOTTOM_INSET_DP = 90
    const val DEFAULT_RIGHT_INSET_DP = 40

    // ------------------------------------------------------------------ 读写

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun fontScale(ctx: Context): Float =
        sp(ctx).getFloat(KEY_FONT, DEF_FONT).coerceIn(FONT_MIN, FONT_MAX)

    fun setFontScale(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(KEY_FONT, v.coerceIn(FONT_MIN, FONT_MAX)).apply()
    }

    fun posX(ctx: Context): Int = sp(ctx).getInt(KEY_POS_X, POS_UNSET)

    fun posY(ctx: Context): Int = sp(ctx).getInt(KEY_POS_Y, POS_UNSET)

    /** 存卡片位置。⚠️ 传的是**窗口坐标** `params.x/y`，不是屏幕坐标 */
    fun savePos(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
    }

    /** 「复位位置」—— 下次挂卡片时回到默认的右下角 */
    fun clearPos(ctx: Context) {
        sp(ctx).edit().remove(KEY_POS_X).remove(KEY_POS_Y).apply()
    }
}
