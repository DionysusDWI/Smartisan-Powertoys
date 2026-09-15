package com.shware.mode.mod.perfmode

import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * ★★★★ 性能模式引擎 —— **锁的生命周期管理**。
 *
 * ## 三条设计原则（都由任务 AK 的实测推导出来）
 *
 * ### ① ★★★ 崩溃自愈：**绝不用"永久锁"，用"短锁 + 续期"**
 *
 * `perfLockAcquire` 有个 `duration` 参数，到期自动失效。所以：
 * ```
 * 每次只申请 45 秒的锁  →  每 15 秒续期一次（先拿新的，再放旧的，无缝）
 * ```
 * ⇒ ★ **进程崩了 / 被杀了 / 忘了释放，锁最多 45 秒后自己消失** ——
 *   **不需要任何"清理残留"的逻辑**，这是最稳的自愈方式。
 *
 * （另一条路是 `setClientBinder` 注册死亡通知让服务替我们释放，
 * 但那条**没验证过**，不用未验证的东西兜底。）
 *
 * ### ② 自动过期：用户设总时长，到点必停
 *
 * ### ③ 安全兜底：续期时读温度，超限自动退出
 *
 * ★ **app 能读 `/sys/class/thermal`**（任务 T 实测：`/proc` 被关、`/sys` 开着）
 * ⇒ 温度保护不需要任何特权。
 *
 * ## ⚠️ 代价必须知道
 *
 * 抬起频率下限 = **更费电 + 更热**。本引擎用"总时长 + 温度上限"两道闸门限制它，
 * 但**代价本身没有量化过**（后续任务）。
 */
object PerfModeEngine {

    private const val TAG = "ModeMod/PerfMode"

    /** ★ 每次申请的锁时长 —— **这就是崩溃自愈的边界**（进程死了最多这么久就恢复） */
    const val LOCK_HOLD_MS = 45_000

    /** 续期间隔（要 < [LOCK_HOLD_MS]，留足余量） */
    private const val RENEW_EVERY_MS = 15_000L

    enum class State { OFF, ON, ERROR }

    @Volatile var state: State = State.OFF
        private set

    /** 当前持有的锁 handle（`-1` = 没有） */
    @Volatile var handle: Int = -1
        private set

    @Volatile var startedAt: Long = 0L
        private set

    /** 用户设定的**总到期时刻**（uptimeMillis 基准） */
    @Volatile var endsAt: Long = 0L
        private set

    /** 最近一次读到的 SoC 温度（°C），NaN = 没读到 */
    @Volatile var socTempC: Float = Float.NaN
        private set

    /** 续期次数（可用来观察引擎有没有在动） */
    @Volatile var renewCount: Int = 0
        private set

    /** ★ 当前档位（任务 AK6：均衡 / 强力） */
    @Volatile var tier: PerfLock.Tier = PerfLock.Tier.STRONG

    @Volatile var note: String = "未开启"
        private set

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "perfmode").apply { isDaemon = true }
    }

    private var renewFuture: ScheduledFuture<*>? = null

    // ------------------------------------------------------------------ 对外

    /** 现在到不到期（`Long.MAX_VALUE` = 没开） */
    fun remainingMs(): Long =
        if (state != State.ON) 0L else (endsAt - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)

    /**
     * 开启性能模式。
     *
     * @param minutes 总时长（分钟）
     * @param tempLimitC 温度上限（°C）；超过就自动停
     * @param tier ★ 档位（[PerfLock.Tier]）—— 默认 [PerfLock.Tier.STRONG]（据实测调整）
     */
    fun start(minutes: Int, tempLimitC: Float, tier: PerfLock.Tier = PerfLock.Tier.STRONG) {
        stop("重启模式")
        if (!PerfLock.available()) {
            state = State.ERROR
            note = "拿不到 vendor.perfservice（HAL 没起来？）"
            Log.w(TAG, note)
            return
        }
        this.tier = tier
        val now = android.os.SystemClock.uptimeMillis()
        startedAt = now
        endsAt = now + minutes * 60_000L
        renewCount = 0
        state = State.ON
        note = "正在开启…"
        Log.i(TAG, "★ 开启性能模式：${minutes} 分钟，温度上限 ${tempLimitC}°C，档位=${tier.label}")

        // 第一次立刻拿锁（在后台线程，binder 调用不能占主线程）
        scheduler.execute {
            if (!acquireOnce()) return@execute
            renewFuture = scheduler.scheduleWithFixedDelay(
                { tick(tempLimitC) }, RENEW_EVERY_MS, RENEW_EVERY_MS, TimeUnit.MILLISECONDS
            )
        }
    }

    /** 关闭并释放（**任何退出路径都要走这里**） */
    fun stop(why: String) {
        renewFuture?.cancel(false)
        renewFuture = null
        val h = handle
        handle = -1
        if (h >= 0) {
            // 释放也要在后台线程
            scheduler.execute { PerfLock.release(h) }
        }
        if (state == State.ON) Log.i(TAG, "★ 关闭性能模式（$why）")
        state = State.OFF
        renewCount = 0
        note = why
    }

    /** 强制释放一次（给设置界面的「立即释放」按钮 / 服务销毁时用） */
    fun releaseNow() = stop("手动释放")

    // ------------------------------------------------------------------ 内部

    /** @return 是否成功拿到锁 */
    private fun acquireOnce(): Boolean {
        val h = PerfLock.acquire(LOCK_HOLD_MS, tier.list)
        if (h < 0) {
            state = State.ERROR
            note = "拿锁失败（perfLockAcquire 返回 $h）"
            Log.w(TAG, note)
            return false
        }
        // ★ 先拿新的再放旧的 —— 中间没有"无锁"的空档
        val old = handle
        handle = h
        renewCount++
        if (old >= 0) PerfLock.release(old)
        return true
    }

    private fun tick(tempLimitC: Float) {
        if (state != State.ON) return

        // ① 到期？
        if (android.os.SystemClock.uptimeMillis() >= endsAt) {
            Log.i(TAG, "⏰ 到期，自动关闭")
            stop("已到期（自动关闭）")
            return
        }

        // ② 温度？
        val t = readSocTempC()
        socTempC = t
        if (!t.isNaN() && t > tempLimitC) {
            Log.w(TAG, "🔥 温度 ${"%.1f".format(t)}°C > 上限 ${tempLimitC}°C ⇒ 自动关闭")
            stop("过热保护（${"%.0f".format(t)}°C）")
            return
        }

        // ③ 续期
        val ok = acquireOnce()
        note = if (ok) {
            "续期中（第 $renewCount 次）" + if (!t.isNaN()) " · ${"%.1f".format(t)}°C" else ""
        } else {
            "续期失败"
        }
    }

    // ------------------------------------------------------------------ 温度

    /**
     * 读 SoC 温度（°C）。
     *
     * ★ **app 能读 `/sys/class/thermal`**（任务 T 实测）。
     *
     * ## 两个必须过滤的坑（任务 T 踩过）
     * 1. **按量纲过滤**：该目录**混着非温度节点**（例如 `soc = 93` 不是温度）
     *    ⇒ 只接受 **1000..200000**（即 0.001 °C 的 1–200 °C）
     * 2. ★ **`lmh-*` 是【限值】不是传感器** —— 恒定 75000，**会把真实温度全盖掉**
     *    ⇒ 名字以 `lmh` 开头的**直接排除**
     */
    private fun readSocTempC(): Float {
        var max = Float.NaN
        try {
            val dir = File("/sys/class/thermal")
            val zones = dir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return Float.NaN
            for (z in zones) {
                val name = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: continue
                if (name.startsWith("lmh")) continue          // ★ 限值，不是传感器
                val raw = runCatching { File(z, "temp").readText().trim().toInt() }.getOrNull() ?: continue
                if (raw < 1000 || raw > 200000) continue      // ★ 量纲过滤
                val c = raw / 1000f
                if (max.isNaN() || c > max) max = c
            }
        } catch (t: Throwable) {
            Log.d(TAG, "读温度失败：${t.message}")
        }
        return max
    }

    /** 给设置界面用的一行状态 */
    fun statusText(): String {
        val t = if (socTempC.isNaN()) "—" else "${"%.1f".format(socTempC)}°C"
        return when (state) {
            State.OFF -> "已关闭 · $note"
            State.ERROR -> "✗ $note"
            State.ON -> {
                val left = remainingMs() / 1000
                "★ 运行中[%s] · 剩余 %d:%02d · 锁 handle=%d · 续期 %d 次 · SoC %s"
                    .format(tier.label, left / 60, left % 60, handle, renewCount, t)
            }
        }
    }
}
