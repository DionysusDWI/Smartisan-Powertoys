package com.shware.perfprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ★★★★★ **内存带宽基准**（任务 AN · 第 4 步）—— 纠正 AK6 的方法论错误。
 *
 * ## 为什么要重测
 *
 * AK6 用**纯整数运算循环**（`x = x*31+1; x ^= x>>7`）判定
 * `CPUBW`（内存带宽）/ `LLCCBW`（末级缓存带宽）**"无效"**。
 *
 * > ★★★ **那个判定【不成立】**：纯整数循环几乎不碰内存（全在寄存器与 L1 里），
 * > **内存带宽提升在那种基准上根本显不出来。**
 *
 * ⇒ 本基准用**大数组流式读写**，这才是内存带宽敏感的模式。
 *
 * ## 设计要点
 *
 * | 点 | 做法 | 为什么 |
 * |---|---|---|
 * | **数组要远大于 LLC** | 用 **256 MB direct buffer** | SM8150 的 LLC 只有几 MB；小于它测的是缓存不是内存 |
 * | **用 direct buffer** | `ByteBuffer.allocateDirect` | ★ 走**堆外内存**，不受 Dalvik 堆上限约束 |
 * | **三种访问模式** | 顺序读 / 顺序写 / 拷贝（读+写） | 覆盖不同带宽特性 |
 * | **多线程** | 4 线程各扫一段 | 单核往往压不满内存带宽 |
 *
 * ## 判读
 *
 * | 现象 | 含义 |
 * |---|---|
 * | **带宽提高** | 该 opcode 对内存带宽有效 |
 * | **无变化** | 真的无效（这次才算数） |
 */
class MemBenchActivity : Activity() {

    companion object {
        private const val TAG = "MemBench"

        private const val SVC = "vendor.perfservice"
        private const val DESCRIPTOR = "com.qualcomm.qti.IPerfManager"
        private const val TX_LOCK_RELEASE_HANDLER = 2
        private const val TX_LOCK_ACQUIRE = 4

        /**
         * ★ 缓冲区大小（MB，`long[]`）。
         *
         * 128 MB **远大于 LLC**（几 MB）⇒ 测的是内存不是缓存。
         * ⚠️ **为什么不用 `ByteBuffer.allocateDirect`**：
         * 实测它只有 ~1 GB/s —— **瓶颈是 Java 逐字节访问的开销，不是内存带宽**
         * （佐证：强力档读出 2.3 GB/s，那只是 CPU 更快、开销更低）。
         * ⇒ ★ 改用 **`long[]` ＋ `System.arraycopy`**（JIT intrinsic，会走向量化 memcpy）。
         */
        private const val BUF_MB = 128
        private const val THREADS = 8

        private const val OP_SCHEDBOOST = 0x43000000
        private const val OP_CPUBW = 0x41800000
        private const val OP_LLCCBW = 0x43400000
        private const val OP_PC = 0x40C00000
        private val FREQ = intArrayOf(0x40800000, 2419, 0x40800100, 1785, 0x40800200, 2956)
    }

    private lateinit var out: TextView
    private val sb = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (14 * resources.displayMetrics.density).toInt()
        out = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#DDDDDD"))
            setBackgroundColor(Color.parseColor("#FF101010"))
            setPadding(pad, pad, pad, pad)
            typeface = Typeface.MONOSPACE
        }
        setContentView(ScrollView(this).apply { addView(out) })
        Thread { runBench() }.start()
    }

    private fun line(s: String) {
        Log.i(TAG, s)
        runOnUiThread { sb.append(s).append('\n'); out.text = sb.toString() }
    }

    private data class R(val readGbs: Double, val writeGbs: Double, val copyGbs: Double)

    private fun runBench() {
        line("========== 内存带宽基准（纠正 AK6 方法错误）==========")
        line("缓冲区 ${BUF_MB} MB × 2 的 long[]（★ 远大于 LLC）／ ${THREADS} 线程")
        line("★ 用 System.arraycopy（JIT intrinsic ⇒ 向量化 memcpy），不是逐字节访问")
        line("")

        val elems = BUF_MB * 1024 * 1024 / 8
        var a: LongArray? = null
        var b: LongArray? = null
        try {
            a = LongArray(elems)
            b = LongArray(elems)
        } catch (t: Throwable) {
            line("✗ 分配 ${BUF_MB}MB × 2 失败: ${t.javaClass.simpleName}: ${t.message}")
            line("DONE"); return
        }
        line("分配成功（每个 ${elems / 1024 / 1024} M 个 long）")
        line("")

        // 先探一次（并确认基准确实受内存限制）
        val probe = measure(a, b)
        line("起始探测: 读 %.1f GB/s ｜ 写 %.1f GB/s ｜ 拷贝 %.1f GB/s".format(
            probe.readGbs, probe.writeGbs, probe.copyGbs))
        line("")

        val cfgs = listOf(
            Triple("① 无锁", null, ""),
            Triple("② +SCHEDBOOST 0xFF", intArrayOf(OP_SCHEDBOOST, 0xFF), "sched"),
            Triple("③ +CPUBW 0xFF", intArrayOf(OP_CPUBW, 0xFF), "cpubw"),
            Triple("④ +CPUBW 0x3FFF", intArrayOf(OP_CPUBW, 0x3FFF), "cpubw"),
            Triple("⑤ +CPUBW 0xFFFF", intArrayOf(OP_CPUBW, 0xFFFF), "cpubw"),
            Triple("⑥ +LLCCBW 0xFFFF", intArrayOf(OP_LLCCBW, 0xFFFF), "llccbw"),
            Triple("⑦ +Sched+CPUBW+LLCCBW", intArrayOf(
                OP_SCHEDBOOST, 0xFF, OP_CPUBW, 0xFFFF, OP_LLCCBW, 0xFFFF), "all"),
            Triple("⑧ 强力(抬频+禁PC)", FREQ + intArrayOf(OP_PC, 1), "ref"),
            // ★★★ 关键对照：单独测 0x40C00000（资源地图里它属 Major 0xC = LLCCBW）
            Triple("⑨ ★只 0x40C00000=1", intArrayOf(OP_PC, 1), "pc_only"),
            Triple("⑩ ★只 0x40C00000=2", intArrayOf(OP_PC, 2), "pc_only"),
        )

        val acc = HashMap<String, MutableList<R>>()
        for (round in 1..2) {
            for ((name, lock, _) in cfgs) {
                var h = -1
                if (lock != null) { h = acquire(120_000, lock); Thread.sleep(2000) }
                val r = measure(a, b)
                if (h >= 0) release(h)
                acc.getOrPut(name) { ArrayList() }.add(r)
                line("  第${round}轮  %-24s 读 %5.1f ｜ 写 %5.1f ｜ 拷贝 %5.1f GB/s".format(
                    name, r.readGbs, r.writeGbs, r.copyGbs))
                Thread.sleep(if (lock != null) 32_000 else 1_500)
            }
        }

        line("")
        line("========== 汇总（两轮均值）==========")
        line("%-26s %9s %9s %9s".format("配置", "读GB/s", "写GB/s", "拷贝GB/s"))
        for ((n, v) in acc) {
            line("%-26s %9.1f %9.1f %9.1f".format(
                n, v.map { it.readGbs }.average(),
                v.map { it.writeGbs }.average(), v.map { it.copyGbs }.average()))
        }
        val base = acc["① 无锁"]?.map { it.copyGbs }?.average() ?: 0.0
        line("")
        for ((n, v) in acc) {
            if (n.startsWith("①")) continue
            val c = v.map { it.copyGbs }.average()
            line("  %-26s 拷贝带宽 %+.1f%%".format(n, (c - base) / base * 100))
        }
        line("")
        line("DONE")
    }

    /** 三模式各测一次（`long[]`，`System.arraycopy` 走 JIT intrinsic） */
    private fun measure(a: LongArray, b: LongArray): R {
        val n = a.size
        val chunk = (n / THREADS) / 8 * 8        // 8 元素对齐
        val bytes = n.toDouble() * 8             // 真实字节数

        // 读：求和（★ 用 AtomicLong 累加，既阻止 JIT 消掉循环，又能跨线程）
        val sink = java.util.concurrent.atomic.AtomicLong(0)
        var t0 = System.nanoTime()
        val rt = (0 until THREADS).map { i ->
            Thread { sink.addAndGet(sumRead(a, i * chunk, chunk)) }.apply { start() }
        }
        rt.forEach { it.join() }
        var dt = (System.nanoTime() - t0) / 1e9
        val read = bytes / 1e9 / dt

        // 写
        t0 = System.nanoTime()
        val wt = (0 until THREADS).map { i ->
            Thread { fill(b, i * chunk, chunk) }.apply { start() }
        }
        wt.forEach { it.join() }
        dt = (System.nanoTime() - t0) / 1e9
        val write = bytes / 1e9 / dt

        // ★ 拷贝：`System.arraycopy` 是 JIT intrinsic ⇒ 会走向量化 memcpy
        t0 = System.nanoTime()
        val ct = (0 until THREADS).map { i ->
            Thread { System.arraycopy(a, i * chunk, b, i * chunk, chunk) }.apply { start() }
        }
        ct.forEach { it.join() }
        dt = (System.nanoTime() - t0) / 1e9
        // 拷贝 = 读 n + 写 n ⇒ 有效流量是 2×
        val copy = bytes * 2 / 1e9 / dt

        if (sink.get() == Long.MIN_VALUE) line("impossible")
        return R(read, write, copy)
    }

    private fun sumRead(buf: LongArray, off: Int, len: Int): Long {
        var s = 0L
        var i = off
        val end = off + len
        while (i < end) { s += buf[i]; i++ }
        return s
    }

    private fun fill(buf: LongArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) { buf[i] = 0x5A5A5A5A5A5A5A5AL; i++ }
    }

    // ------------------------------------------------------------------ binder

    private fun getBinder(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, SVC) as? IBinder
    } catch (t: Throwable) { null }

    private fun acquire(d: Int, list: IntArray): Int {
        val bb = getBinder() ?: return -1
        val da = Parcel.obtain(); val re = Parcel.obtain()
        return try {
            da.writeInterfaceToken(DESCRIPTOR); da.writeInt(d); da.writeIntArray(list)
            bb.transact(TX_LOCK_ACQUIRE, da, re, 0); re.readException(); re.readInt()
        } catch (t: Throwable) { -1 } finally { re.recycle(); da.recycle() }
    }

    private fun release(h: Int): Int {
        if (h < 0) return -1
        val bb = getBinder() ?: return -1
        val da = Parcel.obtain(); val re = Parcel.obtain()
        return try {
            da.writeInterfaceToken(DESCRIPTOR); da.writeInt(h)
            bb.transact(TX_LOCK_RELEASE_HANDLER, da, re, 0); re.readException(); re.readInt()
        } catch (t: Throwable) { -1 } finally { re.recycle(); da.recycle() }
    }
}
