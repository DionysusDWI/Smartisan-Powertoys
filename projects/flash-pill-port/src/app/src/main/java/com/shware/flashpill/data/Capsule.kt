package com.shware.flashpill.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "capsules")
data class Capsule(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    /** 转写文字 / 手输文字 */
    val text: String = "",

    /** 原声音频文件路径（可空） */
    val audioPath: String? = null,

    /** CAPTURED / TRANSCRIBING / TRANSCRIBED */
    val status: String = STATUS_CAPTURED,

    /** 色标：NOTE / IMPORTANT / TODO / MESSAGE / IDEA（对应原版 5 分类） */
    val colorTag: String? = null,

    /** 来源：sidebar / text / share / intent */
    val source: String = "sidebar",

    val pinned: Boolean = false,

    /** 是否已完成（原版待办胶囊的勾选） */
    val done: Boolean = false,
) {
    companion object {
        const val STATUS_CAPTURED = "CAPTURED"
        const val STATUS_TRANSCRIBING = "TRANSCRIBING"
        const val STATUS_TRANSCRIBED = "TRANSCRIBED"
    }

    val isTranscribed: Boolean get() = status == STATUS_TRANSCRIBED
}

/** 原版 5 分类色标 */
enum class ColorTag(val key: String) {
    NOTE("NOTE"),          // 便签（蓝）
    IMPORTANT("IMPORTANT"),// 重要事项（红）
    TODO("TODO"),          // 待办事项（橙）
    MESSAGE("MESSAGE"),    // 待发送信息（绿）
    IDEA("IDEA"),          // 灵感（紫）
}