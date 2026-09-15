package com.shware.flashpill.transcribe

import com.shware.flashpill.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 硅基流动（SiliconFlow）语音转写实现。
 *
 * - 端点：POST https://api.siliconflow.cn/v1/audio/transcriptions
 * - 模型：FunAudioLLM/SenseVoiceSmall
 * - 请求：multipart/form-data（model + file）
 * - 响应：{"text":"...","language":"Chinese","usage":{...}}
 *
 * 实测备注：偶发连接失败（HTTP 000），必须带重试。
 */
class SiliconFlowTranscriber(
    private val apiKey: String,
    private val endpoint: String = BuildConfig.SILICONFLOW_ASR_ENDPOINT,
    private val model: String = BuildConfig.SILICONFLOW_ASR_MODEL,
    private val maxAttempts: Int = 3,
) : Transcriber {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun transcribe(audioPath: String, language: String?): String =
        withContext(Dispatchers.IO) {
            val file = File(audioPath)
            if (!file.exists() || file.length() == 0L) {
                throw TranscriptionException("音频文件不存在或为空: $audioPath")
            }
            if (apiKey.isBlank() || apiKey.startsWith("sk-xxx")) {
                throw TranscriptionException("未配置 SILICONFLOW_API_KEY（见 local.properties）")
            }

            var lastError: Exception? = null
            repeat(maxAttempts) { attempt ->
                try {
                    return@withContext requestOnce(file)
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < maxAttempts - 1) {
                        delay(800L * (attempt + 1)) // 线性退避
                    }
                }
            }
            throw TranscriptionException("转写失败（已重试 $maxAttempts 次）", lastError)
        }

    private fun requestOnce(file: File): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${payload.take(300)}")
            }
            val text = JSONObject(payload).optString("text", "")
            if (text.isBlank()) {
                throw IOException("响应中无 text: ${payload.take(300)}")
            }
            return text
        }
    }
}