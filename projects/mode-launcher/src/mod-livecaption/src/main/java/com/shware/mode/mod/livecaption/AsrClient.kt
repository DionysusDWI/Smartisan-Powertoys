package com.shware.mode.mod.livecaption

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ★★★ DashScope **实时语音识别**客户端。
 *
 * ## 协议（2026-09-12 实测确认，见 [归档 08](../../../../../../../../.ref/Search-Results/08-realtime-asr.md)）
 *
 * 就是 **OpenAI Realtime 协议**：
 * ```
 * wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime
 * Header: Authorization: bearer <key>
 *
 * 发  {"type":"input_audio_buffer.append","audio":"<base64 16kHz 单声道 PCM16>"}
 * 收  conversation.item.input_audio_transcription.text       ← text=已定稿 / stash=识别中的临时文本
 * 收  conversation.item.input_audio_transcription.completed  ← transcript=整句
 * ```
 *
 * **`text` + `stash` 拼起来就是屏幕上滚动的字幕** —— `stash` 会被后续事件修正。
 *
 * ## 音频要求
 *
 * **16 kHz · 单声道 · PCM16**（`session.created` 里报的就是这个）。
 * 采样率不对 ⇒ 识别结果会变成乱码，这是最容易踩的坑。
 *
 * ## ★★★ `session.update` —— 为什么必须主动下发（2026-09-12 补）
 *
 * **不发的后果**（实测踩到）：`turn_detection` 全用服务端默认值
 * （`server_vad` / `silence_duration_ms=800`）⇒ **断句参数一个都控不了**，
 * 出了问题只能猜。
 *
 * ⇒ 连上后主动下发 [SESSION_CONFIG]，把 `silence_duration_ms` 压到 **500 ms**，
 * 比调用方注入的静音（800 ms）**短**，留出余量保证一定触发。
 *
 * > ★ `session.updated` 会把**服务端实际采纳的**配置回推 —— 可以对账，不靠猜。
 */
class AsrClient(
    private val apiKey: String,
    private val model: String,
    private val onEvent: (Event) -> Unit,
) {

    /** 从服务端收到的东西，翻译成本 mod 关心的语义 */
    sealed class Event {
        /** 会话建立（`session.created`）—— 说明鉴权过了 */
        data class Connected(val model: String) : Event()

        /** ★ 增量结果：`text` 已定稿 + `stash` 识别中的临时文本 */
        data class Partial(val text: String, val stash: String, val language: String?) : Event()

        /** ★ 整句定稿 */
        data class Final(val transcript: String, val language: String?, val seconds: Double?) : Event()

        /** 出错（网络 / 鉴权 / 协议） */
        data class Failed(val why: String) : Event()

        /** 连接关闭 */
        data class Closed(val code: Int, val reason: String) : Event()
    }

    private val http = OkHttpClient.Builder()
        // 长连接：读超时设 0（不超时），靠 ping 保活
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    var isOpen = false
        private set

    fun connect() {
        val url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=$model"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "bearer $apiKey")
            .build()
        Log.i(TAG, "→ 连接 $url")
        ws = http.newWebSocket(req, listener)
    }

    /**
     * 推一段 PCM。**必须在 [isOpen] 时调**，否则静默丢弃。
     *
     * ⚠️ 别把整段音频一次性灌进去 —— 那样服务端会当成一整句，字幕就不"滚"了。
     * 按 100 ms 一片持续推，配合 `server_vad` 自动断句。
     */
    fun append(pcm: ByteArray) {
        val socket = ws ?: return
        if (!isOpen) return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val ok = socket.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", b64).toString())
        if (!ok) Log.w(TAG, "append 失败（发送队列满？）")
    }

    fun close() {
        runCatching { ws?.close(1000, "bye") }
        ws = null
        isOpen = false
    }

    /**
     * ★★★ 主动下发断句配置 —— **不发就用服务端默认值，等于把断句交给运气**。
     *
     * `silence_duration_ms` 取 [SILENCE_DURATION_MS]（500）——
     * **必须小于调用方注入的静音长度**，否则注入了也触发不了。
     */
    private fun updateSession() {
        val session = JSONObject()
            .put("input_audio_format", "pcm")
            .put("input_audio_transcription", JSONObject().put("model", model))
            .put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", VAD_THRESHOLD)
                    .put("silence_duration_ms", SILENCE_DURATION_MS)
            )
        val msg = JSONObject().put("type", "session.update").put("session", session)
        val ok = ws?.send(msg.toString()) ?: false
        Log.i(TAG, "${if (ok) "→" else "✗"} session.update 已下发：$session")
    }

    // ------------------------------------------------------------------

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "✓ WS 已开")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handle(text) }
                .onFailure { Log.w(TAG, "解析消息失败: ${it.message}  raw=${text.take(200)}") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isOpen = false
            val why = buildString {
                append(t.javaClass.simpleName).append(": ").append(t.message)
                response?.let { append("  HTTP ").append(it.code) }
            }
            Log.w(TAG, "✗ WS 失败: $why")
            onEvent(Event.Failed(why))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            Log.i(TAG, "WS 关闭 code=$code reason=$reason")
            onEvent(Event.Closed(code, reason))
        }
    }

    private fun handle(raw: String) {
        val d = JSONObject(raw)
        when (val type = d.optString("type")) {
            "session.created", "session.updated" -> {
                isOpen = true
                val s = d.optJSONObject("session")
                val m = s?.optString("model") ?: model
                Log.i(TAG, "✓ 会话已建（$type）model=$m")
                // ★ 对账：服务端**实际采纳**的断句配置（不是我们想发的）
                s?.optJSONObject("turn_detection")?.let {
                    Log.i(TAG, "   ↳ 断句配置：type=${it.optString("type")} " +
                            "threshold=${it.optDouble("threshold")} " +
                            "silence=${it.optInt("silence_duration_ms")}ms")
                }
                onEvent(Event.Connected(m))
                // ★ session.created 之后才能下发 —— 早于它发会被拒
                if (type == "session.created") updateSession()
            }

            // ★ 增量：text 已定稿 + stash 识别中的临时文本
            "conversation.item.input_audio_transcription.text" -> {
                onEvent(
                    Event.Partial(
                        text = d.optString("text"),
                        stash = d.optString("stash"),
                        language = d.optString("language").takeIf { it.isNotEmpty() },
                    )
                )
            }

            // ★ 整句定稿
            "conversation.item.input_audio_transcription.completed" -> {
                val usage = d.optJSONObject("usage")
                onEvent(
                    Event.Final(
                        transcript = d.optString("transcript"),
                        language = d.optString("language").takeIf { it.isNotEmpty() },
                        seconds = usage?.optDouble("duration")?.takeIf { !it.isNaN() },
                    )
                )
            }

            "error" -> {
                val err = d.optJSONObject("error")
                val why = err?.let { "${it.optString("code")}: ${it.optString("message")}" }
                    ?: raw.take(200)
                Log.w(TAG, "✗ 服务端报错 $why")
                onEvent(Event.Failed(why))
            }

            else -> Log.v(TAG, "忽略消息 type=$type")
        }
    }

    private companion object {
        const val TAG = "ModeMod/Caption"

        /** 服务端 VAD 的判定门限（0–1，RMS 口径）—— 归档 08 记录的可取值 */
        const val VAD_THRESHOLD = 0.2

        /**
         * ★ 服务端判"这句说完了"所需的静音时长（ms）。
         *
         * **必须小于调用方注入的静音长度**（[CaptionService.INJECT_SILENCE_MS] = 800），
         * 否则注入了也触发不了 —— 这就是 §X1 那个「0 定稿」的根因所在。
         */
        const val SILENCE_DURATION_MS = 500
    }
}
