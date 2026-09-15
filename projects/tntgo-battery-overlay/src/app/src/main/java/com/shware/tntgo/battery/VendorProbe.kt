package com.shware.tntgo.battery

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.File

/**
 * TNT GO **厂商私有接口**寄存器只读探针（路线 B）。
 *
 * ## 背景
 * TNT GO 有一个 **class 255 / subclass 255 / proto 0** 的厂商自定义接口。
 * 官方 `casthal` HAL 正是通过它读取 TNT GO 的电量（OP 反编译 `TntManagerService.smali` 实证）。
 * 已知的**唯一读取**是：
 * ```
 * IN (0xC1) request=0x51 value=0 index=0xA0 len=2  →  DP lane 数
 * ```
 * 电量「推断」在同一族里换 index（如 `0xA1`），但**从未验证过**。
 *
 * ## 工作原理
 * 全部走 **端点 0 的 controlTransfer**：
 * - **不需要 claim 任何接口** ⇒ **完全不干扰键盘 / 触控板 / CDC 串口**
 * - 只需要一次 `UsbManager.requestPermission()`
 *
 * ## ★ 安全红线
 * **只发 IN 请求（`requestType = 0xC1`），绝不发 OUT（0x41）** ——
 * 已知的 OUT 请求会改屏幕亮度 / 待机 / LED / 色温，误发会真的改变设备状态。
 *
 * ## 自检
 * 扫描前先打一发**已知可读**的 `0xC1/0x51/0/0xA0/2`：
 * - 返回 ≥ 0（通常 = 2）⇒ **厂商通道可用**，后续扫描结果可信
 * - 返回 < 0 ⇒ 通道不通，后面全是噪声
 */
class VendorProbe(private val context: Context) {

    companion object {
        const val VID = 0x31CE
        const val PID = 0x5101

        private const val TAG = "TNTGO_PROBE"

        /** IN | VENDOR | DEVICE —— 只读，绝不用 0x41(OUT)。 */
        private const val REQ_TYPE_IN = 0xC1

        private const val TIMEOUT_MS = 500

        /** 已知可读：读 DP lane 数（`TntManagerService.getDplaneNum()`）。 */
        const val SELF_CHECK_REQ = 0x51
        const val SELF_CHECK_VAL = 0x00
        const val SELF_CHECK_IDX = 0xA0
        const val SELF_CHECK_LEN = 2

        /** 扫描空间（全部只读）。 */
        val REQS = intArrayOf(0x50, 0x51, 0x52, 0x53)
        val IDXES = intArrayOf(0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xB0, 0xB1, 0xC0)
        val LENS = intArrayOf(2, 4, 8, 16)
    }

    /** 一次探测的结果。 */
    data class Hit(
        val request: Int,
        val index: Int,
        val length: Int,
        val ret: Int,
        val bytes: ByteArray
    ) {
        val ok: Boolean get() = ret >= 0

        val hex: String
            get() = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

        /** 小端 uint16。多数 USB 厂商协议用小端。 */
        val le16: Int?
            get() = if (bytes.size >= 2)
                (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            else null

        /** 大端 uint16。 */
        val be16: Int?
            get() = if (bytes.size >= 2)
                ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            else null

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VID && it.productId == PID
        }

    fun hasPermission(): Boolean =
        findDevice()?.let { usbManager.hasPermission(it) } ?: false

    /** 设备上的厂商接口（class 255）描述，供 UI 展示。 */
    fun vendorInterfaceInfo(): String {
        val dev = findDevice() ?: return "(未连接)"
        val ifaces = dev.interfaceCount
        val sb = StringBuilder()
        for (i in 0 until ifaces) {
            val itf = dev.getInterface(i)
            sb.append("  [$i] class=${itf.interfaceClass} sub=${itf.interfaceSubclass} proto=${itf.interfaceProtocol}\n")
        }
        return if (sb.isEmpty()) "(无接口?)" else sb.toString()
    }

    /**
     * 执行完整探测。
     *
     * **阻塞方法，必须在子线程调用。** 最长耗时 ≈ 扫描项数 × 超时。
     * 默认 `4 × 8 × 4 = 128` 项 × 500ms ≈ 最长 64 秒（实际成功的项会立即返回）。
     *
     * @param onProgress 进度回调（已扫描项数, 总项数, 当前命中）
     * @return 报告文本；失败返回 null（附错误说明）
     */
    fun execute(onProgress: ((Int, Int, Hit?) -> Unit)? = null): String? {
        val device = findDevice() ?: run {
            Log.w(TAG, "TNT GO 未连接")
            return null
        }
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "无 USB 权限")
            return null
        }

        val conn = usbManager.openDevice(device) ?: run {
            Log.w(TAG, "openDevice 失败（可能被其它进程占用）")
            return null
        }

        val report = StringBuilder()
        val hits = mutableListOf<Hit>()

        try {
            report.append("# TNT GO 厂商寄存器只读探针\n")
            report.append("# 设备: ${device.deviceName}\n")
            report.append("# VID:PID = %04X:%04X\n".format(device.vendorId, device.productId))
            report.append("# 只发 IN(0xC1)，不发 OUT\n")
            report.append("# 接口列表:\n").append(vendorInterfaceInfo())
            report.append("\n")

            // ---- 自检 ----
            val selfCheck = probe(conn, SELF_CHECK_REQ, SELF_CHECK_VAL, SELF_CHECK_IDX, SELF_CHECK_LEN)
            report.append("## 自检（已知可读：DP lane 数）\n")
            report.append("req=0x%02X idx=0x%02X len=%d → ret=%d bytes=[%s]\n"
                .format(selfCheck.request, selfCheck.index, selfCheck.length, selfCheck.ret, selfCheck.hex))
            report.append(if (selfCheck.ok)
                "✅ 厂商通道可用（ret=${selfCheck.ret}）——后续扫描结果可信\n\n"
            else
                "❌ 自检失败（ret=${selfCheck.ret}）——厂商通道可能不通，后续结果仅供参考\n\n")
            Log.i(TAG, "自检 ret=${selfCheck.ret} hex=${selfCheck.hex}")

            // ---- 全扫描 ----
            val total = REQS.size * IDXES.size * LENS.size
            var done = 0
            report.append("## 全扫描（req × index × len，全部 IN）\n")
            report.append("req,index,len,ret,le16,be16,hex\n")

            for (req in REQS) {
                for (idx in IDXES) {
                    for (len in LENS) {
                        val hit = probe(conn, req, 0, idx, len)
                        done++
                        onProgress?.invoke(done, total, if (hit.ok) hit else null)

                        if (hit.ok) {
                            hits += hit
                            report.append("0x%02X,0x%02X,%d,%d,%s,%s,%s\n".format(
                                hit.request, hit.index, hit.length, hit.ret,
                                hit.le16?.toString() ?: "", hit.be16?.toString() ?: "", hit.hex))
                            Log.i(TAG, "HIT req=0x%02X idx=0x%02X len=%d ret=%d hex=%s"
                                .format(hit.request, hit.index, hit.length, hit.ret, hit.hex))
                        }
                    }
                }
            }

            // ---- 解读 ----
            report.append("\n## 成功返回的项（共 ${hits.size} 个）\n")
            if (hits.isEmpty()) {
                report.append("(无 —— 除自检外没有其它可读寄存器)\n")
            } else {
                for (h in hits) {
                    report.append("- req=0x%02X idx=0x%02X len=%d → le16=%s be16=%s hex=[%s]"
                        .format(h.request, h.index, h.length,
                            h.le16?.toString() ?: "-", h.be16?.toString() ?: "-", h.hex))
                    // 电量百分比在 0..100 之间，标记可疑项
                    val v = h.le16
                    if (v != null && v in 1..100) report.append("   ★ 疑似电量 ${v}%  (le16)")
                    val w = h.be16
                    if (w != null && w in 1..100 && w != v) report.append("   ★ 疑似电量 ${w}%  (be16)")
                    report.append("\n")
                }
            }

            report.append("\n## 已写入\n")
            report.append(csvFile().absolutePath).append("\n")
        } finally {
            runCatching { conn.close() }
        }

        saveCsv(report.toString())
        return report.toString()
    }

    /** 单次 IN 控制传输。**绝不发 OUT。** */
    private fun probe(
        conn: android.hardware.usb.UsbDeviceConnection,
        request: Int,
        value: Int,
        index: Int,
        length: Int
    ): Hit {
        val buf = ByteArray(length)
        val ret = try {
            conn.controlTransfer(REQ_TYPE_IN, request, value, index, buf, length, TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "controlTransfer 异常 req=0x%02X idx=0x%02X: %s"
                .format(request, index, e.message))
            -1
        }
        // ret < 0 = 失败；ret < length 时只有前 ret 字节有效
        val valid = if (ret in 0 until length) buf.copyOf(ret) else if (ret >= length) buf else ByteArray(0)
        return Hit(request, index, length, ret, valid)
    }

    fun csvFile(): File {
        val dir = context.getExternalFilesDir("probe") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "tntgo_probe.txt")
    }

    private fun saveCsv(text: String) {
        runCatching { csvFile().writeText(text) }
            .onFailure { Log.w(TAG, "写文件失败: ${it.message}") }
    }
}
