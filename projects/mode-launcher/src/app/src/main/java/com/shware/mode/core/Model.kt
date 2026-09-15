package com.shware.mode.core

/**
 * 显示器的只读快照。
 *
 * 注意：**逻辑 displayId 与 SurfaceFlinger 的 display id 不是一回事**
 * （实测：TNT GO 逻辑 id = 2，SF id = 4632668781297955861）。
 * 前者给 `am start --display` / `input -d` 用，后者只给 `screencap -d` 用。
 */
data class DisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val state: Int,
    val isPresentation: Boolean,
    val isPrivate: Boolean,
) {
    val isDefault: Boolean get() = id == android.view.Display.DEFAULT_DISPLAY

    /** TNT 屏判据：可作 Presentation 且非 private —— 与 `.paper/04` §11 的探测口径一致 */
    val isExternalCandidate: Boolean get() = isPresentation && !isPrivate && !isDefault

    fun oneLine(): String =
        "display $id  $name  ${width}x$height @${densityDpi}dpi  " +
            "state=$state  presentation=$isPresentation  private=$isPrivate"
}

/** 矩形几何。窗口 bounds 与 `am task resize` 的参数都是这个口径（左, 上, 右, 下）。 */
data class Geo(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** `am task resize` 的四个独立整数参数 */
    fun toShellArgs(): String = "$left $top $right $bottom"

    fun oneLine(): String = "[$left,$top][$right,$bottom] (${width}x$height)"
}

/**
 * 一个任务（Task）的只读快照。
 *
 * `mode` 是**窗口模式**，不是 resizeable 位 ——
 * `am task resize` 只对 FREEFORM(5) / MULTI_WINDOW(6) 生效，
 * 对 fullscreen 调用会**静默失败**（见 `.paper/04` §0 第 4 条硬错误纠正）。
 */
data class TaskInfo(
    val taskId: Int,
    val displayId: Int,
    val component: String,
    val mode: String,
    val bounds: Geo?,
) {
    val isFreeform: Boolean get() = mode.contains("freeform", ignoreCase = true)
    val isFullscreen: Boolean get() = mode.contains("fullscreen", ignoreCase = true)

    fun oneLine(): String =
        "task $taskId  display $displayId  $mode  ${bounds?.oneLine() ?: "?"}  $component"
}

/**
 * 一个 **stack** 的只读快照（`IActivityTaskManager.getAllStackInfos()` 的投影）。
 *
 * ## ★ 为什么需要它（2026-09-12 补）
 *
 * Android 10 的层级是 **task → stack → display**。
 * 而**搬运窗口的 API 吃的是 `stackId`**：
 * `moveStackToDisplay(stackId, displayId)` / `moveTaskToStack(taskId, stackId, onTop)`。
 *
 * ⚠️ 现有的 [TaskInfo] 只给 `taskId`，**不先拿到这张映射就不知道往哪儿搬**。
 *
 * ## ★★★ 这是「跨屏搬运」这条原语的必需品
 *
 * [`Primitives`](Primitives.kt) 的注释写着"整个桌面（…拖拽 / **跨屏搬运**）都由这四条拼出来"——
 * 而在这之前**恰恰缺了搬运**。本类 + [Primitives.moveStackToDisplay] 补齐它。
 */
data class StackInfo(
    val stackId: Int,
    val displayId: Int,
    val topActivity: String,
    val taskIds: List<Int>,
    val bounds: Geo?,
) {
    val isOnDefaultDisplay: Boolean get() = displayId == 0

    fun oneLine(): String =
        "stack $stackId  display $displayId  tasks=$taskIds  " +
            "${bounds?.oneLine() ?: "?"}  $topActivity"
}
