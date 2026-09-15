package com.shware.mode.mod.brightness

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.shware.mode.tntgoserial.TntgoSerial
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ★★ TNT GO 背光控制 —— 走 USB CDC-ACM 的 AT 控制台。
 *
 * ## ★★★ AS3b 之后：**本类不再自己开设备**
 *
 * 设备的开关 / 端口锁 / 命令白名单全部收进了
 * [`TntgoSerial`](../../../../../../../tntgo-serial/src/main/java/com/shware/mode/tntgoserial/TntgoSerial.kt)（共享库 `:tntgo-serial`）。
 * 本类只剩**协议层 + 曲线换算**。
 *
 * ⚠️ **本模块的 `build.gradle.kts` 已经不再依赖 `usb-serial-for-android`** ——
 * 那是"唯一 owner"这条约束的**编译期表达**。
 *
 * 协议（见 [.paper/07](../../../../../../../.paper/07-TNTGO亮度独立控制.md)）：
 * ```
 * AT+BKL          →  +BKL=<值>     ← ★ 查询型（裸发安全）
 * AT+BKL=<值>     →  +BKL=<值>     ← ★ 设置型（实测生效）
 * ```
 *
 * ## ⚠️ 量程（**转过一次弯，结论是 9~2000**）
 *
 * | 来源 | 量程 | 判定 |
 * |---|---|---|
 * | .paper/07（OP 反编译 + 官方公式 `calculateBrightnessMcuValue`） | **9 ~ 2000** | ✅ **最终成立** |
 * | 用户 2026-09-13 口述 | 1 ~ 1000 | 🟡 只是"试到 1000 为止"，**不是设备上限** |
 * | ★ **用户目视**：2000 明显比 1000 亮 | — | ★★★ **决定性证据** |
 *
 * ⚠️ 中途按 "1~1000" 实现过一版 ⇒ **等于把屏幕砍掉一半亮度**。
 * **教训：回读只能证明"MCU 收下了"，证明不了"灯真的变了"。**
 *
 * ## ⚠️ 设备
 *
 * VID `0x31CE` / PID `0x5101`（deltainno **Smartisan TNT go**）。
 *
 * ## ⚠️⚠️ 绝对不能发的命令
 *
 * `AT+SHUTDOWN` / `AT+PWROFF` / `AT+REBOOT` / `AT+RESET` / `AT+RECOVERY` / `AT+UPGRADE`
 * / `AT+FLASHWRITE` / `AT+OTPWRITE` —— **会关机 / 重启 / 变砖 TNT GO**。
 * ★ 现在这条由 `TntgoSerial` 的**白名单**把关（本类即使写错也发不出去）。
 *
 * ## ⚠️ 与电量 mod 共用一条串口（★ AS3b 已实测坐实）
 *
 * 两边用**同一条 CDC-ACM**，设备是**独占**的。实测（2026-09-15）：
 * 用户按亮度键时电量侧轮询失败率 **67%**（不按键时 0%）。
 *
 * ⇒ AS3b 之后两边走 `TntgoSerial` 的**跨进程端口锁**，并遵守一条优先级：
 * > ★★ **后台轮询让路给人的手指。**
 *
 * 具体：电量侧取锁 **`lockWaitMs = 0`（拿不到立刻放弃）**，
 * 而本类取锁**愿意等 3 秒**（人按了就必须响应）。
 */
class TntgoBkl(private val context: Context) {

    companion object {
        private const val TAG = "ModeMod/Bright"

        const val VID = TntgoSerial.VID
        const val PID = TntgoSerial.PID

        /**
         * ⚠️ **字符串本身不能改** —— `BrightnessModService` 用它注册了 receiver。
         */
        const val ACTION_USB_PERMISSION = "com.shware.mode.mod.brightness.USB_PERMISSION"

        /**
         * ★★ **本机实测可用的 MCU 量程 = `9 ~ 2000`**。
         *
         * 来路见类注释的表格。★ **不要按"1~1000"改回去** —— 那会把屏幕砍掉一半亮度。
         */
        const val MCU_MIN = 9
        const val MCU_MAX = 2000

        /**
         * **对账允许的宽边界** —— 比已知量程宽一倍，用来探"2000 之上还有没有更高的档"。
         * ★ 绝不能拿它当量程用（那就又变成"自己验证自己"了）。
         */
        const val RAW_PROBE_MIN = 0
        const val RAW_PROBE_MAX = 6000

        /** 收到 +BKL 之前最多等多久（ms） */
        private const val READ_MS = 700L

        /** 串口被占时重试几次 */
        private const val RETRIES = 3
        private const val RETRY_GAP_MS = 250L

        /**
         * ★★ **取锁愿意等多久**（AS3b 的优先级设计）。
         *
         * 电量侧是 `0`（拿不到就让路），本类是 **3000** ——
         * ★ **因为这边是人的手指，那边是后台轮询。**
         * 宁可让按键多等一小会儿，也不能丢掉一次按键。
         */
        private const val LOCK_WAIT_MS = 3000L

        /** 写进锁文件的占用者名字（对手拿不到锁时能报出"是谁占着"） */
        private const val HOLDER = "brightness"
        private const val HOLDER_STREAM = "brightness-stream"

        private val BKL = TntgoSerial.BKL

        /** ★ 设备拒绝一个值时的回应：`+ERROR=<code>` */
        private val ERROR = TntgoSerial.ERROR

        /** 波特率优先级：TNT GO 的 AT 台实测跑在 115200 */
        private val BAUDS = TntgoSerial.DEFAULT_BAUDS

        /**
         * 工厂曲线：UI 亮度 b ∈ [0,100] → 原始 MCU 值（9 ~ 2000）。
         * 来源：`BrightnessPanel.calculateBrightnessMcuValue`（OP 反编译，五点实测吻合）。
         */
        private fun rawCurve(b: Double): Double =
            0.0033 * b * b * b - 0.1708 * b * b + 4.3464 * b + 5.0814

        /**
         * ★★ **浮点版**：UI 0~100（可为小数）→ MCU 9~2000。
         *
         * ## 为什么必须有它（任务 AJ 的"微小段落感"第二根因）
         *
         * 无极调节时如果**先把 UI 取整**再查曲线，目标值每秒最多只变化 `uiRate` 次
         * （例如 76.6 UI/秒 ⇒ **每秒最多 77 个不同目标**）⇒
         * ★ **发送帧率被"UI 整数化"卡死在 ~77 Hz 以下**，再怎么提高 tick 也没用。
         *
         * 直接在**浮点 UI** 上查曲线、只在 **MCU 上取整** ⇒ 最多有 1991 个不同目标，
         * 帧率不再受 UI 量化限制。**这是消除残留段落感的关键一步。**
         */
        fun uiToMcuF(ui: Float): Int {
            val b = ui.coerceIn(0f, 100f).toDouble()
            return rawCurve(b).roundToInt().coerceIn(MCU_MIN, MCU_MAX)
        }

        /**
         * UI 0~100 → 本机 MCU 9~2000 —— ★ **就是出厂公式本身，不做任何重映射**。
         *
         * ⚠️ **这里我走过一次弯路，记下来**：
         * 一度按"量程 1~1000"把曲线**重映射**到 1000，
         * ⇒ 结果 **UI 100% 只给到 MCU 1000，等于把屏幕砍掉一半亮度**。
         * 用户目视确认「2000 明显更亮」后才纠正过来。
         * **⇒ 只要参数取自官方公式，就不要自作聪明地缩放它。**
         */
        fun uiToMcu(ui: Int): Int = uiToMcuF(ui.toFloat())

        /** MCU → UI（反查）。曲线单调递增 ⇒ 直接扫一遍 101 个点最稳（**不引入数值解法的坑**）。 */
        fun mcuToUi(mcu: Int): Int {
            var best = 0
            var bestDiff = Int.MAX_VALUE
            for (ui in 0..100) {
                val d = abs(uiToMcu(ui) - mcu)
                if (d < bestDiff) { bestDiff = d; best = ui }
            }
            return best
        }

        /** MCU → UI 的**小数**版本，用于把回读值反过来显示得更细 */
        fun mcuToUiLabel(mcu: Int): Int = mcuToUi(mcu)
    }

    /** 一次操作的结果 —— **区分"没做到"的每一种原因**（卡片上要说清楚，不静默失败）。 */
    sealed class Result {
        /** 拿到了 `+BKL=<n>` */
        data class Ok(val mcu: Int) : Result()

        /** 设备不在（没插 TNT GO） */
        object NoDevice : Result()

        /** 设备在，但没有 USB 权限 —— 要引导用户去授权 */
        object NoPermission : Result()

        /**
         * ★★ **设备【明确拒绝】了这个值** —— 它回了 `+ERROR=<code>`。
         *
         * ## 为什么必须和 [Busy] 分开（2026-09-13 实际踩到）
         *
         * 第一版把 `+ERROR=100`（`AT+BKL=3000` 越界）归类成"没等到 +BKL"⇒ 报成
         * **`Busy(串口被占)`**，还**白重试 3 次**。
         * ⇒ ★ **"设备说这个值不行"和"串口被别人占着"是完全不同的两件事**，
         *   混在一起会把一个**确定性的量程事实**伪装成一个**偶发的资源冲突**。
         *
         * ★ 实测：`AT+BKL=3000` ⇒ `+ERROR=100`（`2000` 则正常回 `+BKL=2000`）
         * ⇒ **本机背光量程的硬上限 = 2000**，由 MCU 自己把关。
         */
        data class Rejected(val code: String) : Result()

        /**
         * 试了 [RETRIES] 次都失败。
         *
         * ★ AS3b 之后这里**多了一种明确的原因**：*端口正被另一个 mod 占着*
         * （`TntgoSerial` 的跨进程锁没拿到）—— 措辞里会带上占用者名字。
         */
        data class Busy(val why: String) : Result()

        /** 其它失败 */
        data class Failed(val why: String) : Result()
    }

    // ------------------------------------------------------------------ 设备（全部转交 TntgoSerial）

    fun findDevice(): UsbDevice? = TntgoSerial.findDevice(context)

    fun hasPermission(): Boolean = TntgoSerial.hasPermission(context)

    /**
     * 请求 USB 访问权限 —— **系统弹窗，必须用户点"允许"**。
     *
     * ⚠️ A10 上**没有** `cmd usb` 实现，adb / Shizuku **代授不了**（见计划书 Q2 §0.4）。
     */
    fun requestPermission() = TntgoSerial.requestPermission(context, ACTION_USB_PERMISSION)

    fun resetPermissionRequested() = TntgoSerial.resetPermissionRequested()

    /** 设一个 UI 亮度（0~100）—— 内部换算成 MCU 并回读确认 */
    fun setUi(ui: Int): Result = exec("at+bkl=${uiToMcu(ui)}")

    /**
     * ★ **直接设 MCU 值，并 clamp 到 `[MCU_MIN, MCU_MAX]`** —— **无极调节专用**。
     *
     * ⚠️ 与 [setRawMcu] **故意分开**（任务 AI 在这里踩过坑）：
     * - [setMcuClamped] = 正常工作路径，**必须 clamp**（量程 9~2000 是 MCU 自己把关的硬边界）
     * - [setRawMcu] = **对账/探测**路径，**绝不能 clamp**（否则就变成"自己验证自己"）
     */
    fun setMcuClamped(mcu: Int): Result = exec("at+bkl=${mcu.coerceIn(MCU_MIN, MCU_MAX)}")

    /**
     * 直接设 MCU 值（**量程对账**用 —— 绕过曲线，发原始值）。
     *
     * ## ⚠️⚠️ 这里**故意不 clamp 到 `[MCU_MIN, MCU_MAX]`**（2026-09-13 踩到）
     *
     * 第一版写了 `mcu.coerceIn(MCU_MIN, MCU_MAX)` ⇒ **1500 / 2000 被本函数夹成 1000**，
     * 而日志打印的是**调用方传进来的"意图值"** ⇒
     * 结果看起来**完全像是设备把越界值夹到了 1000** —— **一个自己骗自己的假阳性**。
     *
     * **⇒ 对账函数绝不能自己先把值改掉。** 只留一个**防手滑**的宽边界
     * （`RAW_PROBE_MIN..RAW_PROBE_MAX`，比真实量程宽一倍）。
     */
    fun setRawMcu(mcu: Int): Result {
        if (mcu < RAW_PROBE_MIN || mcu > RAW_PROBE_MAX) {
            return Result.Failed("拒绝发送 $mcu（只在 $RAW_PROBE_MIN..$RAW_PROBE_MAX 内允许,防手滑）")
        }
        return exec("at+bkl=$mcu")
    }

    /**
     * ★ **最近一次真正发出去的 AT 命令**（原样）。
     *
     * 存在的唯一理由：**日志必须打印"实际发了什么",而不是"打算发什么"**。
     * 上面那个坑就是因为打印了意图值才瞒过去的。
     */
    val lastCommand: String get() = TntgoSerial.lastCommand

    /** 查询当前 MCU 值（★ 裸发 `AT+BKL` 是查询型，安全） */
    fun query(): Result = exec("at+bkl")

    // ------------------------------------------------------------------ 内部

    private fun exec(cmd: String): Result {
        if (TntgoSerial.findDevice(context) == null) return Result.NoDevice
        if (!TntgoSerial.hasPermission(context)) {
            requestPermission()
            return Result.NoPermission
        }

        var lastWhy = "未尝试"
        var busyWhy: String? = null
        for (attempt in 1..RETRIES) {
            val r = tryOnce(cmd)
            if (r is Result.Ok) return r
            if (r is Result.NoDevice || r is Result.NoPermission) return r
            // ★ 设备【明确拒绝】⇒ 重试一万次也是同样的结果，立刻返回
            if (r is Result.Rejected) return r
            when (r) {
                is Result.Failed -> lastWhy = r.why
                is Result.Busy -> { lastWhy = r.why; busyWhy = r.why }
                else -> lastWhy = "未知"
            }
            if (attempt < RETRIES) {
                Log.d(TAG, "第 $attempt 次失败（$lastWhy），${RETRY_GAP_MS}ms 后重试")
                Thread.sleep(RETRY_GAP_MS)
            }
        }
        // ★ AS3b：把"抢不到端口"单独报出来 —— 它的排查方向与"设备没回应"完全不同
        return Result.Busy(busyWhy ?: lastWhy)
    }

    /**
     * 试一遍**所有波特率**。
     *
     * ★ **回退语义与电量侧【不同】**（所以回退留在各自调用方，没揉进 `TntgoSerial`）：
     * 这边**每一档都重发命令**，并采用**最后一次**收到的文本；
     * 而电量侧只在"一个字节都没收到"时才试下一档。
     * ⚠️ 把两边统一会**悄悄改掉其中一个的行为**。
     */
    private fun tryOnce(cmd: String): Result {
        var text: String? = null
        for (baud in BAUDS) {
            when (val r = TntgoSerial.exec(
                ctx = context,
                cmd = cmd,
                baud = baud,
                passiveMs = 0L,
                readMs = READ_MS,
                stopWhen = BKL,
                lockWaitMs = LOCK_WAIT_MS,
                holder = HOLDER,
            )) {
                is TntgoSerial.Result.Ok -> {
                    text = r.raw
                    if (BKL.containsMatchIn(r.raw)) break
                }
                is TntgoSerial.Result.Busy -> return Result.Busy("端口被「${r.holder}」占着")
                TntgoSerial.Result.NoDevice -> return Result.NoDevice
                TntgoSerial.Result.NoPermission -> return Result.NoPermission
                is TntgoSerial.Result.Rejected -> return Result.Failed(r.why)
                is TntgoSerial.Result.Failed -> return Result.Failed(r.why)
                TntgoSerial.Result.Empty -> Unit      // 试下一档波特率
            }
        }

        val m = text?.let { BKL.find(it) }
        val err = text?.let { ERROR.find(it) }
        return when {
            // ★ 先看设备是不是【明确拒绝】—— 这比"没收到"信息量大得多
            err != null -> Result.Rejected(err.groupValues[1])
            m != null -> Result.Ok(m.groupValues[1].toIntOrNull() ?: return Result.Failed("+BKL 值解析失败"))
            else -> Result.Failed("没等到 +BKL（收到：${text?.take(60)?.replace("\n", "\\n") ?: "空"}）")
        }
    }

    // ================================================================== ★★★ 连续写入会话
    //
    // ## 为什么需要它（任务 AJ 的"微小段落感"根因）
    //
    // 普通的 [setMcuClamped] 每次都是 **开设备 → 写 → 读 → 关**，实测一次 **~43ms**
    // ⇒ 无极调节的发送帧率被卡在 **~23 Hz**。
    // 而 ramp 的值是按时间连续计算的 ⇒ **每 43ms 才动一下，速度一快，每一下就是一大跳**
    // —— 这正是用户观察到的"帧率有限 vs 报送无限"的冲突。
    //
    // ⇒ **无极调节期间把设备【一直开着】**，之后每次只是 `write()`（亚毫秒级），
    //   帧率能上去一个数量级。代价：
    //   ① 期间**占着 USB 设备** ⇒ ★ AS3b 之后它**持着跨进程端口锁**，
    //      电量侧会**让路**（它不阻塞，只是每轮跳过 ⇒ 那段时间学不到容量，代价是偏保守）
    //   ② 不再逐次回读校验 ⇒ **结束时回读一次**确认
    //   ③ ⚠️ **MCU 对每条命令都会 echo**，不排空会积压（`TntgoSerial.Stream` 里按时间节流）

    /** [beginStream] 的结果 —— 失败原因要能区分（不静默） */
    sealed class StreamResult {
        data class Ok(val stream: Stream) : StreamResult()
        object NoDevice : StreamResult()
        object NoPermission : StreamResult()

        /** ★ AS3b 新增：端口锁被别的 mod 占着 */
        data class Busy(val holder: String) : StreamResult()

        data class Failed(val why: String) : StreamResult()
    }

    /**
     * ★★ 打开一个**连续写入会话**（无极调节专用）。
     * ⚠️ 用完必须 [Stream.close]，否则设备与**端口锁**会一直占着。
     */
    fun beginStream(): StreamResult =
        when (val r = TntgoSerial.beginStream(context, LOCK_WAIT_MS, HOLDER_STREAM)) {
            is TntgoSerial.StreamResult.Ok -> StreamResult.Ok(Stream(r.stream))
            TntgoSerial.StreamResult.NoDevice -> StreamResult.NoDevice
            TntgoSerial.StreamResult.NoPermission -> StreamResult.NoPermission
            is TntgoSerial.StreamResult.Busy -> StreamResult.Busy(r.holder)
            is TntgoSerial.StreamResult.Failed -> StreamResult.Failed(r.why)
        }

    /** 连续写入会话的本 mod 包装（把 `TntgoSerial` 的裸值包回本 mod 的 [Result] 语义） */
    class Stream internal constructor(private val inner: TntgoSerial.Stream) {

        /** 真正写出去过多少次 */
        val writes: Int get() = inner.writes

        /** 最近一次真正写出去的值 */
        val lastWritten: Int get() = inner.lastWritten

        /** 写一个亮度值。**不等回显**。 */
        fun write(mcu: Int): Boolean = inner.write(mcu)

        /**
         * ★ **结束时回读一次**确认设备真的停在目标值上。
         * （流式期间没有逐次校验，所以这一步不能省。）
         */
        fun verify(): Result {
            val v = inner.verify(READ_MS) ?: return Result.Failed("回读没等到 +BKL")
            return Result.Ok(v)
        }

        fun close() = inner.close()
    }
}
