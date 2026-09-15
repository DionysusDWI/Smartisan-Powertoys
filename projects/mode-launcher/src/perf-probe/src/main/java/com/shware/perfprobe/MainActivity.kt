package com.shware.perfprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * ★★★★★ 性能探针（任务 AK）—— **从 app 身份、用手搓 binder 事务调 QTI perf HAL**。
 *
 * ## 为什么是"手搓 binder"
 *
 * 实测（本文件的历史版本）证明：
 * - `android.util.BoostFramework` / `com.qualcomm.qti.Performance` 对 app 是**空壳**
 *   （隐藏 API 把构造器与方法**全部过滤掉**，`declaredMethods` 返回 **0 个**）
 * - `setHiddenApiExemptions` 这个绕过手法**也被拦**
 * - ✅ 但 **`System.loadLibrary("qti-perfd-client")` 成功**（它在 `public.libraries.txt` 里）
 *
 * ⇒ Java 高层 API 走不通。**但 binder 协议本身不需要任何隐藏 API**：
 *
 * | 用到的 | 是否公开 API |
 * |---|---|
 * | `Parcel.obtain()` / `writeInterfaceToken` / `writeInt` / `writeString` / `writeIntArray` | ✅ 公开 |
 * | `IBinder.transact(int, Parcel, Parcel, int)` | ✅ 公开 |
 * | `Process.myTid()` | ✅ 公开 |
 * | ★ `android.os.ServiceManager.getService(String)` | ⚠️ **隐藏类 ⇒ 只有这一处要反射** |
 *
 * ## 协议来自哪里（**不是猜的**）
 *
 * 从 ROM 里 `/system/framework/QPerformance.jar` 反编译出的 `IPerfManager$Stub$Proxy`：
 * ```
 * TRANSACTION_perfLockRelease        = 1
 * TRANSACTION_perfLockReleaseHandler = 2
 * TRANSACTION_perfHint               = 3   ← token, int(hint), String, int, int, int(tid)
 * TRANSACTION_perfLockAcquire        = 4   ← token, int(duration), intArray(list)
 * TRANSACTION_perfUXEngine_events    = 5
 * TRANSACTION_setClientBinder        = 6
 * TRANSACTION_perfGetProp            = 7
 * TRANSACTION_perfPerformanceMode    = 8   ← voice ⇒ oneway
 * ```
 * 接口描述符：`com.qualcomm.qti.IPerfManager`；服务名：`vendor.perfservice`
 *
 * ## 判据（**返回值 + 下游频率**，两个都要看）
 *
 * - `perfHint`/`perfLockAcquire` 返回 **< 0** ⇒ 被拒
 * - 返回 **≥ 0** ⇒ 拿到 handle；**再看 `policy4`/`policy7` 的 `scaling_cur_freq`
 *   是否被钉到 2419200 / 2956800**（空闲时应为 710400 / 825600）
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "PerfProbe"
        private const val SVC = "vendor.perfservice"
        private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"

        private const val TX_LOCK_RELEASE = 1
        private const val TX_LOCK_RELEASE_HANDLER = 2
        private const val TX_HINT = 3
        private const val TX_LOCK_ACQUIRE = 4
        private const val TX_PERFORMANCE_MODE = 8

        /** ★ 从 ROM 自带「性能模式」APK 挖出来的 hint id */
        private const val HINT_PERFORMANCE_MODE = 0x1091
        /** ★ 配置里 `0x1081` = 三簇 CPUBOOST_MAX_FREQ 全给最大哨兵 */
        private const val HINT_MAX_FREQ = 0x1081
        private const val PKG_PERF_MODE = "com.qualcomm.qti.performancemode"

        /** 资源 opcode：三簇 CPUBOOST_MAX_FREQ，值 = MHz */
        private val LOCK_MAX = intArrayOf(0x40800000, 2419, 0x40800100, 1785, 0x40800200, 2956)
    }

    private lateinit var out: TextView
    private val sb = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (14 * resources.displayMetrics.density).toInt()
        out = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#DDDDDD"))
            setBackgroundColor(Color.parseColor("#FF101010"))
            setPadding(pad, pad, pad, pad)
            typeface = Typeface.MONOSPACE
        }
        val btn = Button(this).apply {
            text = "重跑测试"
            isAllCaps = false
            setOnClickListener { sb.setLength(0); runTests() }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(btn)
                addView(ScrollView(this@MainActivity).apply { addView(out) })
            }
        )
        runTests()
    }

    private fun line(s: String) {
        Log.i(TAG, s)
        sb.append(s).append('\n')
        out.text = sb.toString()
    }

    private fun runTests() {
        line("===== 性能探针：手搓 binder 打 QTI perf HAL =====")
        line("pkg=$packageName uid=${applicationInfo.uid} targetSdk=${applicationInfo.targetSdkVersion}")
        line("")

        // ---------- ① 拿 binder（唯一需要反射的一步）----------
        val binder = getPerfBinder()
        if (binder == null) {
            line("✗ 拿不到 vendor.perfservice 的 binder ⇒ 到此为止")
            return
        }
        line("✓ 拿到 binder: ${binder.interfaceDescriptor ?: "(无描述符)"}")
        line("")

        // ---------- ② perfHint（先试"性能模式"那个 id）----------
        var handle = -1
        try {
            val r = perfHint(binder, HINT_PERFORMANCE_MODE, PKG_PERF_MODE, 30000, -1)
            line("② perfHint(0x${Integer.toHexString(HINT_PERFORMANCE_MODE)}, $PKG_PERF_MODE, 30000) → $r")
            if (r >= 0) { handle = r; line("   ★★ 成功！handle=$r") } else line("   ✗ 返回 $r（被拒）")
        } catch (t: Throwable) {
            line("② perfHint ✗ ${t.javaClass.simpleName}: ${t.message}")
        }

        // ---------- ③ perfHint(0x1081) ----------
        try {
            val r = perfHint(binder, HINT_MAX_FREQ, PKG_PERF_MODE, 30000, -1)
            line("③ perfHint(0x1081 = 三簇拉满, 30000) → $r")
            if (r >= 0 && handle < 0) handle = r
        } catch (t: Throwable) {
            line("③ perfHint ✗ ${t.javaClass.simpleName}: ${t.message}")
        }

        // ---------- ④ perfLockAcquire（按 opcode 直接钉频率）----------
        var lockHandle = -1
        try {
            val r = perfLockAcquire(binder, 30000, LOCK_MAX)
            line("④ perfLockAcquire(30000, 三簇钉最高频) → $r")
            if (r >= 0) { lockHandle = r; line("   ★★ 成功！handle=$r") } else line("   ✗ 返回 $r（被拒）")
        } catch (t: Throwable) {
            line("④ perfLockAcquire ✗ ${t.javaClass.simpleName}: ${t.message}")
        }

        // ---------- ⑤ perfPerformanceMode（oneway）----------
        try {
            perfPerformanceMode(binder, true)
            line("⑤ perfPerformanceMode(true) 已发出（oneway，无返回）")
        } catch (t: Throwable) {
            line("⑤ perfPerformanceMode ✗ ${t.javaClass.simpleName}: ${t.message}")
        }

        line("")
        // ---------- ⑦ ★★★ 电源数据源探测 ----------
        probeBattery()
        line("")

        // ---------- ⑥ ★★ 量化验证：突发负载基准 ----------
        runBurstBenchmark(binder)
    }

    /**
     * ★★★★★ 探测电源数据源 —— **任务 AK4 的关键**。
     *
     * ## 为什么要它
     *
     * 用 `dumpsys batterystats` 的 `Discharge` 做功耗 A/B **失败了**：
     * 那个计数器**跳变式更新**（粒度 5–20 mAh），145 秒的窗口只有 ~12 mAh
     * ⇒ **测到的是量化噪声**，甚至得出"加锁比不加锁更省电"的荒谬结论。
     *
     * ## 换用【公开 API】`BatteryManager`
     *
     * | 属性 | 含义 |
     * |---|---|
     * | `BATTERY_PROPERTY_CURRENT_NOW` | ★ **瞬时电流（µA，放电为负）** |
     * | `BATTERY_PROPERTY_CURRENT_AVERAGE` | ★ **平均电流（µA）** —— 比瞬时稳，更适合做窗口平均 |
     * | `BATTERY_PROPERTY_CHARGE_COUNTER` | 剩余电荷（µAh） |
     * | `BATTERY_PROPERTY_ENERGY_COUNTER` | ★ **剩余能量（nWh）** |
     * | `BATTERY_PROPERTY_CAPACITY` | 百分比 |
     *
     * ★ **不需要任何权限**，任何 app 都能读（而 `/sys/class/power_supply/` 下的节点连 shell 都被 SELinux 挡）。
     * ⚠️ 但**厂商 HAL 未必实现** ⇒ 实测说了算（读不到会返回 `Integer.MIN_VALUE`）。
     *
     * ⚠️⚠️ **Kotlin 块注释是可嵌套的** —— 本注释里**绝不能出现「斜杠+星号」连写**
     * （哪怕是在路径通配符里），否则会开出一个**永不闭合的嵌套注释**，
     * 报错却是文件末尾的 `Unclosed comment` ＋ 一堆莫名其妙的 `Unresolved reference`。
     * 这个坑本任务踩过一次。
     */
    private fun probeBattery() {
        line("⑦ ★★★ 电源数据源（BatteryManager 公开 API）")
        val bm = getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager
        if (bm == null) { line("   ✗ 拿不到 BatteryManager"); return }

        val props = listOf(
            "CURRENT_NOW" to android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
            "CURRENT_AVERAGE" to android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
            "CHARGE_COUNTER" to android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
            "ENERGY_COUNTER" to android.os.BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER,
            "CAPACITY" to android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY,
        )
        repeat(3) { round ->
            val sb = StringBuilder("   #$round ")
            for ((name, id) in props) {
                val v = try { bm.getIntProperty(id) } catch (t: Throwable) { Int.MIN_VALUE }
                sb.append("%s=%s  ".format(name, if (v == Int.MIN_VALUE) "不支持" else v.toString()))
            }
            line(sb.toString())
            Thread.sleep(1500)
        }
        line("   （CURRENT_NOW/AVERAGE 单位 µA，**放电时为负**）")
        line("   （ENERGY_COUNTER 单位 nWh）")
    }

    /**
     * ★★★ **突发负载基准** —— 这才是"频率下限"真正发挥作用的场景。
     *
     * ## 为什么不用"持续满载"来测
     *
     * 持续满载时 `schedutil` **本来就会把频率顶到最高**（实测：加不加锁"期间最高频"完全一样）
     * ⇒ ★ **那种测法分辨不出差别**。
     *
     * ## 突发负载才是真实场景
     *
     * 手机上的卡顿大多不是"持续满载"，而是**一簇一簇的短突发**（点一下、滑一下、开个界面）。
     * 没有频率下限时，**每个突发都要从最低频慢慢爬上去**；
     * 有下限时，**第一个指令就是满速**。
     *
     * ⇒ 本基准：重复「**短计算 + 短暂停顿**」，**只统计纯计算耗时**（不含停顿）。
     */
    private fun runBurstBenchmark(binder: IBinder) {
        val rounds = 60
        val burstIters = 3_000_000
        val gapMs = 40L

        line("⑥ ★★ 突发负载基准（$rounds 轮：${burstIters / 1_000_000}M 次整数运算 + 停 ${gapMs}ms）")

        val noLock = burstBench(rounds, burstIters, gapMs)
        line("   无锁 : 纯计算 ${noLock}ms")

        // 施加锁，等它生效
        val h = try { perfLockAcquire(binder, 60000, LOCK_MAX) } catch (t: Throwable) { -1 }
        line("   加锁 perfLockAcquire(60000) → $h")
        if (h < 0) { line("   ✗ 锁没拿到，基准到此为止"); return }
        Thread.sleep(1500)

        val withLock = burstBench(rounds, burstIters, gapMs)
        line("   有锁 : 纯计算 ${withLock}ms")

        val delta = (noLock - withLock) * 100.0 / noLock
        line("   ⇒ 提升 ${"%.1f".format(delta)}%   （${noLock}ms → ${withLock}ms）")
        line("")

        // 释放并复核
        val rel = try { perfLockReleaseHandler(binder, h) } catch (t: Throwable) { -999 }
        line("   释放 perfLockReleaseHandler($h) → $rel")
    }

    /** @return 纯计算总耗时（ms，**不含停顿**） */
    private fun burstBench(rounds: Int, burstIters: Int, gapMs: Long): Long {
        var compute = 0L
        repeat(rounds) {
            val t0 = System.nanoTime()
            var x = 1
            for (i in 0 until burstIters) {
                x = x * 31 + i
                x = x xor (x shr 7)
            }
            compute += System.nanoTime() - t0
            if (x == Int.MIN_VALUE) Log.d(TAG, "impossible")
            Thread.sleep(gapMs)
        }
        return compute / 1_000_000
    }

    // ------------------------------------------------------------------ binder

    /**
     * ★ **唯一需要反射的一步**：`android.os.ServiceManager.getService("vendor.perfservice")`。
     * `ServiceManager` 是隐藏类，但它是**最常被 app 反射**的隐藏类之一
     * ⇒ 该 ROM 到底放不放行，**实测说了算**。
     */
    private fun getPerfBinder(): IBinder? {
        try {
            val c = Class.forName("android.os.ServiceManager")
            val m = c.getMethod("getService", String::class.java)
            val b = m.invoke(null, SVC) as? IBinder
            line("① ServiceManager.getService(\"$SVC\") ✓")
            return b
        } catch (t: Throwable) {
            line("① ServiceManager.getService ✗ ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
    }

    /** `perfHint(int hint, String userStr, int d1, int d2)` ＋ `tid = Process.myTid()` */
    private fun perfHint(b: IBinder, hint: Int, userStr: String, d1: Int, d2: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(hint)
            data.writeString(userStr)
            data.writeInt(d1)
            data.writeInt(d2)
            data.writeInt(Process.myTid())      // ★ 第 5 个参数 = tid
            b.transact(TX_HINT, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle(); data.recycle()
        }
    }

    /** `perfLockAcquire(int duration, int[] list)` */
    private fun perfLockAcquire(b: IBinder, duration: Int, list: IntArray): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(duration)
            data.writeIntArray(list)
            b.transact(TX_LOCK_ACQUIRE, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle(); data.recycle()
        }
    }

    /** `perfPerformanceMode(boolean)` —— 返回 void ⇒ **oneway** */
    private fun perfPerformanceMode(b: IBinder, enable: Boolean) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(if (enable) 1 else 0)
            b.transact(TX_PERFORMANCE_MODE, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    @Suppress("unused")
    private fun perfLockReleaseHandler(b: IBinder, handle: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(handle)
            b.transact(TX_LOCK_RELEASE_HANDLER, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle(); data.recycle()
        }
    }

    @Suppress("unused")
    private fun perfLockRelease(b: IBinder): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TX_LOCK_RELEASE, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle(); data.recycle()
        }
    }
}
