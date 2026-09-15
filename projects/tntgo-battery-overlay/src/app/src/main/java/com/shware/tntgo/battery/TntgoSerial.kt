package com.shware.tntgo.battery

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * TNT GO 串口电量读取器。
 *
 * ## 协议（2026-09-11 实机确认，见 `.paper/plans/G-TNTGo电量探针APK.md` §5.4）
 *
 * TNT GO 的 CDC-ACM 接口是一个 **AT 命令台**（固件 MCU 1.1.1-20201104185843）。
 * 读电量有**两种**方式，都已实测通过：
 *
 * 1. **被动推送** —— 不做任何事，设备每隔若干秒主动发一行：
 *    `+BATCG=4398,100,1,1514,330,2`
 * 2. **主动查询** —— 发 `at+batcg\r\n`（或 `at+bat` / `at+batt`），立即回一行同上
 *
 * ## 字段含义
 * ```
 * +BATCG = <电压mV>, <电量%>, <状态>, <电流mA>, <?>, <?>
 *           4398      100       1       +1512    330   2
 * ```
 * - **第 2 字段（索引 1）= 电量百分比** ★（社区工具同款解析）
 * - 第 4 字段符号 = 充放电方向（**正 = 充电中 / 负 = 放电中**）
 *   —— 社区样例 `+BATCG=3855,60,2,-929,275,2` 即放电态，与本文档实测的 `+1512` 自洽
 *
 * ## 设备
 * VID `0x31CE` / PID `0x5101`（deltainno Smartisan TNT go）
 * 接口 CDC-ACM(class 2/2/1) + CDC-Data(class 10)
 *
 * ## 注意
 * - 手机内核若已绑定 `cdc_acm`，`usb-serial-for-android` 的 `open(force=true)` 会 detach 它。
 *   本机实测**内核未绑定**（`/dev` 下无 `ttyACM*`），故无冲突。
 * - ⚠️ **绝不能发** `AT+SHUTDOWN` / `AT+PWROFF` / `AT+REBOOT` / `AT+RESET` / `AT+RECOVERY`
 *   —— 这些会关机/重启 TNT GO。完整命令集见 `at+help`（200+ 条）。
 */
class TntgoSerial(private val context: Context) {

    companion object {
        const val VID = 0x31CE
        const val PID = 0x5101
        private const val TAG = "TntgoSerial"
        private const val ACTION_USB_PERMISSION = "com.shware.tntgo.battery.USB_PERMISSION"

        /** 电量查询命令（实机确认有响应）。 */
        private const val CMD_BATTERY = "at+batcg"

        /**
         * 解析 `+BATCG=` 行。
         * 组 1 = 电压 mV，组 2 = **电量 %**，其余为状态/电流等。
         */
        private val BATCG_REGEX = Regex("\\+BATCG=(\\d+),(\\d+),(-?\\d+),(-?\\d+),(\\d+),(\\d+)")
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    private var permissionRequested = false

    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VID && it.productId == PID
        }

    fun hasPermission(): Boolean =
        findDevice()?.let { usbManager.hasPermission(it) } ?: false

    /** 请求 USB 访问权限（系统会弹窗）。 */
    fun requestPermissionIfNeeded() {
        val device = findDevice() ?: return
        if (usbManager.hasPermission(device)) return
        if (permissionRequested) return
        permissionRequested = true

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(device, pi)
    }

    /**
     * 读取 TNT GO 电量。
     * 阻塞方法，请在子线程调用。
     *
     * 全程持 [SerialGuard.lock]，与「串口探测 / 自由输入」互斥。
     * ⚠️ **日志格式不可改** —— OP 的 `tntgo_meter_c.sh` 依赖它解析。
     *
     * @return 电量百分比；失败返回 null
     */
    fun readLevel(): Int? = synchronized(SerialGuard.lock) { readLevelLocked() }

    private fun readLevelLocked(): Int? {
        val device = findDevice() ?: run {
            Log.w(TAG, "TNT GO not found")
            return null
        }
        if (!usbManager.hasPermission(device)) {
            requestPermissionIfNeeded()
            Log.w(TAG, "no USB permission yet")
            return null
        }

        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            Log.w(TAG, "no CDC-ACM driver matched")
            return null
        }
        val connection = usbManager.openDevice(device) ?: run {
            Log.w(TAG, "openDevice failed")
            return null
        }
        val port = driver.ports.firstOrNull() ?: run {
            connection.close()
            Log.w(TAG, "no serial port")
            return null
        }

        return try {
            port.open(connection)
            // 波特率 115200（社区项目默认），失败再试 9600（早期开 ADB 教程）
            tryRead(port, 115200) ?: tryRead(port, 9600)
        } catch (e: Exception) {
            Log.w(TAG, "serial open failed: ${e.message}")
            null
        } finally {
            runCatching { port.close() }
            runCatching { connection.close() }
        }
    }

    private fun tryRead(port: UsbSerialPort, baudRate: Int): Int? {
        return try {
            port.setParameters(
                baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            // 清空残留（设备会周期主动推送 +BATCG，先扔掉旧的）
            val flush = ByteArray(256)
            while (port.read(flush, 50) > 0) {
                // drain
            }
            port.write("$CMD_BATTERY\r\n".toByteArray(Charsets.US_ASCII), 1000)

            val buffer = ByteArray(512)
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) {
                val n = port.read(buffer, 300)
                if (n > 0) {
                    sb.append(String(buffer, 0, n, Charsets.US_ASCII))
                    if (sb.contains("+BATCG=")) break
                }
            }
            val m = BATCG_REGEX.find(sb)
            val level = m?.groupValues?.get(2)?.toIntOrNull()
            if (level == null) {
                Log.d(TAG, "no BATCG at $baudRate, raw=${sb.take(120)}")
            } else {
                // 电压 / 电量 / 状态 / 电流 / ? / ?
                Log.d(TAG, "BATCG at $baudRate: mV=${m.groupValues[1]} " +
                        "level=${m.groupValues[2]}% state=${m.groupValues[3]} " +
                        "current=${m.groupValues[4]}mA t=${m.groupValues[5]} ?=${m.groupValues[6]}")
            }
            level
        } catch (e: Exception) {
            Log.w(TAG, "read at $baudRate failed: ${e.message}")
            null
        }
    }
}