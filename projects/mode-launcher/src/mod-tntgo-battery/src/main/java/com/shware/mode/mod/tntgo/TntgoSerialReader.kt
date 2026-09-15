package com.shware.mode.mod.tntgo

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.shware.mode.tntgoserial.TntgoSerial

/**
 * ★★ TNT GO 电量读取器。
 *
 * ## ★★★ AS3b 之后：**本类不再自己开设备**
 *
 * 设备的开关 / 端口锁 / 命令白名单全部收进了
 * [`TntgoSerial`](../../../../../../../tntgo-serial/src/main/java/com/shware/mode/tntgoserial/TntgoSerial.kt)（共享库 `:tntgo-serial`）。
 * 本类只剩**协议层**：何时问、问什么、怎么解析。
 *
 * ⚠️ **本模块的 `build.gradle.kts` 已经不再依赖 `usb-serial-for-android`** ——
 * 这是"唯一 owner"这条约束的**编译期表达**：拿不到 `UsbSerialPort`，
 * 就不可能有人在这里偷偷 `openDevice`。
 *
 * ## 协议（坚果 Pro 3 实测于 2026-09-12，见计划书 Q2 §0.3）
 *
 * TNT GO 的 CDC-ACM 接口是一个 **AT 命令台**。读电量两条路：
 *
 * 1. ★ **被动推送** —— 什么都不做，设备会周期性地吐 `+BATCG=…`。
 *    实测观察：它常常**搭在别的命令的响应里**一起回来 ⇒ 所以**先听再说**。
 * 2. **主动查询** —— 发 `at+batcg`，立刻回一行。
 *
 * 响应格式：
 * ```
 * +BATCG=<电压mV>, <电量%>, <状态>, <电流mA>, <温度×0.1>, <?>
 * ```
 * **第 2 组 = 电量**；第 4 组符号 = 方向（**正 = 充电 / 负 = 放电**）。
 *
 * ## ⚠️ 设备
 *
 * VID `0x31CE` / PID `0x5101`（deltainno **Smartisan TNT go**）。
 * 接口 6 = CDC-ACM(class 2/2/1)，接口 7 = CDC-Data(class 10)。
 *
 * ## ⚠️⚠️ 绝对不能发的命令
 *
 * `AT+SHUTDOWN` / `AT+PWROFF` / `AT+REBOOT` / `AT+RESET` / `AT+RECOVERY`
 * —— **会把 TNT GO 关机或重启**。★ 现在这条由 `TntgoSerial` 的**白名单**把关
 * （本类即使写错也发不出去），但**仍然不要在别处扩展命令**。
 *
 * ## ⚠️ 权限
 *
 * `/dev/bus/usb/001/005` 是 `crw-rw---- root usb`，**shell（含 Shizuku）也开不了** ⇒
 * 必须走 `UsbManager` + **用户手动授权一次**（系统弹窗）。见 [requestPermission]。
 */
class TntgoSerialReader(private val context: Context) {

    companion object {
        const val VID = TntgoSerial.VID
        const val PID = TntgoSerial.PID

        /**
         * ⚠️ **字符串本身不能改** —— `TntgoBatteryService` 用它注册了 receiver，
         * 改了就必须两处一起改（本类只负责"请求权限时带上它"）。
         */
        const val ACTION_USB_PERMISSION = "com.shware.mode.mod.tntgo.USB_PERMISSION"

        private const val TAG = "ModeMod/Tntgo"

        /** ★ 本 mod 唯一允许发送的命令 */
        private const val CMD_BATTERY = "at+batcg"

        /** 协议正则（★ F9：第 5 组支持负数；定义在 `TntgoSerial` 里，两处引用同一份） */
        private val BATCG = TntgoSerial.BATCG

        /** 被动听多久（ms） */
        private const val PASSIVE_MS = 700L

        /** 主动问之后等多久（ms） */
        private const val ACTIVE_MS = 1500L

        /**
         * ★★ **取锁不等待**（AS3b 的优先级设计）。
         *
         * 电量轮询每 30 s 一次，**它等得起，而人的手指等不起**
         * ⇒ 端口被别人占着就**立刻让路**，本轮跳过。
         *
         * ★ 跳过**不是数据损失**：它表现为一次**断档**，而 N6a 已经把断档
         * 改成"段继续 + 断档单独记账"（`segGapMs`），代价只是容量估计**偏保守**。
         * ⇒ **拿一点点保守，换按键不卡。**
         */
        private const val LOCK_WAIT_MS = 0L

        /** 写进锁文件的占用者名字（对手拿不到锁时能报出"是谁占着"） */
        private const val HOLDER = "battery"
    }

    /** 一次读取的结果。**区分"没读到"的每一种原因** —— 卡片上要能说清楚。 */
    sealed class Reading {
        /**
         * 读到了。
         *
         * @param percent 电量 %
         * @param millivolts 电池电压 mV
         * @param currentMa 电池电流 mA（**正 = 充电 / 负 = 放电**）
         * @param tempC ★ **电池温度 °C** —— `+BATCG` 第 5 字段 ÷ 10
         *              （判定依据：[01 §5](../../../../../../.paper/01-TNT-GO硬件基础.md) 两组合独立样本）
         *              ★ **`null` = 这一笔没解析出温度**（F9）。
         *              ⚠️ 原来解析失败给 `0.0`，卡片侧又判 `> 0.0` 才显示 ⇒
         *                **「真的 0 °C」和「没读到」被混成同一件事**。
         */
        data class Ok(
            val percent: Int,
            val millivolts: Int,
            val currentMa: Int,
            val tempC: Double?,
        ) : Reading() {
            /** 电流为正 = 正在给 TNT GO 充电 */
            val charging: Boolean get() = currentMa > 0
        }

        /** 设备不在（没插 TNT GO） */
        object NoDevice : Reading()

        /** 设备在，但没拿到 USB 权限 —— **要引导用户去授权** */
        object NoPermission : Reading()

        /**
         * ★★★ AS3b 新增：**端口正被另一个 mod 占着，本次让路。**
         *
         * ## 为什么必须和 [Failed] 分开
         *
         * 争抢**已被实测坐实**（用户按键时电量侧失败率 **67%**，不按键时 **0%**）。
         * 而在本类分开之前，这种情况被报成 **`Failed(读失败)`** ——
         * ★ **看起来像 bug**。本会话早些时候就有人（我）因此把一次
         * **正常的让路**误判成串口故障，还写进了计划书。
         *
         * ⇒ 「让路」是**设计行为**，「失败」是**故障**。**日志里必须能一眼分开。**
         *
         * @param holder 占着端口的是谁（`brightness` / `brightness-stream` / `?`）
         */
        data class Busy(val holder: String) : Reading()

        /** 有设备有权限，但读失败（含驱动异常、响应里没有 +BATCG） */
        data class Failed(val why: String) : Reading()
    }

    // ------------------------------------------------------------------ 设备（全部转交 TntgoSerial）

    fun findDevice(): UsbDevice? = TntgoSerial.findDevice(context)

    fun hasPermission(): Boolean = TntgoSerial.hasPermission(context)

    /**
     * 请求 USB 访问权限 —— **系统会弹窗，必须用户点"允许"**。
     *
     * ⚠️ A10 上**没有** `cmd usb` 实现，adb/Shizuku **代授不了**（见计划书 Q2 §0.4）。
     */
    fun requestPermission() = TntgoSerial.requestPermission(context, ACTION_USB_PERMISSION)

    /** 用户授权之后要把这个重置回 false，否则设备重插后不会再请求 */
    fun resetPermissionRequested() = TntgoSerial.resetPermissionRequested()

    // ------------------------------------------------------------------ 读

    /**
     * 读一次电量。**阻塞**（约 0.7–2.2 s），**必须在后台线程调用**。
     *
     * ## ★ 波特率回退语义**刻意保持原样**
     *
     * 原实现是 `listenAndAsk(115200) ?: listenAndAsk(9600)` ——
     * `?:` **只在第一档一个字节都没收到时才试第二档**。
     * ★ 若第一档收到了内容但没有 `+BATCG`（垃圾/半行），**不会**再试 9600，
     *   而是直接走 `parse` 报"响应里没有 +BATCG"。
     *
     * ⚠️ 这个语义**容易在重构时被"顺手改对"** —— 而那会**改变现网行为**。
     * 本方法用 `Result.Empty` 精确复刻它（见下面的 `when`）。
     * （对比：亮度侧的回退语义**不一样**，它每档都重发命令 —— 所以回退留在各自调用方。）
     */
    fun read(): Reading {
        if (TntgoSerial.findDevice(context) == null) return Reading.NoDevice
        if (!TntgoSerial.hasPermission(context)) {
            TntgoSerial.requestPermission(context, ACTION_USB_PERMISSION)
            return Reading.NoPermission
        }

        var lastEmpty = true
        for (baud in TntgoSerial.DEFAULT_BAUDS) {
            when (val r = TntgoSerial.exec(
                ctx = context,
                cmd = CMD_BATTERY,
                baud = baud,
                passiveMs = PASSIVE_MS,
                readMs = ACTIVE_MS,
                stopWhen = BATCG,
                lockWaitMs = LOCK_WAIT_MS,
                holder = HOLDER,
            )) {
                is TntgoSerial.Result.Ok -> return parse(r.raw)

                // ★★ 让路 —— 不是故障，本轮跳过（见 Reading.Busy 的注释）
                is TntgoSerial.Result.Busy -> {
                    Log.i(TAG, "端口被「${r.holder}」占着 ⇒ ★本轮让路（不是失败）")
                    return Reading.Busy(r.holder)
                }

                is TntgoSerial.Result.Rejected -> {
                    Log.w(TAG, "读失败（被白名单拒绝）：${r.why}")
                    return Reading.Failed(r.why)
                }

                is TntgoSerial.Result.Failed -> {
                    // ★ 真失败必须留痕 —— 旧实现在 `listenAndAsk` 里有一行 `读失败:`，
                    //   AS3b 重写时**差点把它丢掉**。它是 `measure_serial_contention.sh`
                    //   判定"是否还有有害争抢"的**唯一依据**（让路 ≠ 失败）。
                    Log.w(TAG, "读失败：${r.why}")
                    return Reading.Failed(r.why)
                }
                TntgoSerial.Result.NoDevice -> return Reading.NoDevice
                TntgoSerial.Result.NoPermission -> {
                    TntgoSerial.requestPermission(context, ACTION_USB_PERMISSION)
                    return Reading.NoPermission
                }

                // ★ 一个字节都没收到 ⇒ 试下一档波特率（**复刻原来的 `?:` 语义**）
                TntgoSerial.Result.Empty -> lastEmpty = true
            }
        }
        return Reading.Failed(if (lastEmpty) "没等到 +BATCG（两档波特率都没收到任何字节）" else "没等到 +BATCG")
    }

    // ------------------------------------------------------------------ 解析

    /** 协议解析 —— ★ **留在本类**（`TntgoSerial` 只管收发，不懂 +BATCG） */
    private fun parse(raw: String): Reading {
        val m = BATCG.find(raw)
            ?: return Reading.Failed("响应里没有 +BATCG（${raw.take(50)}）")
        val g = m.groupValues
        return Reading.Ok(
            percent = g[2].toIntOrNull() ?: return Reading.Failed("电量字段解析失败"),
            millivolts = g[1].toIntOrNull() ?: 0,
            currentMa = g[4].toIntOrNull() ?: 0,
            // ★ 第 5 字段 = 温度 × 0.1 °C（`303` → 30.3 °C）。
            //   ★ F9：解析不出来给 **null**（不是 0.0）—— 让"没读到"和"0 °C"分得开。
            tempC = g[5].toIntOrNull()?.let { it / 10.0 },
        )
    }
}
