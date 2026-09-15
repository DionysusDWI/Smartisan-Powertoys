package com.shware.mode.mod.tntgo

import android.content.Context
import android.util.Log
import kotlin.math.abs

/**
 * ★★★★ **容量的自我学习**（任务 AQ · AQ4，用户 §5 要求）。
 *
 * > 用户原话：「tntgo 的真实容量测试需要**持续积累数据**实现，这部分也需要
 * > **实现进 tntgo电量 mod 中**，从而在**使用中不断矫正**数字」
 *
 * ## 一、为什么需要它
 *
 * 容量只能取 **10160 mAh**（网络来源：baike / mydrivers，**不是实测**）。
 * 芯片层能读 `DesignCapacity()`（`0x3C`），但 **AT 口打不开**（[08 §4](../../../../../../.paper/08-TNTGO电池芯片规格与寄存器.md)）。
 *
 * ## 二、★★★ 为什么"学出来的容量"比"网上那个数"更可信
 *
 * 学习用的是**库仑计数**：在一段**纯放电**里累计搬走的电荷，除以电量掉落的百分点。
 *
 * ```
 * 每个轮询点:  ΔQ_i = |I_i| × Δt_i / 3600        (mAh)
 * 一段结束时:  C_est = ΣΔQ / (ΔSOC / 100)
 * ```
 *
 * ★★ **关键性质：它是【自洽】的** ——
 * `C_est` 是用**我们自己读到的那个电流**反推出来的容量。
 * ⇒ 拿它算可用时间时，**电流的系统性偏差会被自动抵消**：
 * 哪怕我们的电流读数整体偏小 10%，学出来的容量也会同步偏小 10%，比值照样对。
 * 这比"相信一个网络上的 10160"要稳得多。
 *
 * ## 三、五条接受条件（★ 防止学歪）
 *
 * | # | 条件 | 为什么 |
 * |---|---|---|
 * | 1 | **全程放电**（`I < 0` 无翻转） | 充放电混在一起，库仑计数没有意义 |
 * | 2 | **`ΔSOC ≥ 5%`** | 电量是**整数 %** ⇒ 跨度太小则量化误差压过信号 |
 * | 3 | **有效时长 ≥ 10 min**（总时长 − 累计断档） | 排除瞬时抖动；★ N6b：断档时长不计入 |
 * | 4 | **相邻采样间隔 ≤ 90 s** | ★ N6a：断档**不作废整段**，只把断档时长单独记账 |
 * | 5 | **结果落在 5000–15000 mAh** | 离谱值直接丢 |
 *
 * ★★ 条件 4 的偏差方向是**故意选的**：断档期间分子（搬走的电荷）**漏记**、
 * 分母（ΔSOC）**照旧完整** ⇒ `C_est` **系统性偏低** ⇒ 可用时间**偏短**。
 * 用户明确要求「宁可偏保守」，所以**不要**去"修准"它。
 *
 * ## 四、★★ 两个容易忽略、但会让功能彻底失效的点
 *
 * 1. ★ **Δt 必须用【实测间隔】，不能用标称的 30 s** ——
 *    串口读要 0.7–2.5 s，`postDelayed` 还会漂移 ⇒ 按 30 s 算会让库仑计数
 *    **系统性偏小**。用两次**成功读数的时间戳之差**。
 * 2. ★ **状态必须持久化** —— mod 会被反复重启（用户开关 / 看门狗 / 崩溃），
 *    不落盘的话**每次都从头开始，永远学不出来**。
 *
 * ## 五、融合策略（用户 2026-09-15 裁决；★★ N9 同日收紧）
 *
 * 保留最近 [KEEP] 个 `C_est`，按下面的规则融合（见 [fusion]）：
 *
 * | 有效段数 | 采用 | 界面标注 |
 * |---|---|---|
 * | < [MIN_SEGMENTS]（= 3） | **先验** [PRIOR_MAH] | 先验值，未校准 |
 * | ≥ 3 且离散度达标 | **中位数** | 已学习 N 段 |
 * | ≥ 3 但离散度超标 | ★ **最小样本** | ⚠️ 未收敛，取保守值 |
 *
 * ### ★★★ 为什么是 3 而不是 2（N9）
 *
 * **2 个点时"中位数"退化成均值** ⇒ 抗离群能力**为零**，一个坏值直接进结果。
 * 而实机单段噪声并不小：同一台设备实测到 **6270** 与 **8630**，相差 **38%**
 * （AQ §10.9 的原始三样本）。多等一段（≈10 分钟放电）换来真正的中位数抗性，很划算。
 *
 * ### ★★★★ 为什么离散度超标时【不能退回先验】—— N9 最关键的一步判断
 *
 * 直觉会说"样本打架 ⇒ 不敢相信 ⇒ 用先验"。**在本机这是错的，而且错在危险方向：**
 *
 * 先验 10160 mAh 是**网络来源、已被实测证伪为偏高** ——
 * 实测 5 段全部落在 **7047–7423**，先验比它们高约 **30%**。
 * ⇒ 退回先验 = 把容量**调高** = 可用时间**变长** = ★ **正是用户明确不要的方向**。
 *
 * ⇒ 保守兜底取**样本里的最小值** —— 一个**真实观测到**的值，不是编出来的。
 *   这样"不敢相信"时的取值**只会更短、不会更长**。
 *
 * ### 离散度为什么用 `MAD / 中位数`
 *
 * `MAD = median(|x_i − median|)` —— 与中位数同源的稳健量。
 * **不用"最大最小差"**：那个被**两个**极值主导，样本一多就虚高
 * （实测同一个 5 段样本：极差比 **5.3%**，而 MAD 比只有 **0.9%**）。
 *
 * 阈值 [DISPERSION_MAX] = 10%：实机收敛后是 **0.9%**，
 * 而单段量化噪声按离线 case ① 的分析可达 **±10~15%**
 * ⇒ 10% 足以放行正常量化噪声，同时拦住真正的"打架"。
 */
class TntgoCapacity(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("tntgo_battery", Context.MODE_PRIVATE)

    /** 一段放电的进行中状态 */
    private var segStartSoc: Int = UNSET
    private var segMah: Double = 0.0
    private var segStartMs: Long = 0L
    private var lastMs: Long = 0L

    /** ★ 本段是否已经丢过"满电平台"（只丢一次，见 [onReading]） */
    private var plateauTrimmed: Boolean = false

    /**
     * ★★★ N6a：**本段累计断档时长**（ms）。
     *
     * 断档（相邻采样 > [MAX_GAP_MS]，串口被抢/读失败）**不再作废整段**，
     * 而是把这段"没有数据的时间"单独记账，用在两处：
     *
     * | 用处 | 为什么 |
     * |---|---|
     * | 条件 3 的有效时长 = 总时长 − 本值 | 12 分钟里断了 5 分钟，真正有数据的只有 7 分钟 |
     * | 提示 `C_est` 偏保守 | 分子不含断档电荷，分母照样完整 ⇒ 估计偏低 |
     *
     * ★ **必须落盘**：不落盘的话 mod 一重启本值归零 ⇒ 有效时长被高估
     * ⇒ 本该被拒的段被接收。与本类其它累加器同理（重启是常态，见类注释 §四.2）。
     */
    private var segGapMs: Long = 0L

    /**
     * ★★ F8：**代际**。
     *
     * ⚠️ 设置界面按「复位」时，它操作的是**它自己那个 [TntgoCapacity] 实例**；
     * **Service 里那个实例的内存态还留着旧段**。
     * ⇒ 复位看起来生效了（盘上清空了），但 Service 下一次 [onReading] 会继续累加，
     *   然后 `persistSegment()` **把旧段又原样写回盘上** —— **复位形同虚设**。
     *
     * ⇒ 用一个自增的代际键：`reset()` 让它 +1；Service 侧的实例每轮比对，
     *   发现变了就**重载内存态**（此时盘上已是空的）。
     */
    private var loadedEpoch: Int = sp.getInt(K_EPOCH, 0)

    init {
        reloadFromPrefs()
    }

    /** ★ 从盘上恢复进行中的累加器（重启不丢，否则永远学不出来） */
    private fun reloadFromPrefs() {
        segStartSoc = sp.getInt(K_SEG_SOC, UNSET)
        segMah = sp.getFloat(K_SEG_MAH, 0f).toDouble()
        segStartMs = sp.getLong(K_SEG_T0, 0L)
        lastMs = sp.getLong(K_LAST_MS, 0L)
        segGapMs = sp.getLong(K_SEG_GAP, 0L)
        plateauTrimmed = false
    }

    // ------------------------------------------------------------------ 对外

    /**
     * ★★ N9：**融合的全过程**（不只是结果）。
     *
     * 把"用了哪个值"和"为什么用它"放在一起返回 —— 因为这一版引入了
     * **两种不同的采用理由**（中位数 / 保守兜底），界面必须能说清是哪一种，
     * 否则用户看到数字变了却不知道为什么。
     *
     * @property mah 实际采用值
     * @property medianMah 样本中位数（`converged = true` 时即 [mah]）
     * @property minMah 样本最小值（`converged = false` 时即 [mah]，见类注释 §五）
     * @property dispersion `MAD / 中位数`；样本为空或先验态时为 [Double.NaN]
     * @property converged 离散度是否达标（达标才敢用中位数）
     * @property count 参与融合的样本数
     */
    data class Fusion(
        val mah: Double,
        val medianMah: Double,
        val minMah: Double,
        val dispersion: Double,
        val converged: Boolean,
        val count: Int,
    )

    /**
     * ★★ N9：融合结果。**有效段 < [MIN_SEGMENTS] ⇒ `null`**（调用方用先验 [PRIOR_MAH]）。
     *
     * ⚠️ 判据是**有效段数**，不是"有没有样本" —— 1~2 段时宁可继续用先验，
     * 也不要用一个退化了的"中位数"（见类注释 §五）。
     */
    val fusion: Fusion?
        get() {
            val list = samples().map { it.toDouble() }.sorted()
            if (list.size < MIN_SEGMENTS) return null
            val med = medianOf(list) ?: return null
            // MAD = median(|x_i − median|)，与中位数同源的稳健离散度
            val mad = medianOf(list.map { abs(it - med) }) ?: 0.0
            val disp = if (med > 0.0) mad / med else Double.NaN
            val converged = disp.isNaN() || disp <= DISPERSION_MAX
            return Fusion(
                mah = if (converged) med else list.first(),
                medianMah = med,
                minMah = list.first(),
                dispersion = disp,
                converged = converged,
                count = list.size,
            )
        }

    /**
     * 当前该用哪个容量。
     *
     * 有效段 < [MIN_SEGMENTS] ⇒ 先验 [PRIOR_MAH]；否则用 [fusion] 的结论。
     */
    val capacityMah: Double
        get() = fusion?.mah ?: PRIOR_MAH

    /** 是否已经切到"学出来的"容量（界面用它决定要不要标「已校准」） */
    val isLearned: Boolean get() = fusion != null

    /** ★★ N9：样本是否已经**收敛**（离散度达标）。段数不够或超标都返回 `false` */
    val isConverged: Boolean get() = fusion?.converged == true

    /** 学习值（融合后的采用值）；段数不够时返回 `null` */
    val learnedMah: Double? get() = fusion?.mah

    /** 已积累的有效段数 */
    val segmentCount: Int get() = samples().size

    /** 最近几段学到的原始值（设置界面展示用） */
    fun samples(): List<Float> =
        sp.getString(K_SAMPLES, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .takeLast(KEEP)

    /**
     * ★ **喂一次读数**。每轮询调一次（**不论成功失败**都要调，才能正确判"断档"）。
     *
     * @param soc 电量 %
     * @param currentMa 原始电流（mA，正=充电）
     * @param nowMs 本次读数的时间戳
     * @return 若这一段刚好完成并产出了一个容量估计，返回它；否则 `null`
     */
    fun onReading(soc: Int, currentMa: Int, nowMs: Long): Double? {
        // ── ★★ F8：先看代际 —— 设置界面按过「复位」就重载内存态
        //
        // ⚠️ 不查这一下的后果：复位只清了盘，Service 内存里的旧段会**原样写回去**。
        val epoch = sp.getInt(K_EPOCH, 0)
        if (epoch != loadedEpoch) {
            Log.i(TAG, "★ 检测到容量学习被复位（代际 $loadedEpoch → $epoch）⇒ 重载内存态")
            loadedEpoch = epoch
            reloadFromPrefs()
            return null      // 本笔不参与累加（刚重载，没有可用的 Δt）
        }

        // ── 条件 1：充电 ⇒ 不计数，**并且结束当前段**（段的定义就是"纯放电"）
        if (currentMa >= 0) {
            if (segStartSoc != UNSET) Log.i(TAG, "段结束（转为充电）")
            resetSegment(nowMs)
            return null
        }

        // ── 条件 4：断档 ⇒ ★★★ N6a：**不作废整段**，只把断档时长单独记账
        //
        // ⚠️ 这里改过三次，**务必读完再动**：
        //
        //   ① **原样**：断档只调 `resetSegment()` 而**没有**把 `lastMs` 推进到 `nowMs`
        //      ⇒ 下面算 `dtMs` 时，断档那几分钟被**按当前电流补进分子**
        //      ⇒ `C_est` **系统性偏高**（实机抓到 +18%，见 §0.2 满电平台之外的 F2）。
        //
        //   ② **修 F2（CC 审计 · 中等）**：改成 `resetSegment(nowMs)` —— 不编造了，
        //      但**整段作废**。实机后果（§10.4）：89% 那段攒了 305 mAh、
        //      只因 184 s 断档就**全丢**，采集成功率掉到 **1/3** ⇒ **学习几乎学不出来**。
        //
        //   ③ ★ **N6a（本处）**：**不编造，也不作废** ——
        //      只 `lastMs = nowMs`（⇒ 本笔 `dtMs = 0`，既不加电荷、也不重复计时），
        //      同时把 gap 记进 `segGapMs`，**段继续累计**。
        //
        // ## ★★ 为什么这条在数学上必然【保守】（这是选它的唯一理由）
        //
        // 分母 ΔSOC 是**完整**的 —— 断档期间电量照样在掉，读数恢复后我们看到的
        // 落差包含那一段；而分子**漏掉**了断档期间的电荷。
        // ⇒ 分子偏小、分母照旧 ⇒ `C_est` = 分子/分母 **系统性偏低**
        // ⇒ 可用时间 **偏短** ⇒ ★★ 正好命中用户明确要求的「宁可偏保守」。
        //
        // ⚠️ 反例警戒：如果哪天为了"修准"而把 gap 电荷**估算**回分子，
        //    偏差方向立刻翻转为**偏高**（可用时间偏长）—— **那是危险方向，不要做**。
        val gap = nowMs - lastMs
        if (lastMs != 0L && gap > MAX_GAP_MS) {
            lastMs = nowMs
            if (segStartSoc != UNSET) {
                segGapMs += gap
                Log.i(
                    TAG,
                    "采样断档 ${gap / 1000}s（>${MAX_GAP_MS / 1000}s）⇒ ★段继续，" +
                        "断档单独记账（本段累计断档 ${segGapMs / 1000}s；" +
                        "分子不含这段电荷 ⇒ 估计偏保守）"
                )
            } else {
                Log.i(TAG, "采样断档 ${gap / 1000}s ⇒ 本无进行中的段，仅重置基线")
            }
        }

        if (segStartSoc == UNSET) {
            segStartSoc = soc
            segMah = 0.0
            segStartMs = nowMs
        }

        // ── 累计电荷（★ 用实测 Δt，不用标称 30 s）
        val dtMs = if (lastMs == 0L) 0L else (nowMs - lastMs).coerceAtLeast(0L)
        segMah += abs(currentMa).toDouble() * dtMs / 3_600_000.0
        lastMs = nowMs

        // ★★★ 修「满电平台」（2026-09-15 实机抓到，见 §3.3）
        //
        // 实测：段从 **100%** 起，前 ~90 mAh 是在电量**钉在 100% 不动**时积累的
        // ⇒ 分子白涨、分母不动 ⇒ 第一次学出来 **11956 mAh**（比先验 10160 高 18%）。
        // ⚠️ 容量偏高 ⇒ 可用时间偏长 ⇒ **直接违反用户"偏向保守"的要求**。
        //
        // 修法：从 100% 起段时，**第一次看到电量真的掉了**就把平台期整段丢掉、
        // 以当前点为新起点。★ 只对"起点=100%"生效 —— 部分电量下电量计会正常走动，
        // 不该动它（否则会白扔一段本来有效的积累）。
        if (segStartSoc >= FULL_SOC && soc < segStartSoc && !plateauTrimmed) {
            Log.i(
                TAG,
                "★ 满电平台：丢掉起步阶段（${"%.0f".format(segMah)} mAh 是在电量钉在" +
                    "$segStartSoc% 时积累的）⇒ 以 $soc% 为新起点"
            )
            segStartSoc = soc
            segMah = 0.0
            segStartMs = nowMs
            // ★ N6：平台期整段丢掉 ⇒ 这期间累计的断档也要一起丢（否则新段凭空背上旧账）
            segGapMs = 0L
            plateauTrimmed = true
            persistSegment()
            return null
        }

        // ── 够不够结一段？
        val dropped = segStartSoc - soc
        if (dropped < MIN_SOC_DROP) {
            persistSegment()
            return null
        }

        // ── 条件 3：段**有效**时长
        //
        // ★★★ N6b：门槛改判**有效时长 = 总时长 − 累计断档**。
        //     不扣的话，一个"12 分钟里断了 5 分钟"的段会被当成合格的 12 分钟段，
        //     而它真正有数据的只有 7 分钟 —— 门槛形同虚设。
        // ★ 断档期间 `segGapMs` 只在有 gap 时增长，所以「不足就再等一笔」仍然成立：
        //   总时长在涨、断档不再涨 ⇒ `effMs` 迟早越过门槛（与 N4 的"再等一笔"同源）。
        val durMs = nowMs - segStartMs
        val effMs = durMs - segGapMs
        if (effMs < MIN_SEG_MS) {
            // ★★★ 修 N4（2026-09-15 AR7 实机抓到）：**这里原来调 `resetSegment()`，是错的。**
            //
            // 能走到这一行说明上面 `dropped < MIN_SOC_DROP` **已经放行** ——
            // 即 **ΔSOC 已经够 5%，段本身合格**，只是"还不够久"。
            // 而 `resetSegment()` 把整整 10 分钟的积累**整个丢掉**。
            //
            // 实测（`段太短 580s ⇒ 不采信`）：段起于 89%，`seg.mah` 外推到 580 s = 381 mAh
            // ⇒ `C_est = 381 / 0.05 = 7620 mAh`（与已学到的 7137 mAh 一致）
            // —— **只差 20 秒，一个有效估计就地蒸发。**
            //
            // ## 为什么"丢掉"是多余且有害的
            //
            // ① **ΔSOC 瞬跳的防御在下面已经有了**：`:220` 的 `est < MIN_MAH || est > MAX_MAH`
            //    会把"5% 只搬走一点点电荷"的假段直接判离谱丢掉 ⇒ 这里不必再防一遍。
            // ② `MIN_SEG_MS = 10 min` 是**按先验 10160 mAh 标定**的（5% = 508 mAh，2.3 A 下 ≈ 13 min）。
            //    真实容量更低或负载更重时，5% 在 10 min 内就掉完 ⇒ **判据自我否定**，
            //    **负载越重越学不出来** —— 而重载恰恰是最该学的时候。
            //
            // ⇒ 正解：**"还不够久"就再等一笔**（不丢、不动基线）。
            //   等 `effMs ≥ MIN_SEG_MS` 时自然会走下面的接收入口，届时 `segMah` 是全程累计的，正确。
            Log.i(
                TAG,
                "段有效时长不足 ${effMs / 1000}s（总 ${durMs / 1000}s − 断档 ${segGapMs / 1000}s" +
                    " < ${MIN_SEG_MS / 60000} 分）⇒ 本笔不结段，继续累计"
            )
            persistSegment()
            return null
        }

        val est = segMah / (dropped / 100.0)

        // ── 条件 5：数值合理
        if (est < MIN_MAH || est > MAX_MAH) {
            Log.w(TAG, "学到的容量离谱 ${"%.0f".format(est)} mAh（${segMah.toInt()} mAh / $dropped%）⇒ 丢弃")
            resetSegment()
            return null
        }

        Log.i(
            TAG,
            "★ 学到一个容量估计：${"%.0f".format(est)} mAh" +
                "（搬走 ${"%.0f".format(segMah)} mAh，电量掉 $dropped%，" +
                "有效 ${effMs / 60000} 分" +
                (if (segGapMs > 0) "，⚠️ 含断档 ${segGapMs / 1000}s ⇒ 此值偏保守" else "") + "）"
        )
        appendSample(est.toFloat())
        resetSegment()
        return est
    }

    /**
     * 复位全部学习成果（设置界面用）。
     *
     * ★★ F8：**必须同时把代际 +1** —— 否则 Service 里那个实例的内存态还留着旧段，
     * 下一轮 `persistSegment()` 会把它**原样写回盘上**，复位形同虚设。
     */
    fun reset() {
        val next = sp.getInt(K_EPOCH, 0) + 1
        sp.edit()
            .remove(K_SAMPLES)
            .remove(K_SEG_SOC).remove(K_SEG_MAH).remove(K_SEG_T0).remove(K_LAST_MS)
            .remove(K_SEG_GAP)
            .putInt(K_EPOCH, next)
            .apply()
        resetSegment()
        loadedEpoch = next
        Log.i(TAG, "容量学习已复位（代际 → $next）")
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 中位数（`Double` 版）。★ **入参无需有序 —— 本函数自己排。**
     *
     * ★ 为什么不直接用 [TntgoPower.median]：那个签名是 `List<Int>`，
     * 而融合要在 `C_est`（带小数的容量估计）上算 **MAD** ——
     * 先 `toInt()` 会把偏差量的分辨率削掉（7221.02 → 7221）。
     * 中位数本身差 0.02 无所谓，但 `median(|x_i − median|)` 是**差值**，
     * 精度损失会直接进离散度。
     *
     * ## ★★★★ 为什么**必须自己排**（这不是防御性编程，是踩过的坑）
     *
     * 第一版把"已排序"当成**调用方的责任**，签名是 `medianOf(sorted: List<Double>)`。
     * 结果 [fusion] 里算 MAD 时传进去的是 `list.map { abs(it - med) }` ——
     * **一个未排序的列表**，而函数按下标取值 ⇒ 拿到的是 `deviations[2]`。
     *
     * 实机后果（2026-09-15，启动日志）：
     * ```
     * 容量=7217 mAh（已学习 5 段，取中位数，离散度 0.0%）   ← ★ 实际应为 0.97%
     * ```
     * 那一位恰好是 `|7216.897 − 7216.897| = 0.0` ⇒ **MAD 恒为 0**
     * ⇒ `converged` **永远为真** ⇒ **「未收敛取最小样本」这条兜底永远不会触发**。
     * ★★ **也就是说：N9 的核心安全阀在真机上是死的，而离线判据全绿。**
     *
     * ⚠️ **离线镜像为什么没抓到**：`verify_tntgo_capacity.py` 是**另写一遍**的实现，
     * 那边写的是 `_median(sorted(...))` —— 两边**手写成了两个样子**。
     * ⇒ 处置：① 本函数自己排序，消灭"忘了排"这个可能性；
     *        ② 另建 `scripts/check_tntgo_capacity_ondevice.py` 做**跨实现对账**。
     *
     * @return 空列表返回 `null`（**不返回 0** —— 0 是个合法容量，不能当哨兵）
     */
    private fun medianOf(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /**
     * 清掉进行中的段。
     *
     * ★★★ [nowMs] **不是可选的** —— 修 F2 的关键就在这里：
     * 断档后如果把 lastMs 留在原地，下一笔的 dtMs 就是整个断档时长，
     * 那段电荷会被按当前电流补进分子。传入 nowMs 让 lastMs = nowMs，
     * 于是下一笔 dtMs = 0（既不加电荷、也不移动基线）。
     *
     * ⚠️ N6a 之后，**断档已经不再走这里**（断档只记账、不作废），
     * 但 [nowMs] 参数对"转为充电""数值离谱"这两条路径仍然是必需的。
     */
    private fun resetSegment(nowMs: Long = 0L) {
        segStartSoc = UNSET
        segMah = 0.0
        segStartMs = 0L
        segGapMs = 0L
        plateauTrimmed = false
        lastMs = nowMs
        sp.edit().remove(K_SEG_SOC).remove(K_SEG_MAH).remove(K_SEG_T0).remove(K_SEG_GAP).apply()
        sp.edit().putLong(K_LAST_MS, nowMs).apply()
    }

    /** 把进行中的段落盘 —— ★ 不落盘的话 mod 一重启就白学 */
    private fun persistSegment() {
        sp.edit()
            .putInt(K_SEG_SOC, segStartSoc)
            .putFloat(K_SEG_MAH, segMah.toFloat())
            .putLong(K_SEG_T0, segStartMs)
            .putLong(K_LAST_MS, lastMs)
            .putLong(K_SEG_GAP, segGapMs)
            .apply()
    }

    private fun appendSample(v: Float) {
        val list = samples().toMutableList()
        list.add(v)
        while (list.size > KEEP) list.removeAt(0)
        sp.edit().putString(K_SAMPLES, list.joinToString(",")).apply()
    }

    companion object {
        private const val TAG = "ModeMod/Tntgo"

        /** ★ 先验容量。⚠️ **网络来源**（baike/mydrivers），**不是实测** ⇒ 界面上一律带 ≈ */
        const val PRIOR_MAH = 10160.0

        /**
         * ★★ N9：有效段数达到它之后才从先验切到学习值。
         *
         * **2 → 3**（2026-09-15 用户裁决）。理由：2 点时中位数退化成均值，
         * 抗离群能力为零，而实机单段噪声实测可达 38%。见类注释 §五。
         */
        const val MIN_SEGMENTS = 3

        /**
         * ★★ N9：离散度上限（`MAD / 中位数`）。超过它就不敢用中位数，改取**最小样本**。
         *
         * ⚠️ **超标时【不退回先验】** —— 先验已知偏高 30%，退回去会让可用时间变长。
         * 见类注释 §五的详解。
         *
         * 取 10% 的依据：实机收敛后实测 **0.9%**；单段量化噪声可达 ±10~15%。
         */
        const val DISPERSION_MAX = 0.10

        /** 保留最近几个估计（取中位数用） */
        private const val KEEP = 5

        /** ★ 到这个电量就算"满电"，起段时要防平台期（实测 100% 起段会偏高 18%） */
        private const val FULL_SOC = 100

        /** 条件 2：电量至少掉这么多 % 才算一段 */
        private const val MIN_SOC_DROP = 5

        /**
         * 条件 3：段**有效**时长至少这么久（有效 = 总时长 − 累计断档，见 [segGapMs]）。
         *
         * ⚠️ **N5（已知标定缺陷）**：10 min 是按**先验 10160 mAh** 标定的
         * （5% = 508 mAh，2.3 A 下 ≈ 13 min）。真实容量更低或负载更重时，
         * 5% 在 10 min 内就掉完 ⇒ 判据**自我否定**，**越重载越学不出来**。
         * 已用 N4 的"再等一笔"把伤害降到最低，但门槛值本身仍是待标定项。
         */
        private const val MIN_SEG_MS = 10 * 60 * 1000L

        /** 条件 4：相邻采样超过它就判"断档" */
        private const val MAX_GAP_MS = 90_000L

        /** 条件 5：合理的容量区间 */
        private const val MIN_MAH = 5000.0
        private const val MAX_MAH = 15000.0

        private const val UNSET = -1

        // SharedPreferences 键
        private const val K_SAMPLES = "cap.samples"
        private const val K_SEG_SOC = "cap.seg.soc"
        private const val K_SEG_MAH = "cap.seg.mah"
        private const val K_SEG_T0 = "cap.seg.t0"
        private const val K_LAST_MS = "cap.last.ms"

        /** ★ N6a：本段累计断档时长（ms）—— 断档不再作废整段，改为单独记账 */
        private const val K_SEG_GAP = "cap.seg.gap"

        /** ★ F8：复位代际。自增即表示"内存态作废，请重载" */
        private const val K_EPOCH = "cap.epoch"
    }
}
