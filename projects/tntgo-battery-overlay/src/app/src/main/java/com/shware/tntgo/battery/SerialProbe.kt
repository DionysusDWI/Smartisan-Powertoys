package com.shware.tntgo.battery

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File

/**
 * TNT GO **CDC-ACM 串口 AT 探测**（路线 C 第二轮）。
 *
 * ## 背景
 * 第一轮已经证实：
 * - CDC-ACM 串口**能打开**（`usb-serial-for-android` 的 `force` claim 奏效）
 * - 发 `at+adb\r\n` 有响应：`ADB ENABLE` + `OK`
 * - 但**没有** `+BATCG=` —— 说明 `at+adb` 只是开 ADB 模式，不是电量查询
 *
 * ## 本轮做什么
 * 1. **被动监听** N 秒（社区工具可能是「on-device 主动周期推送 `+BATCG`」）
 * 2. 逐条发**候选电量命令**，每条都原样记录响应
 * 3. 把全过程 raw dump 落盘，供离线分析
 *
 * ## 安全
 * 只发 AT 文本命令，**不改设备状态**（`at+adb` 除外，它会开 ADB 模式 —— 无害）。
 */
class SerialProbe(private val context: Context) {

    companion object {
        const val VID = 0x31CE
        const val PID = 0x5101

        private const val TAG = "TNTGO_SERIAL"

        /** 被动监听时长（ms）。 */
        private const val PASSIVE_MS = 12_000L

        /** 每条命令发出后的读取窗口（ms）。 */
        private const val CMD_READ_MS = 2_500L

        /**
         * 候选命令（按 OP 需求书 `20260911_OP需求_探针APK_v2.md` §2 分组）。
         *
         * ## ⚠️★★ 2026-09-11 实机事故后收紧
         * 首版把 `at+help` 里的命令**盲发**了一批，其中 `AT+DPDIRECT` / `AT+DPSCALER` /
         * `AT+HDMISCALER` **返回裸 `OK` ⇒ 是「设置型」而非查询型**，
         * 发完后 **DP 链路被重置 → Type-C 重协商 → USB 数据角色掉线**，
         * TNT GO 从 `UsbManager` 消失，**只能物理重插恢复**。
         *
         * ⇒ **批量表只保留「返回数据」的只读查询**；任何返回裸 `OK` 的一律剔除。
         *   剩余命令仍逐个经 [SerialGuard] 过滤。
         */
        val CANDIDATES = listOf(
            // ---- 链路自检（已知有响应） ----
            "at+adb",
            // ---- 电量（任务 G 已验证） ----
            "at+batcg", "at+bat", "at+batt",
            // ---- ★ P0 电池细节（TI BQ 系列；实测均返回数据） ----
            "at+bq25890",          // 无响应
            "at+bq25970",          // 寄存器 dump ★
            "at+temp",             // 温度 ★
            "at+uvlo",             // 欠压阈值 ★
            "at+bq", "at+bq?",     // 三颗 IC 型号 ★★
            // ---- ★ P1 键盘盖 / 霍尔 ----
            "at+getkbfold",        // 返回 OK（无数据，但无害）
            "at+getkbconnect",     // 返回 OK
            "at+getkbpen",         // 无响应
            "at+hall",             // ★ 霍尔三路状态
            "at+kbsyskey",         // 无响应
            // ---- P2 身份 / 状态（均返回数据） ----
            "at+sn", "at+sid",
            "at+status", "at+info",
            "at+getdevicestatus", "at+getdevicedescriptor", "at+getreportdescriptor",
            "at+getfwinfo", "at+sysmsg",
            // ---- P2 屏 / 背光（查询型） ----
            "at+mode", "at+light", "at+screen", "at+bkl",
            "at+tpgetver", "at+tps",
            // ---- 兜底 ----
            "at+ver", "at+help"
            // ⛔ 已剔除（设置型 / 会扰动链路，见类注释）：
            //   at+typecusb  at+typecpin  at+usbstate  at+dpdirect  at+dpscaler
            //   at+dpdebug   at+hdmiscaler  at+at+batcg?  at+at+bat?
        )
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VID && it.productId == PID
        }

    fun hasPermission(): Boolean =
        findDevice()?.let { usbManager.hasPermission(it) } ?: false

    fun reportFile(): File {
        val dir = context.getExternalFilesDir("probe") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "tntgo_serial_probe.txt")
    }

    /**
     * 执行串口探测。**阻塞，必须在子线程调用。**
     *
     * 总耗时 ≈ 开端口 + PASSIVE_MS + 命令数 × CMD_READ_MS。
     * 全程持 [SerialGuard.lock]，与悬浮服务的轮询互斥（期间轮询会失败几次，属预期）。
     */
    fun execute(onProgress: ((String) -> Unit)? = null): String? =
        synchronized(SerialGuard.lock) { executeLocked(onProgress) }

    private fun executeLocked(onProgress: ((String) -> Unit)? = null): String? {
        val device = findDevice() ?: return null
        if (!usbManager.hasPermission(device)) return null

        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            Log.w(TAG, "无 CDC-ACM 驱动匹配")
            return null
        }
        val connection = usbManager.openDevice(device) ?: return null
        val port = driver.ports.firstOrNull() ?: run {
            connection.close(); return null
        }

        val out = StringBuilder()

        try {
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            out.append("# TNT GO 串口 AT 探测（路线 C 第二轮）\n")
            out.append("# 设备: ${device.deviceName}\n")
            out.append("# 端口数=${driver.ports.size}  波特率=115200\n\n")

            // 清空残留
            drain(port)
            out.append("（已清空接收缓冲）\n\n")

            // ---- 阶段 1：被动监听 ----
            onProgress?.invoke("被动监听 ${PASSIVE_MS / 1000}s…")
            out.append("## 阶段 1 · 被动监听 ${PASSIVE_MS / 1000}s（不发任何命令）\n")
            val passive = readFor(port, PASSIVE_MS) { chunk ->
                onProgress?.invoke("被动: ${chunk.take(40)}")
            }
            out.append(if (passive.isBlank()) "(设备未主动推送任何数据)\n" else escape(passive))
            out.append("\n")

            // ---- 阶段 2：逐条命令 ----
            out.append("\n## 阶段 2 · 候选命令逐条测试\n")
            for ((i, cmd) in CANDIDATES.withIndex()) {
                onProgress?.invoke("[${i + 1}/${CANDIDATES.size}] $cmd")
                drain(port)
                val payload = "$cmd\r\n".toByteArray(Charsets.US_ASCII)
                port.write(payload, 1000)
                val resp = readFor(port, CMD_READ_MS, null)
                out.append(">>> $cmd\n")
                out.append(if (resp.isBlank()) "    (无响应)\n" else escape(resp).prependIndent("    "))
                out.append("\n")
                Log.i(TAG, "cmd=$cmd resp=${resp.take(80).replace("\n", "\\n")}")
            }

            out.append("\n## 已写入\n").append(reportFile().absolutePath).append("\n")
        } catch (e: Exception) {
            out.append("\n!! 异常: ${e.javaClass.simpleName}: ${e.message}\n")
            Log.w(TAG, "探测异常", e)
        } finally {
            runCatching { port.close() }
            runCatching { connection.close() }
        }

        runCatching { reportFile().writeText(out.toString()) }
            .onFailure { Log.w(TAG, "写文件失败: ${it.message}") }
        return out.toString()
    }

    /** 读干缓冲，返回读到的字节数。 */
    private fun drain(port: UsbSerialPort): Int {
        val buf = ByteArray(512)
        var total = 0
        while (true) {
            val n = try { port.read(buf, 100) } catch (e: Exception) { break }
            if (n <= 0) break
            total += n
        }
        return total
    }

    /** 在 [ms] 毫秒内持续读取，拼成字符串。 */
    private fun readFor(
        port: UsbSerialPort,
        ms: Long,
        onChunk: ((String) -> Unit)?
    ): String {
        val sb = StringBuilder()
        val buf = ByteArray(512)
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            val n = try {
                port.read(buf, 200)
            } catch (e: Exception) {
                break
            }
            if (n > 0) {
                val s = String(buf, 0, n, Charsets.US_ASCII)
                sb.append(s)
                onChunk?.invoke(s)
            }
        }
        return sb.toString()
    }

    /** 把不可见字符转义，便于写进文本文件。 */
    private fun escape(s: String): String = buildString {
        for (c in s) {
            when {
                c == '\r' -> append("\\r\n")
                c == '\n' -> append("\\n\n")
                c.code < 0x20 || c.code > 0x7E -> append("\\x%02X".format(c.code))
                else -> append(c)
            }
        }
    }
}
