package com.shware.mode.mod

import android.content.ComponentName

/**
 * 一个 mod 的申报信息 —— **全部来自它自己 manifest 的 `meta-data`**（见 [ModContract]）。
 *
 * 刻意做成不可变的纯数据：启动器拿到它之后不做任何"猜测"，缺字段就退回明确的默认值。
 */
data class ModSpec(
    /** ★ 稳定标识（`META_ID`），启动器用它记开关状态 */
    val id: String,
    val name: String,
    val desc: String,
    val target: Target,
    /** ★ 触摸行为 —— 决定窗口标志，见 [Touch] 与 [ModContract] §四 */
    val touch: Touch,
    /** mod 自己申报的契约版本 */
    val api: Int,
    /** mod 里那个 `<service>` 的组件名 —— 拉起/停掉都打它 */
    val component: ComponentName,
    val versionName: String?,
    val enabledByDefault: Boolean,
    /**
     * ★ **可选**：mod 自己声明的**设置界面**（见 [ModContract.META_SETTINGS]）。
     *
     * 非空 ⇒ 宿主的管理器会在这一行多给一个「设置」按钮。
     * `null` ⇒ 不显示按钮（**向后兼容**）。
     */
    val settings: ComponentName? = null,

    // ------------------------------------------------ 以下三个是「图形化 UI」用的可选申报
    // 全部有默认值 ⇒ **老 mod 一个都不用改**，UI 照常显示（只是落在默认分组/默认图标）。

    /**
     * ★ **可选**：分类键（`display` / `performance` / …），见 [ModContract.META_CATEGORY]。
     *
     * `null` ⇒ 由 `FeatureRegistry.guessCategory` 按 [id] 前缀猜，再兜底 `other`。
     */
    val categoryKey: String? = null,

    /** ★ **可选**：同分类内的排序权重，**小的排前面**。缺省 `0`。 */
    val orderHint: Int = 0,

    /** ★ **可选**：图标键，见 [ModContract.META_ICON]。`null` ⇒ 按分类取默认图标。 */
    val iconKey: String? = null,

    /**
     * ★★★ **这个 mod 跑在哪个进程**（`ServiceInfo.processName`）。
     *
     * ## 为什么它是「图层管理」的命根子
     *
     * 合并成**一个** APK 之后，6 个 mod 的 overlay 窗口在 `dumpsys window` 里
     * **标题和 package 完全相同**（都是 `com.shware.mode`）⇒ 光看窗口**分不出是谁**。
     *
     * 但每个 mod 都跑在自己的 `:mod_*` 进程里（manifest 里 `android:process`），
     * 而 window dump 的 `mSession=Session{<hash> <PID>:<uid>}` **带着 PID**：
     *
     * ```
     * Window #27 Window{62bf593 u0 com.shware.mode}:     ← 标题只有包名，无区分度
     *   mDisplayId=100000 mSession=Session{7de40f7 4130:u0a10001}
     *                                              ^^^^ PID
     * ```
     *
     * ⇒ **PID → 进程名 → mod**，这是唯一**不靠猜**的归属路径。
     *
     * > 实测（坚果 A10，2026-09-14）：`ps` 里 `4130 = com.shware.mode:mod_hello`，
     * > 与上面那个窗口的 PID 完全对上。
     */
    val processName: String = "",

    /**
     * ★★★★ **可选**：这个 mod 依赖的无障碍服务组件（`MOD_A11Y`）。
     *
     * `null` ⇒ 不需要无障碍（宿主不做任何无障碍相关的告警或重设，**向后兼容**）。
     *
     * ⚠️ **申报了它，宿主才能发现"这道门被关上了"** ——
     * 否则功能会**静默失效**：服务在跑、界面上绿点亮着、**可按键根本没人在听**。
     * 详见 [ModContract.META_A11Y] 里记的那次真实事故。
     */
    val a11y: ComponentName? = null,
) {

    /** 进程名兜底 —— manifest 不写 `android:process` 时进程名就是包名。 */
    val effectiveProcess: String get() = processName.ifEmpty { packageName }

    val packageName: String get() = component.packageName
    val serviceName: String get() = component.className

    /**
     * 契约版本兼容吗。
     *
     * **不是"越新越好"** —— 而是 mod 不能比启动器更新（它可能用到启动器还不认识的能力）。
     */
    val apiCompatible: Boolean get() = api in 1..ModContract.API_VERSION

    fun oneLine(): String = buildString {
        append("$name  [$id]  → ${target.label}  ${touch.label}  api=$api")
        versionName?.let { append("  v$it") }
        append("  ${component.flattenToShortString()}")
    }

    /**
     * 组件该画在哪块屏 —— **由 mod 自己申报**。
     *
     * 这是用户 2026-09-11 定下的形态边界：
     * 「组件**既能进 TNT 窗口，也能落在手机屏**」⇒ 位置是组件的属性，不是启动器写死的。
     */
    enum class Target(val key: String, val label: String) {
        /** ★ 画在 TNT 屏（`display 100000`）—— "加载进 TNT UI" 的那种 */
        TNT("tnt", "TNT 屏"),

        /** 画在手机屏（`display 0`）—— 手机侧悬浮组件 */
        PHONE("phone", "手机屏");

        companion object {
            /** 认不出来的一律当 [TNT]（那是主要场景），不抛异常。 */
            fun of(s: String?): Target =
                entries.firstOrNull { it.key.equals(s?.trim(), ignoreCase = true) } ?: TNT
        }
    }

    /**
     * ★★★ mod 的**触摸行为**（用户 2026-09-12 明确要求的两条）。
     *
     * 由 mod 在 manifest 里用 `MOD_TOUCH` 申报，**不由启动器猜** ——
     * 因为"有没有按钮"只有 mod 自己知道，而这两者要用**互斥的窗口标志**。
     *
     * > 详见 [ModContract] §四（含机制解释与三个陷阱）。
     */
    enum class Touch(val key: String, val label: String) {
        /**
         * **纯展示**（默认）—— 整块矩形**完全穿透**。
         *
         * 窗口带 `FLAG_NOT_TOUCHABLE` ⇒ 本窗口**永不接收触摸**，
         * 盖住的那块区域下面的东西照常能点。
         *
         * ⚠️ 代价：**自己也点不了** ⇒ 有按钮的 mod **不能**用它。
         */
        NONE("none", "穿透/纯展示"),

        /**
         * **交互**（有按钮）—— **只吃自己矩形内**的点击，矩形外照常穿透。
         *
         * 窗口带 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`：
         * 外部的点击给下层，矩形内的归自己 ⇒ **不会漏到后面**。
         *
         * ⚠️ 根布局**必须 `WRAP_CONTENT`** —— 用 `MATCH_PARENT`
         * （哪怕全透明）触摸区就是整屏，会**吃掉全屏点击**。
         */
        SELF("self", "自吃/有按钮");

        companion object {
            /**
             * **缺省 `none`**（见 [ModContract.META_TOUCH] 里对默认值的取舍说明）：
             * 忘写时宁可"卡片不挡点击"，也不要"卡片吃掉一片区域的点击"。
             */
            fun of(s: String?): Touch =
                entries.firstOrNull { it.key.equals(s?.trim(), ignoreCase = true) } ?: NONE
        }
    }
}
