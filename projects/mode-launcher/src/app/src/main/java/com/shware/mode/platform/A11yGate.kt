package com.shware.mode.platform

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * ★★★★ **无障碍这道"人工门"的读数与重设**（2026-09-14 新增）。
 *
 * ## 为什么需要它
 *
 * 有的 mod 靠 `AccessibilityService` + `canRequestFilterKeyEvents` 拿全局硬件按键
 * （TNT GO 亮度键就是这条路的唯一走法）。
 * 但**无障碍服务必须由用户在系统设置里手动开** —— 这是第四道人工门。
 *
 * ### ⚠️⚠️ 而这道门会**无声无息地关上**
 *
 * 实测（任务 AI 记过，2026-09-14 又踩一次）：
 *
 * ```
 * am force-stop <包名>   ⇒   enabled_accessibility_services 变成 null
 *                             accessibility_enabled 变成 0
 * ```
 *
 * ⇒ **每次冷启动调试 / 装机 / 崩溃重启之后，按键功能就死了**，
 * 而 mod 服务本身**还在前台跑**（`isForeground=true`）
 * ⇒ 界面上那个绿点仍然写着「运行中」 ⇒ ★ **功能静默失效**。
 *
 * 本次就是这样：我为了做冷启动测试 force-stop 了十几次，
 * 结果把用户已经在用的亮度键弄坏了，而**界面上完全看不出来**。
 *
 * ## 这里提供两个能力
 *
 * | 函数 | 用途 |
 * |---|---|
 * | [missingFor] | ★ 读出"这个组件需要的无障碍服务**没开**" ⇒ 界面据此**显式告警**，不再静默 |
 * | [ensureEnabledCommand] | ★ 生成一条**追加式**的 shell 命令 ⇒ 交给 Shizuku（shell 身份）自愈 |
 *
 * ## ★★ 为什么必须"追加"而不是"覆盖"
 *
 * `settings put secure enabled_accessibility_services <值>` 是**整体替换**。
 * 直接写我们的组件会把**用户其它应用的无障碍服务全部关掉**
 * （那是很严重的副作用：读屏、手势、自动化工具都会失效）。
 *
 * ⇒ 一律 **先读 → 合并 → 再写**，见 [ensureEnabledCommand]。
 */
object A11yGate {

    private const val TAG = "Mode/A11yGate"

    /** 一个已安装的无障碍服务。 */
    data class Svc(
        /** 扁平化组件名 `包/类` —— 与 `Settings.Secure` 里的写法一致 */
        val id: String,
        val label: String,
        val pkg: String,
    )

    /**
     * 本机**已安装**的全部无障碍服务（任何应用都读得到）。
     *
     * ⚠️ 这是"装了什么"，不是"开了什么" —— 开了什么要读 [enabledIds]。
     */
    fun installed(ctx: Context): List<Svc> {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return emptyList()
        return runCatching {
            @Suppress("DEPRECATION")
            am.installedAccessibilityServiceList.orEmpty().mapNotNull { info ->
                val si = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
                val cn = ComponentName(si.packageName, si.name)
                Svc(
                    id = cn.flattenToString(),
                    label = runCatching { info.resolveInfo.loadLabel(ctx.packageManager).toString() }
                        .getOrDefault(si.name),
                    pkg = si.packageName,
                )
            }
        }.onFailure { Log.w(TAG, "读已安装无障碍服务失败: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * 当前**已启用**的无障碍服务 id 集合。
     *
     * ⚠️ 值形如 `pkg/cls:pkg2/cls2`（**冒号分隔**）。
     * ★ 两侧都先经 `ComponentName` 归一化再比 —— 系统里存的可能是
     * `.Cls` 缩写形态，直接字符串比会**假报"没开"**。
     */
    fun enabledIds(ctx: Context): Set<String> =
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, ENABLED_KEY)
                .orEmpty()
                .split(':')
                .mapNotNull { normalize(it) }
                .toSet()
        }.onFailure { Log.w(TAG, "读已启用无障碍服务失败: ${it.message}") }
            .getOrDefault(emptySet())

    /**
     * ★ **这个组件需要的无障碍服务里，哪些还没开**。
     *
     * @param declared mod 在 manifest 里申报要用的无障碍组件（`MOD_A11Y`）。
     *                为 `null` ⇒ 这个组件不需要无障碍，返回空。
     * @return 没开的那些（空 = 门是开的，功能应当可用）
     */
    fun missingFor(ctx: Context, declared: ComponentName?): List<Svc> {
        if (declared == null) return emptyList()
        val want = normalize(declared.flattenToString()) ?: return emptyList()
        val enabled = enabledIds(ctx)
        if (want in enabled) return emptyList()
        // 没开 —— 但也要确认它**确实装着**（没装的话是另一个问题）
        val inst = installed(ctx).firstOrNull { normalize(it.id) == want }
        return listOf(
            inst ?: Svc(want, declared.className.substringAfterLast('.'), declared.packageName)
        )
    }

    /** 门是开的吗（`declared == null` 恒为 true —— 不需要门的组件永远"开着"） */
    fun isOpen(ctx: Context, declared: ComponentName?): Boolean =
        missingFor(ctx, declared).isEmpty()

    /**
     * ★★★ **生成一条"追加式"的重设命令**，交给 Shizuku（shell 身份）执行。
     *
     * 思路：**先读回当前值 → 把缺的加进去 → 整体写回**。
     * ⚠️ 绝不直接 `settings put secure … <我们自己的组件>` ——
     *    那会把用户其它应用的无障碍服务**全部关掉**。
     *
     * 命令用 `;` 串起来在**同一个 shell 进程**里跑（读-改-写之间不留窗口）。
     *
     * @param want 要确保开启的组件（`pkg/cls`）
     * @return 可直接交给 `ShellGateway.exec()` 的命令串
     */
    fun ensureEnabledCommand(want: String): String {
        val w = normalize(want) ?: want
        // ① 读现有值 ② 若已含则原样 ③ 否则拼上（注意去掉空段与重复）
        return buildString {
            append("CUR=$(settings get secure $ENABLED_KEY); ")
            append("case \":\$CUR:\" in *\":$w:\"*) ;; *) ")
            append("if [ -z \"\$CUR\" ] || [ \"\$CUR\" = \"null\" ]; then NEW=$w; ")
            append("else NEW=\"\$CUR:$w\"; fi; ")
            append("settings put secure $ENABLED_KEY \"\$NEW\"; ")
            append("settings put secure $ENABLED_FLAG 1; ")
            append("esac")
        }
    }

    /** 归一化成 `包/全类名`（`,Cls` 缩写补包名）。认不出返回 `null`（**不抛异常**）。 */
    fun normalize(id: String?): String? {
        val s = id?.trim().orEmpty()
        if (s.isEmpty()) return null
        val cn = ComponentName.unflattenFromString(s) ?: return null
        return cn.flattenToString()
    }

    /** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`（**冒号分隔**的组件列表） */
    const val ENABLED_KEY = "enabled_accessibility_services"

    /** `Settings.Secure.ACCESSIBILITY_ENABLED`（0/1 总开关） */
    const val ENABLED_FLAG = "accessibility_enabled"
}
