package com.shware.mode.mod.brightness

import android.content.Context
import android.content.SharedPreferences

/**
 * ★ 亮度键行为的可调参数（任务 AJ）。
 *
 * 存在 `SharedPreferences` 里 ⇒ [KeyFilterService]（按键侧）与 [SettingsActivity]（设置界面）
 * **同一个进程、同一份配置**，改完立即生效（**每次按键都重新读，不做内存缓存** —— 省得还要处理失效）。
 *
 * ## 为什么这几个值要可调
 *
 * 用户的原始要求：「短按保留段落感，长按无极调节；长按阈值 200ms（**设置中可调**）；
 * 无极调节的 BKL 变化速度**也可调**，但**应当保持线性，不要加速**」。
 */
object Prefs {

    private const val FILE = "brightness_prefs"

    /** ★ 长按判定阈值（ms）—— 首次按下后超过它仍处于按下状态 ⇒ 判定为长按 */
    const val KEY_LONG_MS = "long_press_ms"

    /** ★ 无极调节速度 —— 单位见 [KEY_DOMAIN] */
    const val KEY_RATE = "ramp_rate"

    /** 短按步进（UI %） */
    const val KEY_STEP = "tap_step"

    /**
     * ★★ **线性域** —— 无极调节"匀速"是在哪个量纲上匀速。
     *
     * | 值 | 含义 | 手感 |
     * |---|---|---|
     * | `bkl`（默认） | 按 **BKL(MCU) 值**匀速 —— **用户的原始说法** | ⚠️ 感知上**前快后慢**：<br/>BKL 9→2000 里，前 10% 的路程就走完了 UI 的 0→50% |
     * | `ui` | 按 **UI(感知) 值**匀速 | ★ 感知上**真正匀速**（全程一样快） |
     *
     * ⇒ 默认给 `bkl`（照用户说的做），但**留着开关**，因为两者的手感差别很大。
     */
    const val KEY_DOMAIN = "ramp_domain"

    const val DOMAIN_BKL = "bkl"
    const val DOMAIN_UI = "ui"

    // ------------------------------------------------------------------ ★ OSD 卡片位置（用户可拖动）

    const val KEY_POS_X = "osd_x"
    const val KEY_POS_Y = "osd_y"

    /** 没存过位置的哨兵值 */
    const val POS_UNSET = Int.MIN_VALUE

    // ------------------------------------------------------------------ 默认值

    const val DEF_LONG_MS = 200
    const val DEF_RATE = 600          // BKL/秒 ⇒ 满量程(1991)约 3.3 秒
    /** ★ 短按步进：用户 2026-09-13「一次短按对应 2% 亮度」（原为 5，段落感太粗） */
    const val DEF_STEP = 2
    const val DEF_DOMAIN = DOMAIN_UI   // ★ 默认给"感知匀速"（真正匀速；BKL 域会前快后慢）

    /** 取值范围（给设置界面做滑杆用） */
    const val LONG_MS_MIN = 50
    const val LONG_MS_MAX = 600

    const val RATE_MIN = 50
    const val RATE_MAX = 3000

    const val STEP_MIN = 0
    const val STEP_MAX = 20

    // ------------------------------------------------------------------ 读写

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun longPressMs(ctx: Context): Int =
        sp(ctx).getInt(KEY_LONG_MS, DEF_LONG_MS).coerceIn(LONG_MS_MIN, LONG_MS_MAX)

    fun rate(ctx: Context): Int =
        sp(ctx).getInt(KEY_RATE, DEF_RATE).coerceIn(RATE_MIN, RATE_MAX)

    fun step(ctx: Context): Int =
        sp(ctx).getInt(KEY_STEP, DEF_STEP).coerceIn(STEP_MIN, STEP_MAX)

    fun domain(ctx: Context): String =
        sp(ctx).getString(KEY_DOMAIN, DEF_DOMAIN).takeIf { it == DOMAIN_UI } ?: DOMAIN_BKL

    fun put(ctx: Context, key: String, value: Int) {
        sp(ctx).edit().putInt(key, value).apply()
    }

    fun putDomain(ctx: Context, value: String) {
        sp(ctx).edit().putString(KEY_DOMAIN, value).apply()
    }

    // ------------------------------------------------------------------ OSD 位置

    /** 用户拖过的位置；没拖过返回 [POS_UNSET]（此时用默认的"底部居中"） */
    fun posX(ctx: Context): Int = sp(ctx).getInt(KEY_POS_X, POS_UNSET)

    fun posY(ctx: Context): Int = sp(ctx).getInt(KEY_POS_Y, POS_UNSET)

    /**
     * ★ 存卡片位置。
     *
     * ⚠️ 存的是**窗口坐标 `params.x/y`**（相对内容区），不是屏幕坐标 ——
     * 读回来直接赋给 `params` 即可自洽（任务 T 踩过：两者差一个状态栏高度）。
     */
    fun savePos(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
    }
}
