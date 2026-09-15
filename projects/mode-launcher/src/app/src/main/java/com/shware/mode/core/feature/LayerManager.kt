package com.shware.mode.core.feature

import android.content.Context
import android.content.Intent
import android.util.Log
import com.shware.mode.shell.ShellGateway

/**
 * 一个**图层** —— 某个功能画在某块屏上的那一块东西。
 *
 * ## ★★★ 关键设计：这里装的是【真实窗口】，不是 mod 的申报值
 *
 * 每个 mod 在自己进程里 `addView`，宿主**碰不到别人的 Window 对象**。
 * 于是有两条路：
 *
 * | 路 | 结果 |
 * |---|---|
 * | ❌ 信 mod 申报（`MOD_TARGET` / `MOD_TOUCH`） | 申报和现实可以不一致 —— 界面会**撒谎** |
 * | ✅ **读 `dumpsys window` 的窗口真身** | 大小/位置/在屏/触摸标志**全是内核态事实** |
 *
 * 本实现走后者。一条 `dumpsys window windows` 里就能拿到：
 *
 * ```
 * Window #27 Window{62bf593 u0 com.shware.mode}:
 *   mDisplayId=100000 stackId=9 mSession=Session{7de40f7 4130:u0a10001}   ← ★ PID
 *   mOwnerUid=10001 mShowToOwnerOnly=true package=com.shware.mode appop=SYSTEM_ALERT_WINDOW
 *   mAttrs={(40,40)(wrapxwrap) gr=TOP END CENTER ty=APPLICATION_OVERLAY fmt=TRANSLUCENT
 *     fl=NOT_FOCUSABLE NOT_TOUCHABLE HARDWARE_ACCELERATED smpfl=0x1}      ← ★ 触摸实证
 *   Requested w=260 h=117
 *   mViewVisibility=0x0
 *   mFrame=[1860,40][2120,157]                                            ← ★ 位置与大小
 *   isVisible=true
 * ```
 *
 * > 上面这段是**坚果 Pro 3 的真实输出**（2026-09-14，`mod-hello`）。
 * > 注意 `mFrame` 与 `Requested w/h` + `x=40 gravity=TOP|END`（2160−40−260=1860）
 * > **三者互相印证** —— 说明解析出来的几何是可信的。
 */
data class LayerInfo(
    /** 归属的功能 id；`null` = 认不出是谁的窗口 */
    val featureId: String?,

    /** 展示名（认不出时退回窗口标题） */
    val featureName: String,

    /** 窗口标题 —— ⚠️ 合并成一个 APK 后**全是包名**，只有排错时有用 */
    val windowTitle: String,

    /** 宿主进程 pid */
    val pid: Int,

    /** 进程名（`com.shware.mode:mod_hello`）—— **归属的实际依据** */
    val processName: String,

    val packageName: String,

    /** 画在哪块屏：`0` = 手机屏，`≥100000` = TNT 虚拟屏 */
    val displayId: Int,

    /**
     * ★ **实测**的触摸行为 —— 从 `fl=` 里读，不是从 `MOD_TOUCH` 抄。
     *
     * 窗口带 `FLAG_NOT_TOUCHABLE` ⇒ [Feature.Touch.NONE]（整块穿透）；
     * 否则 ⇒ [Feature.Touch.SELF]（只吃自己矩形内）。
     */
    val touch: Feature.Touch,

    /** 当前是不是真的显示着（`isVisible` + `mViewVisibility`） */
    val visible: Boolean,

    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,

    /** 窗口类型串（`APPLICATION_OVERLAY` / `BASE_APPLICATION` …），排错用 */
    val windowType: String,

    /**
     * ★ **不属于任何已注册功能**的窗口（认不出归属、且不是本包的）。
     *
     * 保留它们是**故意的**：TNT 上一块来路不明的 overlay 恰恰是最该被看见的东西。
     */
    val thirdParty: Boolean = false,
) {

    val isTnt: Boolean get() = displayId >= FIRST_VIRTUAL_DISPLAY_ID
    val displayLabel: String get() = if (isTnt) "TNT 屏 ($displayId)" else "手机屏 ($displayId)"

    /** `260×117 @ (1860,40)` */
    val geometry: String get() = "$w×$h @ ($x,$y)"

    /** 面积（用于重叠判定与排序） */
    val area: Long get() = w.toLong() * h.toLong()

    /** 两个图层的矩形相交吗（同屏、都可见、面积非零才算） */
    fun overlaps(other: LayerInfo): Boolean =
        displayId == other.displayId && visible && other.visible &&
            area > 0 && other.area > 0 &&
            x < other.x + other.w && other.x < x + w &&
            y < other.y + other.h && other.y < y + h

    companion object {
        /** 虚拟屏 id 的起点 —— 与 `ShellGateway.FIRST_VIRTUAL_DISPLAY_ID` 同一个约定。 */
        const val FIRST_VIRTUAL_DISPLAY_ID = 100000
    }
}

/**
 * ★★★★★ **图层管理器**（任务 AP / AP3）—— 「多层同屏管理」的大脑。
 *
 * ## 一、职责边界（★ 刻意不越权）
 *
 * | # | 做 | 不做 |
 * |---|---|---|
 * | 1 | **登记**：谁在哪块屏上画了多大一块 | ❌ 不 `removeView` 别人的窗口（做不到，也不该做） |
 * | 2 | **实测**：位置/大小/在屏/触摸标志 | ❌ 不替 mod 决定该画在哪 |
 * | 3 | **控制**：通过广播请 mod 自己隐藏/显示 | ❌ 不直接改别人的 WindowManager.LayoutParams |
 * | 4 | **冲突检测**：两个"自吃"图层叠在一起 | |
 *
 * > ★★ 宿主**没有**别的窗口的所有权 —— 这是 Android 的硬边界。
 * > 所以正确的形态是「**登记处 ＋ 协调者**」，而不是「拥有者」。
 * > 一切控制都走**契约**（[ACTION_LAYER_CONTROL] 广播），mod 不实现也**不会崩**，
 * > 只是那条指令没人听 —— **平滑演进**。
 *
 * ## 二、归属怎么算出来的（★ 这是本类最要紧的一段）
 *
 * 合并成一个 APK 后，6 个 mod 的窗口标题**完全相同**（都是 `com.shware.mode`）
 * ⇒ **标题没有区分度**。真正的链条是：
 *
 * ```
 * dumpsys window  →  mSession=Session{<hash> <PID>:<uid>}
 *                                   ↓
 * ps -A -o PID,NAME  →  <PID> = com.shware.mode:mod_hello
 *                                   ↓
 * Feature.processName  ←→  匹配  →  归属到 mod-hello
 * ```
 *
 * ★ 两次 dump **合并成一次 shell 调用**（用哨兵行分隔）—— 省一次 IPC，
 * 也避免"两次快照之间进程变了"导致对不上号。
 *
 * ## 三、⚠️ 已知局限（要如实告诉用户，不要假装全能）
 *
 * 1. **拿不到别人的进程名时归属会落空** —— 会显示成"未知窗口"，而不是瞎猜
 * 2. **位置只是窗口外框**，不是内容排版后的实际视觉块
 * 3. **控制指令是"请求"不是"命令"** —— mod 没实现广播就没有任何反应
 */
class LayerManager(
    private val context: Context,
    private val shell: ShellGateway,
) {

    /**
     * ★ 抓一次全部图层的**真实**快照。
     *
     * @param features 用来做归属匹配（靠 [Feature.processName]）
     * @return 失败 = 查不了（通常是 Shizuku 没连）⇒ 调用方该显示"未知"，**不要显示成"没有图层"**
     */
    fun probe(features: List<Feature>): Result<List<LayerInfo>> {
        // ★ 一次 IPC 拿两份 dump（哨兵行分隔）
        val raw = shell.exec("dumpsys window windows; echo $MARK; ps -A -o PID,NAME")
            .getOrElse { return Result.failure(it) }

        val cut = raw.indexOf(MARK)
        if (cut < 0) {
            // 拿不到 ps ⇒ 仍然可以列窗口，只是**归属会全部落空**。
            // 宁可给出"位置对但名字未知"的图层，也不要整个失败。
            Log.w(TAG, "没拿到 ps 段（哨兵 $MARK 不在输出里）⇒ 归属将不可用")
            return Result.success(parseWindows(raw, emptyMap(), features))
        }

        val winDump = raw.substring(0, cut)
        val pidMap = parsePs(raw.substring(cut + MARK.length))
        return Result.success(parseWindows(winDump, pidMap, features))
    }

    /** 只列**我们自己的**图层（默认视图；第三方窗口一般不列） */
    fun probeMine(features: List<Feature>): Result<List<LayerInfo>> =
        probe(features).map { list -> list.filter { it.featureId != null } }

    // ------------------------------------------------------------------ 解析

    /**
     * 解析 `ps -A -o PID,NAME`。
     *
     * ⚠️ 首行是表头 `PID NAME`，靠 `toIntOrNull()` 自然跳过 —— 不要按行号切。
     */
    fun parsePs(text: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val sp = t.indexOfFirst { it == ' ' || it == '\t' }
            if (sp <= 0) continue
            val pid = t.substring(0, sp).toIntOrNull() ?: continue
            val name = t.substring(sp).trim()
            if (name.isNotEmpty()) out[pid] = name
        }
        return out
    }

    /**
     * 解析 `dumpsys window windows`，按 `Window #` 切块。
     *
     * ⚠️ **必须按块解析，不能全局 grep** ——
     * `mFrame=` / `package=` 这些行在每个窗口里都出现，
     * 全局搜索会把 A 窗口的 frame 配到 B 窗口的 package 上。
     */
    fun parseWindows(
        dump: String,
        pidMap: Map<Int, String>,
        features: List<Feature>,
    ): List<LayerInfo> {
        val byProcess = features.filter { it.processName.isNotEmpty() }
            .associateBy { it.processName }
        val out = ArrayList<LayerInfo>()

        for (block in splitBlocks(dump)) {
            val head = block.firstOrNull() ?: continue
            // 只认 `Window #n Window{hash u0 <title>}:` 这种行
            val mHead = RX_HEAD.find(head) ?: continue
            val title = mHead.groupValues[1].trim()

            val body = block.drop(1).joinToString("\n")
            val pkg = RX_PKG.find(body)?.groupValues?.get(1) ?: continue
            val pid = RX_PID.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val displayId = RX_DISPLAY.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val type = RX_TYPE.find(body)?.groupValues?.get(1) ?: "?"

            // ★ 只收 overlay 型窗口 —— BASE_APPLICATION 那些是普通 Activity，不是"图层"
            if (type != "APPLICATION_OVERLAY") continue

            val frame = RX_FRAME.find(body)?.groupValues
            val x = frame?.get(1)?.toIntOrNull() ?: 0
            val y = frame?.get(2)?.toIntOrNull() ?: 0
            val x2 = frame?.get(3)?.toIntOrNull() ?: 0
            val y2 = frame?.get(4)?.toIntOrNull() ?: 0

            val flags = RX_FLAGS.find(body)?.groupValues?.get(1).orEmpty()
            val touch = if (flags.contains("NOT_TOUCHABLE")) Feature.Touch.NONE else Feature.Touch.SELF

            // `mViewVisibility=0x0` = VISIBLE；`0x8` = GONE
            val viewVis = RX_VIEWVIS.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val isVisible = RX_ISVISIBLE.find(body)?.groupValues?.get(1)?.toBoolean() ?: false
            val visible = isVisible && viewVis != 0x8

            val proc = pidMap[pid].orEmpty()
            val owner = byProcess[proc]

            out += LayerInfo(
                featureId = owner?.id,
                // ★ 归属不到时用**窗口标题**（如 `game barrage view`）——
                //   比再打一遍包名有信息量得多；标题也空才退回进程名
                featureName = owner?.name ?: title.ifEmpty { proc },
                windowTitle = title,
                pid = pid,
                processName = proc,
                packageName = pkg,
                displayId = displayId,
                touch = touch,
                visible = visible,
                x = x, y = y,
                w = (x2 - x).coerceAtLeast(0),
                h = (y2 - y).coerceAtLeast(0),
                windowType = type,
                thirdParty = owner == null && pkg != context.packageName,
            )
        }

        // 排布顺序：先按屏，再按"是不是我们的"，最后按面积从大到小（大的在下层）
        return out.sortedWith(
            compareBy({ it.displayId }, { it.featureId == null }, { -it.area }, { it.featureName })
        )
    }

    /** 按 `Window #` 行切块；顺带丢掉块内嵌的 `WindowStateAnimator{…}` 之类子块（它们不带头行） */
    private fun splitBlocks(dump: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur: ArrayList<String>? = null
        for (line in dump.lineSequence()) {
            // ⚠️ 用 trimStart 而不是写死两个空格 —— 不同 ROM 的缩进不同，
            //    写死会让整个解析【静默返回空列表】（看起来像"一个图层都没有"）
            if (line.trimStart().startsWith("Window #")) {
                cur?.let { out += it }
                cur = ArrayList()
            }
            cur?.add(line)
        }
        cur?.let { out += it }
        return out
    }

    // ------------------------------------------------------------------ 冲突检测

    /**
     * ★ **两个"自吃"图层叠在一起** —— 上面那个会吃掉下面那个的点击。
     *
     * 为什么只报 `self`×`self`：
     * `none`（带 `FLAG_NOT_TOUCHABLE`）**完全不接收触摸**，叠多少层都不影响别人
     * ⇒ 纯展示卡片叠在一起**不是问题**，报出来只会是噪音。
     */
    fun findConflicts(layers: List<LayerInfo>): List<LayerConflict> {
        val touchy = layers.filter {
            it.visible && it.touch == Feature.Touch.SELF && it.featureId != null
        }
        val out = ArrayList<LayerConflict>()
        for (i in touchy.indices) {
            for (j in i + 1 until touchy.size) {
                val a = touchy[i]; val b = touchy[j]
                if (a.overlaps(b)) out += LayerConflict(a, b, overlapArea(a, b))
            }
        }
        return out.sortedByDescending { it.overlapArea }
    }

    private fun overlapArea(a: LayerInfo, b: LayerInfo): Long {
        val w = (minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)).coerceAtLeast(0)
        val h = (minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)).coerceAtLeast(0)
        return w.toLong() * h.toLong()
    }

    // ------------------------------------------------------------------ 控制（走契约广播）

    /**
     * ★ **请某个（或全部）mod 隐藏 / 显示自己的图层**。
     *
     * ⚠️ 这是**请求**，不是命令 —— mod 没实现 [ACTION_LAYER_CONTROL] 就毫无反应。
     * **不要**把它当成一定能生效的操作写进 UI 文案。
     *
     * @param featureId `null` = 广播给所有 mod 进程（各自判断要不要理会）
     */
    fun setVisible(featureId: String?, visible: Boolean): Result<Unit> = runCatching {
        val i = Intent(ACTION_LAYER_CONTROL)
            .putExtra(EXTRA_LAYER_OP, if (visible) OP_SHOW else OP_HIDE)
            .putExtra(EXTRA_LAYER_TARGET, featureId ?: "")
        context.sendBroadcast(i)
        Log.i(TAG, "→ 图层广播 ${if (visible) OP_SHOW else OP_HIDE} target=${featureId ?: "(全部)"}")
    }

    fun toggle(featureId: String, currentlyVisible: Boolean): Result<Unit> =
        setVisible(featureId, !currentlyVisible)

    companion object {
        private const val TAG = "Mode/LayerManager"

        /** 把两次 dump 隔开的哨兵行 —— 用一个正常 dump 里绝不会出现的串。 */
        private const val MARK = "___POWERTOYS_MARK_7f3a___"

        /** ★ 图层控制广播 —— mod 侧**可选**实现，见计划书 AP §4.3。 */
        const val ACTION_LAYER_CONTROL = "com.shware.mode.action.LAYER_CONTROL"
        const val EXTRA_LAYER_OP = "com.shware.mode.extra.LAYER_OP"
        const val EXTRA_LAYER_TARGET = "com.shware.mode.extra.LAYER_TARGET"
        const val OP_HIDE = "hide"
        const val OP_SHOW = "show"
        const val OP_TOGGLE = "toggle"

        /**
         * `  Window #27 Window{62bf593 u0 com.shware.mode}:`
         *
         * ⚠️ **不能用 `Window\{[^}]*\s(\S+)\}`** —— `[^}]*` 贪婪，
         * `(\S+)` 只会吃到**最后一个词** ⇒ 标题 `game barrage view` 被截成 `view`。
         * （实测 32 个窗口里有 3 个标题带空格，一截就错。）
         *
         * 改成：跳过 `hash u<userId>`，把**剩下全部**当标题（`.*?` 到第一个 `}:` 为止）。
         *
         * ⚠️ 也不要在末尾写 `$` —— Kotlin 的 raw string 里 `$` 会跟字符串模板打架，
         * 写成 `\s*$` 容易踩"到底算字面量还是模板"的坑；这里按行取，本来也不需要锚尾。
         */
        private val RX_HEAD = Regex("""^\s*Window #\d+ Window\{\S+\s+u\d+\s+(.*?)\}:""")
        private val RX_PKG = Regex("""\bpackage=(\S+)""")
        private val RX_PID = Regex("""mSession=Session\{[^}]*?\s(\d+):""")
        private val RX_DISPLAY = Regex("""mDisplayId=(-?\d+)""")
        private val RX_TYPE = Regex("""\bty=(\S+)""")
        private val RX_FRAME = Regex("""mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
        private val RX_FLAGS = Regex("""\n\s+fl=([^\n]*)""")
        private val RX_VIEWVIS = Regex("""mViewVisibility=0x([0-9a-fA-F]+)""")
        private val RX_ISVISIBLE = Regex("""isVisible=(\w+)""")
    }
}

/** 两个"自吃"图层叠在一起 —— 上面那个会吃掉下面那个的点击。 */
data class LayerConflict(
    val upper: LayerInfo,
    val lower: LayerInfo,
    val overlapArea: Long,
) {
    val displayLabel: String get() = upper.displayLabel
    val overlapLabel: String
        get() = "重叠约 ${overlapArea / 1000} 千像素²"

    fun oneLine(): String =
        "${upper.featureName} ⇄ ${lower.featureName}  $displayLabel  $overlapLabel"
}
