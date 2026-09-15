package com.shware.mode.mod.perfmode

import android.content.Context

/** 性能模式的可调参数 */
object Prefs {

    private const val FILE = "perfmode_prefs"

    const val KEY_MINUTES = "minutes"
    const val KEY_TEMP_LIMIT = "temp_limit"
    const val KEY_TIER = "tier"

    /** 默认 15 分钟 */
    const val DEF_MINUTES = 15

    /**
     * ★★★★★ 默认档位 = **强力**（2026-09-13 据实测调整，原为"均衡"）。
     *
     * ## 为什么改
     *
     * 任务 AN 实测（详见 [PerfLock.Tier] 的表格）：
     *
     * | 场景 | 均衡（只抬频） | 强力（＋禁 PC） |
     * |---|---|---|
     * | 突发负载 | +26% | ★★ **+75%** |
     * | **AV1 软解** | ⚠️ **−9.0%**（波动 76%） | ★★ **+35.8%**（稳定） |
     * | 空闲功耗 | ≈ 0 | +60 mA |
     * | 满载能效 | 每帧 −12% | 每帧 −3.7% |
     *
     * ★ **均衡档有一个很糟的性质：「只能在没有真实负载时显得有用」** ——
     * 真实重负载下它是**负的且极不稳定**。
     *
     * ## 代价可控
     *
     * 强力的空闲代价是 **+60 mA**，但：
     * - 默认时长只有 15 分钟 ⇒ `+60 mA × 15 min ≈ 0.4%` 电量
     * - 有**温度闸门**与**自动过期**兜底
     * - ★ 满载时它反而**能效为正**（每帧能耗 −3.7%）
     *
     * ⇒ ★ **默认强力；「轻量」留给"想长时间挂着"的场景。**
     */
    const val DEF_TIER = "strong"

    /**
     * 默认温度上限 65 °C。
     * ⚠️ 这是读 `/sys/class/thermal` 里的**最高 zone**（已排除 `lmh-*` 限值节点），
     * 不是严格的"结温" —— **保守取 65** 而不是贴着 75~80。
     */
    const val DEF_TEMP_LIMIT = 65

    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 120

    const val MIN_TEMP = 45
    const val MAX_TEMP = 80

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun minutes(ctx: Context): Int =
        sp(ctx).getInt(KEY_MINUTES, DEF_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun tempLimit(ctx: Context): Int =
        sp(ctx).getInt(KEY_TEMP_LIMIT, DEF_TEMP_LIMIT).coerceIn(MIN_TEMP, MAX_TEMP)

    /** ★ 档位 key（见 [PerfLock.Tier]） */
    fun tier(ctx: Context): String = sp(ctx).getString(KEY_TIER, DEF_TIER) ?: DEF_TIER

    fun put(ctx: Context, key: String, v: Int) {
        sp(ctx).edit().putInt(key, v).apply()
    }

    fun putStr(ctx: Context, key: String, v: String) {
        sp(ctx).edit().putString(key, v).apply()
    }
}
