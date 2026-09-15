package com.shware.mode.mod.perfmon

import android.content.Context

/**
 * 本 mod 的**本地配置**（`SharedPreferences`，mod 私有）。
 *
 * 存两件事：
 * 1. **启用哪些指标**（用户在 [SettingsActivity] 里勾的）
 * 2. **卡片位置**（用户长按拖动后落下的坐标）
 *
 * ## 为什么位置必须持久化
 *
 * 拖动如果只在内存里，**每次重启卡片就弹回右上角** —— 那拖动等于白做。
 *
 * ## 为什么带 [version]
 *
 * [PerfMonService] 每秒跑一次。让它**逐项比对**配置太啰嗦，
 * 直接比一个**版本号**：变了就整块重建卡片。写入方（设置界面 / 拖动）负责 `bump`。
 */
class PerfConfig(context: Context) {

    private val sp = context.getSharedPreferences("perfmon", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 指标开关

    /** 当前启用的指标集合。**没设过 = 全开**。 */
    val enabled: Set<String>
        get() = sp.getStringSet(K_METRICS, null)?.toSet() ?: PerfMetrics.ALL_LABELS.toSet()

    fun isEnabled(label: String): Boolean = label in enabled

    fun setEnabled(label: String, on: Boolean) {
        val s = enabled.toMutableSet()
        if (on) s.add(label) else s.remove(label)
        sp.edit().putStringSet(K_METRICS, s).apply()
        bumpVersion()
    }

    // ------------------------------------------------------------------ 位置

    /**
     * 卡片位置，`Gravity.TOP or Gravity.START` 下的**绝对像素**。
     *
     * @return `null` = 还没拖过（用默认位置）
     */
    val pos: Pair<Int, Int>?
        get() = if (sp.contains(K_X)) sp.getInt(K_X, 0) to sp.getInt(K_Y, 0) else null

    fun savePos(x: Int, y: Int) {
        sp.edit().putInt(K_X, x).putInt(K_Y, y).apply()
    }

    fun clearPos() {
        sp.edit().remove(K_X).remove(K_Y).apply()
    }

    // ------------------------------------------------------------------ 版本号

    /** 变了 ⇒ 服务重建卡片 */
    var version: Int
        get() = sp.getInt(K_VER, 0)
        private set(value) {
            sp.edit().putInt(K_VER, value).apply()
        }

    /** 外部（如"复位位置"）改完配置后调它，通知服务重建 */
    fun bump() {
        version = version + 1
    }

    private fun bumpVersion() = bump()

    private companion object {
        const val K_METRICS = "metrics"
        const val K_X = "pos.x"
        const val K_Y = "pos.y"
        const val K_VER = "cfg.ver"
    }
}
