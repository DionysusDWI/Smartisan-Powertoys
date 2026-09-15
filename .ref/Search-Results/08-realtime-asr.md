# 08 · **实时语音识别（ASR）云服务** —— SiliconFlow vs DashScope

> 状态:**已归档**(2026-09-12)
> 归档日期:2026-09-12 ｜ 抓取方式:`curl` 直连 API + **WebSocket 实测**
> ★ **背景**:任务 W 要做「实时字幕 mod」—— 需要**流式**语音识别

## 覆盖范围

| 提供商 | 结论 |
|---|---|
| **SiliconFlow（硅基流动）** | ❌ **没有流式端点**（`/v1/realtime` → `Not Found`），只有**文件式** ASR；实测延迟**不可用于实时** |
| ★ **DashScope（阿里云百炼）** | ✅ ★★ **有真正的 realtime ASR**（`qwen3-asr-flash-realtime`，OpenAI Realtime 协议，**已端到端跑通**） |

---

## 一、SiliconFlow —— **不行**

### 模型（`GET /v1/models`，共 94 个，语音相关 7 个）

```
FunAudioLLM/SenseVoiceSmall        ← ASR（文件式）
Qwen/Qwen3-ASR-1.7B                ← ASR
XingChenAGI/XingChenASR-V3.2       ← ASR
XingChenAGI/XingChenASR-V3.2-Ultra
XingChenAGI/XingChenASR-Diarize-V3.0   （带说话人分离）
FunAudioLLM/CosyVoice2-0.5B        ← TTS
fnlp/MOSS-TTSD-v0.5                ← TTS
```

### 端点探测（POST，读错误码判断路由是否存在）

| 端点 | 结果 |
|---|---|
| `/v1/realtime` | ❌ **`Not Found`** —— **路由不存在** |
| `/v1/audio/transcriptions` | ✅ 存在（`{"code":20015,"message":"The parameter is invalid."}`） |
| `/v1/chat/completions` | ✅ 存在 |

### ★ 实测两个硬伤

| # | 现象 |
|---|---|
| **1** | **ASR 连测 3 次，全部 60 秒超时**（`%{time_total}` = 60.01s）⇒ **实时性完全不成立** |
| **2** | ★ **TTS 截断**：一段 5.76 秒的话只生成 **0.28 秒**音频（13614 字节 @24kHz）<br/>且 `voice` **必须写全** `FunAudioLLM/CosyVoice2-0.5B:alex` —— 简称 `alex` 会返回 **53 字节**的错误体 |

> ⚠️ 这两条是 2026-09-12 当日实测；SiliconFlow 可能随时改进，**用之前重新测一次**。

---

## 二、★★★ DashScope —— **有真正的 realtime ASR，且已端到端跑通**

### 模型（`GET /compatible-mode/v1/models`，共 249 个）

**★ 实时 ASR 相关：**

```
qwen3-asr-flash-realtime                  ← ★★ 主角
qwen3-asr-flash-realtime-2025-10-27
qwen3-asr-flash-realtime-2026-02-10
qwen-audio-3.0-realtime-flash
qwen-audio-3.0-realtime-plus
qwen3-livetranslate-flash-realtime        ← 实时识别 + 翻译
qwen3.5-livetranslate-flash-realtime
qwen3-omni-flash-realtime / qwen3-s2s-flash-realtime
qwen3-tts-flash-realtime（TTS） ｜ qwen3-omni / qwen3.5-omni（多模态实时）
```

**非实时：** `fun-asr-flash-2026-06-15` ｜ `qwen-audio-3.0-asr-flash` ｜ `qwen3-asr-flash-2026-02-10`
**TTS（非实时）：** `qwen3-tts-flash` ｜ `qwen3-tts-instruct-flash` ｜ `qwen-tts-2025-05-22`

### ★ 协议 —— **就是 OpenAI Realtime 协议**（实测 `session.created`）

```
wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime
Header: Authorization: bearer <DASHSCOPE_API_KEY>
```

```json
{"type":"session.created","session":{
  "model":"qwen3-asr-flash-realtime",
  "modalities":["text"],
  "input_audio_format":"pcm",           // ★ 16kHz 单声道 PCM16
  "sample_rate":16000,
  "input_audio_transcription":{"model":"qwen3-asr-flash-realtime"},
  "turn_detection":{"type":"server_vad","threshold":0.2,"silence_duration_ms":800}
}}
```

**★ `server_vad`** ⇒ **持续推流即可，服务端自动断句** —— 正是实时字幕要的。
（也可 `session.update` 传 `{"turn_detection": null}` 关掉，改手动 `input_audio_buffer.commit`。）

> ### ★★ 2026-09-12 复测：上面的默认值**确认无误**，并由本机实测补三条
>
> 在客户端加了「把服务端**实际采纳**的配置回读打印」，实测对账：
>
> ```
> session.created（服务端默认）:  server_vad  threshold=0.2  silence=800ms
> session.updated（采纳我们下发）: server_vad  threshold=0.2  silence=500ms
> ```
>
> ① ★ **默认确实是 `silence_duration_ms=800`** —— 上面那份记录是准的。
> ② ★ **`session.update` 确实生效**，且 `session.updated` 会回推**采纳后的**值 ⇒ **可对账，不用猜**。
> ③ ★★ **`silence=500` 比 `800` 断句灵敏得多**（同样内容 50 s 内：500 出 10 条定稿、
> 最长段落 3.1 s；800 只出 3 条、最长段落 **8.9 s**）⇒ **建议下发 500**。
>
> ⚠️ 另注：**`usage.duration` 是【整场会话累计】的计费秒数，不是这一句的时长**
> （连续 10 条定稿的值单调递增：40.0 → 43.0 → … → 77.0）。
> **它直接就是账单口径**，可以拿来当成本表用。

### 收发的消息

| 方向 | 消息 |
|---|---|
| 发 | `{"type":"input_audio_buffer.append","audio":"<base64 pcm16>"}` |
| 发 | `{"type":"input_audio_buffer.commit"}`（VAD 关掉时才需要） |
| 收 | `{"type":"input_audio_buffer.committed"}` |
| 收 | ★ **`conversation.item.input_audio_transcription.text`**<br/>`{"text":"<已定稿>", "stash":"<正在识别的临时文本>", "language":"zh", "emotion":"neutral"}` |
| 收 | ★ **`conversation.item.input_audio_transcription.completed`**<br/>`{"transcript":"<整句定稿>", "language":"zh", "emotion":"neutral", "usage":{...}}` |

> ★ **`text` + `stash` 拼起来就是屏幕上滚动的字幕** —— `stash` 会被后续事件修正。

### ✅ 端到端实测（2026-09-12）

输入（用 DashScope TTS `qwen3-tts-flash` / voice `Cherry` 合成）：

> 大家好，这里是实时字幕测试。今天验证语音识别的准确度和延迟。

推送 5.76 秒 16kHz PCM16 到 realtime ASR，收到：

```
conversation.item.input_audio_transcription.completed
  transcript: "大家好，这里是实时字幕测试，今天验证语音识别的准确度和延迟。"
  language: "zh"   emotion: "neutral"   usage: {"duration": 6, "total_tokens": 113, ...}
```

**⇒ 与原文一字不差。** 计费按 **`usage.duration`（秒）**。

### DashScope TTS（造测试音频用）

```
POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
{"model":"qwen3-tts-flash","input":{"text":"…","voice":"Cherry","language_type":"Chinese"}}
→ 返回 output.audio.url（**要再下载一次**），实测 24kHz 单声道 16bit，时长正常
```

---

## 三、★★★ 顺带查到的**平台事实**：坚果自带的「实时字幕」是 `com.smartisanos.hearingaid`

> 2026-09-12 用户提示「tnt ui 里有系统中的实时字幕，可以参照」后实测查明。

| 项 | 值 |
|---|---|
| **包名** | **`com.smartisanos.hearingaid`**（`flags=[SYSTEM]`） |
| **窗口** | `Window{… u0 HearingAid}`，`ty=APPLICATION_OVERLAY`、`fmt=TRANSPARENT`、**`fill x fill`（铺满 TNT 屏）** |
| **开关** | `settings global`：`hearing_switch` / **`tablet_hearing_real_caption`**（TNT）/ `hearing_real_caption`（手机）/ `hearing_speaker_sound` |
| **入口** | TNT 的**快捷开关面板**里有个「实时字幕」磁贴 |
| **界面** | 居中卡片（带最小化/最大化/关闭装饰）+ **「正在识别系统声音…」** |
| ★ **抓音频的路** | **`android.permission.CAPTURE_AUDIO_OUTPUT`（signature 级）** ⇒ **只有系统应用拿得到** |

**⇒ 三条结论：**
1. **它抓的是【系统声音/播放音频】**，不是麦克风 —— 与 Android Live Caption 语义一致
2. **它走特权路径**（`CAPTURE_AUDIO_OUTPUT`），**无 root 复刻不了那条路**
3. ★ **但公开路径存在**：A10 起普通 app 可用 **`AudioPlaybackCapture` + `MediaProjection`** 抓播放音频
   （代价：要用户授权；且 app 若 `allowAudioPlaybackCapture=false` 则抓不到）
4. ★ 「劫持」在无 root 下**唯一能做到的语义**是**「接管」**：用 `settings` 关掉系统的
   （`tablet_hearing_real_caption=0`，shell/Shizuku 可写），再由自己的 overlay 顶上

> ⚠️ 注意：**`dumpsys captioning` 查不到** —— 那是 AOSP 的服务名，锤子用自己的实现。

---

## 四、★★★ 平台事实补充：**音频输入设备清单**（2026-09-12 实测）

`dumpsys media.audio_policy` → `Available input devices`：

| # | 名字 | type | address | 说明 |
|---|---|---|---|---|
| 1 | **Built-In Mic** | `IN_BUILTIN_MIC` | **bottom** (id=18) | ✅ 底部主麦 |
| 3 | **Built-In Back Mic** | `IN_BACK_MIC` | **back** (id=19) | ✅ 背部麦（降噪） |
| 7 | ★ **USB-Audio - Smartisan TNT go** | `IN_USB_HEADSET` | card=1 (id=95) | ★★ **TNT GO 自带麦克风** |
| 2 | Telephony Rx | `IN_TELEPHONY_RX` | — | 通话回授 |
| 4 | Remote Submix In | `IN_REMOTE_SUBMIX` | — | 播放捕获的虚拟输入 |
| 5,6 | FM Tuner / afe proxy TX | — | — | 非麦 |

### ★★ 关于"波束成形 / 提高指向性"

| 想做的事 | 结论 |
|---|---|
| 用多麦做波束成形 | ❌ **Android 不给普通 app 多通道麦阵** |
| `channel masks 0x000c`（立体声） | ⚠️ 是**混合过**的，**不是原始双麦信号** |
| 手机两麦间距 | ~十几 cm ⇒ 对语音频段指向性本就弱 |
| ★ **实际可行的** | **`AudioRecord.setPreferredDevice()` 选具体麦克风**；<br/>TNT GO 的 USB 麦**物理位置天然正确** |

**⚠️ 实测**：TNT GO 的 USB 麦 **`setPreferredDevice` 返回 true、`getRoutedDevice()` 也确认**，
**但送出来是静音**。手机内置麦正常。⇒ 原因未查明，**自动策略因此优先内置麦**。

### 音频相关的两个隐藏/删除常量（编译坑）

| 常量 | 情况 |
|---|---|
| `AudioDeviceInfo.TYPE_BACK_MIC` | ❌ **隐藏常量**，公开 SDK 没有 —— 改用 `getAddress()` |
| `MediaProjection.start(cb, handler)` | ❌ **compileSdk 35 已删除**，且音频捕获**不需要**它 |

---

## 五、对本案的用法

```
手机播放/麦克风音频
  → 16kHz 单声道 PCM16
  → base64 → input_audio_buffer.append（持续推）
  → conversation.item.input_audio_transcription.text / .completed
  → 卡片上渲染 text + stash
```

**⚠️ 未验证**：Android 侧怎么拿到 16kHz PCM（`AudioPlaybackCapture` vs `MediaRecorder.AudioSource.MIC`）
—— 见 [计划书 W](../../.paper/plans/W-实时字幕mod.md) 的地基验证节。

---

## 来源

| URL / 方式 | 主题 | 抓取日期 | 关键信息 |
|---|---|---|---|
| `GET api.siliconflow.cn/v1/models` | SiliconFlow 模型清单 | 2026-09-12 | 94 个，7 个语音；**无 realtime** |
| `POST api.siliconflow.cn/v1/realtime` | 端点探测 | 2026-09-12 | ❌ **`Not Found`** |
| `POST api.siliconflow.cn/v1/audio/transcriptions` | ASR | 2026-09-12 | 路由存在，但**3 次全 60s 超时** |
| `GET dashscope.aliyuncs.com/compatible-mode/v1/models` | DashScope 模型清单 | 2026-09-12 | 249 个；★ **`qwen3-asr-flash-realtime`** |
| ★ `wss://dashscope.aliyuncs.com/api-ws/v1/realtime` | ★ **realtime ASR 协议** | 2026-09-12 | ★ **OpenAI Realtime 协议；16k PCM；server_vad；实测识别正确** |
| `POST dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation` | DashScope TTS | 2026-09-12 | `qwen3-tts-flash` + voice `Cherry`，返回音频 URL |

> **注：这次没走 SearxNG** —— 它当时引擎全被限流（`brave: too many requests`、`duckduckgo/google/startpage: CAPTCHA`）。
> 改为**直接问 API 自己**（列模型 + 探端点 + 实测 WS），比搜文档更权威。
