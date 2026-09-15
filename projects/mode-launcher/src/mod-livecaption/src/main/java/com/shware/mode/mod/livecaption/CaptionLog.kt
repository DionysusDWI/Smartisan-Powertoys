package com.shware.mode.mod.livecaption

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★★★ 把转写结果**实时落盘**。
 *
 * ## 为什么必须落盘（用户 2026-09-12 提出）
 *
 * > 「可以让转写出来的文本实时写入后台一个文档，然后只展示最新的多少字内容，
 * > 　以避免时间长了之后实时字幕窗口性能下降」
 *
 * **两个独立的问题，一个方案一起解**：
 *
 * | 问题 | 本类的角色 |
 * |---|---|
 * | ★ **卡片性能**：长会话下送进 `TextView` 的字越来越多 | 卡片只渲染**末尾 N 字**（见 [CaptionConfig.showChars]），全文由本类承接 |
 * | ★ **内容会丢**：字幕只活在内存里，服务一停/一崩**全没了** | 每句定稿**立刻追加**到文件并 flush ⇒ 随时 `adb pull` 都在 |
 *
 * ## 落点
 *
 * ```
 * /sdcard/Android/data/com.shware.mode.mod.livecaption/files/captions/caption-<yyyyMMdd_HHmmss>.txt
 * ```
 *
 * - 用 `getExternalFilesDir` ⇒ **不需要任何存储权限**（app 自己的外部目录）
 * - ★ **每次服务启动开一个新文件**（文件名带时间戳）⇒ 不会把多次会话混在一起
 * - ★ **每写一行就 flush** ⇒ 会话中途 `adb pull` 也能拿到完整内容，不必等它正常结束
 *
 * ## 行格式
 *
 * ```
 * [16:21:23] 便是战斗机的研发同样负责气动测试，这一次库尔觉得无论如何都得整一个风洞了。
 * ```
 *
 * ⚠️ **只写【已定稿】的句子**（`completed` 事件）——
 * 那些「识别中的临时文本」（`stash`）**会被后续结果修正**，写进去等于存错字。
 */
class CaptionLog(context: Context) {

    private val fmtTime = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 本次会话的转写文件（服务启动时定下来，整个会话都写它） */
    val file: File

    private var writer: BufferedWriter? = null

    @Volatile
    var lines: Int = 0
        private set

    @Volatile
    var chars: Int = 0
        private set

    /** 写失败过吗（写失败不能静默 —— 用户以为在存其实没存） */
    @Volatile
    var lastError: String? = null
        private set

    init {
        val dir = File(context.getExternalFilesDir(null), "captions")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        file = File(dir, "caption-$stamp.txt")
        runCatching {
            dir.mkdirs()
            writer = BufferedWriter(FileWriter(file, true))
            Log.i(TAG, "✓ 转写落盘：${file.absolutePath}")
        }.onFailure {
            lastError = it.message
            Log.w(TAG, "✗ 打开转写文件失败：${it.message}")
        }
    }

    /**
     * 追加一句**已定稿**的转写。
     *
     * 用 `synchronized` —— 调用点在主线程（`onAsrEvent`），
     * 但将来若挪到别的线程也不会写坏。
     */
    @Synchronized
    fun append(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val w = writer ?: return
        runCatching {
            w.write("[${fmtTime.format(Date())}] $t")
            w.newLine()
            w.flush()                 // ★ 立刻落盘 —— 中途 pull 也能拿到
            lines++
            chars += t.length
        }.onFailure {
            lastError = it.message
            Log.w(TAG, "✗ 写转写失败：${it.message}")
        }
    }

    /** 服务停止时收尾 */
    @Synchronized
    fun close() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        Log.i(TAG, "转写文件已收尾：${file.name}  共 $lines 行 / $chars 字")
    }

    /** 给设置界面看的一行摘要 */
    fun summary(): String = buildString {
        append("文件  ").append(file.name).append('\n')
        append("已写  ").append(lines).append(" 行 / ").append(chars).append(" 字")
        lastError?.let { append("\n⚠️ 最近一次写失败：").append(it) }
    }

    private companion object {
        const val TAG = "ModeMod/Caption"
    }
}
