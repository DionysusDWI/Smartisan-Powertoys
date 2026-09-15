package com.shware.mode.mod.tntgo

import android.content.Context
import android.util.Log
import com.shware.mode.tntgoserial.TntgoState

/**
 * ★★★★★ **亮度 → 功耗 的原始样本台账 + `I(b)` 曲线**（任务 AR12）。
 *
 * ## 动机（用户原话）
 *
 * > 「不同的亮度对应 tntgo 的功耗并不相同，**tntgo 本身的功耗并不是绝对值**」
 *
 * ## ★★★ 为什么存【原始样本】而不是只存"每个桶的中位数"
 *
 * 分桶口径（并档容差、取中位数还是均值、要不要分充放电）**一定会改**。
 * 一旦盘上只留了上次口径算出来的结果，**改口径就得重新采一遍** ——
 * 而重采需要用户配合（几个亮度稳态各驻留几分钟）。
 *
 * ⇒ ★ 存 `(UI, MCU, 电流, 充电状态)` 原文，**口径随时可以离线重算**。
 *   这条与工作区已有的纪律同源：*"改了算子的语义，就要检查盘上有没有用旧语义产出的样本"*。
 *
 * ## ★★★★ AR12b：台账加了 **MCU**（`§8` 的口径裁决）
 *
 * AR12c-2 已判定「**拟合与插值一律走 MCU 域**」，而原来的台账**只存 UI**。
 * 两条路：
 *
 * | | ① 台账改存 **MCU 直读量**（本方案） | ② 事后用 `rawCurve` 反解 |
 * |---|---|---|
 * | 数据性质 | ★ **实测值** | ⚠️ **推算值** |
 * | 风险 | 旧 3 字段样本要能兼容解析 | ★ **曲线一变，已采样本被追溯篡改** |
 * | 跨模块 | ★ 不需要依赖亮度模块的私有公式 | ⚠️ 要把 `rawCurve` 复制到电量模块（两份实现 ⇒ 一定会漂） |
 *
 * ⇒ **采用 ①**。新样本写成 4 字段 `ui:mcu:mA:chg`；旧的 3 字段 `ui:mA:chg`
 *   **照旧解析**（`mcu = null`），只是**不参与曲线拟合** —— 不丢弃、也不算错。
 *
 * ## ★★ 为什么必须记【充电状态】
 *
 * `+BATCG` 的电流是**电池侧净电流**，含义随状态**整个翻转**：
 *
 * | 状态 | `I` 的含义 | 亮度↑ 时 |
 * |---|---|---|
 * | **放电**（`I < 0`） | `I = −(负载 − 外部供给)` | `I` **更负**（`|I|` 更大） |
 * | **充电**（`I > 0`） | `I = 充电器供给 − 负载` | `I` **更小**（负载吃掉了供给） |
 *
 * ⇒ ★ 两种状态**绝不能混在一个桶里** —— 混了之后"亮度→功耗"会被充电状态**整个淹没**。
 */
class TntgoBklProfile(context: Context) {

    private val app = context.applicationContext

    private val sp = app.getSharedPreferences("tntgo_battery", Context.MODE_PRIVATE)

    /**
     * 一条样本。
     *
     * @param mcu ★ **MCU 域**（`TntgoBkl.MCU_MIN..MCU_MAX`）；
     *            `null` = **旧格式样本，没有这个量**（不是"值为 0"）
     */
    data class Sample(
        val ui: Int,
        val currentMa: Int,
        val charging: Boolean,
        val mcu: Int? = null,
    ) {
        /** `|I|` —— 功耗的可比量（充放电方向不同，见类注释） */
        val absMa: Int get() = if (currentMa < 0) -currentMa else currentMa
    }

    /**
     * ★ **记一笔**。
     *
     * @param ui 当前亮度（0–100，**UI 域**）；`null` = 亮度未知 ⇒ **不记**
     * @param mcu 当前亮度（**MCU 域**）；`null` = 未知 ⇒ **不记**
     *
     * ★ 红线：**不知道就不记，绝不猜一个亮度进去** —— 那会把曲线学歪且看不出来。
     *   `ui` 与 `mcu` 都来自同一个状态通道（[TntgoState.readBrightness]），
     *   要么都有、要么都没有。
     */
    fun record(ui: Int?, mcu: Int?, currentMa: Int, nowMs: Long) {
        if (ui == null || mcu == null) return
        val u = ui.coerceIn(0, 100)
        val s = Sample(u, currentMa, currentMa > 0, mcu)
        val list = samplesRaw().toMutableList()
        list.add("$u:${s.mcu}:${s.currentMa}:${if (s.charging) 1 else 0}")
        while (list.size > KEEP) list.removeAt(0)
        sp.edit().putString(K_SAMPLES, list.joinToString(",")).apply()
        lastRecordedAt = nowMs
    }

    /** 最近记一笔的时刻（0 = 从未记过）—— 诊断用 */
    @Volatile
    var lastRecordedAt: Long = 0L
        private set

    /** 原始条目（旧→新） */
    fun samplesRaw(): List<String> =
        sp.getString(K_SAMPLES, "").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 解析后的样本（旧→新）。
     *
     * ★ **兼容两种格式**（AR12b）：
     * - `ui:mcu:mA:chg` —— 当前格式（**MCU 是实测的**）
     * - `ui:mA:chg`     —— AR12c 时期的旧格式 ⇒ `mcu = null`（**不猜、不反解**）
     *
     * 解析不了的条目**跳过并计数**，不静默吞掉。
     */
    fun samples(): List<Sample> {
        val out = ArrayList<Sample>(KEEP)
        var bad = 0
        var legacy = 0
        for (raw in samplesRaw()) {
            val p = raw.split(':')
            val s: Sample? = when (p.size) {
                4 -> {
                    val ui = p[0].toIntOrNull()
                    val mcu = p[1].toIntOrNull()
                    val ma = p[2].toIntOrNull()
                    val ch = p[3].toIntOrNull()
                    if (ui == null || mcu == null || ma == null || ch == null) null
                    else Sample(ui, ma, ch == 1, mcu)
                }
                3 -> {
                    val ui = p[0].toIntOrNull()
                    val ma = p[1].toIntOrNull()
                    val ch = p[2].toIntOrNull()
                    if (ui == null || ma == null || ch == null) null
                    else { legacy++; Sample(ui, ma, ch == 1, null) }
                }
                else -> null
            }
            if (s == null) bad++ else out.add(s)
        }
        if (bad > 0) Log.w(TAG, "台账里有 $bad 条解析不了（已跳过，未计入统计）")
        if (legacy > 0) Log.i(TAG, "台账里有 $legacy 条旧格式样本（无 MCU，不参与曲线拟合）")
        return out
    }

    /** 清空（设置界面「复位」用） */
    fun reset() {
        sp.edit().remove(K_SAMPLES).remove(K_CURVE).apply()
        lastRecordedAt = 0L
        Log.i(TAG, "亮度-功耗台账与曲线已复位")
    }

    /**
     * ★ **[AR12c 可行性判据] 分状态、按亮度桶取中位数。**
     *
     * ⚠️ 这是**判读工具**（UI 域、固定桶宽），与产品采用的曲线（[buildCurve] 走 MCU 域）
     * **不是同一个东西** —— 保留它是因为 AR12c 的归档结论是用这个口径得出的，
     * 改口径会让历史结论失去可比性。
     */
    fun bucketMedians(bucketSize: Int = 10): Map<Boolean, Map<Int, Int>> {
        val grouped = samples().groupBy { it.charging }
        return grouped.mapValues { (_, list) ->
            list.groupBy { it.ui / bucketSize.coerceAtLeast(1) }
                .mapValues { (_, g) -> medianInt(g.map { it.absMa }) }
                .toSortedMap()
        }
    }

    // ------------------------------------------------------------------ AR12b：曲线

    /**
     * ★★★★★ **从台账算出 `I(MCU)` 曲线**（充/放各一张）。
     *
     * 口径全部集中在 [TntgoBklCurve]（纯函数、可在 PC 上离线复算）。
     */
    fun buildCurve(): TntgoBklCurve.Curves {
        val all = samples()
        val legacy = all.count { it.mcu == null }
        val withMcu = all.filter { it.mcu != null }
        return TntgoBklCurve.Curves(
            charging = TntgoBklCurve.plateaus(
                withMcu.filter { it.charging }, { it.mcu }, { it.absMa.toDouble() },
            ),
            discharging = TntgoBklCurve.plateaus(
                withMcu.filter { !it.charging }, { it.mcu }, { it.absMa.toDouble() },
            ),
            legacyNoMcu = legacy,
        )
    }

    /**
     * ★★ **算一次并把结果落盘**（卡片每次刷新都会调用 ⇒ 内部按样本条数节流）。
     *
     * ⚠️ **落的是"档位表 + 拟合系数"，不是原始样本的替代品** ——
     * 原始样本仍在 `bkl.samples` 里；曲线**随时可以从它重算**。
     * 存下来的唯一目的是：**卡片与离线脚本能对着同一份东西说话**（可对账）。
     */
    fun refreshCurve(force: Boolean = false) {
        val total = samplesRaw().size
        if (!force && total == lastBuiltCount && sp.contains(K_CURVE)) return
        val c = buildCurve()
        val payload = buildString {
            append("{\"v\":2")
            append(",\"legacy\":").append(c.legacyNoMcu)
            append(",\"chg\":\"").append(levelsText(c.charging)).append('"')
            append(",\"dis\":\"").append(levelsText(c.discharging)).append('"')
            append(",\"ts\":").append(System.currentTimeMillis())
            append("}")
        }
        sp.edit().putString(K_CURVE, payload).apply()
        lastBuiltCount = total
        val d = TntgoBklCurve.gate(c.discharging)
        Log.i(
            TAG,
            "AR12b：曲线重算（样本 $total 条，可用带 MCU 的 ${total - c.legacyNoMcu} 条）" +
                    "｜放电档位 ${c.discharging.size} 个 ⇒ ${if (d == TntgoBklCurve.Gate.Ok) "可插值" else "★ 档位不足，不给曲线"}" +
                    "｜充电档位 ${c.charging.size} 个",
        )
    }

    /**
     * ★ 读回盘上的曲线（卡片用）。**解析不出来 ⇒ 空 ⇒ 调用方降级**，不猜。
     */
    fun loadCurve(): TntgoBklCurve.Curves {
        val raw = sp.getString(K_CURVE, null) ?: run {
            Log.i(TAG, "曲线读取：盘上还没有 ${K_CURVE}（mod 从没算过）")
            return TntgoBklCurve.Curves()
        }
        if (!raw.contains("\"v\":$CURVE_FORMAT_V")) {
            // ★ 版本对不上（格式改过）⇒ 让 refreshCurve 重算一次，不要硬解
            Log.w(TAG, "曲线格式版本不是 $CURVE_FORMAT_V ⇒ 丢弃并重算（格式已升级，不硬解旧结构）")
            lastBuiltCount = -1
            return TntgoBklCurve.Curves()
        }
        return runCatching { parseCurve(raw) }.getOrElse {
            Log.w(TAG, "曲线解析失败（${it.javaClass.simpleName}: ${it.message}）⇒ 当作没有曲线")
            TntgoBklCurve.Curves()
        }
    }

    /**
     * ★★★★ **档位表的落盘格式：扁平，不带嵌套**（`mcu:ma:n;mcu:ma:n`）。
     *
     * ## 为什么不用嵌套数组（`[[mcu,ma,n],…]`）—— 这是被 bug 逼出来的
     *
     * 2026-09-15 实机对账时，**Kotlin 与 Python 两份解析器各自独立地犯了
     * 同一个 off-by-one**：靠"括号配平"找嵌套数组的结尾时，把结束的 `]`
     * **排除在切片之外**，于是正则匹配不到、表被读成空。
     *
     * ⚠️ 更糟的是**症状**：空表恰好是**合法状态**（"还没有曲线"）⇒
     *    两份实现都出错时，它们**仍然是一致的** —— 这正是工作区纪律 ⑦
     *    「两边各写一遍，镜像全绿而实现是错的」的教科书形态。
     *
     * ⇒ ★ **把结构上的难点删掉，而不是修两遍**：扁平串用 `split` 就读完了，
     *   **没有配平、没有嵌套、没有正则**。
     */
    private fun levelsText(ls: List<TntgoBklCurve.Level>): String =
        ls.joinToString(";") { "${it.mcu}:${"%.1f".format(it.absMa)}:${it.n}" }

    /**
     * ⚠️ 手写解析（不引 JSON 库 —— 这个模块刻意只依赖 Android 框架）。
     * 结构由 [levelsText] 唯一生产 ⇒ **只认这一种形态**，认不出就当空。
     */
    private fun parseCurve(raw: String): TntgoBklCurve.Curves {
        fun text(key: String): String {
            val m = Regex("\"$key\":\"([^\"]*)\"").find(raw) ?: return ""
            return m.groupValues[1]
        }
        fun levels(key: String): List<TntgoBklCurve.Level> {
            val s = text(key)
            if (s.isEmpty()) return emptyList()
            val out = ArrayList<TntgoBklCurve.Level>()
            for (item in s.split(';')) {
                val p = item.split(':')
                if (p.size != 3) {
                    Log.w(TAG, "曲线表项解析不了（已跳过）：\"$item\"")
                    continue
                }
                val mcu = p[0].toIntOrNull()
                val ma = p[1].toDoubleOrNull()
                val n = p[2].toIntOrNull()
                if (mcu == null || ma == null || n == null) {
                    Log.w(TAG, "曲线表项数值非法（已跳过）：\"$item\"")
                    continue
                }
                out.add(TntgoBklCurve.Level(mcu, ma, n))
            }
            return out
        }
        return TntgoBklCurve.Curves(
            charging = levels("chg"),
            discharging = levels("dis"),
            legacyNoMcu = Regex(""""legacy":(\d+)""").find(raw)?.groupValues?.get(1)?.toInt() ?: 0,
        )
    }

    /**
     * ★★ **读一次当前亮度**（走共享状态通道）。
     *
     * @return `null` = **亮度未知**（亮度 mod 没运行 / 心跳停了 / 从未设过）
     *         ⇒ 调用方**必须降级显示，不许猜**
     */
    fun currentBrightness(context: Context): TntgoState.Brightness? =
        TntgoState.readBrightness(context)

    private fun medianInt(v: List<Int>): Int {
        if (v.isEmpty()) return 0
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    companion object {
        private const val TAG = "ModeMod/Tntgo"

        /** 保留最近多少条（≈ 每 30 s 一条 ⇒ 240 条约 2 小时） */
        private const val KEEP = 240

        private const val K_SAMPLES = "bkl.samples"
        private const val K_CURVE = "bkl.curve"

        /**
         * ★ 曲线落盘格式版本。**改格式就要 +1** —— 旧结构一律丢弃重算，不硬解。
         *
         * 历史：`1` = 嵌套数组 `[[mcu,ma,n],…]`（★ 已被扁平格式取代，见 [levelsText]）；
         *      `2` = 扁平串 `mcu:ma:n;mcu:ma:n`。
         */
        private const val CURVE_FORMAT_V = 2
    }

    /** 上次重算曲线时的样本条数（节流用） */
    private var lastBuiltCount = -1
}
