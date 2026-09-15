package com.shware.mode.core

/**
 * 一个输入设备（来自 `getevent -pl`）。
 *
 * ⚠️ **设备编号是【主机相关】的，绝不能写死。**
 * 实测同一台 TNT GO 键盘：
 *   - 坚果 Pro 3 上枚举为 `/dev/input/event8`
 *   - 小米 17 Pro Max 上枚举为 `/dev/input/event11`
 * ⇒ 一律**按名字发现**，不按编号。（依据：`.paper/03` §2.3 vs 本轮小米实测）
 */
data class InputDevice(val path: String, val name: String) {

    /** 像键盘的 */
    val looksKeyboard: Boolean get() = name.contains("Keyboard", ignoreCase = true)

    /** 是不是 TNT GO 那一套（USB 复合设备有 5~6 个接口，名字都带这个前缀） */
    val isTntGo: Boolean get() = name.contains("Smartisan TNT go", ignoreCase = true)

    fun oneLine(): String = "$path  $name"

    companion object {
        private val RX_ADD = Regex("""^add device \d+:\s+(/dev/input/\S+)""")
        private val RX_NAME = Regex("""^\s+name:\s+"(.*)"\s*$""")

        /** 解析 `getevent -pl` 的输出。 */
        fun parse(dump: String): List<InputDevice> {
            val out = mutableListOf<InputDevice>()
            var pending: String? = null
            for (line in dump.lineSequence()) {
                val a = RX_ADD.find(line)
                if (a != null) {
                    pending = a.groupValues[1]
                    continue
                }
                val n = RX_NAME.find(line)
                if (n != null && pending != null) {
                    out += InputDevice(pending, n.groupValues[1])
                    pending = null
                }
            }
            return out
        }

        /**
         * 挑主键盘：优先 TNT GO 的 Keyboard，退而求其次任意 Keyboard。
         *
         * 之所以要挑：TNT GO 在小米上是一整套 USB HID 复合设备，
         * 键盘 / 触摸板 / 鼠标 / UNKNOWN 会一起出现，不能"第一个就是"。
         */
        fun pickKeyboard(devices: List<InputDevice>): InputDevice? =
            devices.firstOrNull { it.isTntGo && it.looksKeyboard }
                ?: devices.firstOrNull { it.looksKeyboard }
    }
}
