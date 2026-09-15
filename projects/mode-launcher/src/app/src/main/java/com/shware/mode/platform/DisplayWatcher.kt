package com.shware.mode.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.shware.mode.core.DisplayInfo

/**
 * 显示器枚举 + 热插拔监听。**不需要任何权限。**
 *
 * ⚠️ 坑（`.paper/04` §2.5 实测）：**逻辑 displayId 会变**（已见 1→3→15→16→17→19）
 * ⇒ **任何地方都不准硬编码 displayId**，一律现取现用。
 */
class DisplayWatcher(context: Context) {

    private val dm = context.getSystemService(DisplayManager::class.java)

    /** 变化回调；参数是变化后的全量快照 */
    var onChanged: ((List<DisplayInfo>, String) -> Unit)? = null

    var addedCount = 0; private set
    var removedCount = 0; private set
    var changedCount = 0; private set

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            addedCount++
            emit("★ 显示器接入 id=$displayId（累计接入 $addedCount 次）")
        }

        override fun onDisplayRemoved(displayId: Int) {
            removedCount++
            emit("★ 显示器移除 id=$displayId（累计移除 $removedCount 次）")
        }

        override fun onDisplayChanged(displayId: Int) {
            changedCount++
            emit("显示器变化 id=$displayId")
        }
    }

    fun start() {
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        emit("已注册 DisplayListener")
    }

    fun stop() {
        runCatching { dm.unregisterDisplayListener(listener) }
    }

    /** 当前全部显示器 */
    fun snapshot(): List<DisplayInfo> = dm.displays.map { it.toInfo() }

    /** TNT 屏候选（presentation 且非 private 且非默认屏） */
    fun externalCandidates(): List<DisplayInfo> = snapshot().filter { it.isExternalCandidate }

    /**
     * ★★ 默认目标屏 = **承载 TNT 桌面的那个 display**。
     *
     * ⚠️ **既不能取 `firstOrNull()`，也不能取「面积最大者」** —— 两端都会选错：
     *
     * | 平台 | 陷阱 |
     * |---|---|
     * | 小米 17 Pro Max | 有**两块**内置屏：display 0（主屏）+ **display 1（背屏）**，<br/>背屏的 `FLAG_PRESENTATION` 也是 true，会被 `isExternalCandidate` 一并选中 |
     * | **坚果 Pro 3** | ★★ 是**双 display** 架构（2026-09-12 实测）：<br/>· `display 3` = 物理 HDMI 屏（`FLAG_PRESENTATION`）<br/>· **`display 100000` = `smt.tnt.virtual.display`（`FLAG_MIRROR_PC`）**<br/>**TNT 桌面渲染在 100000**，再镜像到物理屏 3<br/>⚠️ **两者尺寸完全相同（都是 2160×1440）⇒「面积最大者」必然选错** |
     *
     * ⇒ 判据改为：**取 `displayId` 最大者** ——
     * 虚拟屏 id 从 100000 起，天然排在所有物理屏之后，而 TNT 桌面正是跑在虚拟屏上。
     *
     * ⚠️ 这仍是启发式 —— 真正的解法是让用户显式指定（MODE 启动器的设置项，待做）。
     */
    fun defaultTarget(): DisplayInfo? =
        externalCandidates().maxByOrNull { it.id }

    private fun emit(what: String) {
        Log.i(TAG, what)
        onChanged?.invoke(snapshot(), what)
    }

    private fun Display.toInfo(): DisplayInfo {
        val m = DisplayMetrics()
        runCatching { getRealMetrics(m) }
        val f = runCatching { flags }.getOrDefault(0)
        val st = runCatching { state }.getOrDefault(Display.STATE_UNKNOWN)
        return DisplayInfo(
            id = displayId,
            name = runCatching { name }.getOrDefault("?"),
            width = m.widthPixels,
            height = m.heightPixels,
            densityDpi = m.densityDpi,
            state = st,
            isPresentation = (f and Display.FLAG_PRESENTATION) != 0,
            isPrivate = (f and Display.FLAG_PRIVATE) != 0,
        )
    }

    companion object {
        private const val TAG = "Mode/DisplayWatcher"

        fun stateName(s: Int): String = when (s) {
            Display.STATE_ON -> "ON"
            Display.STATE_OFF -> "OFF"
            Display.STATE_DOZE -> "DOZE"
            Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
            Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
            Display.STATE_UNKNOWN -> "UNKNOWN"
            else -> "?($s)"
        }
    }
}
