package com.shware.mode.mod.perfmode

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log

/**
 * ★★★★★ QTI perf HAL 的 **binder 客户端**（任务 AK 攻克的成果）。
 *
 * ## 这条路是怎么找到的（**别重走弯路**）
 *
 * | 试法 | 结果 |
 * |---|---|
 * | `echo performance > scaling_governor` | ❌ 权限（`root`/`system` 所有） |
 * | `service call vendor.perfservice …` | ❌ ★ **事务码会猜错** |
 * | app 反射 `android.util.BoostFramework` | ❌ ★ **`declaredMethods` 返回 0 个**（整类被隐藏 API 清空） |
 * | `VMRuntime.setHiddenApiExemptions` | ❌ 也被拦 |
 * | ★ **手搓 binder 事务** | ✅✅✅ **成功且实测有效** |
 *
 * ## 为什么"手搓"能绕过隐藏 API（**全部公开 API，只有一处反射**）
 *
 * | 用到的 | 是否公开 |
 * |---|---|
 * | `Parcel.obtain/writeInterfaceToken/writeInt/writeString/writeIntArray` | ✅ 公开 |
 * | `IBinder.transact(int, Parcel, Parcel, int)` | ✅ 公开 |
 * | `Process.myTid()` | ✅ 公开 |
 * | ⚠️ `android.os.ServiceManager.getService(String)` | ⚠️ 隐藏类 —— **全程只有这一处反射**（实测放行） |
 *
 * ## 协议（**从 ROM 的 `QPerformance.jar` 反编译读出，不是猜的**）
 *
 * 服务名 `vendor.perfservice` ｜ 接口描述符 `com.qualcomm.qti.IPerfManager`
 * ```
 * 1 = perfLockRelease          ← token
 * 2 = perfLockReleaseHandler   ← token, int(handle)
 * 3 = perfHint                 ← token, int(hint), String, int, int, int(tid)  ★ 查包名白名单
 * 4 = perfLockAcquire          ← token, int(duration), intArray(list)         ★ 不查白名单
 * 8 = perfPerformanceMode      ← token, int(0/1)   ★ void ⇒ oneway
 * ```
 *
 * > ★★★ **关键差异**：`perfHint` **查包名白名单**（我们的包不在里面 ⇒ 一路 `-1`）；
 * > **`perfLockAcquire` 不查** ⇒ 这才是能用的那个。
 *
 * ## 资源 opcode（`perfLockAcquire` 的 `list`）
 *
 * ⚠️⚠️ **顺序是 BIG → LITTLE → PRIME，不是"小/大/超大"！**（本节曾是错的，见下）
 *
 * | opcode | ★ 实测确认的含义 | 簇 |
 * |---|---|---|
 * | **`0x40800000`** | **CPUBOOST_MAX_FREQ —— 【大核 Gold】** | `policy4` (cpu4-6) |
 * | **`0x40800100`** | **CPUBOOST_MAX_FREQ —— 【小核 Silver】** | `policy0` (cpu0-3) |
 * | **`0x40800200`** | CPUBOOST_MAX_FREQ —— 超大核 Prime | `policy7` (cpu7) |
 * | `0x40804000 / 0x40804100 / 0x40804200` | CPUBOOST_MIN_FREQ（同序：大/小/超大） | — |
 * | `0x43000000` | SCHEDBOOST | — |
 * | `0x41800000` | CPUBW_MIN_FREQ（内存带宽） | — |
 * | `0x43400000` | LLCCBW（末级缓存带宽） | — |
 * | `0x40C00000` | POWER COLLAPSE | — |
 *
 * ### ⚠️ 勘误：这个顺序**曾经读反过**（2026-09-13 修正）
 *
 * `perfboostsconfig.xml` 的注释顺序本来就是 **BIG / LITTLE / PRIME**，
 * 但最初按"小/大/超大"去套 ⇒ **大核一直在被请求 1785 而不是 2419**。
 * 表现是"**请求 2419 只生效 1804800**"，一度被误认为是"perfd 内部有档位映射"。
 *
 * ★ **实测判别法**（单对 opcode 逐个发，看哪一簇动）：
 * ```
 * 单发 0x40800100 = 2419   → 三簇都不动            （因为 2419 被 clamp 到小核上限，而小核本来就在上限）
 * 单发 0x40800000 = 1785   → 【大核】动到 1804800   ← ★ 真相在这里
 * 单发 0x40800200 = 2956   → 【超大核】动到 2956800
 * 修正后 0x40800000 = 2419 → 【大核】动到 2419200   ← ★★ 满血
 * ```
 *
 * ### ★★ 值语义：**向上取整到该簇最近的可用档位**（实测）
 * ```
 * 大核 请求 1000 → 1056000      请求 1500 → 1612800
 *      请求 1785 → 1804800      请求 2419 → 2419200（= cpuinfo_max）
 * ```
 */
object PerfLock {

    private const val TAG = "ModeMod/PerfMode"

    private const val SVC = "vendor.perfservice"
    private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"

    private const val TX_LOCK_RELEASE = 1
    private const val TX_LOCK_RELEASE_HANDLER = 2
    private const val TX_LOCK_ACQUIRE = 4

    /**
     * ★★★ **档位**（任务 AK6 实测得出）。
     *
     * | 档 | 组合 | 突发负载（60 轮纯计算） | ★ 空闲功耗（交替 2 轮实测） |
     * |---|---|---|---|
     * | 无锁基线 | — | 1081 ms | 176.2 mA |
     * | **均衡** | 三簇 MAX_FREQ | **789 ms（+26%）** | ★ **173.0 mA（−1.8%，噪声内）≈ 0** |
     * | **强力** | ＋ **`POWER COLLAPSE = 1`** | ★★ **269 ms（+75%）** | ⚠️ **236.2 mA（+60 mA / +34%）** |
     *
     * ## ★★ 算账（决定要不要用强力档）
     *
     * ```
     * +60 mA × 15 分钟 = 15 mAh ÷ 4000 mAh ≈ 0.4% 电量
     * ```
     * ⇒ ★ **短时（15 分钟级）开强力档非常划算**（0.4% 电换 +75% 响应）；
     * 但**长时间挂着不划算**（1 小时 ≈ 1.5%，8 小时 ≈ 12%）。
     *
     * 两轮测得的**谷值**也能佐证：无锁/均衡 谷 ≈ 113–125 mA，
     * 强力 **谷 ≈ 192–200 mA** —— CPU 再也降不到低功耗，这就是那 60 mA 的来源。
     *
     * ## ★★ `POWER COLLAPSE = 1` 是什么
     *
     * `0x40C00000`。⚠️ **它在资源地图里属 `Major 0xC = llccbw`（末级缓存带宽）**，
     * **不是**我们最初以为的 "POWER COLLAPSE"（那个名字是猜的）。
     *
     * ★ 实测：它**单独**就能把 AV1 软解从 24 fps 提到 **43 fps**。
     * ⚠️ 但**它到底改了什么，机理【不明】**（三个假说都被直接测量排除）。⇒
     * **突发任务不用等"唤醒"**，所以延迟大幅下降。
     *
     * **值语义已实测**：`= 0` 与不加该项**完全无差别**（777 vs 789 ms），`= 1` 才生效。
     *
     * ## ⚠️ 权衡（**不粉饰**）
     *
     * 强力档=**牺牲待机功耗换响应速度** ⇒ **不适合长时间挂着**。
     *
     * ## ★★★★★ 任务 AN（2026-09-13）实测：**收益几乎全部来自 `POWER COLLAPSE`**
     *
     * 用 **AV1 软解**做重负载基准（SM8150 **无 AV1 硬解** ⇒ 必然软解），
     * 全量解码 901 帧量 fps（交替 2 轮）：
     *
     * | 配置 | 解码 fps | 相对无锁 | 稳定性（各轮） |
     * |---|---|---|---|
     * | 无锁 | 24.1 | — | 20.5 – 25.5（**稳定地慢**） |
     * | ★ **只禁 PC**（仅 `0x40C00000=1`） | ★★ **42.9** | **+65.5%** | ★ 42.5 – 43.0（极差 0.5） |
     * | ⚠️ 轻量（只抬频） | ⚠️ **34.3** | 不稳定 | ⚠️ **双峰 43.4/43.4/20.6/20.7/43.5** |
     * | **强力（抬频 ＋ 禁 PC）** | ★★★ **44.4** | **+71.2%** | 44.4 / 44.4 |
     *
     * ### ★★★★ 观察到的事实：收益几乎全部来自 `0x40C00000`，不是抬频率
     *
     * - ★ **抬频独自不但没用，还在【拖后腿】**（均值低于无锁，且极不稳）
     * - ⚠️ **机理不明**（热降频 / 运行队列等待 / 深 C-state 三个假说均已被直接测量排除）；
     *   而禁 PC **不拉高时钟**，只免除"核被断电后重新唤醒"的延迟
     * - ★★ 而且**方差被压掉了**（有锁各轮几乎不动）—— 对"流畅"这种主观感受很重要
     *
     * ⇒ ★★★ **`BALANCED` 只适合轻负载/突发；重负载（软解视频、渲染）必须用 `STRONG`。**
     *
     * ## ★ 实测无效的 opcode（**别再试**）
     *
     * `SCHEDBOOST (0x43000000, 0xFF)` / `CPUBW_MIN_FREQ (0x41800000, 0xFF)` /
     * `LLCCBW (0x43400000, 0xFFFF)` —— 叠加后 778 / 779 / 788 ms，
     * **全部落在 789 ms 的噪声内**。
     * ⚠️ **但注意**：那个基准是**纯整数运算**，**测不出内存带宽**
     * （任务 AN 已纠正该方法论错误）⇒ **这三项仍未真正排除**。
     */
    enum class Tier(val key: String, val label: String, val list: IntArray, val desc: String) {
        /**
         * ★★★ **默认档**：在抬频之外加上 `0x40C00000` —— **重负载场景的正解**。
         *
         * ★★★ **收益主要来自这一项**（`0x40C00000`），不是抬频 —— 见类注释的实测表。
         */
        STRONG(
            "strong", "强力（推荐）",
            intArrayOf(
                0x40800000, 2419,   // 大核 Gold  cpu4-6 → 2.419 GHz
                0x40800100, 1785,   // 小核 Silver cpu0-3 → 1.785 GHz
                0x40800200, 2956,   // 超大核 Prime cpu7 → 2.956 GHz
                0x40C00000, 1,      // ★ POWER COLLAPSE = 1（收益主要来自这一项）
            ),
            "★ 软解视频 +36~71% · 突发 +75% · 满载每帧能耗 −3.7% · ⚠️ 空闲 +60 mA"
        ),

        /**
         * ⚠️ **只抬频率下限 —— 实测对软解的性能【不稳定（双峰）】。**
         *
         * | 场景 | 实测 |
         * |---|---|
         * | 突发负载 | +26%（好，但不如强力档的 +75%） |
         * | ★ **AV1 软解**（n=5） | ⚠️ **双峰：43.4 / 43.4 / 20.6 / 20.7 / 43.5**<br/>均值 34.3，**极差 22.9** |
         * | 对照：无锁 | 20.5 – 25.5（**稳定地慢**，均值 24.1） |
         * | 对照：只禁 PC | 42.5 – 43.0（**稳定快**，极差仅 0.5） |
         * | 空闲功耗 | ★ ≈ 0（唯一优势） |
         *
         * ### ⚠️⚠️ 机理【仍然不明】—— 三个假说全部被直接测量排除
         *
         * | # | 假说 | 结局 |
         * |---|---|---|
         * | 1 | 热降频 | ❌ 温度不支持（本档 55–58°C 比强力 60–62°C **更凉**却更慢） |
         * | 2 | 线程在**运行队列**等待 | ❌ `schedstat` 四配置无差异（5.2–5.4%） |
         * | 3 | CPU 从**深 C-state** 恢复慢 | ❌ `cpuidle` 进入次数无差异（3701–3909） |
         *
         * ⇒ ★★ **只陈述观察到的事实，不写机理断言。**
         *
         * ### ⚠️ 两条**已被推翻**的旧说法（**别再写**）
         *
         * | 旧说法 | 为什么错 |
         * |---|---|
         * | 「热降频导致更慢」 | ❌ 温度数据不支持 |
         * | 「比无锁**更慢**（−9%）」 | ❌ **区间重叠**（[20.6, 25.5]）⇒ 那是**双峰均值被误读**<br/>实际上本档 **≥ 无锁**（一半样本远好于无锁） |
         *
         * ⇒ ★ 本档只适合"想长时间挂着、且没什么重负载"的场景。
         */
        LIGHT(
            "balanced", "轻量（长时间）",
            intArrayOf(
                0x40800000, 2419,
                0x40800100, 1785,
                0x40800200, 2956,
            ),
            "只抬频率下限 · 空闲代价≈0 · ⚠️ 软解表现【不稳定】(双峰 43/21)"
        );

        companion object {
            /** ★ 默认 = [STRONG]（2026-09-13 据实测调整，原为"只抬频"档） */
            fun of(key: String?): Tier = entries.firstOrNull { it.key == key } ?: STRONG
        }
    }

    /** ★ 兼容旧代码：默认档位（现为 [Tier.STRONG]） */
    val PRESET_PERFORMANCE: IntArray get() = Tier.STRONG.list

    // ------------------------------------------------------------------ binder

    @Volatile
    private var cachedBinder: IBinder? = null

    @Volatile
    private var cachedMethod: java.lang.reflect.Method? = null

    /**
     * 拿 `vendor.perfservice` 的 binder。
     *
     * ★ **唯一需要反射的一步** —— `android.os.ServiceManager` 是隐藏类，
     * 但它是 app 最常反射的隐藏类之一，**本 ROM 实测放行**。
     * 反射结果缓存，避免每次续期都查一遍。
     */
    fun binder(): IBinder? {
        // ★ 缓存的 binder 要探活（`pingBinder()` 是公开 API；`isBrowsable()` 是隐藏的）
        cachedBinder?.let { if (it.pingBinder()) return it else cachedBinder = null }
        return try {
            val m = cachedMethod ?: Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).also { cachedMethod = it }
            val b = m.invoke(null, SVC) as? IBinder
            cachedBinder = b
            if (b == null) Log.w(TAG, "vendor.perfservice 不在（HAL 没起来？）")
            b
        } catch (t: Throwable) {
            Log.w(TAG, "拿 binder 失败：${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 服务在不在（给设置界面显示用） */
    fun available(): Boolean = binder() != null

    /**
     * ★ 申请一个频率锁。
     *
     * @param durationMs **锁的存活时长** —— ★ 这个值就是"崩溃自愈"的边界：
     *   进程崩了没人释放，锁也会在这么久之后自己消失。
     * @return `>= 0` = handle；`< 0` = 失败
     */
    fun acquire(durationMs: Int, list: IntArray): Int {
        val b = binder() ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(durationMs)
            data.writeIntArray(list)
            b.transact(TX_LOCK_ACQUIRE, data, reply, 0)
            reply.readException()
            val r = reply.readInt()
            Log.i(TAG, "perfLockAcquire(${durationMs}ms) → $r")
            r
        } catch (t: Throwable) {
            Log.w(TAG, "perfLockAcquire 异常：${t.javaClass.simpleName}: ${t.message}")
            -1
        } finally {
            reply.recycle(); data.recycle()
        }
    }

    /** 释放指定 handle 的锁。返回 0 = 成功 */
    fun release(handle: Int): Int {
        if (handle < 0) return -1
        val b = binder() ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(handle)
            b.transact(TX_LOCK_RELEASE_HANDLER, data, reply, 0)
            reply.readException()
            val r = reply.readInt()
            Log.i(TAG, "perfLockReleaseHandler($handle) → $r")
            r
        } catch (t: Throwable) {
            Log.w(TAG, "释放异常：${t.javaClass.simpleName}: ${t.message}")
            -1
        } finally {
            reply.recycle(); data.recycle()
        }
    }

    @Suppress("unused")
    private fun tidForDebug() = Process.myTid()
}
