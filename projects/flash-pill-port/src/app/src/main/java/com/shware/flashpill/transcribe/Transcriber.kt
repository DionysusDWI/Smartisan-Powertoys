package com.shware.flashpill.transcribe

/**
 * 语音转写接口。实现可替换（SiliconFlow / DashScope / 系统 STT / 端上 Whisper）。
 */
interface Transcriber {
    /**
     * 转写音频文件。
     *
     * @param audioPath 本地音频文件路径（推荐 16kHz / 单声道 / 16bit WAV）
     * @param language  可选语言提示（如 "zh"）
     * @return 转写文本
     */
    suspend fun transcribe(audioPath: String, language: String? = null): String
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)