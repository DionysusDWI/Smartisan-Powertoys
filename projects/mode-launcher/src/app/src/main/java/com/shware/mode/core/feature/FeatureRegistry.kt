package com.shware.mode.core.feature

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.shware.mode.mod.ModRegistry
import com.shware.mode.mod.ModRuntime
import com.shware.mode.mod.ModSpec
import com.shware.mode.mod.ModState
import com.shware.mode.mod.ModStore
import com.shware.mode.platform.A11yGate
import com.shware.mode.shell.ShellGateway

/**
 * ★★★★★ **功能注册表**（任务 AP）—— 把「发现 / 合并 / 状态」收在一处。
 *
 * ## 它在架构里的位置
 *
 * ```
 *  UI (HomeActivity / LayerActivity)
 *      ↓ 只认 Feature
 *  ★ FeatureRegistry  ← 本文件：唯一的"来源适配"点
 *      ↓
 *  ModRegistry / ModRuntime / ModStore   （已有的 mod 基础设施，不动）
 * ```
 *
 * ## ★★ 可扩展性：以后加来源只改这里
 *
 * 现在只有一个来源（外装/内置的 mod APK，都走 `ModRegistry`）。
 * 将来若要有**内置功能**（不走 mod 契约、直接是一个 Kotlin 对象），
 * 只需在这里**多一个 `adaptBuiltin()`**，**UI 与 API 一行不用改**。
 *
 * > 判据见 [Feature] 的注释：**新增功能时 UI 零改动**。
 */
class FeatureRegistry(
    private val context: Context,
    private val shell: ShellGateway,
) {

    private val modRegistry = ModRegistry(context)
    private val modRuntime = ModRuntime(context, shell)
    private val store = ModStore(context)

    /**
     * ★ 拿一份完整的功能清单（含实时运行状态）。
     *
     * ⚠️ 会走一次 `dumpsys activity services`（约 2700 行）—— 见 [ModRuntime.probeAll]。
     * ⇒ **不要在滚动回调里频繁调**；UI 用「刷新」按钮或下拉触发。
     */
    fun load(): List<Feature> {
        val specs = modRegistry.discover()
        // 一次 dumpsys 覆盖全部；失败（Shizuku 没连）时退化成 UNKNOWN，而不是谎报"未运行"
        val states: Map<String, ModState> = modRuntime.probeAll(specs).getOrElse {
            Log.w(TAG, "probeAll 失败：${it.message}")
            emptyMap()
        }
        return specs.map { adaptMod(it, states[it.id]) }
            .sortedWith(compareBy({ it.category.order }, { it.order }, { it.name }))
    }

    /** 只发现、不查状态（UI 首帧用，快） */
    fun discoverOnly(): List<Feature> =
        modRegistry.discover().map { adaptMod(it, null) }
            .sortedWith(compareBy({ it.category.order }, { it.order }, { it.name }))

    // ------------------------------------------------------------------ 适配

    /**
     * ★★★ **唯一的一处「mod 契约 → 统一模型」转换**。
     *
     * 换来源（内置 / 远程）时**只改这里**。
     */
    private fun adaptMod(spec: ModSpec, state: ModState?): Feature {
        val builtin = spec.packageName == context.packageName
        return Feature(
            id = spec.id,
            name = spec.name,
            desc = spec.desc,
            // ★ 分类：契约上没写 ⇒ 按 MOD_ID 前缀猜一个合理的，再兜底 OTHER
            category = Feature.Category.of(spec.categoryKey ?: guessCategory(spec.id)),
            target = when (spec.target) {
                ModSpec.Target.TNT -> Feature.Target.TNT
                ModSpec.Target.PHONE -> Feature.Target.PHONE
            },
            enabled = store.isEnabled(spec),
            state = when (state) {
                ModState.RUNNING -> Feature.State.RUNNING
                ModState.CREATED -> Feature.State.RUNNING   // 已创建即算在跑（非前台是个待查信号）
                ModState.ABSENT -> Feature.State.STOPPED
                ModState.UNKNOWN, null -> Feature.State.UNKNOWN
            },
            settings = spec.settings,
            touch = when (spec.touch) {
                ModSpec.Touch.NONE -> Feature.Touch.NONE
                ModSpec.Touch.SELF -> Feature.Touch.SELF
            },
            source = if (builtin) Feature.Source.BUILTIN else Feature.Source.EXTERNAL,
            order = spec.orderHint,
            versionName = spec.versionName,
            api = spec.api,
            iconKey = spec.iconKey,
            processName = spec.effectiveProcess,
            gate = a11yGate(spec),
        )
    }

    /**
     * ★★★★ **这道人工门（无障碍）开了没有** —— `null` = 不需要 / 已经开着。
     *
     * ## 为什么必须显式读、显式报
     *
     * 无障碍登记会被 **`am force-stop <包名>`** 抹掉（实测），
     * 而 mod 服务本身**还在前台跑** ⇒ 界面绿点写着「运行中」，
     * **可按键根本没人在听** ⟹ ★ **功能静默失效，用户只能靠"感觉不对"发现**。
     *
     * ★ 2026-09-14 真实事故：我为做冷启动测试 force-stop 了十几次，
     *   把用户已在用的 **TNT GO 亮度键**弄坏了，而界面上完全看不出来。
     *
     * ⇒ 只要 mod 申报了 `MOD_A11Y`，这里就**每次**去读一次真实状态，
     *   没开就挂一条 [Feature.Gate]，界面负责显示、[com.shware.mode.LauncherService] 负责自愈。
     *
     * ⚠️ 没申报的 mod **一律返回 null** —— 绝不替它猜"是不是需要无障碍"（**向后兼容**）。
     */
    private fun a11yGate(spec: ModSpec): Feature.Gate? {
        val comp = spec.a11y ?: return null
        val missing = A11yGate.missingFor(context, comp)
        if (missing.isEmpty()) return null
        return Feature.Gate(
            key = "a11y",
            label = "无障碍服务未开启 —— 按键不会生效",
            hint = "设置 → 辅助功能 → 服务 → 「${missing.first().label}」" +
                "（★ 在列表最底部，要往下滚）",
        )
    }

    /**
     * ★ **兜底分类**：契约没写 `MOD_CATEGORY` 时，按 id 前缀猜。
     *
     * ⚠️ 这只是"让老 mod 看起来整齐一点"，**不是必需** —— 猜不中就是 `OTHER`。
     */
    private fun guessCategory(id: String): String = when {
        id.startsWith("tntgo.bright") -> "display"
        id.startsWith("tntgo") -> "display"
        id.startsWith("perf.mode") -> "performance"
        id.startsWith("perf.mon") -> "performance"
        id.startsWith("live.") -> "media"
        id.startsWith("input.") -> "input"
        else -> "other"
    }

    // ------------------------------------------------------------------ 操作

    /** 拉起一个功能 */
    fun start(feature: Feature, displayId: Int): Result<Unit> {
        val spec = specOf(feature.id) ?: return Result.failure(IllegalStateException("找不到 ${feature.id}"))
        return modRuntime.start(spec, displayId)
    }

    fun stop(feature: Feature): Result<Unit> {
        val spec = specOf(feature.id) ?: return Result.failure(IllegalStateException("找不到 ${feature.id}"))
        return modRuntime.stop(spec)
    }

    fun setEnabled(id: String, on: Boolean) = store.setEnabled(id, on)

    /**
     * 按 id 找一个功能（只发现，不查实时状态 —— 快）。
     *
     * ★ 对外 API（[com.shware.mode.api.PowerToysApiService]）用得到：
     * 它每次调用都是独立的一次跨进程请求，拿不到 UI 那份缓存。
     */
    fun find(id: String): Feature? = discoverOnly().firstOrNull { it.id == id }

    private fun specOf(id: String): ModSpec? = modRegistry.discover().firstOrNull { it.id == id }

    /** 哪块屏 —— `MOD_TARGET` 决定；TNT 屏 id 由调用方算好（见 DisplayWatcher） */
    fun targetDisplay(f: Feature, tntDisplayId: Int): Int =
        if (f.target == Feature.Target.TNT) tntDisplayId else 0

    /** 设置界面的组件名（没有就是 null） */
    fun settingsComponent(f: Feature): ComponentName? = f.settings

    private companion object {
        const val TAG = "Mode/FeatureRegistry"
    }
}
