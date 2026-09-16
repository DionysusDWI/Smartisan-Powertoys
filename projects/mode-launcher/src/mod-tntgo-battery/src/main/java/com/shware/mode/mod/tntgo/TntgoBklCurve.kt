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
 * ## ★★★★ 三条"不猜"的红线（与 AR §阶段 C+ 同源）
 *
 * 1. **实测带外 ⇒ 不外推。** 若某档电流只测到 `MCU ≤ 1000`，用户却在 `MCU 2000`，
 *    把这条直线延长过去是 `≈ 2.9 W` —— **可能差一倍多，却看着很精确**。
 *    ⇒ [estimate] 返回 [Where.AboveBand] / [Where.BelowBand]，由调用方**降级**，
 *      ★ 与"亮度未知 ⇒ 显示亮度未知"是同一条纪律。
 * 2. **档位太少 ⇒ 不给曲线。** 只有一档时斜率是 `0/0`，
 *    硬拟合出来的是一条**恰好过该点的水平线** —— 那是编的，不是学的。
 * 3. ★★ **走向反物理 ⇒ 不给曲线**（**AR13 才真正接上**）。若更亮的一档反而更省电，
 *    说明台账里有**别的东西在漂**（正是 AR12c 那条 860 mA 反例所担心的）；
 *    此时那条直线是被异常点**拽着**的，插值出来的四位数毫安是**假的**。
 *    ⇒ [gate] 返回 [Verdict.Rejected]，[estimate] 降级成"该处未测"。
 *    ⚠️ 方向随充/放**翻转** —— 判据必须带 [Trend]，见 [Trend] 的注释。
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

    /**
     * ★★ **建表的结果**（[plateauing]）：可用档位 ＋ **被 G1 剔掉的薄档数**。
     *
     * ⚠️ `thinDropped` **必须报出来**，不能悄悄扔：读日志的人要能分辨
     * 「这台机器只测到 2 档」与「测到 5 档、其中 2 档证据太薄被剔」——
     * 两者的处置完全不同（前者要用户多测，后者是数据本来就有噪声）。
     */
    data class Plateauing(
        val levels: List<Level>,
        /** 因 `n < MIN_N_PER_LEVEL` 被剔掉的档数 */
        val thinDropped: Int,
    )

    /** 拟合结果：一条直线 `|I| = a + b·MCU` */
    data class Line(val a: Double, val b: Double) {
        fun at(mcu: Double): Double = a + b * mcu
    }

    /**
     * ★★★★ **拟合的结论等级** —— 三种情况必须分开，不许含糊。
     *
     * ⚠️ **AR13 更正**：原版只有 `Ok / NotEnoughLevels` 两种 ⇒ **"反物理"无处可放**，
     * 于是 `maxDrop()`（当时已写好）**零调用点**、文档却写着"两道闸门"。
     * ⇒ 判据接不上线，往往**不是忘了写，而是结论等级里没有它的位置**。
     */
    enum class Verdict {
        /** 档位 ≥ [MIN_LEVELS]、数值有效、且走向与物理一致 ⇒ 可以插值 */
        Ok,

        /** 档位太少 ⇒ ★ **不给曲线**（不是"给一条差的"） */
        NotEnoughLevels,

        /** ★★ 数据走向与**该状态的物理方向相反** ⇒ 有别的东西在漂 ⇒ **曲线不采用** */
        Rejected,
    }

    /**
     * ★★★★ **`|I|` 随亮度的正确走向** —— 充/放电**方向相反**，判据必须带它。
     *
     * | trend | `I` 的含义 | 亮度↑ 时 `\|I\|` 应当 |
     * |---|---|---|
     * | [Discharge] | `I = −(负载 − 外部供给)` | ★ **上升** |
     * | [Charge] | `I = 充电器供给 − 负载` | ★ **下降**（实测 `−0.4295 / 1000 MCU`） |
     *
     * ⚠️⚠️ 这正是"把 [maxDrop] 直接接进 [gate] **会引入 bug**"的原因：
     * `maxDrop` 只表达**放电**方向的违规 ⇒ 会把**正确的充电曲线判死**。
     */
    enum class Trend {
        Discharge, Charge;

        /**
         * ★ 该方向的**正确走向**，一句话 —— 让日志/报告能**自证方向**（C1-e）。
         *
         * ⚠️ 只报"[Rejected]"而不报方向，读的人就得**信任**"与 X 态相悖"这句；
         *    报出走向之后，那一对数字**自己**能说明它为什么违规。
         */
        fun riseVerb(): String = if (this == Discharge) "上升" else "下降"

        /** 违规长什么样 —— 即"变亮反而……" */
        fun badVerb(): String = if (this == Discharge) "更省电" else "更耗电"
    }

    /**
     * 当前亮度落在实测带的哪里 —— ★ **只回答"位置"**，不回答"能不能信"。
     *
     * ⚠️ **G3（2026-09-16）**：这个枚举**曾经**被当成了"给不出数的原因"来用
     * ⇒ 「曲线被拒」被压成 [BelowBand]/[AboveBand] ⇒ 卡片把它说成"该处未测"。
     * 位置与原因**正交**，所以原因被拆到 [Unusable] 去了。
     */
    enum class Where {
        /** ★ 带内 ⇒ **插值**（这是唯一可以给出数值的情形） */
        InBand,

        /** 低于实测带下沿 ⇒ ★ **不外推**，用带内最暗端如实说"该处未测" */
        BelowBand,

        /** 高于实测带上沿 ⇒ ★ **不外推**，用带内最亮端如实说"该处未测" */
        AboveBand,

        /** 该充放状态**一个可用档位都没有** ⇒ 位置本身无从谈起 */
        NoLevels,
    }

    /**
     * ★★★★★ **给不出数的【原因】**（G3，2026-09-16）—— 与 [Where] 正交的第二维。
     *
     * ## 为什么必须有它：两个原因→**两个相反的动作**
     *
     * | 卡片上说的话 | 用户会做什么 |
     * |---|---|
     * | 「该处未测」 | **等** —— 暗处本来就没测过，下次路过就有了 |
     * | 「曲线被拒」 | **重采** —— 数据里有东西在漂，等是等不来的 |
     *
     * ⛔ 而在 G3 之前，这两件事在卡片上**长得一模一样**（都走 `where != InBand` 那一支）。
     *
     * ## ★★ 这是纪律 ⑳ 的**第二次**复发
     *
     * `Verdict` 当初只有 `Ok/NotEnoughLevels` ⇒ 「反物理」**无处可放** ⇒ `maxDrop()` 零调用点。
     * 这里同型：`Where` 里**放不下**"被拒" ⇒ `estimate()` 只能把它压成带外 ⇒ 调用方无从分辨。
     * ⇒ **接线/改文案的第一步，往往是扩充结论的取值域，不是在调用处硬塞一个 `if`。**
     */
    enum class Unusable {
        /**
         * ★★ 走向与**该状态的物理方向相反** ⇒ 有别的东西在漂 ⇒ **曲线不采用**。
         *
         * 卡片要说**为什么**给不出数：这不是"没测过"，是"测了但不可信"。
         */
        Rejected,

        /** 可用档位太少（`<` [MIN_LEVELS]，或 G1 之后**一个都不剩**）⇒ 不给曲线 */
        NotEnoughLevels,
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
        /**
         * ★★★ **给不出数的原因**；`null` = **能给出数**（[where] == [Where.InBand]）。
         *
         * ⚠️ **G3 新增（2026-09-16）**：以前这个信息**根本不存在** ⇒
         * 「曲线被拒」与「该处未测」在卡片上是同一句话（见 [Unusable] 的注释）。
         * ★ 默认值 `null` 是**故意**的：让旧调用点编译期不受影响，
         *   而**新代码必须显式回答"能不能信"**（不填 = 声称可用，所以三处返回点都要填）。
         */
        val unusable: Unusable? = null,
    )

    /** 拟合需要的最少档位数 —— ⚠️ 1 档时斜率是 `0/0`，**拟合出来的是编的** */
    const val MIN_LEVELS = 2

    /**
     * ★★★★★ **一档至少要有多少条样本，才算"一档"**（G1，2026-09-16）。
     *
     * ## 为什么必须有这条 —— 它不是"统计洁癖"，是**算出来的**
     *
     * 闸门判违规靠的是**相邻两档的中位数之差**。样本数少到一定程度时，
     * **中位数本身就不是那个档位的值了**，于是判据会对着一个**不存在的档位**开火：
     *
     * | 真机实测（240 样本台账） | |
     * |---|---|
     * | `mcu=178` 档 | 只有 **2** 条：`1116` 与 `2108`（**充/放切换瞬态**，见下方拓扑说明） |
     * | 中位数 | **1612** —— 落在两个真值**中间**，**这个档位根本不存在** |
     * | 后果 | 它比 `497` 档的 `1344` 还高 ⇒ 判出 `178→497 反向 268 mA` ⇒ **整条放电曲线被拒** |
     * | 剔掉它 | 放电 `980 → 1344 → 2068` **完全单调**，一次违规都没有 |
     *
     * ★ 同一份台账里 `mcu=94` 只有 **1** 条、充电侧 `mcu=23` 只有 **1** 条（`3704`）——
     *   都是同一个形态：**单条样本档能单枪匹马判死整条曲线**。
     *
     * ## ★★ 为什么是 3（而不是"看着顺眼的 5"）
     *
     * ① **中位数要在 n ≥ 3 时才开始"抗得住一条离群"**：n=1 时中位数就是那条样本本身，
     *    n=2 时它是两条的均值 ⇒ **离群值能直接把它拽走一半**。
     * ② **镜像里早就有这个数**：离线工具 `scripts/analyze_bkl_curve.py` 的
     *    `MIN_N_PER_LEVEL = 3`（且注释写着"必须与 `TntgoBklCurve` 一致"）。
     *    ⚠️ 但那边**只标注、不剔除** ⇒ 判读仍拿薄档去判 ⇒ **两边行为不一致**。
     *    这里把它**提到闸门层**，同时消除那处不一致。
     * ③ **不迁就数据**：真机 240 样本台账上 `n≥3` 让**两条**曲线都通过，
     *    而**厚档的真违规仍然被拒**（负例守门，见回归套件 ⑤-G1）。见
     *    `scripts/g1_threshold_matrix.py` 的门槛矩阵。
     *
     * ## ⚠️ 为什么"档内离散上限"这一条**没有**一起加（留作 G1b，未验收）
     *
     * 实测确实有档内离散很大的**厚档**（`mcu=2000 n=8`，跨度 **1766 mA**，含一条 `405`）。
     * 但**手上的数据不足以定那个门槛**：不知道 `405` 是瞬态、是外围负载、还是真值。
     * ⇒ 凭一条样本定一个常数，就是**让判据去迁就一次观测**。
     * ★ 这里只做**能算出来的那一条**；离散上限等**更多台账**到手再定。
     */
    const val MIN_N_PER_LEVEL = 3

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
        minN: Int = 1,
    ): List<Level> = plateauing(samples, mcuOf, absMaOf, tolerance, bucketMcu, minN).levels

    /**
     * ★★★ **G1：建表 ＋ 同时报出"被剔掉几档"**（[plateaus] 的完整版）。
     *
     * ⚠️ 为什么不让调用方自己"再数一遍"：那要**再跑一次同样的分档**，
     * 于是"表里的档"与"被剔的档"是**两次独立计算**的结果 ——
     * 两处一旦漂开，日志说"剔了 2 档"而表里其实剔了 3 档，**没人会发现**。
     * ★ 一次算、一份结果，日志与判据**看的是同一个数**。
     */
    fun <T> plateauing(
        samples: List<T>,
        mcuOf: (T) -> Int?,
        absMaOf: (T) -> Double,
        tolerance: Int = TOLERANCE_MCU,
        bucketMcu: Int = MAX_BUCKET_MCU,
        minN: Int = 1,
    ): Plateauing {
        val pts = samples
            .mapNotNull { s -> mcuOf(s)?.let { it to absMaOf(s) } }
            .filter { (_, ma) -> ma > 0.0 }
            .sortedBy { it.first }
        if (pts.isEmpty()) return Plateauing(emptyList(), 0)

        val out = ArrayList<Level>()
        var thin = 0
        var start = 0
        var lo = pts[0].first                       // 簇内**最小**值 —— 容差与宽度都从它量
        for (i in 1..pts.size) {
            val over = i == pts.size ||
                    (pts[i].first - lo > tolerance) ||
                    (pts[i].first - lo > bucketMcu)
            if (over) {
                // ★★ G1：**样本数不够的簇根本不是一档**（依据见 [MIN_N_PER_LEVEL]）。
                //    ⚠️ 在这里剔、而不是"留下再标注"，因为**下游全都只认这张表**：
                //    闸门、拟合、插值带。留在表里 ⇒ 每个下游都要各自记得跳过一次
                //    ⇒ 漏掉任何一处都会让薄档重新变成"合法档位"（G1 复发）。
                val bucket = pts.subList(start, i)
                if (minN <= 1 || bucket.size >= minN) out.add(level(bucket)) else thin++
                if (i < pts.size) { start = i; lo = pts[i].first }
            }
        }
        return Plateauing(out, thin)
    }

    private fun level(bucket: List<Pair<Int, Double>>): Level =
        Level(
            mcu = medianInt(bucket.map { it.first }),
            absMa = median(bucket.map { it.second }) ?: 0.0,
            n = bucket.size,
        )

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

    /**
     * ★★★★★ **拟合的结论等级 —— 这是产品【真正】走的那道闸**（AR13 接线）。
     *
     * ## 原来错在哪（2026-09-15 查出，AR13 修）
     *
     * 本函数原先**只判** `levels.size >= MIN_LEVELS && fitLine(levels) != null`，
     * 而 [maxDrop]（注释自称"反物理⇒曲线不该被采用"）**全仓库零调用点**，
     * 且本函数就在它正上方 47 行。文档写"两道闸门"，产品运行时**只有一道**。
     *
     * ⚠️ 症状的形态：**让一切看起来正常** —— `gate()` 照旧返回 `Ok`、
     *    日志照旧打「可插值」、卡片照旧显示曲线值，**没有任何一处报错**。
     *
     * ## 为什么必须收 [trend] 参数（而不是"把 [maxDrop] 接进来"）
     *
     * [maxDrop] 的拒绝方向**只假设放电态**（更亮 ⇒ 更耗电）。而充电态
     * `I = 充电器供给 − 负载` ⇒ **`|I|` 随亮度下降是物理正确的**（实测 `−0.4295`）。
     * ⇒ 直接把 `maxDrop` 接进来会**把正确的充电曲线判死**。
     *
     * @param levels 档位表（充/放**各传各自的表**，绝不混）
     * @param trend  ★ 该表的物理方向（见 [Trend]）
     */
    fun gate(levels: List<Level>, trend: Trend): Verdict =
        gate(levels, trend, MIN_N_PER_LEVEL)

    /**
     * ★★★★★ **拟合的结论等级（带 G1 门槛）—— 这是产品【真正】走的那道闸**。
     *
     * ## ★ G1（2026-09-16）：`n < minN` 的档**不进判据**
     *
     * 见 [MIN_N_PER_LEVEL]：薄档的中位数**不是那个档位的值**，
     * 拿它去判"反物理"就是对着不存在的档位开火（实测：一条 `n=2` 的档
     * 判死了整条放电曲线）。
     *
     * ⚠️ **为什么在这里再滤一次**（`plateaus` 已经能滤）：
     * 本函数是**公开 API**，`curves` 也可能是从盘上回读的
     * （回读路径不经过 `plateaus`）⇒ 门槛**必须钉在闸门里**，
     * 否则"回读的曲线"会绕过 G1。**判据要长在唯一入口上，不是长在某个调用点上。**
     *
     * @param minN 一档最少样本数；★ 传 `1` = **关掉 G1**（只给回归套件的"有牙证伪"用）
     */
    fun gate(levels: List<Level>, trend: Trend, minN: Int): Verdict {
        val wire = wireLevels(levels, minN)
        if (wire.size < MIN_LEVELS) return Verdict.NotEnoughLevels
        if (worstViolation(wire, trend) != null) return Verdict.Rejected
        if (fitLine(wire) == null) return Verdict.NotEnoughLevels
        return Verdict.Ok
    }

    /** 兼容重载：旧调用点（只有一条产品路径）按**放电**语义。新代码请显式传 [Trend]。 */
    fun gate(levels: List<Level>): Verdict = gate(levels, Trend.Discharge)

    /**
     * ★ **G1 的过滤本身** —— 判据与拟合都只认它的输出。
     *
     * ⚠️ 与 `plateaus(..., minN)` **同一个口径**；两处必须一致
     * （`plateaus` 用在**建表**时，`wireLevels` 用在**回读/外部传入**时）。
     */
    fun wireLevels(levels: List<Level>, minN: Int = MIN_N_PER_LEVEL): List<Level> =
        if (minN <= 1) levels else levels.filter { it.n >= minN }

    /**
     * ★★★★ **"当前亮度下电流是多少"** —— 带外**绝不外推**，反物理**绝不采用**。
     *
     * ⚠️ **AR13 更正**：原版**只查了带外**，把 [gate] 提到的"反物理"**漏了** ——
     * 于是 `maxDrop()` 那 900 mA 的反向跳变**进了插值**：
     * 直线被"更亮的一档反而更省电"往下拽 ⇒ 卡片会给出一个四位数毫安的假读数。
     * ⇒ 现在先过 [gate]：`Rejected` 时一律降级成"该处未测"（与带外同一条纪律）。
     *
     * @param mcu 当前亮度的 **MCU 域**值（不是 UI）
     * @param trend ★ 该表的物理方向
     */
    fun estimate(levels: List<Level>, mcu: Int, trend: Trend): Estimate =
        estimate(levels, mcu, trend, MIN_N_PER_LEVEL)

    /**
     * ★★★★★ **"当前亮度下电流是多少"** —— 带外**绝不外推**，反物理**绝不采用**。
     *
     * ⚠️ **AR13 更正**：原版**只查了带外**，把 [gate] 提到的"反物理"**漏了** ——
     * 于是 `maxDrop()` 那 900 mA 的反向跳变**进了插值**：
     * 直线被"更亮的一档反而更省电"往下拽 ⇒ 卡片会给出一个四位数毫安的假读数。
     * ⇒ 现在先过 [gate]：`Rejected` 时一律降级成"该处未测"（与带外同一条纪律）。
     *
     * ★★ **G1（2026-09-16）**：薄档（`n < minN`）**也不进插值**，而且**不算进实测带的端点**
     * —— 一条 `n=2` 的档给出的"实测带"与它的中位数一样是编的。
     * ⇒ 连带：若薄档恰好在带的最暗/最亮端，**带会缩短**，于是那些位置正确地变成"该处未测"。
     *
     * @param mcu 当前亮度的 **MCU 域**值（不是 UI）
     * @param trend ★ 该表的物理方向
     * @param minN 一档最少样本数（见 [MIN_N_PER_LEVEL]）；传 `1` = 关掉 G1
     */
    fun estimate(levels: List<Level>, mcu: Int, trend: Trend, minN: Int): Estimate {
        val wire = wireLevels(levels, minN)
        if (wire.isEmpty()) {
            return Estimate(
                absMa = null,
                where = Where.NoLevels,
                clampAbsMa = null,
                bandMcuMin = null,
                bandMcuMax = null,
                levels = 0,
                unusable = Unusable.NotEnoughLevels,
            )
        }
        val sorted = wire.sortedBy { it.mcu }
        val lo = sorted.first()
        val hi = sorted.last()
        // ★★ 闸门（AR13）：反物理 ⇒ 不给数、不插值
        // ★★★ G3（2026-09-16）：判读**只做一次**并把结论**留下来** ——
        //    原来这里把 `gate(...) != Verdict.Ok` 直接折成 `line = null`，
        //    于是"被拒"与"带外"在 [Estimate] 里**长得一样** ⇒ 卡片只能说"该处未测"。
        val verdict = gate(wire, trend, minN)
        val line = if (verdict == Verdict.Ok) fitLine(wire) else null
        if (line == null) {
            // ★ G3：位置照报（与下面可用分支**同一个口径**），**原因**另说。
            // ⚠️ 原代码这里用 `sorted.first()` 并起名 `only`（"唯一那档"）——
            //    那是"拟合失败＝只剩一档"时代的遗留名；被拒时它其实是**最暗端那一档**。
            //    ⇒ 改名 `loEnd`，位置判定改用 `lo.mcu`。
            val where = when {
                mcu < lo.mcu -> Where.BelowBand
                mcu > hi.mcu -> Where.AboveBand
                // ★ 带内却给不出数 ⇒ 位置说"带内"，原因由 [Estimate.unusable] 说 ——
                //   调用方**必须**先看 `unusable`，不能只看 `where != InBand`。
                else -> Where.InBand
            }
            return Estimate(
                absMa = null,
                where = where,
                clampAbsMa = if (mcu < lo.mcu) lo.absMa else hi.absMa,
                bandMcuMin = lo.mcu,
                bandMcuMax = hi.mcu,
                levels = wire.size,
                unusable = if (verdict == Verdict.Rejected) Unusable.Rejected
                           else Unusable.NotEnoughLevels,
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
            levels = wire.size,
            unusable = null,
        )
    }

    /** 兼容重载：旧调用点按**放电**语义。新代码请显式传 [Trend]。 */
    fun estimate(levels: List<Level>, mcu: Int): Estimate = estimate(levels, mcu, Trend.Discharge)

    /**
     * ★★★★★ **"走向与物理相反"的【那一对档】＋ 幅度** —— `null` = 档位不足 / **没有违规**。
     *
     * ## ★★★ 为什么必须带上"是哪一对"（C1，2026-09-16）
     *
     * 原来只报幅度 ⇒ 设备日志里只出现 `反物理（走向与放电态相悖）`，**看不出是哪一对**。
     * ⛔ 后果**不是"少一点信息"，而是会把读日志的人引到相反的结论上**：
     *
     * | 日志现状 | 读的人会得出 |
     * |---|---|
     * | 放电被拒 ＋ 充电被拒 | ★ **「方向无关」**（＝闸门坏了） |
     * | 真相 | 两个方向**各自**拒了**不同**的一对 ⇒ 闸门**按方向判得好好的** |
     *
     * ★ 而"两条都拒 ⇒ 方向无关"**正是 G2 一开始把判据写错的同一个陷阱**
     *   （见 `docs/20260916_AR13_G2_方向区分性证据.md` §2.3）。
     * ⇒ [wideningPair] 存在的理由不是"多打日志"，而是**把设备侧那个歧义消灭掉**。
     *
     * ⚠️ 必须同时返回 [Widening.trend]：**判它时用的是哪个方向**。
     *   只报数字不报方向，读的人仍然要**信任**那句"与 X 态相悖"。
     *
     * @return `null` = 档位不足无法判断 **或** 走向正确；
     *         否则为**违规幅度最大**的那一对相邻档（幅度 > 0）
     */
    fun wideningPair(levels: List<Level>, trend: Trend): Widening? {
        val s = levels.sortedBy { it.mcu }
        if (s.size < 2) return null
        var best: Widening? = null
        for (i in 1 until s.size) {
            val lo = s[i - 1]
            val hi = s[i]
            // 相邻两档：`Δ` = 变亮带来的 |I| 变化量
            val delta = hi.absMa - lo.absMa
            val bad = if (trend == Trend.Discharge) -delta else delta
            if (bad > 0.0 && bad > (best?.violationMa ?: 0.0)) {
                best = Widening(lo.mcu, hi.mcu, lo.absMa, hi.absMa, delta, bad, trend)
            }
        }
        return best
    }

    /**
     * ★★★★★ **"走向与物理相反"的最大幅度（mA）** —— `null` = 档位不足 / **没有违规**。
     *
     * | trend | 正确走向 | 违规（"反物理"） |
     * |---|---|---|
     * | [Trend.Discharge] | 亮度↑ ⇒ `\|I\|` **上升** | 更亮的一档**更省电** |
     * | [Trend.Charge] | 亮度↑ ⇒ `\|I\|` **下降** | 更亮的一档**更耗电** |
     *
     * ⚠️ **原来叫 `maxDrop`，只表达放电方向**（`更亮却更省电` 才算违规）。
     * 那个方向**在充电态是错的** —— 见 [Trend] 与 [gate] 的注释。
     * 名字改掉是有意的：**一个不看方向的函数，接上闸门就会误杀**。
     *
     * ★ **与 [wideningPair] 的关系**：两者**同一个判据**（这里返回幅度、那里返回一整对）。
     *   ⚠️ 它们**必须**一直一致 ⇒ 回归套件里有判据盯着这一条
     *   （判据若自己再实现一遍方向逻辑，就只能自证 —— G2 §5.1 那个假绿的教训）。
     *
     * @return `null` = 档位不足无法判断 **或** 走向正确；否则为违规幅度（> 0）
     */
    fun worstViolation(levels: List<Level>, trend: Trend): Double? {
        // ★ 刻意**直接读 trend 字段**（而不是把整段转发给 wideningPair）：
        //   这样"方向分支真的进了编译产物"在字节码里**看得见**（回归套件 ⑤-G2 层 3 就查它）。
        val s = levels.sortedBy { it.mcu }
        if (s.size < 2) return null
        var worst = 0.0
        for (i in 1 until s.size) {
            // 相邻两档：`Δ` = 变亮带来的 |I| 变化量
            val delta = s[i].absMa - s[i - 1].absMa
            val bad = if (trend == Trend.Discharge) -delta else delta
            if (bad > worst) worst = bad
        }
        return if (worst > 0.0) worst else null
    }

    /**
     * ★★★★ **一趟"反物理"违规的完整交代**（C1）。
     *
     * @param loMcu 更**暗**的那一档的 MCU
     * @param hiMcu 更**亮**的那一档的 MCU
     * @param loAbsMa 暗档的 `|I|` 中位数（mA）
     * @param hiAbsMa 亮档的 `|I|` 中位数（mA）
     * @param delta  `|I|(亮) − |I|(暗)` ⇒ **负** = 变亮反而更省电
     * @param violationMa ★ **反向幅度**（> 0）＝ [worstViolation] 会返回的那个数
     * @param trend ★ 判它时用的方向 —— **必须带着**，否则读的人仍要盲信"与 X 态相悖"
     */
    data class Widening(
        val loMcu: Int,
        val hiMcu: Int,
        val loAbsMa: Double,
        val hiAbsMa: Double,
        val delta: Double,
        val violationMa: Double,
        val trend: Trend,
    ) {
        /** 给人看的一段：`178→497 反向 268 mA` —— ★ 设备日志与离线工具**同口径** */
        fun brief(): String =
            "$loMcu→$hiMcu 反向 ${"%.0f".format(violationMa)} mA"
    }

    /**
     * ⚠️ **兼容保留**：等价于「放电方向的违规幅度」，`null` ⇒ `0.0`。
     * 新代码请用 [worstViolation]（它带方向）。
     */
    fun maxDrop(levels: List<Level>): Double? = worstViolation(levels, Trend.Discharge) ?: 0.0


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
