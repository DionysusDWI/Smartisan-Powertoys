package com.shware.flashpill.transcribe

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * 最简 WAV 录音器：AudioRecord(PCM16) → 16kHz / 单声道 / 16bit WAV。
 * 选 WAV 是因为 SenseVoice 可直接识别，且无需引入编码库。
 */
class WavRecorder(
    private val sampleRate: Int = 16_000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
) {
    companion object {
        private const val TAG = "WavRecorder"
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44
        private const val BUFFER_BYTES = 4096
    }

    @Volatile
    private var recording = false

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var output: File? = null

    /** 实时振幅回调（0~32767），用于绘制波形。 */
    var onAmplitude: ((Int) -> Unit)? = null

    val isRecording: Boolean get() = recording

    fun start(target: File) {
        if (recording) return

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) throw IllegalStateException("getMinBufferSize failed: $minBuf")

        @SuppressLint("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuf * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord 初始化失败")
        }

        audioRecord = record
        output = target
        recording = true
        worker = thread(name = "wav-recorder") { writeLoop(record, target) }
        record.startRecording()
    }

    /** 停止录音并返回 WAV 文件；未在录音时返回 null。 */
    fun stop(): File? {
        if (!recording) return null
        recording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        worker?.join(2_000)
        audioRecord?.release()
        audioRecord = null
        worker = null
        return output
    }

    private fun writeLoop(record: AudioRecord, target: File) {
        val buffer = ByteArray(BUFFER_BYTES)
        var totalBytes = 0L
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(target, "rw")
            raf.setLength(0)
            raf.write(ByteArray(WAV_HEADER_SIZE)) // 先占位，最后回填

            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    raf.write(buffer, 0, read)
                    totalBytes += read
                    onAmplitude?.invoke(peakAmplitude(buffer, read))
                } else if (read < 0) {
                    Log.w(TAG, "read error: $read")
                    break
                }
            }

            raf.seek(0)
            raf.write(createWavHeader(totalBytes))
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
        } finally {
            runCatching { raf?.close() }
        }
    }

    private fun peakAmplitude(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val v = if (sample > 32767) sample - 65536 else sample
            val abs = if (v < 0) -v else v
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    private fun createWavHeader(dataSize: Long): ByteArray {
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // SubChunk1Size
        header.putShort(1.toShort()) // PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // blockAlign
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())
        return header.array()
    }
}