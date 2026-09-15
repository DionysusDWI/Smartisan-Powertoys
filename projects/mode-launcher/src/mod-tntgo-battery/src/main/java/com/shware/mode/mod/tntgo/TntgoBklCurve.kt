package com.shware.mode.mod.tntgo

import kotlin.math.abs

/**
 * ★★★★★ **亮度 → 电流 曲线的算法核心**（任务 AR12b）。
 *
 * ## 为什么单独一个文件、而且【不依赖任何 Android API】
 *
 * ★ 与 [TntgoPower] 同源的理由：**公式要能在 PC 上离线验算**，
 * 不用装机、不用等 30 秒轮询。
 * 离线验算脚本：`scripts/analyze_bkl_curve.py`
 * ⇒ **两边必须算出同样的数**（改这里就要回去跑那个脚本）。
 *
 * ## ★★★★ 自变量域 = **MCU**，不是 UI（AR12c-2 已判定）
 *
 * `UI → MCU` 是**出厂 gamma 曲线**（`TntgoBkl.rawCurve`，三次式）。
 * 实测把「线性于 MCU」与「线性于 UI」两个模型放到它们分歧最大的那一点
 * （`UI=66 / MCU=497`）上比：
 *
 * | 模型 | 预测 | 与实测 1248 mA 之差 |
 * |---|---|---|
 * | ★ **线性于 MCU** | **1238.6** | ★ **0.3 × MAD** |
 * | 线性于 UI | 1584.2 | 10.8 × MAD |
 *
 * ⇒ ★ **拟合与插值一律落在 MCU 域**（UI 是**感知**量纲，不是电流的量纲）。
 *
 * ## ★★★ 为什么"每档取中位数、再在中位数上拟合"
 *
 * | 做法 | 问题 |
 * |---|---|
 * | 对**原始样本**做最小二乘 | 档内样本数不均衡（用户在某档停得久）⇒ **那一档的权重被样本数垄断**，而这与"那里更重要"无关 |
 * | **先取档内中位数、再拟合** | ★ 每一档**只贡献一个点**；且中位数对串口偶发跳变免疫（实测相邻两次能差一倍） |
 *
 * ## ★★ 为什么必须按**充/放电分开**
 *
 * `+BATCG` 的电流是**电池侧净电流**，含义随状态**整个翻转**：
 *
 * | 状态 | `I` 的含义 | 亮度↑ 时 |
 * |---|---|---|
 * | **放电**（`I < 0`） | `I = −(负载 − 外部供给)` | `\|I\|` **更大** |
 * | **充电**（`I > 0`） | `I = 充电器供给 − 负载` | `I` **更小**（负载吃掉供给） |
 *
 * ⇒ ★ 混在一起拟合 ⇒ 曲线被充电状态**整个淹没**，而且**看不出来**。
 *
 * ## ★★★★ 两条"不猜"的红线（与 AR §阶段 C+ 同源）
 *
 * 1. **实测带外 ⇒ 不外推。** 若某档电流只测到 `MCU ≤ 1000`，用户却在 `MCU 2000`，
 *    把这条直线延长过去是 `≈ 2.9 W` —— **可能差一倍多，却看着很精确**。
 *    ⇒ [estimate] 返回 [Where.AboveBand] / [Where.BelowBand]，由调用方**降级**，
 *      ★ 与"亮度未知 ⇒ 显示亮度未知"是同一条纪律。
 * 2. **档位太少 ⇒ 不给曲线。** 只有一档时斜率是 `0/0`，
 *    硬拟合出来的是一条**恰好过该点的水平线** —— 那是编的，不是学的。
 */
object TntgoBklCurve {

    /**
     * 台账里的**一档**：某个 MCU 档位上驻留期间的 `|I|` 中位数。
     *
     * ⚠️ 它是**从原始样本算出来的**（不是原始样本本身）——
     * 但**口径公开、可离线重算**，且回读时**必带 n**，
     * ⇒ 不会变成"只能信它、没法验它"的黑盒。
     */
    data class Level(
        /** 该档 MCU 的代表值（档内样本 MCU 的**中位数**） */
        val mcu: Int,
        /** 该档 `|I|` 的中位数（mA） */
        val absMa: Double,
        /** 该档的样本数 —— ★ 必须一直带着它（回读时要靠它判断可信度） */
        val n: Int,
    )

    /** 充/放两张表 —— ★ 绝不混（见类注释） */
    data class Curves(
        val charging: List<Level> = emptyList(),
        val discharging: List<Level> = emptyList(),
        /** 台账里**解析不出 MCU** 的老样本条数（3 字段格式 ⇒ 用不上，不算错） */
        val legacyNoMcu: Int = 0,
    ) {
        fun levels(forCharging: Boolean): List<Level> =
            if (forCharging) charging else discharging
    }

    /** 拟合结果：一条直线 `|I| = a + b·MCU` */
    data class Line(val a: Double, val b: Double) {
        fun at(mcu: Double): Double = a + b * mcu
    }

    /** 拟合的**结论等级** —— 两种情况必须分开，不许含糊 */
    enum class Gate {
        /** 档位 ≥ [MIN_LEVELS] 且数值有效 ⇒ 可以插值 */
        Ok,

        /** 档位太少 ⇒ ★ **不给曲线**（不是"给一条差的"） */
        NotEnoughLevels,
    }

    /** 当前亮度落在实测带的哪里 */
    enum class Where {
        /** ★ 带内 ⇒ **插值**（这是唯一可以给出数值的情形） */
        InBand,

        /** 低于实测带下沿 ⇒ ★ **不外推**，用带内最暗端如实说"该处未测" */
        BelowBand,

        /** 高于实测带上沿 ⇒ ★ **不外推**，用带内最亮端如实说"该处未测" */
        AboveBand,

        /** 该充放状态压根没有档位 */
        NoLevels,
    }

    /** 一次"当前亮度下电流是多少"的查询结果 */
    data class Estimate(
        /** 预测的 `|I|`（mA）；**仅在 [where] == [Where.InBand] 时可信** */
        val absMa: Double?,
        val where: Where,
        /** 带内最近一端的实测 `|I|`（降级显示时用它，并如实标注"该处未测"） */
        val clampAbsMa: Double?,
        /** 实测带的 MCU 范围 */
        val bandMcuMin: Int?,
        val bandMcuMax: Int?,
        /** 参与拟合的档位数 */
        val levels: Int,
    )

    /** 拟合需要的最少档位数 —— ⚠️ 1 档时斜率是 `0/0`，**拟合出来的是编的** */
    const val MIN_LEVELS = 2

    /**
     * ★★★★ **把样本按「MCU 档位」聚成桶，再取桶内中位数。**
     *
     * ## 为什么不是固定桶宽
     *
     * 用户是按**按键**调亮度的 ⇒ MCU 值落在**离散的几个档位**上（实测 `63 / 497 / 2000`）。
     * 固定桶宽（比如 200）会把 `497` 与 `604` 分到不同桶、又把 `1900` 与 `2000` 合并 ——
     * 而这个分合**与物理无关**。
     *
     * ★ 改用**相邻值聚类**（与簇内**首位**相差 ≤ [tolerance] 就并入）：
     * 无极调节产生的 ±1~2 抖动会被并进同一档，而真正不同的档位分得开。
     *
     * @param mcuOf 从样本取 MCU；返回 `null` = 该样本**没有 MCU**（旧 3 字段格式）⇒ 跳过
     * @param tolerance 并档容差（见 [TOLERANCE_MCU] 的由来）
     * @param bucketMcu 兜底上界：一簇最多跨多少 MCU（防"用户连续扫亮度"把一整段并成一档）
     * @return 按 MCU 升序的档位表
     */
    fun <T> plateaus(
        samples: List<T>,
        mcuOf: (T) -> Int?,
        absMaOf: (T) -> Double,
        tolerance: Int = TOLERANCE_MCU,
        bucketMcu: Int = MAX_BUCKET_MCU,
    ): List<Level> {
        val pts = samples
            .mapNotNull { s -> mcuOf(s)?.let { it to absMaOf(s) } }
            .filter { (_, ma) -> ma > 0.0 }
            .sortedBy { it.first }
        if (pts.isEmpty()) return emptyList()

        val out = ArrayList<Level>()
        var start = 0
        var lo = pts[0].first                       // 簇内**最小**值 —— 容差与宽度都从它量
        for (i in 1..pts.size) {
            val over = i == pts.size ||
                    (pts[i].first - lo > tolerance) ||
                    (pts[i].first - lo > bucketMcu)
            if (over) {
                out.add(level(pts, start, i))
                if (i < pts.size) { start = i; lo = pts[i].first }
            }
        }
        return out
    }

    private fun level(pts: List<Pair<Int, Double>>, from: Int, to: Int): Level {
        val sl = pts.subList(from, to)
        return Level(
            mcu = medianInt(sl.map { it.first }),
            absMa = median(sl.map { it.second }) ?: 0.0,
            n = sl.size,
        )
    }

    /**
     * ★ **加权最小二乘拟合一条直线**（权重 = 档位样本数 `n`）。
     *
     * 权重只用来**区分证据强弱**（某档停了 30 个样本、另一档只有 6 个），
     * ★ 而不是让某一档垄断拟合 —— 那件事已经由"先取档内中位数"解决了。
     *
     * @return `null` = 数值退化（档位不足 / 所有 MCU 相同）⇒ **调用方必须当作"不给曲线"**
     */
    fun fitLine(levels: List<Level>): Line? {
        if (levels.size < MIN_LEVELS) return null
        val xs = levels.map { it.mcu.toDouble() }
        if (xs.max() - xs.min() < 1.0) return null      // 全在同一档 ⇒ 斜率无定义

        val w = levels.map { it.n.coerceAtLeast(1).toDouble() }
        val sw = w.sum()
        val mx = levels.mapIndexed { i, l -> w[i] * l.mcu }.sum() / sw
        val my = levels.mapIndexed { i, l -> w[i] * l.absMa }.sum() / sw
        var sxx = 0.0
        var sxy = 0.0
        for (i in levels.indices) {
            val dx = levels[i].mcu - mx
            sxx += w[i] * dx * dx
            sxy += w[i] * dx * (levels[i].absMa - my)
        }
        if (sxx <= 1e-9) return null
        val b = sxy / sxx
        return Line(a = my - b * mx, b = b)
    }

    /** 拟合的**结论等级**（★ 不足就不给，而不是给一条差的） */
    fun gate(levels: List<Level>): Gate =
        if (levels.size >= MIN_LEVELS && fitLine(levels) != null) Gate.Ok else Gate.NotEnoughLevels

    /**
     * ★★★★ **问"当前亮度下电流是多少"** —— 带外**绝不外推**。
     *
     * @param mcu 当前亮度的 **MCU 域**值（不是 UI）
     */
    fun estimate(levels: List<Level>, mcu: Int): Estimate {
        if (levels.isEmpty()) {
            return Estimate(null, Where.NoLevels, null, null, null, 0)
        }
        val sorted = levels.sortedBy { it.mcu }
        val lo = sorted.first()
        val hi = sorted.last()
        val line = fitLine(levels)
        if (line == null) {
            // 只有一档（或数值退化）⇒ ★ 给不出斜率，只能说"该处未测"
            val only = sorted.first()
            return Estimate(
                absMa = null,
                where = if (mcu < only.mcu) Where.BelowBand else Where.AboveBand,
                clampAbsMa = only.absMa,
                bandMcuMin = lo.mcu, bandMcuMax = hi.mcu, levels = levels.size,
            )
        }
        val where = when {
            mcu < lo.mcu -> Where.BelowBand
            mcu > hi.mcu -> Where.AboveBand
            else -> Where.InBand
        }
        return Estimate(
            absMa = if (where == Where.InBand) line.at(mcu.toDouble()) else null,
            where = where,
            clampAbsMa = if (mcu < lo.mcu) lo.absMa else hi.absMa,
            bandMcuMin = lo.mcu,
            bandMcuMax = hi.mcu,
            levels = levels.size,
        )
    }

    /** ★★ **单调性自检**：相邻档位中位数差的最大**反向**跳变（mA）。
     *
     * 物理上 `|I|` 随 MCU 单调递增。若出现反向跳变，说明台账里有别的东西在漂
     * （正是 AR12c 那条 860 mA 反例所担心的）⇒ 曲线**不该被采用**。
     *
     * @return `null` = 档位不足，无法判断
     */
    fun maxDrop(levels: List<Level>): Double? {
        val s = levels.sortedBy { it.mcu }
        if (s.size < 2) return null
        var worst = 0.0
        for (i in 1 until s.size) {
            val d = s[i - 1].absMa - s[i].absMa     // 更亮的一档却更省电 ⇒ 正数（反物理）
            if (d > worst) worst = d
        }
        return worst
    }

    // ------------------------------------------------------------------ 小工具

    /** 中位数（`Double`）—— ★ 与 AR12 / N9 同一个口径：**先排序再取中间** */
    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun medianInt(v: List<Int>): Int {
        if (v.isEmpty()) return 0
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /**
     * 相邻 MCU 值并成一档的容差。
     *
     * 依据：无极调节时用户看到的 UI 不变、但 MCU 会有 ±1~2 的抖动；
     * 而实测的**档位间距**最小也有 `63 → 497`（434）。
     * ⇒ 40 远小于真实间距、又远大于抖动 ⇒ 两侧都安全。
     */
    const val TOLERANCE_MCU = 40

    /** 兜底上界：一簇最多跨多少 MCU（防连续扫亮度把一整段并成一档） */
    const val MAX_BUCKET_MCU = 300
}
