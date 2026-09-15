package com.shware.mode.shell

import android.util.Log
import com.shware.mode.core.DisplayInfo
import com.shware.mode.core.Geo
import com.shware.mode.core.Shortcuts
import com.shware.mode.core.TaskInfo
import com.shware.mode.platform.DisplayWatcher
import java.util.concurrent.Executors

/**
 * ★ 把「组合键」变成「动作」—— 任务 N 的那座桥。
 *
 * 读键在 [com.shware.mode.input.KeyStream]（Shizuku shell + `getevent`），
 * 执行在这里（四原语 / shell 命令）。本类是两者的接头处。
 *
 * ## 三条纪律
 *
 * 1. **跑在后台单线程** —— [ShellGateway.resizeTask] 内部有轮询 `Thread.sleep`，
 *    不能在主线程。而 `KeyStream.onCombo` 恰恰是在主线程回调的。
 * 2. **只在TNT 屏上操作** —— 目标是 [DisplayWatcher.defaultTarget]（面积最大的那块），
 *    不去碰手机自己的屏。
 * 3. **只动 freeform / 多窗口任务** —— 全屏任务 `am task resize` 会**静默失败**
 *    （任务 L 踩过），所以要先筛掉，否则报错原因会指向错误的方向。
 */
class ShortcutEngine(
    private val shell: ShellGateway,
    private val watcher: DisplayWatcher,
    /**
     * 宿主自己的包名 —— 用来在选窗口时**排除自己**。
     *
     * ⚠️ 宿主也在 TNT 屏上（被 TNT 当自由窗口接管），且 taskId 通常最大
     * ⇒ 不排除的话，`Ctrl+Win+←` 之类的动作会作用到**宿主自己**身上。
     * 依据：2026-09-12 真机实测（「改几何」改到了宿主自己）。
     */
    private val selfPackage: String,
) {

    /** 每次动作结束回报一次（含失败）。**回调在后台线程**，UI 要自己切主线程。 */
    var onResult: ((ok: Boolean, msg: String) -> Unit)? = null

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tntwm-shortcut").apply { isDaemon = true }
    }

    /**
     * @return `true` = 这个组合键**被我们接管了**（调用方据此决定要不要回显）；
     *         `false` = 不在 TNT 表里，我们不碰
     */
    fun handle(combo: String): Boolean {
        val def = Shortcuts.lookup(combo) ?: return false
        worker.execute {
            val (ok, msg) = runCatching { execute(def) }.getOrElse {
                false to "✗ ${def.tntName} 异常: ${it.javaClass.simpleName}: ${it.message}"
            }
            Log.i(TAG, msg)
            onResult?.invoke(ok, msg)
        }
        return true
    }

    fun shutdown() {
        runCatching { worker.shutdownNow() }
    }

    // ---------------------------------------------------------------- 动作分发

    private fun execute(def: Shortcuts.Def): Pair<Boolean, String> = when (def.action) {
        Shortcuts.Action.SNAP_LEFT -> snap(def, left = true)
        Shortcuts.Action.SNAP_RIGHT -> snap(def, left = false)
        Shortcuts.Action.FULLSCREEN -> fullscreen(def)
        Shortcuts.Action.DESKTOP -> home(def)
        Shortcuts.Action.NOTIFICATION -> simple(def, "cmd statusbar expand-notifications")
        Shortcuts.Action.LOCK -> simple(def, "input keyevent 223")   // 223 = KEYCODE_SLEEP
        Shortcuts.Action.FILE_MANAGER -> fileManager(def)
        // 🟡 未实现 —— 但**要在 UI 里说清楚为什么**，别让它静默什么都不做
        Shortcuts.Action.SCREENSHOT,
        Shortcuts.Action.CLOSE_WINDOW,
        Shortcuts.Action.CLIPBOARD,
        -> false to "🟡 ${def.tntName}：本版未实现（$UNIMPL_REASON）"
    }

    // ---------------------------------------------------------------- 窗口动作

    /** ★ 旗舰动作：把TNT 屏最顶窗口吸附到左/右半屏 */
    private fun snap(def: Shortcuts.Def, left: Boolean): Pair<Boolean, String> {
        val d = target() ?: return false to "✗ 没有TNT 屏"
        val t = topResizable(d.id)
            ?: return false to "✗ TNT 屏上没有可自由缩放的窗口（全屏窗口 resize 会静默失败）"

        val half = d.width / 2
        val geo = if (left) Geo(0, 0, half, d.height) else Geo(half, 0, d.width, d.height)
        return applyGeo(def.tntName, t, geo)
    }

    private fun fullscreen(def: Shortcuts.Def): Pair<Boolean, String> {
        val d = target() ?: return false to "✗ 没有TNT 屏"
        val t = topResizable(d.id)
            ?: return false to "✗ TNT 屏上没有可自由缩放的窗口（全屏窗口 resize 会静默失败）"
        return applyGeo(def.tntName, t, Geo(0, 0, d.width, d.height))
    }

    /**
     * 改几何 + **回读验证**（沿用 [ShellGateway.resizeTask] 的验下游纪律）。
     *
     * ★★★ **不要做任何几何补偿。** 坚果实测（2026-09-12）：
     * ```
     * 请求 am task resize 14 100 100 800 800  →  实得 mBounds=Rect(100, 100 - 800, 800)
     * ```
     * **请求 == 结果，1:1 零缩放。**
     *
     * ⚠️ 小米端 MIUI 自由窗口有「内容 = 容器 × 0.7」那层缩放，所以那边要请求 `target / 0.7`。
     *    **坚果没有这一层。** 若把那个补偿带过来 ⇒ 窗口会大 1/0.7 ≈ **43%**，
     *    而且 `bounds` 回读**自洽**（回读的也是容器）⇒ **静默错误，最难发现**。
     *    依据：计划书 Q §2.1 方言对照表第 8 条。
     */
    private fun applyGeo(name: String, t: TaskInfo, target: Geo): Pair<Boolean, String> {
        val ok = shell.resizeTask(t.taskId, target).getOrDefault(false)
        return if (ok) {
            true to "✓ $name → ${t.component}  ${target.oneLine()}"
        } else {
            false to "✗ $name 未生效（期望 ${target.oneLine()}，实得 ${currentBounds(t.taskId)}，taskId=${t.taskId}）"
        }
    }

    /**
     * 「桌面」。TNT 的 `Win+D` 是回到桌面 —— 在桌面壳（任务 A1）还不存在的现在，
     * 等价物就是**让TNT 屏回 HOME**。
     *
     * ⚠️ 这里用 `input` 是**对的**：`input` 走 InputManager 注入，
     * 与我们的 `getevent` **读**是两条独立的路 ——
     * 读键**不能**用它（任务 M §4.6③ 已证），但**执行动作**正是它该干的。
     */
    private fun home(def: Shortcuts.Def): Pair<Boolean, String> {
        // 仍先确认TNT 屏在（`--ext-display` 需要 TNT 屏存在）
        target() ?: return false to "✗ 没有TNT 屏"
        // ⚠️ 坚果的 `input` **不认 `-d`**，发往 TNT 屏要用 `--ext-display`（2026-09-12 实测）
        val out = shell.exec("input --ext-display keyevent 3").getOrElse {
            return false to "✗ ${def.tntName}: ${it.message}"
        }
        return if (looksLikeError(out)) {
            false to "✗ ${def.tntName}: ${out.trim().take(120)}"
        } else {
            true to "✓ ${def.tntName}（TNT 屏 HOME）"
        }
    }

    private fun fileManager(def: Shortcuts.Def): Pair<Boolean, String> {
        val d = target() ?: return false to "✗ 没有TNT 屏"
        val comp = FILE_MANAGERS.firstOrNull { installed(it) }
            ?: return false to "✗ 文件管理器一个都没装（试过 ${FILE_MANAGERS.size} 个候选）"
        val tid = shell.launchToDisplay(comp, d.id).getOrElse { return false to "✗ ${it.message}" }
        return true to "✓ ${def.tntName} → TNT 屏 taskId=$tid  ($comp)"
    }

    private fun simple(def: Shortcuts.Def, cmd: String): Pair<Boolean, String> {
        val out = shell.exec(cmd).getOrElse { return false to "✗ ${def.tntName}: ${it.message}" }
        return if (looksLikeError(out)) {
            false to "✗ ${def.tntName}: ${out.trim().take(120)}"
        } else {
            true to "✓ ${def.tntName}"
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun target(): DisplayInfo? = watcher.defaultTarget()

    /**
     * TNT 屏上**最顶的、可自由缩放**的任务。
     *
     * **为什么 `firstOrNull` 就是最顶**：`ShellGateway.listTasks()` 保序 ——
     * 同一 display 段内是「顶→底」，且按 taskId 去重时**只认第一次出现**
     * （那一次才落在它自己的 display 段里；后面的重复段不可信）。
     * 依据：计划书 N §2.2。
     */
    private fun topResizable(displayId: Int): TaskInfo? =
        shell.listTasks().getOrNull()?.firstOrNull {
            it.displayId == displayId &&
                // ⚠️ 排除宿主自己 —— 它也在 TNT 屏上，不排除会把自己的窗口改掉
                //    （2026-09-12 真机实测踩到，「改几何」改到了宿主自己身上）
                !it.component.startsWith(selfPackage) &&
                (it.isFreeform || it.mode.contains("multi", ignoreCase = true))
        }

    private fun currentBounds(taskId: Int): String =
        shell.taskState(taskId).getOrNull()?.bounds?.oneLine() ?: "任务已不在任务表里"

    private fun installed(component: String): Boolean {
        val pkg = component.substringBefore('/')
        return shell.exec("pm path $pkg").getOrDefault("").contains("package:")
    }

    private fun looksLikeError(out: String): Boolean =
        out.contains("Error", ignoreCase = true) || out.contains("Exception")

    companion object {
        private const val TAG = "Mode/Shortcut"
        private const val UNIMPL_REASON = "见计划书 Q §4（MODE 启动器的下一步）"

        // ★★ 已删除 `FREEFORM_CONTENT_SCALE = 0.7` 及其 `toContainer()` 补偿 ——
        //    那是 **MIUI 自由窗口专有**的内容缩放，**坚果没有这一层**。
        //    坚果实测：请求 `100 100 800 800` → 实得 `Rect(100,100 - 800,800)`（1:1）。
        //    保留它会让所有窗口大 43%，且 bounds 回读**自洽** ⇒ 静默错误。
        //    依据：计划书 Q §2.1 方言对照表第 8 条。

        /** 文件管理器候选 —— 按「坚果 → AOSP → 厂商私有」排，取第一个装了的 */
        private val FILE_MANAGERS = listOf(
            "com.smartisanos.filemanager/.tablet.TabletActivity",
            "com.smartisanos.filemanager/.activity.FileManagerActivity",
            "com.android.documentsui/.files.FilesActivity",
            "com.android.documentsui/.DocumentsActivity",
            "com.android.fileexplorer/.activity.MainActivity",
            "com.miui.filemanager/.ui.FileManagerActivity",
        )
    }
}
