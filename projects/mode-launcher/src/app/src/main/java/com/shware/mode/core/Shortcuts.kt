package com.shware.mode.core

/**
 * ★ TNT 快捷键表 —— 取 `.paper/03` §2.2 的【**win 列**】。
 *
 * 为什么是 win 列：真机按键实测 `flags = 8` ⇒ `isMacMode = false` ⇒ 走 win 列
 * （三重印证见 `.paper/03` §2.2）。**mac 列在本机不生效，不实现。**
 *
 * ⛔ **刻意不实现** `Win+M` / `Win+Q` / `Win+H` ——
 * 它们在 win 列绑的是 `NONE`，本机按了没反应。实现了反而是"发明 TNT 没有的行为"。
 *
 * 这一层是**纯 Kotlin**（无 Android、无 Shizuku），所以可以用
 * `tools/verify_shortcuts.py` 之类的脚本离线回归 —— 与 `KeyCodec` 同一套纪律。
 */
object Shortcuts {

    enum class Action {
        SNAP_LEFT, SNAP_RIGHT, FULLSCREEN, DESKTOP, NOTIFICATION,
        FILE_MANAGER, LOCK, CLOSE_WINDOW, SCREENSHOT, CLIPBOARD,
    }

    /**
     * @param combo     **规范序**（见 [canonical]）—— 表里必须这么写
     * @param action    动作
     * @param tntName   TNT 原版叫什么（用于 UI 回显，与 `.paper/03` §2.2 对齐）
     */
    data class Def(val combo: String, val action: Action, val tntName: String)

    val TABLE: List<Def> = listOf(
        Def("Ctrl+Win+←", Action.SNAP_LEFT, "全屏移到左"),
        Def("Ctrl+Win+→", Action.SNAP_RIGHT, "全屏移到右"),
        Def("F11", Action.FULLSCREEN, "全屏"),
        Def("Win+D", Action.DESKTOP, "桌面"),
        Def("Win+A", Action.NOTIFICATION, "通知中心"),
        Def("Win+E", Action.FILE_MANAGER, "新建文件管理器窗口"),
        Def("Win+L", Action.LOCK, "锁屏"),
        Def("Alt+F4", Action.CLOSE_WINDOW, "关闭窗口"),
        Def("Shift+Win+S", Action.SCREENSHOT, "截图"),
        Def("PrintScreen", Action.SCREENSHOT, "全屏截图"),
        Def("Win+V", Action.CLIPBOARD, "速览 / 剪贴板"),
    )

    /** 修饰键的规范顺序 —— 表里的组合必须按这个序写 */
    private val MOD_ORDER = listOf("Ctrl", "Alt", "Shift", "Win")

    /**
     * ★ 把「按下顺序」的组合键转成「规范序」——**查表的前提**。
     *
     * 为什么必须转：[KeyCodec.Decoder] **刻意保留按下顺序**
     * （先按 Ctrl 得 `Ctrl+Win+←`，先按 Win 得 `Win+Ctrl+←`）——
     * 那对**显示**是对的（有诊断价值），但**查表不能依赖顺序**：
     * 同一个快捷键，用户先按哪个修饰键是随机的。
     *
     * 规则：最后一段是主键，其余是修饰键；修饰键按 [MOD_ORDER] 排。
     * 认不出的修饰键（理论上不该有）原序附在已知修饰键之后，不丢信息。
     */
    fun canonical(combo: String): String {
        val parts = combo.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return combo.trim()

        val key = parts.last()
        val mods = parts.dropLast(1)
        val known = MOD_ORDER.filter { o -> mods.any { it.equals(o, ignoreCase = true) } }
        val unknown = mods.filter { m -> MOD_ORDER.none { it.equals(m, ignoreCase = true) } }
        return (known + unknown + key).joinToString("+")
    }

    /**
     * 查表。传入的 `combo` **可以是任意按下顺序**（内部会先规范化）。
     * @return null = 这个组合键不在 TNT 表里，不该被我们接管
     */
    fun lookup(combo: String): Def? {
        val c = canonical(combo)
        return TABLE.firstOrNull { it.combo == c }
    }
}
