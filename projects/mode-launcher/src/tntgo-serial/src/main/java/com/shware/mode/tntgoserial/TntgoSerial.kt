package com.shware.mode.tntgoserial

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * ★★★★ **TNT GO 串口的唯一 owner**（任务 AS3b）。
 *
 * ## 一、它解决的是什么问题（★ 这个问题已被【实测坐实】，不是假想）
 *
 * `mod-tntgo-battery` 与 `mod-tntgo-brightness` 用**同一条 CDC-ACM**。
 * 设备是**独占**的 ⇒ 两边会撞。实测（2026-09-15，`measure_serial_contention.sh`）：
 *
 * | 时间窗 | 电量侧轮询 | 写失败 | 失败率 |
 * |---|---|---|---|
 * | 用户**不**按键 | 12 | 0 | **0%** |
 * | 用户**正在**按键（89 秒 / 173 次） | 3 | **2** | ★★ **67%** |
 * | 按键之后 | 1 | 0 | 0% |
 *
 * ⇒ ★ **单向争抢：亮度赢，电量输。**
 *
 * ## 二、★★★ 为什么用【跨进程文件锁】而不是"把两个 mod 并到一个进程"
 *
 * AS 计划书 §3.1 原本要求并进程（理由是"进程内锁才有效"）。**拟稿时发现它会把体验改坏**：
 *
 * | # | 问题 |
 * |---|---|
 * | 1 | ★★★ 电量侧的被动监听一次要 **0.7–2.5 s**。并进程 + 互斥锁 ⇒ **用户按亮度键要等最多 2.5 s** —— 而按键手感是本 mod 的核心体验 |
 * | 2 | 失败域合并（现在电量崩了亮度还能用） |
 * | 3 | `KeyFilterService` 是**系统绑定**的 `AccessibilityService`；把它和"每 30 s 做 2.5 s 阻塞 USB I/O 的轮询器"塞进同一进程，是拿按键实时性换架构整洁 |
 *
 * ⇒ ★★ 而**跨进程文件锁不需要改任何进程拓扑**：两个 mod 已是**同一个 APK**
 *   （任务 AL）⇒ 同一个 `filesDir` ⇒ `FileChannel.lock()` 就是**真正的跨进程互斥**。
 *
 * ## 三、★★ 优先级设计：**让"能等的"让路给"不能等的"**
 *
 * | 使用者 | 取锁方式 | 为什么 |
 * |---|---|---|
 * | 电量轮询（每 30 s） | ★ **`lockWaitMs = 0`：拿不到立刻放弃** | 失配一次只是一次**断档**，已被 **N6a 正确记账**（段不作废、只记时长、估计偏保守）；而排队会让**按键变卡** |
 * | 亮度按键（人按的） | `lockWaitMs = 3000`，短重试 | 人按了就必须响应，不能因为轮询在跑就丢掉一次按键 |
 * | 无极调节 ramp | 阻塞取锁 + **整个会话期间持锁** | 已经在用户手里了，必须连续 |
 *
 * > ★ **一句话：后台轮询让路给人的手指。**
 * > 这比"并进程 + 谁都阻塞"更贴合产品要求，而且**不需要动无障碍服务**。
 *
 * ## 四、★ 白名单而不是黑名单
 *
 * `AT+SHUTDOWN` / `AT+PWROFF` / `AT+REBOOT` / `AT+RESET` / `AT+RECOVERY`
 * / `AT+UPGRADE` / `AT+FLASHWRITE` / `AT+OTPWRITE` —— **会关机 / 重启 / 变砖 TNT GO**。
 *
 * ⚠️ 黑名单挡不住"没想到的那一条"。这里只放行**已知只读或已知安全**的三条命令形状。
 *
 * ## 五、★ 用法（两个 mod 都长这样）
 *
 * ```kotlin
 * // 电量：拿不到锁就放弃（lockWaitMs = 0）
 * val r = TntgoSerial.exec(ctx, "at+batcg", 115200, passiveMs = 700, readMs = 1500, stopWhen = BATCG)
 *
 * // 亮度：人按的，愿意等
 * val r2 = TntgoSerial.exec(ctx, "at+bkl=800", 115200, readMs = 700, stopWhen = BKL, lockWaitMs = 3000)
 * ```
 */
object TntgoSerial {

    private const val TAG = "ModeMod/Serial"

    /** VID `0x31CE` / PID `0x5101`（deltainno **Smartisan TNT go**） */
    const val VID = 0x31CE
    const val PID = 0x5101

    /** 默认波特率优先级：TNT GO 的 AT 台实测跑在 115200 */
    val DEFAULT_BAUDS = intArrayOf(115200, 9600)

    /**
     * ★ **命令白名单**（正则逐条 `matches` 全串）。
     *
     * | 形状 | 谁用 | 性质 |
     * |---|---|---|
     * | `at+batcg` | 电量 | 查询型（安全） |
     * | `at+bkl` | 亮度 | ★ **查询型**（裸发安全） |
     * | `at+bkl=<1~4 位数字>` | 亮度 | 设置型（**必须带数值**，`at+bkl=` 空值不放行） |
     */
    private val WHITELIST = listOf(
        Regex("""at\+batcg""", RegexOption.IGNORE_CASE),
        Regex("""at\+bkl""", RegexOption.IGNORE_CASE),
        Regex("""at\+bkl=\d{1,4}""", RegexOption.IGNORE_CASE),
    )

    /**
     * 命令是否在白名单内。
     *
     * ★ 抽成**公开的纯函数**是为了能**离线跑单测**（宿主上可跑，不需要设备）——
     * 判据 4「白名单真的拦得住非法命令」就靠它。
     */
    fun isAllowed(cmd: String): Boolean {
        val c = cmd.trim()
        return WHITELIST.any { it.matches(c) }
    }

    /** 不在白名单里 ⇒ 返回一句人话说明；在 ⇒ `null` */
    fun rejectionOf(cmd: String): String? =
        if (isAllowed(cmd)) null
        else "命令不在白名单内，拒绝发送：`${cmd.trim()}`（只允许 at+batcg / at+bkl / at+bkl=<值>）"

    // ================================================================== 结果

    /** 一次串口操作的结果。★ **区分"没做到"的每一种原因** —— 不静默失败。 */
    sealed class Result {
        /** 收到了内容（原样文本，**解析交给调用方** —— 本库不懂协议） */
        data class Ok(val raw: String) : Result()

        /**
         * 一个字节都没收到。
         *
         * ⚠️ 与 [Failed] **刻意分开**：本库不判断"这是不是我要的回应" ——
         * 那是调用方的事（它才知道 `+BATCG=` / `+BKL=` 长什么样）。
         */
        object Empty : Result()

        /** 设备不在（没插 TNT GO） */
        object NoDevice : Result()

        /** 设备在，但没拿到 USB 权限 —— 要引导用户去授权 */
        object NoPermission : Result()

        /** ★ 白名单拒绝（**不是失败，是保护**） */
        data class Rejected(val cmd: String, val why: String) : Result()

        /**
         * ★★ **端口正被别人占着，本次【让路】。**
         *
         * ★ 与 [Failed] 分开是本设计的关键之一：
         * 早先这种情况被报成「读失败」，看起来像 bug ——
         * 本会话就有人（我）因此把一个**正常的让路**误判成串口故障并写进了计划书。
         *
         * @param holder 占着端口的是谁（`brightness` / `battery` / `?`）
         */
        data class Busy(val holder: String) : Result()

        /** 其它失败（异常 / 驱动不匹配 / openDevice 失败） */
        data class Failed(val why: String) : Result()
    }

    // ================================================================== 设备

    private fun usbManager(ctx: Context): UsbManager =
        ctx.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findDevice(ctx: Context): UsbDevice? =
        usbManager(ctx).deviceList.values.firstOrNull { it.vendorId == VID && it.productId == PID }

    fun hasPermission(ctx: Context): Boolean =
        findDevice(ctx)?.let { usbManager(ctx).hasPermission(it) } ?: false

    @Volatile
    private var permissionRequested = false

    /**
     * 请求 USB 访问权限 —— **系统弹窗，必须用户点"允许"**。
     *
     * ⚠️ A10 上**没有** `cmd usb` 实现，adb / Shizuku **代授不了**（见 Q2 §0.4）。
     * ⚠️ `PendingIntent` flags：S 以下必须给 `0` —— 系统要往这个 Intent 里
     *    **填 extra**（`EXTRA_PERMISSION_GRANTED`），给 IMMUTABLE 就填不进去了。
     *
     * @param action 调用方自己的 action 字符串（**保持与旧实现一致**，
     *               否则已经注册好的 receiver 会收不到）
     */
    fun requestPermission(ctx: Context, action: String) {
        val device = findDevice(ctx) ?: return
        if (usbManager(ctx).hasPermission(device)) return
        if (permissionRequested) return
        permissionRequested = true

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(action).setPackage(ctx.packageName),
            flags
        )
        usbManager(ctx).requestPermission(device, pi)
        Log.i(TAG, "→ 已请求 USB 权限（等用户在系统弹窗上点允许）")
    }

    fun resetPermissionRequested() { permissionRequested = false }

    // ================================================================== 跨进程锁

    /**
     * 锁文件。
     *
     * ★ 用 `filesDir` 而不是 `cacheDir` —— cache 会被系统清掉，
     * 而**锁文件被删掉时两个进程会各自新建一个 inode**，
     * 于是它们**锁的不是同一个对象** ⇒ 互斥静默失效。
     */
    private fun lockFile(ctx: Context): File = File(ctx.applicationContext.filesDir, "tntgo_serial.lock")

    /** 进程内一层（同一进程内两个线程不能同时 `tryLock` 同一个文件 —— 会抛 `OverlappingFileLockException`） */
    private val procLock = ReentrantLock()

    /**
     * 取锁成功时拿到的一整套句柄 —— 必须成对释放。
     *
     * ⚠️ 是 **`internal` 而不是 `private`** —— 因为 [Stream] 的构造器要收它，
     * 而构造器至少得是 `internal`（见 [Stream] 的注释）。
     * 若这里写 `private`，Kotlin 会报
     * *"'internal' exposes its 'private-in-class' parameter type"*（**本轮已踩**）。
     * ★ 对外仍然不可见：消费者只看得到 [StreamResult.Ok] 里的 `Stream`。
     */
    internal class Lease(
        val raf: RandomAccessFile,
        val fileLock: FileLock,
    ) {
        fun release() {
            runCatching { fileLock.release() }
            runCatching { raf.close() }
            procLock.unlock()
        }
    }

    private sealed class Locked<out T> {
        data class Got<T>(val value: T) : Locked<T>()

        /** 没拿到；[holder] 是**从锁文件里读出来的**占用者（尽力而为，可能为 `?`） */
        data class Denied(val holder: String) : Locked<Nothing>()
    }

    /**
     * ★★★ [withPortLock] 检测到"本线程自己已经拿着端口"时用的占位名。
     *
     * 见 [withPortLock] 里的**自嵌套守卫**。
     */
    private const val HOLDER_SELF_NESTED = "自己（同一线程已持有端口 ⇒ 不要嵌套 exec）"

    /**
     * 取"端口锁" → 干活 → 释放。
     *
     * @param holder 本次占用者的名字（写进锁文件，**让对手能报出"谁占着"**）
     * @param waitMs `0` = **不等待，拿不到立刻放弃**（电量轮询就走这条）
     */
    private fun <T> withPortLock(ctx: Context, holder: String, waitMs: Long, body: () -> T): Locked<T> {
        // ★★★ 自嵌套守卫（2026-09-15 实机抓到 6.5 秒空转之后加的）
        //
        // 同一个线程**已经在用端口**（典型：`Stream` 会话还没 `close()`）却又调 `exec`：
        //   · `procLock` 是 `ReentrantLock` ⇒ **同线程可重入，会"成功"**
        //   · 但**文件锁不行** —— 同一个 JVM 里第二条通道拿同一个文件会抛
        //     `OverlappingFileLockException` ⇒ 一直拿不到 ⇒ **空转到超时**
        // ⇒ 结果是**静默空转**（实机实测 **6.5 秒**），而不是一条看得懂的错误。
        //
        // ⇒ 立刻拒绝、把原因写清楚。
        //   ⚠️ 这也是**唯一正确**的语义：此刻设备已被那条流开着，
        //      嵌套 `exec` 就算放行，`claimInterface` 也会 EBUSY。
        if (procLock.isHeldByCurrentThread) {
            Log.w(TAG, "★ 拒绝自嵌套：本线程已持有端口（$holder 又想用）⇒ 请改用已开的 Stream")
            return Locked.Denied(HOLDER_SELF_NESTED)
        }

        val got = try {
            if (waitMs <= 0L) procLock.tryLock() else procLock.tryLock(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!got) return Locked.Denied(readHolder(ctx))

        var lease: Lease? = null
        try {
            val raf = RandomAccessFile(lockFile(ctx), "rw")
            val ch = raf.channel
            val deadline = System.currentTimeMillis() + waitMs
            var fl: FileLock? = null
            while (true) {
                fl = try {
                    ch.tryLock()
                } catch (e: OverlappingFileLockException) {
                    // 只可能发生在同进程内 —— 上面的 procLock 已经挡住了，走到这里说明有别的入口
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "文件锁异常：${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                if (fl != null) break
                if (waitMs <= 0L || System.currentTimeMillis() >= deadline) {
                    runCatching { raf.close() }
                    return Locked.Denied(readHolder(ctx))
                }
                Thread.sleep(20)
            }
            lease = Lease(raf, fl)
            writeHolder(raf, holder)
            return Locked.Got(body())
        } finally {
            lease?.release() ?: runCatching { procLock.unlock() }
        }
    }

    /**
     * 读"谁占着"。
     *
     * ★ **尽力而为，绝不阻塞** —— 拿不到锁时它常常正在被写，
     * 读出来是半行也无所谓（只是日志里的一句话）。
     */
    private fun readHolder(ctx: Context): String = try {
        val f = lockFile(ctx)
        if (!f.exists()) "?" else f.readText().trim().ifEmpty { "?" }.take(40)
    } catch (e: Exception) {
        "?"
    }

    /** 把占用者写进锁文件（**拿锁之后**才写，写完不关 —— 句柄由 [Lease] 管） */
    private fun writeHolder(raf: RandomAccessFile, holder: String) {
        runCatching {
            raf.setLength(0)
            raf.write("$holder@${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8))
            raf.channel.force(true)
        }
    }

    // ================================================================== 短开短关

    /**
     * ★★ **唯一的一条执行入口**：开 → （可选被动听）→ （可选发命令）→ 读 → 关。
     *
     * ## 为什么把"被动听"也放进来
     *
     * TNT GO 会**周期性主动推** `+BATCG=`。电量侧原来的做法是
     * "先白听 700 ms，没等到才发 `at+batcg`" —— 这一步必须在**同一次设备会话内**完成，
     * 否则两次开设备之间那段推送就漏了。
     *
     * ## ⚠️ 行为与重构前的对应关系（**刻意保持不变**）
     *
     * | 调用方 | 参数 |
     * |---|---|
     * | 电量 | `cmd="at+batcg"`, `passiveMs=700`, `readMs=1500`, `lockWaitMs=0` |
     * | 亮度 | `cmd="at+bkl..."`, `passiveMs=0`, `readMs=700`, `lockWaitMs=3000` |
     *
     * ★ **波特率回退留在调用方**，因为两边的回退语义**本来就不一样**
     * （电量侧只在"一个字节都没收到"时才试下一档；亮度侧每一档都重发命令）——
     * 揉进本函数会**悄悄改掉其中一个的行为**。
     *
     * @param stopWhen 收到匹配这段文本就提前结束（`null` = 一直读到超时）
     * @return [Result.Ok] 带原始文本；[Result.Empty] 表示一个字节都没收到
     */
    fun exec(
        ctx: Context,
        cmd: String?,
        baud: Int = 115200,
        passiveMs: Long = 0L,
        readMs: Long = 1500L,
        stopWhen: Regex? = null,
        lockWaitMs: Long = 3000L,
        holder: String = "?",
    ): Result {
        cmd?.let { c -> rejectionOf(c)?.let { return Result.Rejected(c, it) } }

        val device = findDevice(ctx) ?: return Result.NoDevice
        val usb = usbManager(ctx)
        if (!usb.hasPermission(device)) return Result.NoPermission

        return when (val locked = withPortLock(ctx, holder, lockWaitMs) {
            tryOnce(ctx, usb, device, cmd, baud, passiveMs, readMs, stopWhen)
        }) {
            is Locked.Denied -> Result.Busy(locked.holder)
            is Locked.Got -> locked.value
        }
    }

    /**
     * 开设备 → 干活 → 关设备。
     *
     * ★ **每次都是短开短关** —— 别的 app（含系统那一侧）也可能碰这个接口，
     * 长时间占着是不礼貌的。唯一的例外是无极调节的 [Stream]（那是有意为之）。
     */
    private fun tryOnce(
        ctx: Context,
        usb: UsbManager,
        device: UsbDevice,
        cmd: String?,
        baud: Int,
        passiveMs: Long,
        readMs: Long,
        stopWhen: Regex?,
    ): Result {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: return Result.Failed("没有匹配的 CDC-ACM 驱动")
        val connection = usb.openDevice(device)
            ?: return Result.Failed("openDevice 失败（设备可能被别的 app 占着）")
        val port = driver.ports.firstOrNull()
            ?: run { runCatching { connection.close() }; return Result.Failed("驱动没给出可用端口") }

        return try {
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            val buf = ByteArray(256)
            val sb = StringBuilder()

            // ① 先抽掉开设备前后积压的东西，否则会把【上一次的回应/旧推送】当成这一次的
            runCatching { while (port.read(buf, 10) > 0) { /* drain */ } }

            // ② 被动听（TNT GO 周期性主动推）
            if (passiveMs > 0L) {
                val deadline = System.currentTimeMillis() + passiveMs
                while (System.currentTimeMillis() < deadline) {
                    if (stopWhen != null && stopWhen.containsMatchIn(sb)) break
                    val n = port.read(buf, 120)
                    if (n > 0) sb.append(String(buf, 0, n, Charsets.US_ASCII))
                }
            }

            // ③ 主动问（★ 只有"没拿到目标"才问 —— 被动已经拿到了就不打扰设备）
            val already = stopWhen != null && stopWhen.containsMatchIn(sb)
            if (cmd != null && !already) {
                port.write("$cmd\r\n".toByteArray(Charsets.US_ASCII), 1000)
                lastCommand = cmd          // ★ 记"真发出去的"，不是"打算发的"
                val deadline = System.currentTimeMillis() + readMs
                while (System.currentTimeMillis() < deadline) {
                    if (stopWhen != null && stopWhen.containsMatchIn(sb)) break
                    val n = port.read(buf, 120)
                    if (n > 0) sb.append(String(buf, 0, n, Charsets.US_ASCII))
                }
            }

            Log.d(TAG, "@$baud [${cmd ?: "(被动)"}] raw=${sb.toString().take(120).replace("\n", "\\n")}")
            if (sb.isEmpty()) Result.Empty else Result.Ok(sb.toString())
        } catch (e: Exception) {
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { port.close() }
            runCatching { connection.close() }
        }
    }

    /**
     * ★ **最近一次真正发出去的 AT 命令**（原样）。
     *
     * 存在的唯一理由：**日志必须打印"实际发了什么"，而不是"打算发什么"**。
     * 亮度侧曾经因为打印**意图值**而制造出一个假阳性
     * （以为"设备把越界值夹到了 1000"，其实是本函数自己先 clamp 了）。
     */
    @Volatile
    var lastCommand: String = ""
        private set

    // ================================================================== 连续写入会话

    /** [beginStream] 的结果 */
    sealed class StreamResult {
        data class Ok(val stream: Stream) : StreamResult()
        object NoDevice : StreamResult()
        object NoPermission : StreamResult()
        data class Busy(val holder: String) : StreamResult()
        data class Failed(val why: String) : StreamResult()
    }

    /**
     * ★★ 打开一个**连续写入会话**（无极调节专用）。
     *
     * ## 为什么需要它
     *
     * 普通的 [exec] 每次都是"开设备 → 写 → 读 → 关"，实测一次 **~43 ms**
     * ⇒ 无极调节的发送帧率被卡在 **~23 Hz**，速度一快每一下就是一大跳
     * —— 这正是用户观察到的"微小段落感"。
     *
     * ⚠️ **整个会话期间持有端口锁** —— 用完**必须** [Stream.close]，
     * 否则电量侧会一直让路（它不会阻塞，只是每轮都跳过 ⇒ 学习停摆）。
     */
    fun beginStream(ctx: Context, lockWaitMs: Long = 3000L, holder: String = "brightness-stream"): StreamResult {
        val device = findDevice(ctx) ?: return StreamResult.NoDevice
        val usb = usbManager(ctx)
        if (!usb.hasPermission(device)) return StreamResult.NoPermission

        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: return StreamResult.Failed("没有匹配的 CDC-ACM 驱动")
        val connection = usb.openDevice(device)
            ?: return StreamResult.Failed("openDevice 失败（设备可能被别的 app 占着）")
        val port = driver.ports.firstOrNull()
            ?: run { runCatching { connection.close() }; return StreamResult.Failed("驱动没给出可用端口") }

        // ★ 先开好设备，再取锁 —— 反过来会让"取到锁但设备开不了"这种情形更难排查
        try {
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { val b = ByteArray(256); while (port.read(b, 10) > 0) { } }
        } catch (e: Exception) {
            runCatching { port.close() }
            runCatching { connection.close() }
            return StreamResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        }

        val lease: Lease
        when (val locked = acquireLease(ctx, holder, lockWaitMs)) {
            is Locked.Denied -> {
                runCatching { port.close() }
                runCatching { connection.close() }
                return StreamResult.Busy(locked.holder)
            }
            is Locked.Got -> lease = locked.value
        }
        return StreamResult.Ok(Stream(connection, port, lease))
    }

    /** 与 [withPortLock] 同源，但**把租约交出去**（由 [Stream] 在 close 时释放） */
    private fun acquireLease(ctx: Context, holder: String, waitMs: Long): Locked<Lease> {
        // ★ 同 [withPortLock] 的自嵌套守卫 —— 已经开着一条流还想再开一条，同样立刻拒绝
        if (procLock.isHeldByCurrentThread) {
            Log.w(TAG, "★ 拒绝自嵌套：本线程已持有端口（$holder 又想开一条流）")
            return Locked.Denied(HOLDER_SELF_NESTED)
        }
        val got = try {
            if (waitMs <= 0L) procLock.tryLock() else procLock.tryLock(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!got) return Locked.Denied(readHolder(ctx))

        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(lockFile(ctx), "rw")
            val ch = raf.channel
            val deadline = System.currentTimeMillis() + waitMs
            var fl: FileLock? = null
            while (true) {
                fl = try { ch.tryLock() } catch (e: OverlappingFileLockException) { null } catch (e: Exception) { null }
                if (fl != null) break
                if (waitMs <= 0L || System.currentTimeMillis() >= deadline) {
                    runCatching { raf.close() }
                    procLock.unlock()
                    return Locked.Denied(readHolder(ctx))
                }
                Thread.sleep(20)
            }
            writeHolder(raf, holder)
            return Locked.Got(Lease(raf, fl))
        } catch (e: Exception) {
            runCatching { raf?.close() }
            procLock.unlock()
            return Locked.Denied(readHolder(ctx))
        }
    }

    /**
     * 连续写入会话 —— **只 write，不逐次等回显**。
     *
     * ⚠️ 回显处理：MCU 对每条命令都会 echo（约 10 字节），**不排空会积压**。
     * 但**每次写完都排空太贵** —— `read()` 有最小超时，实测把帧率从 ~90 Hz 压到 **~41 Hz**。
     * ⇒ 按时间节流抽干（见 [DRAIN_EVERY_MS]）。
     *
     * ★ 构造器是 **`internal`**（**不是 `private`**）—— 两个原因：
     *   ① 只允许 [beginStream] 造它 —— 它必须**同时**拿到设备与端口锁，缺一不可；
     *   ② ★★ **Kotlin 里嵌套类的 `private` 构造器，外层 `object` 是访问不到的**
     *      （与 Java 的内部类不同 —— Java 里外层能访问）⇒ 写 `private` 会报
     *      *"Cannot access '<init>': it is private in 'Stream'"*（**本轮已踩**）。
     *      所以构造器只能放到 `internal`，并相应地把 [Lease] 也提到 `internal`。
     *      对外依然不可见：消费者只看得到 [StreamResult.Ok] 里的 `Stream`。
     *
     * ★ 它**不是 `inner`** —— `TntgoSerial` 是 `object`（单例），
     *   `object` 里没有"外部实例"这回事，`inner` 在这里**编译不过**（**本轮已踩**）。
     *   而它需要的 [isAllowed] / [DRAIN_EVERY_MS] 都是 `object` 自己的成员，
     *   **嵌套类本来就能访问** ⇒ 不需要 `inner`。
     */
    class Stream internal constructor(
        private val connection: UsbDeviceConnection,
        private val port: UsbSerialPort,
        private val lease: Lease,
    ) {
        private val buf = ByteArray(256)
        private var closed = false
        private var lastDrain = 0L

        /** 真正写出去过多少次 */
        var writes: Int = 0
            private set

        /** 最近一次真正写出去的值 */
        var lastWritten: Int = -1
            private set

        /** 写一个 MCU 值。**不等回显**。 */
        fun write(mcu: Int): Boolean {
            if (closed) return false
            if (!isAllowed("at+bkl=$mcu")) {
                Log.w(TAG, "流式写入被白名单拒绝：at+bkl=$mcu")
                return false
            }
            return try {
                port.write("at+bkl=$mcu\r\n".toByteArray(Charsets.US_ASCII), 120)
                lastCommand = "at+bkl=$mcu"
                lastWritten = mcu
                writes++
                maybeDrain()
                true
            } catch (e: Exception) {
                Log.w(TAG, "流式写入失败（第 $writes 次）：${e.message}")
                false
            }
        }

        /** 按时间节流地抽干回显 */
        private fun maybeDrain() {
            val now = SystemClock.uptimeMillis()
            if (now - lastDrain < DRAIN_EVERY_MS) return
            lastDrain = now
            runCatching { while (port.read(buf, 2) > 0) { /* discard echo */ } }
        }

        /**
         * ★ **结束时回读一次**确认设备真的停在目标值上。
         * （流式期间没有逐次校验，所以这一步不能省。）
         *
         * @return 设备报回来的 MCU 值；失败返回 `null`
         */
        fun verify(readMs: Long = 700L): Int? {
            if (closed) return null
            return try {
                val sb = StringBuilder()
                runCatching { while (port.read(buf, 2) > 0) { } }   // 清空积压
                port.write("at+bkl\r\n".toByteArray(Charsets.US_ASCII), 200)
                lastCommand = "at+bkl"
                val deadline = System.currentTimeMillis() + readMs
                while (System.currentTimeMillis() < deadline && !sb.contains("+BKL=")) {
                    val n = port.read(buf, 120)
                    if (n > 0) sb.append(String(buf, 0, n, Charsets.US_ASCII))
                }
                BKL.find(sb.toString())?.groupValues?.get(1)?.toIntOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "回读失败：${e.message}")
                null
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { port.close() }
            runCatching { connection.close() }
            lease.release()
            Log.i(TAG, "流式会话结束：共写入 $writes 次，末值 $lastWritten")
        }
    }

    // ================================================================== 协议片段

    /** `+BKL=<n>` */
    val BKL = Regex("""\+BKL=(\d+)""")

    /** 设备拒绝一个值时的回应：`+ERROR=<code>` */
    val ERROR = Regex("""\+ERROR=(\d+)""")

    /** ★ F9：第 5 组（温度）原来是 `(\d+)` —— **负数匹配不上**；实测全为正但语义上不该假设 */
    val BATCG = Regex("""\+BATCG=(\d+),(\d+),(-?\d+),(-?\d+),(-?\d+),(\d+)""")

    /** 流式写入期间，多久抽干一次回显（ms） */
    private const val DRAIN_EVERY_MS = 100L
}
