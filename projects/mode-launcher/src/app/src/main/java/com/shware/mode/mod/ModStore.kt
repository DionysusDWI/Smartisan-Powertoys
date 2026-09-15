package com.shware.mode.mod

import android.content.Context

/**
 * mod 的**开关状态**存放处（启动器的 `SharedPreferences`）。
 *
 * 只存两件事：`on:<modId>` → 布尔。
 * 状态里**不存**"上次是否在跑" —— 那个由 [ModRuntime.probeAll] **实时查**，
 * 免得 UI 显示一个过期结论。
 */
class ModStore(context: Context) {

    private val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 没显式设过 ⇒ 用 mod 自己申报的默认值（`META_ENABLED_BY_DEFAULT`，通常 false） */
    fun isEnabled(spec: ModSpec): Boolean =
        sp.getBoolean(KEY_PREFIX + spec.id, spec.enabledByDefault)

    fun setEnabled(id: String, on: Boolean) {
        sp.edit().putBoolean(KEY_PREFIX + id, on).apply()
    }

    /**
     * 所有【显式打开过】的 mod id。
     *
     * ⚠️ 用途是**开机/重启后自动重拉**（[com.shware.mode.LauncherService] 用），
     * 所以这里**只认显式 true**，不吃默认值 ——
     * 否则某个默认开的 mod 会在用户关掉它之后又被自动拉起来。
     */
    fun explicitEnabledIds(): Set<String> =
        sp.all.filter { it.value == true }
            .keys.map { it.removePrefix(KEY_PREFIX) }
            .toSet()

    private companion object {
        const val SP_NAME = "mode_mods"
        const val KEY_PREFIX = "on:"
    }
}
