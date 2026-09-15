package com.shware.mode.mod.brightness

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shware.mode.tntgoserial.TntgoState
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToInt

/**
 * ★★ 亮度状态机 + 串口写队列 + **长按无极调节引擎**。
 *
 * 两个 service 共用它（同一个 APK ⇒ 同一个进程 ⇒ 天然共享）：
 * - [KeyFilterService] —— **按键来源**（无障碍按键过滤器）
 * - [BrightnessModService] —— **显示 + 生命周期**（TNT 屏上的 OSD 卡片）
 *
 * ## ★★★ 交互模型（任务 AJ，按用户 2026-09-13 的规格）
 *
 * ```
 * DOWN ──┬─ ★ 立即走【短按步进】ui ±= STEP   ← 保留段落感
 *        └─ 起定时器（默认 200ms，可调）
 *              ├─ 期间收到 UP          ⇒ 短按，结束
 *              └─ 到点仍是按下状态     ⇒ ★ 进入【长按无极】
 *                                       从这一刻起按【时间】线性匀速调节
 * UP ──── 结束
 * ```
 *
 * ### 实测依据（AJ1，2026-09-13）
 * 按住时 `event12` **一个事件都不发**（只有按下/松开各一个），
 * Android 侧 `repeatCount` **恒为 0** ⇒ ★ **存在真正的「持续按下」状态，且没有自动重复需要过滤**。
 *
 * ## ★★★ 无极调节为什么要"按时间反算"而不是"每次加固定值"
 *
 * 一次 `AT+BKL=` 是 **开→写→读→关**，实测每次 **~40–50ms**，且**会抖动**。
 * 若按"每次发送加固定量"，实际速度就会随串口抖动而变 —— **那就不是线性了**。
 *
 * ⇒ 每次真正要发送时，都用**从 ramp 起点的经过时间**反算目标值：
 * ```
 * target = clamp(起点 + 方向 · 速度 · (now − 起点时刻)/1000)
 * ```
 * ⇒ **发送快慢只影响平滑度，不影响速度与线性度。**（用户明确要求"保持线性，不要加速"）
 */
object BrightnessCore {

    private const val TAG = "ModeMod/Bright"

    /** 卡片在最后一次按键之后还显示多久 */
    const val OSD_HOLD_MS = 2500L

    /** 无极调节的 tick 间隔（ms）。
     *  ★ 流式模式下每次只是 `write()` + 排空回显（亚毫秒级）⇒ 这里的 10ms 是**目标帧率上限 ~100 Hz**。
     *  ⚠️ 退化模式（流式开不起来）下受串口 ~43ms 往返限制，实际仍约 23 Hz。 */
    private const val STREAM_TICK_MS = 6L

    /** 状态分类 —— **卡片上要能一眼区分这几种"没生效"** */
    enum class Health { UNKNOWN, PENDING, OK, NO_DEVICE, NO_PERMISSION, BUSY, FAILED }

    data class State(
        val ui: Int?,
        val mcu: Int?,
        val health: Health,
        val note: String,
        val keyCount: Int,
        val a11yConnected: Boolean,
        /** ★ 是否正在长按无极调节（卡片据此保持显示、不自动隐藏） */
        val ramping: Boolean,
    )

    interface Listener {
        fun onBrightnessState(s: State)
    }

    // ------------------------------------------------------------------ 串口命令（★ 用密封类，别再拿整数当哨兵）

    private sealed class Cmd {
        /** 查询当前值（裸发 `AT+BKL`） */
        object Probe : Cmd()

        /** 设 UI 亮度（走出厂曲线，clamp 到量程内） */
        data class Ui(val v: Int) : Cmd()

        /** ★ 直接设 MCU 值并 clamp 到 `[MCU_MIN, MCU_MAX]` —— **无极调节专用** */
        data class Mcu(val v: Int) : Cmd()

        /** 设原始 MCU 值**不 clamp** —— **量程对账专用**（详见 [TntgoBkl.setRawMcu]） */
        data class RawMcu(val v: Int) : Cmd()
    }

    @Volatile private var appCtx: Context? = null
    @Volatile private var bkl: TntgoBkl? = null

    @Volatile var ui: Int? = null
        private set

    @Volatile var mcu: Int? = null
        private set

    @Volatile var health: Health = Health.UNKNOWN
        private set

    @Volatile var note: String = "启动中…"
        private set

    @Volatile var keyCount: Int = 0
        private set

    @Volatile var a11yConnected: Boolean = false
        private set

    @Volatile var ramping: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** ★ 容量 1 —— 队列里永远只有"最新目标"（旧的被 poll 掉） */
    private val queue = ArrayBlockingQueue<Cmd>(1)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tntgo-bkl").apply { isDaemon = true }
    }

    /** 按键状态机 / 无极调节的定时器都跑在这条线程上 */
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tntgo-keysm").apply { isDaemon = true }
        }

    @Volatile private var workerStarted = false

    // ------------------------------------------------------------------ 按键状态机

    @Volatile private var pressedDir = 0          // 0 = 未按下；+1 = 调大；-1 = 调小
    private var pressedAt = 0L

    private var longPressFuture: ScheduledFuture<*>? = null

    /** ★ 无极调节专用线程（它在整段长按期间**持有串口**） */
    @Volatile private var rampThread: Thread? = null

    /**
     * ★★ **串口互斥锁** —— 保证「流式会话」与「逐次开关」不会同时碰同一个 USB 设备。
     *
     * USB 设备是**独占**的（`claimInterface` 撞上会 EBUSY）⇒ 两边必须串行。
     */
    private val serialLock = ReentrantLock()

    // ------------------------------------------------------------------ 生命周期

    fun attach(ctx: Context) {
        if (appCtx == null) {
            appCtx = ctx.applicationContext
            bkl = TntgoBkl(ctx.applicationContext)
            ensureWorker()
        }
    }

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }

    fun removeListener(l: Listener) { listeners.remove(l) }

    fun current(): State = State(ui, mcu, health, note, keyCount, a11yConnected, ramping)

    fun setA11y(connected: Boolean) {
        a11yConnected = connected
        if (connected) {
            Log.i(TAG, "★ 无障碍按键过滤器已连接")
            if (health == Health.UNKNOWN) { note = "已连接，等待按键"; health = Health.PENDING }
            // ★ 连上就顺手查一次当前值 —— 否则"第一次按键"会从一个瞎猜的值起步
            refresh()
        } else {
            Log.w(TAG, "无障碍按键过滤器断开")
            // ⚠️ 断开时可能正按着键 —— 必须收尾，否则 ramp 会一直跑
            abortPress("无障碍服务断开")
            if (ui == null) { health = Health.FAILED; note = "无障碍服务未启用（设置 → 无障碍）" }
        }
        notifyListeners()
    }

    // ------------------------------------------------------------------ ★ 按键入口

    /**
     * 第一次按下（`ACTION_DOWN`，且当前不在按下状态）。
     *
     * ★ **立刻走一次短按步进**（保留段落感），然后安排"长按判定"。
     *
     * @param dir +1 = 调大 / -1 = 调小
     */
    fun onKeyDown(ctx: Context, dir: Int) {
        attach(ctx)
        keyCount++

        if (pressedDir != 0) {
            // 防御：上一次还没收到 UP（不该发生）。如实记，并按新的一次算
            Log.w(TAG, "⚠ 收到重复 DOWN（上一次未收到 UP，dir=$pressedDir）—— 先收尾再重算")
            abortPress("重复 DOWN")
        }

        pressedDir = dir
        pressedAt = SystemClock.uptimeMillis()

        // ① ★ 短按步进：立即执行
        val step = Prefs.step(ctx)
        if (step > 0) {
            val cur = ui ?: 50
            val next = (cur + dir * step).coerceIn(0, 100)
            ui = next
            health = Health.PENDING
            note = "→ MCU ${TntgoBkl.uiToMcu(next)}（串口中…）"
            Log.i(TAG, "★ 短按步进：$cur% → $next%  (MCU ${TntgoBkl.uiToMcu(next)})")
            notifyListeners()
            push(Cmd.Ui(next))
        } else {
            // STEP=0 ⇒ 短按不跳档。此时短按将"什么都不做"，如实告知
            Log.i(TAG, "按下（步进=0，短按不改变亮度）")
        }

        // ② 长按判定
        val longMs = Prefs.longPressMs(ctx).toLong()
        longPressFuture?.cancel(false)
        longPressFuture = scheduler.schedule(
            { beginRamp(ctx, dir) }, longMs, TimeUnit.MILLISECONDS
        )
        Log.i(TAG, "  └ ${longMs}ms 后若仍按着 ⇒ 进入无极调节")
    }

    /** 松开（`ACTION_UP`）—— 结束短按或长按 */
    fun onKeyUp() {
        if (pressedDir == 0) return
        val heldMs = SystemClock.uptimeMillis() - pressedAt
        val wasRamping = ramping
        abortPress(if (wasRamping) "长按结束" else "短按结束")
        Log.i(
            TAG,
            if (wasRamping) "★ 松开：长按结束（按住 ${heldMs}ms）" else "松开：短按（按住 ${heldMs}ms）"
        )
        if (wasRamping) {
            health = Health.OK
            note = "长按结束（按住 ${heldMs}ms）"
            notifyListeners()
        }
    }

    /** 收尾：取消定时器、停 ramp、清按下状态。**任何异常路径都必须调它** */
    private fun abortPress(why: String) {
        longPressFuture?.cancel(false); longPressFuture = null
        if (ramping) Log.i(TAG, "⏹ 无极调节停止（$why）")
        // ★ 靠标志让 ramp 线程自己退出 —— **不 interrupt**：
        //   USB 写操作被打断可能把设备留在半途状态，让它写完当前这一条再收尾更稳。
        ramping = false
        pressedDir = 0
    }

    // ------------------------------------------------------------------ ★ 无极调节

    private fun beginRamp(ctx: Context, dir: Int) {
        // 期间已松开 / 换了方向 ⇒ 不是长按
        if (pressedDir != dir) {
            Log.d(TAG, "长按判定到点，但按下状态已结束（dir 现为 $pressedDir）⇒ 判为短按")
            return
        }

        val dom = Prefs.domain(ctx)
        val rate = Prefs.rate(ctx).toFloat()
        /**
         * ★★ **速率的口径必须统一**（2026-09-13 实测踩到）。
         *
         * 设置值**永远以 `BKL/秒` 为准**（用户的原始说法）。
         * 但两个域的**满量程长度差 20 倍**：
         * - BKL 域：9→2000 = **1991** 个单位
         * - UI  域：0→100  = **100**  个单位
         *
         * ⇒ 若把同一个数字直接用在 UI 域，`600` 就变成"每秒走 6 遍全量程"
         *   （实测：**0.2 秒**从 90% 冲到最低，完全没法用）。
         *
         * ⇒ UI 域的速率**按"同样的满量程用时"换算**：
         * ```
         * uiRate = rate · 100 / 1991
         * ```
         * 这样 `rate=600` 在两个域里都表示"**满量程约 3.3 秒**"，手感一致。
         */
        val uiRate = rate * 100f / (TntgoBkl.MCU_MAX - TntgoBkl.MCU_MIN).toFloat()
        val t0 = SystemClock.uptimeMillis()
        val fromUi = (ui ?: 50).toFloat()
        val fromMcu = (mcu ?: TntgoBkl.uiToMcu(fromUi.roundToInt())).toFloat()

        ramping = true
        health = Health.PENDING
        val fullSweepSec = (TntgoBkl.MCU_MAX - TntgoBkl.MCU_MIN) / rate
        note = "长按无极调节：${if (dom == Prefs.DOMAIN_UI) "感知(UI)" else "BKL"} " +
                "${rate.roundToInt()} BKL/秒（满量程约 ${"%.1f".format(fullSweepSec)} 秒）"
        Log.i(
            TAG,
            "★★ 进入长按无极调节：线性域=$dom  速度=${rate.roundToInt()} BKL/秒" +
                    "（UI 域换算为 ${"%.1f".format(uiRate)} UI/秒，满量程约 " +
                    "${"%.1f".format(fullSweepSec)} 秒）  起点 ui=${fromUi.roundToInt()} mcu=${fromMcu.roundToInt()}"
        )
        notifyListeners()

        // ★★ 起一条**专用 ramp 线程**：它在整段长按期间**把串口一直开着**（见 [TntgoBkl.beginStream]），
        //    这样才能把发送帧率从 ~23 Hz 提上去，消掉"微小段落感"。
        val th = Thread(
            { runRamp(dom, rate, uiRate, t0, fromUi, fromMcu, dir) },
            "tntgo-ramp"
        ).apply { isDaemon = true }
        rampThread = th
        th.start()
    }

    /**
     * ★★★ 无极调节主循环（跑在专用线程上）。
     *
     * ## 为什么要有这条线程（用户 2026-09-13 反馈："无极调节过程中依然存在微小的段落感"）
     *
     * 他的推断是对的：**"帧率的有限"与"调节报送的无限"起了冲突**。
     *
     * 旧实现的每一次发送都是 **开设备→写→读→关 ≈ 43ms** ⇒ **帧率被卡在 ~23 Hz**，
     * 而目标值是按时间连续算的 ⇒ **每 43ms 才动一下，速度一快每一下就是一大跳**。
     *
     * ⇒ 本线程**全程持有串口**，每次只 `write()`（亚毫秒级）+ 排空回显
     * ⇒ 帧率上一个数量级，段落感随之消失。
     *
     * **代价（都如实处理）**：
     * - 期间**占着 USB 设备** ⇒ 电量 mod 的轮询会失败（已有 `Busy` 分支，**不静默**）
     * - 不再逐次回读 ⇒ **结束时 `verify()` 回读一次**确认
     * - 流式开不起来 ⇒ **退化为逐次开关**（23 Hz，段落感回来但功能不受影响）
     */
    private fun runRamp(
        dom: String,
        rate: Float,
        uiRate: Float,
        t0: Long,
        fromUi: Float,
        fromMcu: Float,
        dir: Int,
    ) {
        val b = bkl ?: return
        serialLock.lock()
        var stream: TntgoBkl.Stream? = null
        try {
            when (val sr = b.beginStream()) {
                is TntgoBkl.StreamResult.Ok -> {
                    stream = sr.stream
                    Log.i(TAG, "★★ 流式会话已开 —— 期间【只 write + 排空回显】，不再逐次开关设备")
                }
                else -> Log.w(
                    TAG,
                    "⚠ 流式会话开不起来（$sr）⇒ 退化为逐次开关（~23 Hz，会有轻微段落感）"
                )
            }

            var lastSent = Int.MIN_VALUE
            var frames = 0
            var lastNotify = 0L
            val statStart = SystemClock.uptimeMillis()

            while (ramping && pressedDir == dir) {
                val dt = (SystemClock.uptimeMillis() - t0) / 1000f
                val target = if (dom == Prefs.DOMAIN_UI) {
                    // ★ 用【浮点】UI 查曲线（不要先取整！否则帧率被 UI 量化卡死 —— 见 uiToMcuF 注释）
                    TntgoBkl.uiToMcuF(fromUi + dir * uiRate * dt)
                } else {
                    (fromMcu + dir * rate * dt).roundToInt()
                        .coerceIn(TntgoBkl.MCU_MIN, TntgoBkl.MCU_MAX)
                }

                if (target != lastSent) {
                    val ok = if (stream != null) {
                        stream.write(target)
                    } else {
                        b.setMcuClamped(target) is TntgoBkl.Result.Ok
                    }
                    if (ok) {
                        lastSent = target
                        frames++
                        mcu = target
                        ui = TntgoBkl.mcuToUi(target)
                    }
                    // ★ 通知要节流 —— 否则 100 Hz 地刷 UI 会把主线程压垮
                    val now = SystemClock.uptimeMillis()
                    if (now - lastNotify >= 100) { lastNotify = now; notifyListeners() }
                }
                Thread.sleep(STREAM_TICK_MS)
            }

            val elapsed = (SystemClock.uptimeMillis() - statStart) / 1000f
            if (elapsed > 0.05f) {
                Log.i(
                    TAG,
                    "★ 流式统计：$frames 次写入 / ${"%.2f".format(elapsed)}s" +
                            " = ${"%.0f".format(frames / elapsed)} Hz" +
                            (if (stream == null) "（退化模式）" else "（全速流式）")
                )
            }

            // ★ 结束时回读一次 —— 流式期间没有逐次校验，这一步不能省
            if (stream != null) {
                when (val r = stream.verify()) {
                    is TntgoBkl.Result.Ok -> {
                        if (lastSent >= 0 && r.mcu != lastSent) {
                            // ★★ 兜底自愈：流式期间没逐次校验，万一有命令被丢了/截断了，
                            //    这里补发一次绝对目标值（`AT+BKL` 是**绝对值**不是增量 ⇒ 补发即纠正）
                            //
                            // ★★★ AS3b 修正（2026-09-15 实机抓到）：**经手上这条流补发**。
                            //
                            // ⚠️ 这里原来写的是 `b.setMcuClamped(lastSent)` —— 它走
                            //    `TntgoSerial.exec` ⇒ 要**取那把跨进程端口锁**；
                            //    而**本函数手里的 `stream` 正持有那把锁**（要到 finally 才释放）
                            //    ⇒ **自己等自己**：`procLock` 可重入所以"通过"，
                            //      但同 JVM 第二条通道拿同一个文件会抛
                            //      `OverlappingFileLockException` ⇒ 空转到超时。
                            //
                            //    实机证据：`第 1 次失败（端口被「brightness-stream@…」占着）`
                            //    ⇒ 补发整整等了 **6.5 秒**（3 次 × lockWaitMs 3000），
                            //      期间用户按什么都没反应。
                            //
                            // ★ 而拿**已开的流**补发本来就更对：端口就在我们手上，直接写。
                            //   性质不变（`AT+BKL` 是绝对值 ⇒ 补发即纠正）。
                            Log.w(TAG, "⚠ 结束回读 ${r.mcu} ≠ 目标 $lastSent ⇒ 经同一条流补发")
                            val wrote = stream.write(lastSent)
                            val back = if (wrote) stream.verify() else null
                            val fixed = (back as? TntgoBkl.Result.Ok)?.mcu ?: r.mcu
                            mcu = fixed
                            ui = TntgoBkl.mcuToUi(fixed)
                            health = Health.OK
                            note = "长按结束 → 回读 ${r.mcu} 与目标不符，已补发 ⇒ 现为 +BKL=$fixed"
                            Log.i(TAG, "✓ 补发后 +BKL=$fixed")
                        } else {
                            mcu = r.mcu
                            ui = TntgoBkl.mcuToUi(r.mcu)
                            health = Health.OK
                            note = "长按结束 → 回读 +BKL=${r.mcu}"
                            Log.i(TAG, "✓ 结束回读 +BKL=${r.mcu}")
                        }
                    }
                    else -> {
                        health = Health.FAILED
                        note = "长按结束但回读失败：$r"
                        Log.w(TAG, note)
                    }
                }
                notifyListeners()
            }
        } catch (e: Exception) {
            Log.e(TAG, "ramp 线程异常", e)
        } finally {
            stream?.close()
            serialLock.unlock()
        }
    }

    // ------------------------------------------------------------------ 手动入口（设置界面用）

    /** 按一次键（供设置界面的「暗一档 / 亮一档」按钮复用短按逻辑） */
    fun nudge(ctx: Context, delta: Int) {
        attach(ctx)
        val step = Prefs.step(ctx).coerceAtLeast(1)
        val cur = ui ?: 50
        val next = (cur + delta * step).coerceIn(0, 100)
        ui = next
        health = Health.PENDING
        note = "→ MCU ${TntgoBkl.uiToMcu(next)}（串口中…）"
        Log.i(TAG, "手动步进：$cur% → $next%")
        notifyListeners()
        push(Cmd.Ui(next))
    }

    /** 重新查询设备当前值（裸发 `AT+BKL`，安全） */
    fun refresh() {
        ensureWorker()
        push(Cmd.Probe)
        Log.i(TAG, "→ 查询当前亮度")
    }

    /** 直接设原始 MCU 值（**量程对账**用，不 clamp） */
    fun setRawMcu(value: Int) {
        ensureWorker()
        push(Cmd.RawMcu(value))
    }

    // ------------------------------------------------------------------ 串口线程

    /** ★ 只保留最新目标：先清空再放 */
    private fun push(c: Cmd) {
        ensureWorker()
        queue.poll()
        queue.offer(c)
    }

    private fun ensureWorker() {
        if (workerStarted) return
        synchronized(this) {
            if (workerStarted) return
            workerStarted = true
        }
        worker.execute {
            while (true) {
                val first = try { queue.take() } catch (e: InterruptedException) { return@execute }
                var latest: Cmd = first
                // ★ 合并：把积压的都吃掉，只留最后一个
                while (true) {
                    val n = queue.poll() ?: break
                    latest = n
                }
                runCatching { applyBlocking(latest) }
                    .onFailure { Log.e(TAG, "串口线程异常", it) }
            }
        }
    }

    private fun applyBlocking(cmd: Cmd) {
        val b = bkl ?: return
        // ★ 与 ramp 线程串行 —— USB 设备是独占的，不能两边同时碰
        serialLock.lock()
        try {
            applyBlockingLocked(b, cmd)
        } finally {
            serialLock.unlock()
        }
    }

    private fun applyBlockingLocked(b: TntgoBkl, cmd: Cmd) {
        val result = when (cmd) {
            Cmd.Probe -> b.query()
            is Cmd.Ui -> b.setUi(cmd.v)
            is Cmd.Mcu -> b.setMcuClamped(cmd.v)
            is Cmd.RawMcu -> b.setRawMcu(cmd.v)
        }

        when (result) {
            is TntgoBkl.Result.Ok -> {
                mcu = result.mcu
                when (cmd) {
                    Cmd.Probe -> {
                        // 查询回来 ⇒ 反推当前 UI，**这是唯一可信的当前值来源**
                        ui = TntgoBkl.mcuToUi(result.mcu)
                        health = Health.OK
                        note = "已读到当前值"
                    }
                    is Cmd.RawMcu -> {
                        health = Health.OK
                        note = "已设 MCU ${cmd.v} → 回读 ${result.mcu}"
                    }
                    is Cmd.Mcu -> {
                        // ★ 无极调节路径：**不要把 note 刷得太勤**，否则卡片一直在跳
                        ui = TntgoBkl.mcuToUi(result.mcu)
                        health = Health.OK
                        if (!ramping) note = "AT+BKL=${cmd.v} → 回读 +BKL=${result.mcu}"
                    }
                    is Cmd.Ui -> {
                        ui = cmd.v
                        health = Health.OK
                        note = "AT+BKL=${TntgoBkl.uiToMcu(cmd.v)} → 回读 +BKL=${result.mcu}"
                    }
                }
                Log.d(TAG, "✓ $note")
            }

            TntgoBkl.Result.NoDevice -> {
                health = Health.NO_DEVICE
                note = "没找到 TNT GO（VID 0x31CE / PID 0x5101）"
                Log.w(TAG, note)
            }

            TntgoBkl.Result.NoPermission -> {
                health = Health.NO_PERMISSION
                note = "等 USB 授权 —— 请点系统弹窗的「允许」（勾「默认」以后不再问）"
                Log.w(TAG, note)
            }

            is TntgoBkl.Result.Rejected -> {
                health = Health.FAILED
                note = "★ 设备拒绝了这个值（+ERROR=${result.code}）—— 超出背光量程 " +
                        "${TntgoBkl.MCU_MIN}~${TntgoBkl.MCU_MAX}"
                Log.w(TAG, note)
            }

            is TntgoBkl.Result.Busy -> {
                health = Health.BUSY
                note = "串口被占（多半是电量 mod 在轮询）：${result.why}"
                Log.w(TAG, note)
            }

            is TntgoBkl.Result.Failed -> {
                health = Health.FAILED
                note = result.why
                Log.w(TAG, "✗ $note")
            }
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        val s = current()
        publishBrightness(s)
        for (l in listeners) runCatching { l.onBrightnessState(s) }
    }

    // ------------------------------------------------------------------ ★★★ AR12 亮度状态通道

    /**
     * ★★★★ **把当前亮度发布到跨 mod 状态通道**（任务 AR12）。
     *
     * 电量 mod 要拿它给功耗做上下文 ——「≈ 5.6 W **@ 亮度 60%**」。
     * 通路见 [`TntgoState`]（共享模块 `:tntgo-serial`，**原子写 ＋ 心跳**）。
     *
     * ## ★★ 红线：**不知道就不发布**
     *
     * `ui == null`（还没查过 / 查询失败）⇒ **直接 return，不写文件**。
     *
     * ⚠️ 这一条是**故意的**：写一个猜的值进去，电量侧会当成真的用，
     * 于是"亮度→功耗"曲线会**在无人察觉的情况下学歪**
     * （AR §阶段 C+ 的原话：猜错的亮度会让曲线学歪，而且**用户看不出来**）。
     * ⇒ 宁可让通道**空着**，电量侧显示「亮度未知」。
     */
    private fun publishBrightness(s: State): Boolean {
        val ctx = appCtx
        if (ctx == null) {
            lastPublishReason = "还没 attach（appCtx 为空）"
            return false
        }
        val u = s.ui
        if (u == null) {
            // ★ 红线：不知道 ⇒ 不发布。但**必须留下原因** —— 静默 return
            //   正是 2026-09-15 那次"心跳看起来在跑、实际什么都没写"的根源。
            lastPublishReason = "★ 亮度还不知道（ui=null, health=${s.health}）⇒ 按红线不发布"
            return false
        }
        val m = s.mcu ?: TntgoBkl.uiToMcu(u)
        val ok = TntgoState.publishBrightness(ctx, m, u)
        lastPublishReason =
            if (ok) "✓ 已写入（ui=$u mcu=$m）" else "✗ 写文件失败（见 ModeMod/State 的警告）"
        return ok
    }

    /** 最近一次发布的结果（诊断用；★ 心跳会把它打进日志） */
    @Volatile
    var lastPublishReason: String = "还没发布过"
        private set

    /**
     * ★★★ AR12：**重新发布一次当前亮度**（心跳用）。
     *
     * ## 为什么需要心跳，而不是"改了才写"
     *
     * 电量侧判"亮度可不可信"的依据是**时间戳新鲜度**，
     * 而**"文件很久没改" ≠ "亮度失效"** —— 用户设了 60% 两小时不动，
     * 那个 60% 依然有效。
     *
     * 真正要防的是「**亮度 mod 已经不在管这块屏了**」。
     * ⇒ 定期重发同一个值 ⇒ 时间戳不再前进就**只可能**是 mod 不在了。
     *
     * @return ★★ **真的写出去了吗**。调用方（心跳）**必须把"没写"说出来** ——
     *         ⚠️ 这个返回值是**布尔**，不是"原因字符串里有没有 ok 字样"：
     *         第一版我让调用方 `startsWith("ok")` 去判，而实际文案是 `✓ 已写入…`
     *         ⇒ **每一次成功都被打成了"没有发布"**（当场在真机日志里看到）。
     *         ★ 教训：**"状态"要作为状态返回，不要作为文案的一部分让调用方去猜。**
     */
    fun republish(): Boolean {
        val ok = publishBrightness(current())
        lastRepublishOk = ok
        return ok
    }

    /** 最近一次 [republish] 是否真的写成功（心跳日志用；与文案解耦） */
    @Volatile
    var lastRepublishOk: Boolean = false
        private set
}
