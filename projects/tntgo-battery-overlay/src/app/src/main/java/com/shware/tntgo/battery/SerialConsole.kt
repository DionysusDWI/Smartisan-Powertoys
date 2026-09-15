package com.shware.tntgo.battery

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File

/**
 * TNT GO **串口自由输入台**（OP 需求书 §3 的「强烈建议」）。
 *
 * 候选表硬编码在 APK 里 ⇒ 每加一条命令都要重打包。
 * 本类提供「手输任意 AT 命令 → 立即看响应」，**一次到位**。
 *
 * ## 安全
 * 发送前一律经 [SerialGuard.blockedReason] 过滤 ——
 * **禁区命令即使手输也直接拒绝，不打开串口。**
 *
 * ## 并发
 * 全程持 [SerialGuard.lock]，与 `TntgoSerial`（悬浮轮询）串行化。
 * 单次命令「开端口 → 写 → 读 → 关端口」，**不长期占用**。
 */
object SerialConsole {

    private const val TAG = "TNTGO_CONSOLE"
    private const val VID = 0x31CE
    private const val PID = 0x5101
    private const val READ_MS = 2_500L

    /** 一次发送的结果。 */
    data class Result(
        /** 是否真的发出去了（false = 被禁区拦截 / 无设备 / 无权限 / 端口打不开） */
        val accepted: Boolean,
        /** 被拦截时命中的禁区标识 */
        val blockedBy: String? = null,
        /** 响应原文（`\r\n` 已转义为可见形式） */
        val response: String = "",
        /** 出错说明 */
        val error: String? = null
    )

    fun findDevice(context: Context): UsbDevice? {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.deviceList.values.firstOrNull { it.vendorId == VID && it.productId == PID }
    }

    fun hasPermission(context: Context): Boolean {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = findDevice(context) ?: return false
        return mgr.hasPermission(dev)
    }

    fun reportFile(context: Context): File {
        val dir = context.getExternalFilesDir("probe") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "tntgo_console.txt")
    }

    /**
     * 发送一条 AT 命令并读回响应。**阻塞，请在子线程调用。**
     *
     * @param raw 用户输入（可写 `AT+XXX` / `at+xxx` / `XXX`）
     */
    fun send(context: Context, raw: String): Result {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return Result(accepted = false, error = "命令为空")

        // ★★ 禁区过滤 —— 在打开串口之前
        SerialGuard.blockedReason(cmd)?.let { hit ->
            Log.w(TAG, "⛔ 拒绝发送禁区命令: $cmd (命中 $hit)")
            return Result(accepted = false, blockedBy = hit)
        }

        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findDevice(context)
            ?: return Result(accepted = false, error = "TNT GO 未连接")
        if (!mgr.hasPermission(device)) {
            return Result(accepted = false, error = "未授权 USB 权限")
        }

        synchronized(SerialGuard.lock) {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: return Result(accepted = false, error = "无 CDC-ACM 驱动匹配")
            val connection = mgr.openDevice(device)
                ?: return Result(accepted = false, error = "openDevice 失败（可能被占用）")
            val port = driver.ports.firstOrNull()
                ?: run { connection.close(); return Result(accepted = false, error = "无串口端口") }

            return try {
                port.open(connection)
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                drain(port)                                    // 丢掉设备主动推送的旧行
                port.write("$cmd\r\n".toByteArray(Charsets.US_ASCII), 1000)
                val resp = readFor(port, READ_MS)

                val escaped = escape(resp)
                Log.i(TAG, ">>> $cmd\n$escaped")
                appendLog(context, cmd, escaped)

                Result(accepted = true, response = escaped)
            } catch (e: Exception) {
                Log.w(TAG, "发送失败: ${e.message}")
                Result(accepted = false, error = "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { port.close() }
                runCatching { connection.close() }
            }
        }
    }

    private fun drain(port: UsbSerialPort) {
        val buf = ByteArray(512)
        while (true) {
            val n = try { port.read(buf, 100) } catch (e: Exception) { break }
            if (n <= 0) break
        }
    }

    private fun readFor(port: UsbSerialPort, ms: Long): String {
        val sb = StringBuilder()
        val buf = ByteArray(512)
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            val n = try { port.read(buf, 200) } catch (e: Exception) { break }
            if (n > 0) sb.append(String(buf, 0, n, Charsets.US_ASCII))
        }
        return sb.toString()
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when {
                c == '\r' -> append("\\r\n")
                c == '\n' -> append("\\n")
                c.code < 0x20 || c.code > 0x7E -> append("\\x%02X".format(c.code))
                else -> append(c)
            }
        }
    }

    private fun appendLog(context: Context, cmd: String, escaped: String) {
        runCatching {
            reportFile(context).appendText(">>> $cmd\n$escaped\n\n")
        }.onFailure { Log.w(TAG, "写日志失败: ${it.message}") }
    }
}
