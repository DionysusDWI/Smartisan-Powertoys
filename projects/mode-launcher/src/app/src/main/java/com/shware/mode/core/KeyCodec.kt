package com.shware.mode.core

/**
 * ★ `getevent` 一行的解码器。
 *
 * 为什么它不是「把十六进制翻译成名字」那么简单：
 *
 * TNT GO 键盘上那 **7 个专用键**在 Linux 层**全是同一个键码** `KEY_UNKNOWN(240)`
 * —— 因为 `Generic.kl` 没定义它们的 HID usage
 * （依据 `.paper/03` §2.3④，原始日志 `refs/raw_keylog.txt`）：
 *
 * ```
 * 0004 0004 000700a5    ← MSC_SCAN：HID usage（真身份，7 个键各不相同）
 * 0001 00f0 00000001    ← EV_KEY：Linux 键码 240 = KEY_UNKNOWN（7 个键全一样）
 * ```
 *
 * **⇒ 唯一能区分它们的只有 `MSC_SCAN` 行里的 HID usage。**
 *
 * ## 由此定下的两条纪律
 *
 * 1. **以 HID usage 为主键，Linux 键码只作兜底** —— 本文件的 [usageLabel] 是主表。
 * 2. **抓包时不能只过滤 `EV_KEY`** —— 必须把 `EV_MSC / MSC_SCAN` 一起留下，
 *    否则那 7 个键会退化成 7 个无法区分的 `KEY_UNKNOWN`。
 *
 * ## 支持的两种行格式
 *
 * ```
 * [  118351.218709] EV_MSC       MSC_SCAN             000700e7     ← getevent -lt（带标签）
 * [  118351.218709] 0004 0004 000700e7                           ← getevent -t（裸十六进制）
 * ```
 */
object KeyCodec {

    const val PAGE_KEYBOARD = 0x07
    const val PAGE_CONSUMER = 0x0C

    /** 一次已解码的 `EV_KEY` 事件。 */
    data class KeyStroke(
        /** true = 按下，false = 抬起 */
        val pressed: Boolean,
        /** 长按自动重复（value=2） */
        val repeat: Boolean,
        /** 完整 HID usage，如 `0x000700e7`；设备不发 MSC_SCAN 时为 null */
        val usage: Int?,
        /** 人类可读名：`锤子键(R)` / `A` / `KEY_UNKNOWN` */
        val label: String,
        /** Linux 键码，如 126；裸格式下可解析，解析不到为 null */
        val evdevCode: Int?,
        /** `KEY_RIGHTMETA`，或裸格式下的 `0x007e` */
        val evdevName: String,
        val isModifier: Boolean,
        /** 按下【非修饰键】时：当前按住的修饰键（按按下顺序）+ 本键；否则 null */
        val combo: String?,
        val ts: String,
    ) {
        fun oneLine(): String {
            val act = when {
                repeat -> "REPEAT"
                pressed -> "DOWN"
                else -> "UP"
            }
            val u = usage?.let { "usage=0x%08x".format(it) } ?: "usage=?"
            val e = evdevCode?.let { "evdev=$it($evdevName)" } ?: "evdev=$evdevName"
            return "$act  $label  |  $u  $e"
        }
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 带标签：`[ts] EV_MSC MSC_SCAN 000700e7`
     *
     * ⚠️ 时间戳是**可选**的 —— 归档日志 `refs/raw_keylog.txt` 就没带（当年没加 `-t`）。
     * 少写这个 `?` 会让那份证据完全解析不了。
     */
    private val RX_LABELED = Regex("""^(?:\[\s*([\d.]+)]\s+)?(\S+)\s+(\S+)\s+(\S+)\s*$""")

    /** 裸十六进制：`[ts] 0004 0004 000700e7`（同样允许无时间戳） */
    private val RX_RAW = Regex("""^(?:\[\s*([\d.]+)]\s+)?([0-9a-fA-F]{1,4})\s+([0-9a-fA-F]{1,4})\s+([0-9a-fA-F]{1,8})\s*$""")

    private data class RawLine(
        val ts: String,
        /** 归一化后的 `EV_*` 短名 */
        val kind: String,
        /** 归一化后的 `MSC_SCAN` / `KEY_*` 名，裸格式下是 `0xXXXX` */
        val codeName: String,
        val codeNum: Int?,
        val valueName: String,
        val valueNum: Int?,
    )

    private fun parse(line: String): RawLine? {
        RX_RAW.find(line)?.let { m ->
            val type = m.groupValues[2].toInt(16)
            val code = m.groupValues[3].toInt(16)
            val value = m.groupValues[4].toInt(16)
            return RawLine(
                ts = m.groupValues[1],
                kind = if (type == 0x04) "EV_MSC" else if (type == 0x01) "EV_KEY" else "EV_$type",
                codeName = if (type == 0x04 && code == 0x04) "MSC_SCAN" else "0x%04x".format(code),
                codeNum = code,
                valueName = "%08x".format(value),
                valueNum = value,
            )
        }
        RX_LABELED.find(line)?.let { m ->
            val v = m.groupValues[4]
            return RawLine(
                ts = m.groupValues[1],
                kind = m.groupValues[2],
                codeName = m.groupValues[3],
                // 标签格式给的是**名字**（KEY_RIGHTMETA），编号后面由 evdevCode 查表兜底
                codeNum = null,
                valueName = v,
                // ★★ `MSC_SCAN` 的值**就是 HID usage**，是个十六进制串 —— 必须解出来。
                //    这里原本写的是 `null`，后果是 usage 永远为 null ⇒ 所有键退化成
                //    KEY_* 名字 ⇒ TNT GO 那 7 个死键**又变回无法区分**（240/240/240…）。
                //    这正是本模块最该防的失效模式，由 tools/verify_keycodec.py 抓到。
                //    非十六进制的值（DOWN / UP / REPEAT）自然得到 null，无需特判。
                valueNum = v.toIntOrNull(16),
            )
        }
        return null
    }

    // ------------------------------------------------------------------ 解码器

    /**
     * 有状态的解码器 —— 需要状态是因为：
     * - `MSC_SCAN` 与它对应的 `EV_KEY` 是**两行**，必须配对
     * - 组合键要**记住当前按住了哪些修饰键**
     *
     * ⚠️ 非线程安全。每次抓包起一个，`reset()` 清状态。
     */
    class Decoder {
        /** 按**按下顺序**存 —— 这样 `Ctrl+Win+←` 会还原成 `Ctrl+Win+←` 而不是被排序 */
        private val held = LinkedHashSet<Int>()
        private var pendingUsage: Int? = null

        fun reset() {
            held.clear()
            pendingUsage = null
        }

        /** 当前按住的修饰键（按按下顺序） */
        fun heldModifiers(): List<String> = held.mapNotNull { modifierName(it) }

        /**
         * 吃一行。
         * @return 非 null 表示这一行是一次完整的 `EV_KEY`；其余（`MSC_SCAN` / 无关行）返回 null
         */
        fun feed(line: String): KeyStroke? {
            val r = parse(line) ?: return null

            // ① MSC_SCAN —— 记下 HID usage，等它的 EV_KEY 来配对
            if (r.kind == "EV_MSC" && r.codeName == "MSC_SCAN") {
                pendingUsage = r.valueNum
                return null
            }

            // ② 只认 EV_KEY
            if (r.kind != "EV_KEY") return null

            val pressed = when (r.valueName.uppercase()) {
                "DOWN", "1", "00000001" -> true
                else -> false
            }
            val repeat = r.valueName.uppercase() in setOf("REPEAT", "2", "00000002")

            val usage = pendingUsage
            pendingUsage = null   // 配对即消费 —— MSC_SCAN 总在 EV_KEY 前一行，留着反而会串味
            val usageId = usage?.let { it and 0xFFFF }

            val isMod = usageId != null && modifierName(usageId) != null
            val label = usage?.let { usageLabel(it) } ?: r.codeName

            // 修饰键的按下/抬起 → 维护集合
            // （先判 usageId 非空让智能转换生效；`isMod` 本身已蕴含它非空）
            if (usageId != null && isMod) {
                if (pressed) held.add(usageId) else held.remove(usageId)
            }

            // 组合键：只在【按下非修饰键】时给出
            val combo = if (pressed && !isMod) {
                val mods = heldModifiers()
                if (mods.isEmpty()) label else (mods + label).joinToString("+")
            } else null

            val evdevCode = r.codeNum ?: evdevNameToCode(r.codeName)

            return KeyStroke(
                pressed = pressed,
                repeat = repeat,
                usage = usage,
                label = label,
                evdevCode = evdevCode,
                evdevName = r.codeName,
                isModifier = isMod,
                combo = combo,
                ts = r.ts,
            )
        }
    }

    // ------------------------------------------------------------------ 名字表

    private fun evdevNameToCode(name: String): Int? =
        if (name.startsWith("KEY_")) EVDEV[name] else null

    /**
     * ★ 组合键里的修饰键短名 —— 与 [usageLabel] **刻意分开**。
     *
     * 显示单个键时用 `Win(L)`（诊断价值：看得出按的是哪个物理键），
     * 但**组合里必须把左右合并**：Android 的 `META_META_ON` 只有**一位**，
     * 系统层面左 Win 和右 Win 是同一个修饰键。
     * 不合并的话会出现「按左 Win 得 `Win(L)+D`、按右 Win 得 `Win+D`」——
     * 同一个语义两串字，下游没法写匹配规则。
     * （`.paper/03` §2.2 的 TNT 快捷键表也正是这么写的：`Ctrl + Win + ←`。）
     */
    fun modifierName(usageId: Int): String? = when (usageId) {
        0xE0, 0xE4 -> "Ctrl"
        0xE1, 0xE5 -> "Shift"
        0xE2, 0xE6 -> "Alt"
        0xE3, 0xE7 -> "Win"
        else -> null
    }

    /** ★ 主表：HID usage → 人类可读名。Linux 键码只作兜底。 */
    fun usageLabel(usage: Int): String? {
        val page = (usage ushr 16) and 0xFFFF
        val id = usage and 0xFFFF
        return when (page) {
            PAGE_KEYBOARD -> KEYBOARD[id]
            PAGE_CONSUMER -> CONSUMER[id]
            else -> null
        }
    }

    private val KEYBOARD: Map<Int, String> = buildMap {
        // 字母 A–Z：usage 0x04–0x1D
        ('A'..'Z').forEachIndexed { i, c -> put(0x04 + i, c.toString()) }
        // 数字 1–9：0x1E–0x26；0：0x27
        (1..9).forEach { put(0x1D + it, it.toString()) }
        put(0x27, "0")
        // 符号
        put(0x2D, "-"); put(0x2E, "="); put(0x2F, "["); put(0x30, "]")
        put(0x31, "\\"); put(0x33, ";"); put(0x34, "'"); put(0x35, "`")
        put(0x36, ","); put(0x37, "."); put(0x38, "/")
        // 控制键
        put(0x28, "Enter"); put(0x29, "Esc"); put(0x2A, "Backspace")
        put(0x2B, "Tab"); put(0x2C, "Space"); put(0x39, "CapsLock")
        // 功能键 F1–F12：0x3A–0x45
        (1..12).forEach { put(0x39 + it, "F$it") }
        // 编辑 / 导航
        put(0x46, "PrintScreen"); put(0x48, "Pause")
        put(0x49, "Insert"); put(0x4A, "Home"); put(0x4B, "PageUp")
        put(0x4C, "Delete"); put(0x4D, "End"); put(0x4E, "PageDown")
        put(0x4F, "→"); put(0x50, "←"); put(0x51, "↓"); put(0x52, "↑")
        // ★ 修饰键
        // ⚠️ 这里**不写「锤子键」** —— 哪个物理键是锤子 logo 键尚未定论，见下一行注释
        put(0xE0, "Ctrl(L)"); put(0xE1, "Shift(L)"); put(0xE2, "Alt(L)"); put(0xE3, "Win(L)")
        put(0xE4, "Ctrl(R)"); put(0xE5, "Shift(R)"); put(0xE6, "Alt(R)"); put(0xE7, "Win(R)")
        // ★★ TNT GO 的 7 个专用键 —— Linux 层全是 KEY_UNKNOWN(240)，只有 usage 能区分
        //    名字取自 `.paper/03` §2.3④ 的 HID 标准定义
        put(0xA5, "专用键·App菜单"); put(0xA6, "专用键·菜单帮助"); put(0xA7, "专用键·菜单退出")
        put(0xA8, "专用键·菜单选择"); put(0xA9, "专用键·菜单右"); put(0xAA, "专用键·菜单左")
        put(0xAB, "专用键·菜单上")
    }

    /** HID Consumer 页 —— TNT GO 的「带图标的键」走这里（依据 `refs/keylog_cc.txt`）。 */
    private val CONSUMER: Map<Int, String> = mapOf(
        0x6F to "亮度+", 0x70 to "亮度−",
        0xE2 to "静音", 0xE9 to "音量+", 0xEA to "音量−",
        0xB5 to "下一曲", 0xB6 to "上一曲", 0xB3 to "播放/暂停",
        0xCD to "播放", 0xB7 to "停止",
        0xE8 to "媒体", 0x18A to "邮件", 0x192 to "计算器",
        0x194 to "浏览器主页", 0x221 to "搜索", 0x223 to "主页",
        0x226 to "返回", 0x227 to "前进",
    )

    /** Linux 键码表 —— **只用于兜底**（设备不发 MSC_SCAN 时） */
    private val EVDEV: Map<String, Int> = mapOf(
        "KEY_ESC" to 1, "KEY_1" to 2, "KEY_2" to 3, "KEY_3" to 4, "KEY_4" to 5,
        "KEY_5" to 6, "KEY_6" to 7, "KEY_7" to 8, "KEY_8" to 9, "KEY_9" to 10,
        "KEY_0" to 11, "KEY_MINUS" to 12, "KEY_EQUAL" to 13, "KEY_BACKSPACE" to 14,
        "KEY_TAB" to 15, "KEY_Q" to 16, "KEY_W" to 17, "KEY_E" to 18, "KEY_R" to 19,
        "KEY_T" to 20, "KEY_Y" to 21, "KEY_U" to 22, "KEY_I" to 23, "KEY_O" to 24,
        "KEY_P" to 25, "KEY_LEFTBRACE" to 26, "KEY_RIGHTBRACE" to 27, "KEY_ENTER" to 28,
        "KEY_LEFTCTRL" to 29, "KEY_A" to 30, "KEY_S" to 31, "KEY_D" to 32, "KEY_F" to 33,
        "KEY_G" to 34, "KEY_H" to 35, "KEY_J" to 36, "KEY_K" to 37, "KEY_L" to 38,
        "KEY_SEMICOLON" to 39, "KEY_APOSTROPHE" to 40, "KEY_GRAVE" to 41,
        "KEY_LEFTSHIFT" to 42, "KEY_BACKSLASH" to 43, "KEY_Z" to 44, "KEY_X" to 45,
        "KEY_C" to 46, "KEY_V" to 47, "KEY_B" to 48, "KEY_N" to 49, "KEY_M" to 50,
        "KEY_COMMA" to 51, "KEY_DOT" to 52, "KEY_SLASH" to 53, "KEY_RIGHTSHIFT" to 54,
        "KEY_KPASTERISK" to 55, "KEY_LEFTALT" to 56, "KEY_SPACE" to 57, "KEY_CAPSLOCK" to 58,
        "KEY_F1" to 59, "KEY_F2" to 60, "KEY_F3" to 61, "KEY_F4" to 62, "KEY_F5" to 63,
        "KEY_F6" to 64, "KEY_F7" to 65, "KEY_F8" to 66, "KEY_F9" to 67, "KEY_F10" to 68,
        "KEY_NUMLOCK" to 69, "KEY_SCROLLLOCK" to 70, "KEY_F11" to 87, "KEY_F12" to 88,
        "KEY_RIGHTCTRL" to 97, "KEY_RIGHTALT" to 100, "KEY_HOME" to 102,
        "KEY_UP" to 103, "KEY_PAGEUP" to 104, "KEY_LEFT" to 105, "KEY_RIGHT" to 106,
        "KEY_END" to 107, "KEY_DOWN" to 108, "KEY_PAGEDOWN" to 109,
        "KEY_INSERT" to 110, "KEY_DELETE" to 111, "KEY_MUTE" to 113,
        "KEY_VOLUMEDOWN" to 114, "KEY_VOLUMEUP" to 115, "KEY_POWER" to 116,
        "KEY_LEFTMETA" to 125, "KEY_RIGHTMETA" to 126, "KEY_COMPOSE" to 127,
        "KEY_SYSRQ" to 99, "KEY_PAUSE" to 119,
        "KEY_BRIGHTNESSDOWN" to 224, "KEY_BRIGHTNESSUP" to 225,
        "KEY_UNKNOWN" to 240,
    )

    /** 反向查：Linux 键码 → `KEY_*` 名（UI 里显示用） */
    fun evdevName(code: Int): String =
        EVDEV.entries.firstOrNull { it.value == code }?.key ?: "0x%04x".format(code)
}
