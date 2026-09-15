package com.shware.tntgo.battery

/**
 * 串口安全护栏。
 *
 * ## 为什么需要
 * TNT GO 的 CDC-ACM 是一个**完整 AT 命令台**（`at+help` 列出 200+ 条）。
 * 其中一部分命令**会关机 / 重启 / 擦写固件 / 变砖设备**。
 * OP 的需求书（`20260911_OP需求_探针APK_v2.md` §4）明确要求
 * **在 UI 与代码里都做黑名单过滤**，**即使手输也拒绝发送**。
 *
 * ## 设计取舍
 * **硬拒绝，不提供 bypass。** 少一条探测命令的代价 ≪ 变砖一块 TNT GO。
 * 匹配规则：归一化（去 `AT+` 前缀、去空白、转大写）后做**前缀匹配**。
 */
object SerialGuard {

    /** ★ 绝对禁区。归一化后的命令若**以其中任一项开头**，一律拒绝。 */
    private val DANGEROUS_TOKENS = listOf(
        // 电源 / 复位
        "SHUTDOWN", "PWROFF", "POWEROFF", "REBOOT", "RESET", "RECOVERY", "STARTUP", "SLEEP",
        // 固件
        "UPGRADE", "FLASHWRITE", "OTPWRITE", "SCALERUPDATE", "SETFW", "SETFMMODE",
        "SCALEUPDATE", "SCALEFLASHWRITE", "SCALEFLASHID", "SCALEFLASHEB",
        "FLASHEC", "FLASHEB", "FLASHES", "FLASHTEST",
        // 擦除 / 清空
        "ERASE", "DATACLR", "BKPCLR", "I2CERRORCLEAR",
        // 其它危险写操作
        "SETGPIO", "WDGTEST", "LCDBLINK", "SETDEVICERESET",

        // ★★★ 显示 / USB 链路「设置型」命令 —— 2026-09-11 实机教训
        // 这些命令**返回裸 OK**（不是查询），会**重置 DP 链路并触发 Type-C 重协商**，
        // 后果：**USB 数据角色掉线，TNT GO 从 `UsbManager` 消失，只能物理重插恢复**。
        // 事故记录见 `.paper/plans/H-探针APK-v2.md`。
        "DPDIRECT", "DPSCALER", "DPDEBUG", "HDMISCALER", "DPTRAIN", "DPTEST",
        "TYPECUSB", "TYPEUSB", "USBSTATE", "SETHIDSLEEP", "SETHIDWAKEUP",
        "LCDON", "LCDOFF", "DSPON", "DSPOFF", "CAMON", "CAMOFF",
        "WIFION", "WIFIOFF", "WIFIRESET", "WIFISLEEP", "WIFIWAKEUP", "WIFICON",
        "TPSLEEP", "TYPEPIN", "TYPECPIN"
    )

    /**
     * 检查命令是否安全。
     *
     * @return `null` = 安全可以发送；否则返回**命中的禁区标识**（供 UI 展示原因）
     */
    fun blockedReason(raw: String): String? {
        val norm = normalize(raw) ?: return null
        return DANGEROUS_TOKENS.firstOrNull { norm.startsWith(it) }
    }

    /** 归一化：去掉可能的 `AT+` / `AT` 前缀与空白，转大写。 */
    private fun normalize(raw: String): String? {
        var s = raw.trim().uppercase()
        if (s.isEmpty()) return null
        // 允许用户写 "AT+XXX" / "AT XXX" / "at+xxx" / 纯 "XXX"
        s = s.removePrefix("AT").removePrefix("+").removePrefix(" ").trim()
        return s.ifEmpty { null }
    }

    /**
     * ★★ **全局串口互斥锁**。
     *
     * CDC-ACM 同一时刻只能被一个 `UsbDeviceConnection` claim。
     * `TntgoSerial`（悬浮服务的轮询）、`SerialProbe`（批量探测）、
     * `SerialConsole`（自由输入）三方必须串行化，否则后到者会 claim 失败。
     *
     * 用法：`synchronized(SerialGuard.lock) { ...开端口 / 读 / 关端口... }`
     */
    val lock = Any()
}
