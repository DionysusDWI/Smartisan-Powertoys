package com.shware.mode.core.feature

import android.content.ComponentName

/**
 * ★★★★★ **统一功能模型**（任务 AP）—— UI 与 API 都只认它。
 *
 * ## 为什么要这一层
 *
 * 在这之前，UI 直接读 [com.shware.mode.mod.ModSpec]（那是**mod 契约**的模型）。
 * ⇒ 后果：**UI 与"外装 mod"这件事绑死了** ——
 * 想加一个"内置功能"、或者从别处（远程配置）来一个功能，UI 就得改。
 *
 * ★ 现在：**一切皆 [Feature]**。来源由 [Source] 表达，UI **不关心**。
 *
 * ## 可扩展性（本任务的核心判据）
 *
 * > **以后新增一个 mod，UI 与 API 【一行都不用改】。**
 *
 * 做法：
 * 1. mod 契约上的 `MOD_CATEGORY` / `MOD_ORDER` / `MOD_ICON` **全部可选** ⇒ 老 mod 照常显示
 * 2. UI **按 [Category] 分组渲染** ⇒ 新分类只加一个枚举值
 * 3. `ModSpec → Feature` 的转换**集中在一处**（[FeatureRegistry]）⇒ 换来源只改那里
 */
data class Feature(
    /** ★ 稳定标识（沿用 mod 的 `MOD_ID` ⇒ 用户的开关键状态不会因改 UI 而丢） */
    val id: String,

    val name: String,
    val desc: String,

    /** ★ 分类 —— UI 靠它分组 */
    val category: Category,

    /** 目标屏（TNT / 手机） */
    val target: Target,

    /** ★ 用户开关（持久化在宿主） */
    val enabled: Boolean,

    /** ★ 实际运行状态（由 `dumpsys` 探测，见 ModRuntime.probeAll） */
    val state: State,

    /** 有设置界面就有值 ⇒ UI 显示「设置」按钮（**向后兼容**：没有就不显示） */
    val settings: ComponentName?,

    /** 申报的触摸行为 ⇒ 决定它在「图层」里的性质 */
    val touch: Touch,

    /** 来源 */
    val source: Source,

    /** 排序权重（小的在前；同权重按名字） */
    val order: Int,

    /** 版本（外装 mod 来自 PackageManager；内置为 null） */
    val versionName: String?,

    /** ★ 契约兼容版本（mod 报的 `MOD_API`）；内置功能恒为当前版本 */
    val api: Int = com.shware.mode.mod.ModContract.API_VERSION,

    /** ★ mod 自己申报的图标键（`MOD_ICON`）。`null` ⇒ 用 [icon] 的分类兜底 */
    val iconKey: String? = null,

    /**
     * ★★ **这个功能跑在哪个进程** —— 图层归属的凭据（见 `ModSpec.processName`）。
     *
     * 合并成一个 APK 后，6 个 mod 的窗口标题**全都是包名**；
     * 只有「PID → 进程名」这条链能把窗口认到具体功能上。
     */
    val processName: String = "",

    /**
     * ★★★★ **这道"人工门"没开** —— 非 `null` 就是功能**不完整**，界面必须显式告警。
     *
     * ## 为什么要有这个字段（2026-09-14 的真实事故）
     *
     * 无障碍服务必须由用户在系统设置里手动开，而 **`am force-stop <包名>`
     * 会把它整个抹掉**（`enabled_accessibility_services` → `null`）。
     *
     * ⇒ 后果是**功能静默失效**：mod 服务还在前台跑，本界面的绿点写着「运行中」，
     *   **可按键根本没人在听**。用户只能靠"感觉不对"发现。
     *
     * ★ 本次就是这么坏掉的 —— 我为了做冷启动测试 force-stop 了十几次，
     *   把用户已经在用的 TNT GO 亮度键弄坏了，而**界面上完全看不出来**。
     *
     * ⇒ 有了这个字段，[com.shware.mode.ui.FeatureAdapter] 会在卡片上打一条**琥珀色告警**，
     *   并且 [com.shware.mode.LauncherService] 会尝试**自愈**。
     */
    val gate: Gate? = null,
) {

    /**
     * 一道**没开的门**。
     *
     * @param key 稳定标识（用于去重/埋点），如 `a11y`
     * @param label 一句话说清"缺什么"，如 `无障碍服务未开启`
     * @param hint 怎么开，如 `设置 → 辅助功能 → 服务 → 「TNT GO 亮度键」`
     */
    data class Gate(val key: String, val label: String, val hint: String)

    /**
     * ★ **实际要显示的图标键 —— 永不空**。
     *
     * 优先用 mod 申报的 [iconKey]；没申报或申报了空串 ⇒ **退回本分类的默认图标**。
     * ⇒ 新 mod **不写 `MOD_ICON` 也一定有个像样的图标**，不会出现空位。
     *
     * ⚠️ 这是**键名**，不是资源 id —— 见 [ModContract.META_ICON] 里对跨包资源 id 的说明。
     */
    val icon: String get() = iconKey?.takeIf { it.isNotBlank() } ?: category.iconKey

    /**
     * ★ 契约版本兼容吗（与 `ModSpec.apiCompatible` 同一判据）。
     *
     * **不是"越新越好"** —— 而是 mod 不能比宿主更新：
     * 它可能用到宿主还不认识的能力。不兼容时 UI 要把开关和按钮**变灰**。
     */
    val apiCompatible: Boolean get() = api in 1..com.shware.mode.mod.ModContract.API_VERSION

    /**
     * 这个功能**此刻是不是正在屏幕上画东西**（⇒ 会出现在「图层管理」里）。
     *
     * ★ 判据是 [state]，不是 [touch] ——
     * 「有窗口」和「窗口吃不吃触摸」是两件事：
     * 一个纯展示（`touch = none`）的卡片**照样是一层**，只是穿透而已。
     */
    val drawsLayer: Boolean get() = state == State.RUNNING

    enum class Target(val key: String, val label: String) {
        TNT("tnt", "TNT 屏"),
        PHONE("phone", "手机屏");

        companion object {
            fun of(k: String?): Target = entries.firstOrNull { it.key == k } ?: TNT
        }
    }

    /** ★ 触摸行为 —— `none` = 完全穿透，`self` = 只吃自己那一块 */
    enum class Touch(val key: String, val label: String) {
        NONE("none", "穿透"),
        SELF("self", "可交互");

        companion object {
            fun of(k: String?): Touch = entries.firstOrNull { it.key == k } ?: NONE
        }
    }

    /** 运行状态 */
    enum class State(val label: String) {
        RUNNING("运行中"),
        STOPPED("未运行"),
        ERROR("异常"),
        UNKNOWN("未知"),
    }

    /** 来源 —— ★ UI 不关心，但 API 与"排查问题"时有用 */
    enum class Source(val label: String) {
        /** 与宿主同一个 APK（合并后的内置功能） */
        BUILTIN("内置"),

        /** ★ 外装的独立 mod APK */
        EXTERNAL("外装"),
    }

    /**
     * ★ 分类 —— **UI 按它分组**。
     *
     * 新增分类：**只在这里加一个枚举值**，UI 自动多一组。
     */
    enum class Category(val key: String, val label: String, val order: Int, val iconKey: String) {
        DISPLAY("display", "显示", 10, "cat_display"),
        INPUT("input", "输入", 20, "cat_input"),
        PERFORMANCE("performance", "性能", 30, "cat_performance"),
        MEDIA("media", "媒体", 40, "cat_media"),
        SYSTEM("system", "系统", 50, "cat_system"),
        OTHER("other", "其它", 99, "cat_other");

        companion object {
            /** ★ 未知/缺省一律落到 [OTHER] ⇒ **老 mod 不会因为没写分类而消失** */
            fun of(k: String?): Category =
                entries.firstOrNull { it.key == k?.trim()?.lowercase() } ?: OTHER
        }
    }
}
