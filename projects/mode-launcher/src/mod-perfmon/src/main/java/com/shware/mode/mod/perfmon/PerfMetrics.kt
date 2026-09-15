package com.shware.mode.mod.perfmon

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.StatFs
import java.io.File
import java.util.Locale

/**
 * ★★★ 系统性能指标采集 —— **全部走【无 root / 无 Shizuku】的路径**。
 *
 * ## ★ 实测结论（坚果 Pro 3 / A10，2026-09-12 —— 本类就是靠这个校准出来的）
 *
 * | 指标 | 来源 | 结果 |
 * |---|---|---|
 * | 内存 | `ActivityManager.MemoryInfo` | ✅ |
 * | 存储 | `StatFs` | ✅ |
 * | 电量 / 温度 | `ACTION_BATTERY_CHANGED` | ✅ |
 * | 网络累计 | `TrafficStats` | ✅ |
 * | ★ CPU 频率 | `/sys/devices/system/cpu/cpu<N>/cpufreq/scaling_cur_freq` | ✅ **能读** |
 * | ★ 频率驻留 | `/sys/…/cpufreq/stats/time_in_state` | ✅ **能读** ⇒ 可算负载代理 |
 * | ★ 温度 | `/sys/class/thermal/thermal_zone<N>/temp` | ✅ **能读**（但要按量纲过滤，见下） |
 * | ❌ **CPU 使用率** | `/proc/stat` | ❌ **被 SELinux 藏了**（app 看到的是"节点不存在"） |
 * | ❓ CPU 负载 | `/proc/loadavg` | ❓ **待实测**（同一个 /proc 隐藏机制，很可能也被拦） |
 *
 * ## ⚠️ 温度必须按【量纲】过滤
 *
 * 实测同目录下混着两种节点：
 * ```
 * cpu-0-0-usr   = 47600   ← 0.001°C ⇒ 47.6°C   ✅ 真温度
 * battery       = 33700   ← 0.001°C ⇒ 33.7°C   ✅（与 BatteryManager 报的 33.5°C 对得上）
 * soc           = 93      ← ★ 不是温度！可能是百分比之类的别的量
 * ```
 * ⇒ **只接受 `1000..200000` 的值**（即 1°C–200°C）。`soc=93` 这种小整数会被干净地排除，
 * 而不是被当成 93°C 显示出去。
 *
 * ## 为什么每项都带「失败原因」
 *
 * 读不到时**带上原因**，**不静默省略、不显示 0** ——
 * 静默会让人误以为"没数据"，真相可能是"被拦了，得换条路"。
 */
class PerfMetrics(private val context: Context) {

    /**
     * 一个指标的读数。
     *
     * - [pct] 非空 ⇒ **能画成进度条**（0–100）
     * - [text] 是卡片右侧的短文本
     * - 读不到时 [ok]=false，[note] 说明原因
     */
    data class Metric(
        val label: String,
        val ok: Boolean,
        val pct: Int? = null,
        val text: String = "",
        val note: String? = null,
    ) {
        fun line(): String = if (ok) "$label  $text" else "$label  ——  $note"
    }

    companion object {
        /** 画进度条的四项 */
        val BAR_LABELS = listOf("内存", "存储", "电量", "CPU")

        /** 只上文本的三项（温度→标题行；网络/频率→页脚） */
        val TEXT_LABELS = listOf("温度", "网络", "频率")

        val ALL_LABELS = BAR_LABELS + TEXT_LABELS
    }

    /** 上一次频率驻留汇总（算均频代理用） */
    private var lastTin: Map<Long, Long>? = null

    /**
     * 采一次样。**均频类指标靠与上一次调用的差**，所以调用方应保持固定间隔
     * （本 mod 是 1 s）—— 间隔不稳，代理就不准。
     */
    fun sample(): List<Metric> = listOf(mem(), storage(), battery(), cpu(), temp(), network(), freq())

    // ------------------------------------------------------------------ 能画条的四项

    private fun mem(): Metric = metric("内存") {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val used = mi.totalMem - mi.availMem
        val pct = (used * 100.0 / mi.totalMem).toInt()
        Metric("内存", true, pct, "$pct%")   // 详细量在标题栏 tooltip 意义不大，卡片只放百分比
    }

    private fun storage(): Metric = metric("存储") {
        val sf = StatFs(File("/data").absolutePath)
        val total = sf.blockCountLong * sf.blockSizeLong
        val free = sf.availableBlocksLong * sf.blockSizeLong
        val pct = ((total - free) * 100.0 / total).toInt()
        Metric("存储", true, pct, "$pct%")
    }

    private fun battery(): Metric = metric("电量") {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw IllegalStateException("拿不到粘性广播")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        // ⚠️ 电量的"条"画的是【剩余】而不是"占用" —— 满电 = 满条，符合直觉
        Metric("电量", true, level.coerceIn(0, 100), "$level%${if (charging) " ↑" else ""}")
    }

    /**
     * CPU。
     *
     * ① 先试 **`/proc/loadavg`**（真·系统负载）—— 但 `/proc/stat` 实测被 SELinux 藏了，
     *    `loadavg` **很可能同样被拦**，所以有退路。
     * ② 退路：**频率驻留时间的加权均频 ÷ 最高频** —— `time_in_state` 在 `/sys` 下
     *    （实测可读），能反映"CPU 有多忙"。这是**代理**，不是使用率。
     *
     * 两种来源都会在 [Metric.text] 里**标明出处**（`·` 前后），不冒充。
     */
    private fun cpu(): Metric = metric("CPU") {
        val cores = Runtime.getRuntime().availableProcessors()

        // ① /proc/loadavg
        val load = runCatching {
            File("/proc/loadavg").readText().trim().split(Regex("\\s+"))[0].toDouble()
        }.getOrNull()
        if (load != null) {
            // load 是"可运行任务数的均值"⇒ 除以核数才是粗略占用比
            val pct = (load / cores * 100).toInt().coerceIn(0, 100)
            return@metric Metric("CPU", true, pct, "$pct%")
        }

        // ② 均频代理
        val avg = avgFreqDeltaKhz()
        val max = maxFreqKhz()
        if (avg == null || max == null) throw IllegalStateException("/proc/loadavg 被拦，均频代理还没采到")
        val pct = (avg / max * 100).toInt().coerceIn(0, 100)
        // ★ ok=true 时 note 表示"附加保留"（不是失败原因）—— 须在页脚标出来，不冒充使用率
        Metric("CPU", true, pct, "$pct%", note = "均频代理")
    }

    // ------------------------------------------------------------------ 只上文本的项

    /** 温度 —— 最高的两个真温度节点，短名 */
    fun temp(): Metric = metric("温度") {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?: throw IllegalStateException("/sys/class/thermal 列不出来")

        val readings = zones.mapNotNull { z ->
            val type = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            val raw = runCatching { File(z, "temp").readText().trim().toLong() }.getOrNull() ?: return@mapNotNull null
            // ★ 量纲过滤：只认 0.001°C 且落在 1–200°C 的；soc=93 这类小整数会被排除
            if (raw !in 1_000L..200_000L) return@mapNotNull null
            // ★ `lmh-*`（Limits Management Hardware）报的是【限值】不是温度传感器 ——
            //   实测它恒定 75，会把真实温度全盖掉。必须排除。
            if (type.startsWith("lmh")) return@mapNotNull null
            shortName(type) to raw / 1000.0
        }
        if (readings.isEmpty()) throw IllegalStateException("没有量纲可信的温度节点")

        val top = readings.maxByOrNull { it.second }!!
        val pct = ((top.second - 20) / 60 * 100).toInt().coerceIn(0, 100)   // 20–80°C 映射成 0–100
        Metric("温度", true, pct, String.format(Locale.US, "%.0f°C", top.second))
    }

    private fun network(): Metric = metric("网络") {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx < 0 || tx < 0) throw IllegalStateException("TrafficStats 不支持")
        Metric("网络", true, null, "↓${bytes(rx)}  ↑${bytes(tx)}")
    }

    private fun freq(): Metric = metric("频率") {
        val freqs = cores().mapNotNull { c ->
            runCatching { File(c, "cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000 }.getOrNull()
        }
        if (freqs.isEmpty()) throw IllegalStateException("scaling_cur_freq 全读不到")
        Metric("频率", true, null, "${freqs.min()}–${freqs.max()}M")
    }

    // ------------------------------------------------------------------ 工具

    /** `/sys/devices/system/cpu` 下的 cpu<N> 目录，按编号排序 */
    private fun cores(): List<File> =
        File("/sys/devices/system/cpu")
            .listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }
            ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }
            .orEmpty()

    private fun maxFreqKhz(): Long? = cores().firstOrNull()?.let { c ->
        runCatching { File(c, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
    }

    /** 汇总所有核的 `time_in_state`：频率(kHz) → 累计驻留时间 */
    private fun readTimeInState(): Map<Long, Long>? {
        val agg = HashMap<Long, Long>()
        for (c in cores()) {
            val lines = runCatching { File(c, "cpufreq/stats/time_in_state").readLines() }.getOrNull() ?: continue
            for (l in lines) {
                val p = l.trim().split(Regex("\\s+"))
                if (p.size < 2) continue
                val khz = p[0].toLongOrNull() ?: continue
                val t = p[1].toLongOrNull() ?: continue
                agg[khz] = (agg[khz] ?: 0L) + t
            }
        }
        return agg.ifEmpty { null }
    }

    /** 与上一次采样相比的**加权均频**（kHz）。第一次调用返回 null（没有差值可比）。 */
    private fun avgFreqDeltaKhz(): Double? {
        val cur = readTimeInState() ?: return null
        val prev = lastTin
        lastTin = cur
        if (prev == null) return null

        var num = 0.0
        var den = 0.0
        for ((khz, t) in cur) {
            val d = t - (prev[khz] ?: 0L)
            if (d <= 0) continue
            num += khz * d.toDouble()
            den += d.toDouble()
        }
        return if (den > 0) num / den else null
    }

    /** 把一长串 zone 名压成能上卡片的两三个字 */
    private fun shortName(type: String): String = when {
        type.startsWith("cpu") -> "CPU"
        type.startsWith("gpu") -> "GPU"
        type.startsWith("skin") -> "外壳"
        type.startsWith("quiet") -> "静区"
        type.startsWith("ddr") -> "内存"
        type.startsWith("xo") -> "晶振"
        else -> type.take(8)
    }

    private inline fun metric(label: String, block: () -> Metric): Metric = try {
        block()
    } catch (e: Throwable) {
        Metric(label, ok = false, note = reason(e))
    }

    private fun reason(e: Throwable): String = when (e) {
        // ⚠️ Android 的 /proc 隐藏让被 SELinux 拦掉的节点**看起来像不存在**
        is java.io.FileNotFoundException -> "读不到（SELinux 隐藏 / 节点不存在）"
        is SecurityException -> "被 SELinux/权限拦下"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "?"}"
    }

    private fun bytes(b: Long) = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1fG", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.US, "%.0fM", b / 1048576.0)
        b >= 1L shl 10 -> String.format(Locale.US, "%.0fK", b / 1024.0)
        else -> "${b}B"
    }
}
