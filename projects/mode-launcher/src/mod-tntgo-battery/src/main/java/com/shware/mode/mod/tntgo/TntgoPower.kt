package com.shware.mode.mod.tntgo

import kotlin.math.abs

/**
 * ★★★ **功耗 / 可用时间的算法**（任务 AQ · AQ3）。
 *
 * ## 为什么单独一个文件、而且【不依赖任何 Android API】
 *
 * 这样公式就能在 **PC 上离线验算**，不用装机、不用等 30 秒轮询。
 * 离线验算脚本：[`scripts/verify_tntgo_power.py`](../../../../../../../../scripts/verify_tntgo_power.py)
 * ⇒ **两边必须算出同样的数**（改这里就要回去跑那个脚本）。
 *
 * ## ★★ 数据来源与它**不包含**什么（用户 2026-09-15 裁决）
 *
 * `+BATCG = <电压mV>,<电量%>,<状态>,<电流mA>,<温度×0.1°C>,<?>`
 *
 * 我们算的是**电池侧**的功率 `|I| × V`。
 *
 * ⚠️⚠️ **它 = TNT GO 自身耗电 ＋ 输送给手机的那部分**。
 * 任务 AQ2 已实测：**端口功率读不到**（`AT+BQ25970` 的 ADC 块恒 0、
 * `BQ25890` 无响应、`USBSTATE` 是禁区、手机 sysfs 对 shell 与 app 都拒）
 * ⇒ **扣不掉**。用户裁决：**只报总功耗，界面上明确标注「含对外供电」**。
 *
 * ⇒ ★ **界面文案里那行「含对外供电」不是装饰，是这条数据的一部分。不要删。**
 */
object TntgoPower {

    /** ★ 用户指定的保守系数：显示值 = 估算值 × 0.9 */
    const val CONSERVATIVE = 0.90

    /** 超过这个小时数就不给精确值了（见 [formatHours] 的说明） */
    const val MAX_HOURS = 99.0

    /** 电流取最近多少次的中位数 */
    const val MEDIAN_WINDOW = 5

    /** 少于这么多次有效读数就不给可用时间 */
    const val MIN_SAMPLES = 3

    // ------------------------------------------------------------------ 平滑

    /**
     * 取中位数。
     *
     * ★ **为什么是中位数而不是均值**：串口读数偶发跳变（实测同一段里
     * 相邻两次能差出一倍），均值会被单次坏值**整个拖偏**，中位数对它免疫。
     *
     * @return 空列表返回 `null`（**不返回 0** —— 0 会被当成"电流为零"）
     */
    fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    // ------------------------------------------------------------------ 功耗

    /**
     * 电池侧瞬时功耗（W）。`V` 电池电压 mV，`I` 电池电流 mA。
     *
     * ⚠️ 取绝对值 —— 充电时也算"功率"，只是含义不同（见 [estimate]）。
     * ⚠️ **不含对外供电的扣除**（扣不掉，见类注释）。
     */
    fun watts(millivolts: Int, currentMa: Int): Double =
        abs(currentMa).toDouble() * millivolts / 1_000_000.0

    /** `≈ 5.6 W` */
    fun formatWatts(w: Double): String = "≈ %.1f W".format(w)

    // ------------------------------------------------------------------ 可用时间

    /** 可用时间的估算结果。★ 用**密封类而不是 nullable Double** —— 四种"不给数"的原因要分开说。 */
    sealed class Estimate {
        /** 有数可给（**已经乘过 0.9**） */
        data class Hours(val h: Double) : Estimate()

        /** 正在充电 ⇒ 没有"还能用多久"这回事 */
        object Charging : Estimate()

        /** 估算值超过 [MAX_HOURS] ⇒ 只给一句 `> 99 h` */
        object TooLong : Estimate()

        /** 有效样本不够（刚启动 / 刚插上） */
        data class Warming(val have: Int, val need: Int) : Estimate()

        /** 压根没读到数（没插 TNT GO / 读失败） */
        object NoData : Estimate()
    }

    /**
     * 估算可用时间。
     *
     * ```
     * t_显示(h) = (C × SOC% / 100) / |I| × 0.90
     * ```
     *
     * @param soc 电量 %
     * @param currentMa ★ 应当是**已经平滑过**的电流（见 [median]）
     * @param capacityMah 当前采用的容量（先验 or 自学习值，见 [TntgoCapacity]）
     * @param sampleCount 目前累计的有效读数个数
     */
    fun estimate(
        soc: Int,
        currentMa: Int,
        capacityMah: Double,
        sampleCount: Int,
    ): Estimate {
        if (capacityMah <= 0.0) return Estimate.NoData
        if (sampleCount < MIN_SAMPLES) return Estimate.Warming(sampleCount, MIN_SAMPLES)

        // ★ 充电中（含"恰好为 0"）⇒ 不给时间。
        //   给一个"还能用很久"是**误导** —— 它正在变多，不是变少。
        if (currentMa >= 0) return Estimate.Charging

        val i = abs(currentMa).toDouble()
        val raw = capacityMah * soc / 100.0 / i          // 小时
        val shown = raw * CONSERVATIVE

        // ★★ 按【时间】截断，不按【电流】截断（任务 AQ 离线验算查出来的）。
        //    曾经写成 `if (|I| < 100) "> 99h"` —— 结果 I = -100 时算出 86.9 h，
        //    一个毫无意义却看着很精确的数。阈值取多少都会在边界漏一段，
        //    直接对结果截断就没这个问题。
        if (shown > MAX_HOURS) return Estimate.TooLong

        return Estimate.Hours(shown)
    }

    /** 把估算结果变成卡片上那一行**
     *
     * ⚠️ **`≈` 一律保留** —— 容量是学的/网络来的，不是实测的。
     */
    fun formatEstimate(e: Estimate): String = when (e) {
        is Estimate.Hours -> "≈ %.1f h".format(e.h)
        Estimate.Charging -> "↑ 充电中"
        Estimate.TooLong -> "> 99 h"
        is Estimate.Warming -> "估算中… (${e.have}/${e.need})"
        Estimate.NoData -> "—"
    }
}
