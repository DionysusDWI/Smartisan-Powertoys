package com.shware.mode.tntgoserial

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ★★★★ **跨 mod 的共享状态通道**（任务 AR12）。
 *
 * ## 为什么需要它
 *
 * AR12 要回答「**不同的亮度对应 TNT GO 的功耗并不相同**」（用户原话）。
 * 而亮度是**亮度 mod** 用 `AT+BKL=` 设的，电流是**电量 mod** 读的 ——
 * 两边分属**两个进程**，需要一条数据通路。
 *
 * ## ★★★ 为什么是"共享文件"而不是"读对方的 SharedPreferences"
 *
 * AR 计划书原方案 A 是「亮度 mod 写自己的 prefs；电量 mod 直接读那个 XML」，
 * 并自带一条警告：**`SharedPreferences` 跨进程不安全（各进程有缓存）**。
 *
 * AS3b 之后有了更好的选择 —— 两个 mod 已经共用 `:tntgo-serial` 这个模块，
 * 而且**同一个 APK ⇒ 同一个 `filesDir`**：
 *
 * | | 方案 A（解析对方 prefs XML） | ★ 本方案（共享状态文件） |
 * |---|---|---|
 * | 契约位置 | 散在"另一个模块的私有文件格式"里 | ★ 在**共享模块**里，与串口同一处 |
 * | 读法 | 解析别人的 SharedPreferences XML（格式耦合） | 自定义小文件 |
 * | 写坏风险 | `apply()` 非原子，可能读到半截 | ★ **temp ＋ rename 原子替换** |
 *
 * ## ★★ 有效性判据：**心跳**，不是"文件多久没改"
 *
 * 这里有一个容易做错的地方：**"文件很久没更新"不等于"亮度值失效"** ——
 * 用户设了 60% 然后两小时不动，那个 60% 依然是**有效的**。
 *
 * 真正要防的是「**亮度 mod 已经不在管这块屏了**」（被 force-stop、
 * 或 TNT GO 重新插拔后亮度被别人改了）⇒ 此时我们记的值可能已经不对。
 *
 * ⇒ 所以判据是 **心跳**：亮度 mod **定期重发**同一个值（即使没变），
 *   于是 [readBrightness] 只要看到时间戳超过 [HEARTBEAT_MS] × [STALE_FACTOR]
 *   就判定为「**亮度未知**」——
 *   ★ 这与 AR §阶段 C+ 的红线一致：**没运行/不可信 ⇒ 显示「亮度未知」并降级，绝不猜**。
 */
object TntgoState {

    private const val TAG = "ModeMod/State"

    /** 亮度 mod 的心跳周期（ms）—— 与 `BrightnessModService` 里的常量必须一致 */
    const val HEARTBEAT_MS = 30_000L

    /** 超过心跳的这么多倍没消息 ⇒ 认为亮度 mod 不管事了 */
    const val STALE_FACTOR = 3

    /** 亮度值的有效窗口 */
    const val VALID_MS = HEARTBEAT_MS * STALE_FACTOR

    /** 亮度快照 */
    data class Brightness(
        /** MCU 域原始值（`TntgoBkl.MCU_MIN..MCU_MAX`） */
        val mcu: Int,
        /** UI 域 0–100（★ 显示用这个 —— MCU 域是设备量纲，不是感知量纲） */
        val ui: Int,
        /** 发布时刻（`System.currentTimeMillis()`） */
        val tsMs: Long,
    ) {
        fun ageMs(nowMs: Long = System.currentTimeMillis()): Long = nowMs - tsMs
    }

    private fun file(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "tntgo_brightness.state")

    /**
     * ★ **发布当前亮度**（亮度 mod 调）。
     *
     * ## 原子写
     *
     * 先写 `*.tmp` 再 `rename` —— **同目录内 rename 是原子的**（POSIX），
     * 所以读者**绝不会**看到半截文件。
     * ⚠️ 直接覆写则可能出现"读到一半"的窗口，而那会让电量侧把一个**解析失败的
     * 瞬间**误判成「亮度未知」，在曲线上留下一个**假的空洞**。
     *
     * ⚠️ 写失败**不抛异常**（包装成日志）—— 亮度功能不该因为一个统计文件而受影响。
     *
     * @return ★★★ **是否真的写成功了**。调用方（心跳）**必须把 `false` 说出来** ——
     *         2026-09-15 的实机故障就是"心跳**静默**停摆"：状态文件的 `ts` 停住，
     *         而整条链上一行日志都没有，只能从"电量侧一直说亮度未知"这个
     *         **远端的症状**上才反推得出来。
     */
    fun publishBrightness(
        ctx: Context,
        mcu: Int,
        ui: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        return runCatching {
            tmp.writeText("mcu=$mcu\nui=$ui\nts=$nowMs\n")
            if (!tmp.renameTo(f)) {
                // 极少数情况下 rename 会失败（目标存在且文件系统不支持覆盖）
                f.delete()
                if (!tmp.renameTo(f)) {
                    Log.w(TAG, "亮度状态写入失败：rename 不成功（${f.absolutePath}）")
                    return@runCatching false
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "亮度状态写入异常：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /**
     * ★★ **通道为什么"不可信"** —— 把 `readBrightness` 的一个 `null`
     * 拆成四种**能采取不同行动**的原因。
     *
     * ## 为什么必须拆
     *
     * 2026-09-15 的实机故障里，电量侧只会说一句「亮度未知」。
     * 于是**四种完全不同的原因**（没启动 / 文件坏了 / 心跳停了 / 从未设过）
     * 在日志里长得**一模一样** —— 排查只能靠猜，而且猜错方向（我先怀疑的是
     * "亮度 mod 死了"，实际进程活得好好的，是**心跳从未被排程**）。
     *
     * ⇒ ★ **"不可用"这个结论必须能说出它是怎么不可用的。**
     */
    sealed class Unusable {
        /** 状态文件不存在 ⇒ 亮度 mod 从没发布过（没启动 / 从未设过值） */
        object NoFile : Unusable()

        /** 文件在，但字段缺失或解析不了 ⇒ ★ **写坏了**（和"没启动"完全两回事） */
        data class Corrupt(val why: String) : Unusable()

        /** ★★ 文件完好、值也在，只是**太旧** ⇒ 发布者（心跳）停了 */
        data class Stale(val mcu: Int, val ui: Int, val ageMs: Long) : Unusable()

        /** 读取本身抛了异常（IO / 权限） */
        data class IoError(val why: String) : Unusable()

        fun describe(): String = when (this) {
            NoFile -> "状态文件不存在（亮度组件从未发布过）"
            is Corrupt -> "状态文件内容坏了：$why"
            is Stale -> "★ 心跳停了：文件里还有 ui=$ui/mcu=$mcu，但已 stale ${ageMs / 1000}s" +
                    "（有效窗口 ${VALID_MS / 1000}s）"
            is IoError -> "读状态文件出错：$why"
        }
    }

    /**
     * ★ 读**原始**亮度（**不做新鲜度判定**）—— 纯诊断用。
     *
     * @return `(亮度, 不可用原因)` 二选一；两者**必有且只有一个**非空
     */
    fun readBrightnessRaw(
        ctx: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<Brightness?, Unusable?> {
        val f = file(ctx)
        if (!f.exists()) return null to Unusable.NoFile
        val kv = HashMap<String, String>(4)
        try {
            f.forEachLine { line ->
                val i = line.indexOf('=')
                if (i > 0) kv[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
        } catch (e: Exception) {
            return null to Unusable.IoError("${e.javaClass.simpleName}: ${e.message}")
        }
        val mcu = kv["mcu"]?.toIntOrNull()
            ?: return null to Unusable.Corrupt("mcu 缺失或不是整数（raw=\"${kv["mcu"]}\"）")
        val ui = kv["ui"]?.toIntOrNull()
            ?: return null to Unusable.Corrupt("ui 缺失或不是整数（raw=\"${kv["ui"]}\"）")
        val ts = kv["ts"]?.toLongOrNull()
            ?: return null to Unusable.Corrupt("ts 缺失或不是整数（raw=\"${kv["ts"]}\"）")
        val b = Brightness(mcu, ui, ts)
        val age = nowMs - ts
        return if (age > VALID_MS) null to Unusable.Stale(mcu, ui, age) else b to null
    }

    /**
     * 读当前亮度。**读不到 / 太旧 ⇒ `null`（调用方必须降级，不许猜）**。
     *
     * ⚠️ 只给"能不能用"，**不给原因** —— 要原因用 [readBrightnessRaw]。
     *
     * @param maxAgeMs 有效窗口；默认 [VALID_MS]
     */
    fun readBrightness(
        ctx: Context,
        maxAgeMs: Long = VALID_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Brightness? {
        val f = file(ctx)
        if (!f.exists()) return null
        val kv = HashMap<String, String>(4)
        runCatching {
            f.forEachLine { line ->
                val i = line.indexOf('=')
                if (i > 0) kv[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
        }.onFailure {
            Log.w(TAG, "亮度状态读取异常：${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        val mcu = kv["mcu"]?.toIntOrNull() ?: return null
        val ui = kv["ui"]?.toIntOrNull() ?: return null
        val ts = kv["ts"]?.toLongOrNull() ?: return null
        if (nowMs - ts > maxAgeMs) return null
        return Brightness(mcu, ui, ts)
    }

    /** 测试/排查用：把通道清掉（比如亮度 mod 被卸载时） */
    fun clearBrightness(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
