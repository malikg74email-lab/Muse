package io.zer0.muse.livecall

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Shaheen 2 Fix Phase 2: Gemini Live (BidiGenerateContent) 会话的纯配置层。
 *
 * 这里只放常量与 setup 消息的构造(无 Android / 网络依赖),保证 JVM 单测可直接验证
 * 「模型 / 语音 / 系统指令 / 工具声明确实进了 setup」。设计依据见
 * SHAHEEN2_FIX_PHASE2_LOG.md:
 *  - 端点是 Gemini API key 面的官方 raw WebSocket 地址;
 *  - 模型固定为当前的 native-audio Live 模型(带日期的 preview id),
 *    绝不用 Malik 的聊天模型(gemini-3.1-flash-lite 是文本聊天模型,无法走 Live 音频);
 *  - 打开服务端 VAD(automaticActivityDetection.disabled=false)是 barge-in 的前提:
 *    服务端在播放期间检测到 Malik 说话会发 serverContent.interrupted,客户端据此清空播放队列。
 */
object LiveCallConfig {

    /** 当前 native-audio Live 模型(原生音频 = 更自然的人声 + 支持 function calling)。 */
    const val LIVE_MODEL_ID = "gemini-2.5-flash-native-audio-preview-12-2025"

    /** Gemini Live 预置语音(沉稳、信息型男声)。 */
    const val DEFAULT_VOICE_NAME = "Charon"

    /** 说话语种:巴基斯坦乌尔都语 —— 配合 Roman Urdu 的语音人格,让发音/语调贴合。 */
    const val DEFAULT_LANGUAGE_CODE = "ur-PK"

    /** 麦克风上行采样率(Live API 输入固定要求 16kHz PCM)。 */
    const val INPUT_SAMPLE_RATE = 16_000

    /** 模型下行音频采样率(Live API 输出固定 24kHz PCM)。 */
    const val OUTPUT_SAMPLE_RATE = 24_000

    /** 上行帧长:100ms 小包流式发送(低延迟,符合「小压缩包流式」要求)。 */
    const val CHUNK_MS = 100

    /** 单帧 PCM 字节数(16-bit 单声道):16000 * 2 * 100 / 1000 = 3200。 */
    const val INPUT_FRAME_BYTES = INPUT_SAMPLE_RATE * 2 * CHUNK_MS / 1000

    /** 上行音频的 MIME(Live API 要求带 rate 后缀)。 */
    const val INPUT_AUDIO_MIME = "audio/pcm;rate=$INPUT_SAMPLE_RATE"

    private const val WS_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** 拼接带 key 的 WebSocket 地址。key 只出现在连接 URL 中,绝不写进日志。 */
    fun webSocketUrl(apiKey: String): String = "$WS_BASE_URL?key=$apiKey"

    /**
     * 语音人格:与聊天人格(default_persona_en.prompt)同源的精简版 —— 只保留在
     * 语音通话里承重的规则(身份 / 所有者 / Roman Urdu / 短句 / 先动手再汇报 /
     * 不伪造成功)。聊天的 mood 标记、artifact 规则等文本格式要求不进口语。
     */
    const val VOICE_SYSTEM_INSTRUCTION = """
You are Shaheen 2, Malik's personal mobile agent. Your core name is Shaheen. You were created by Azhar Farooq. Never say you are Muse or mention Muse; if asked who made you, answer: Azhar Farooq.

You are on a LIVE VOICE CALL with Malik, your owner. Speak Roman Urdu (Urdu written in Latin script) by default. If Malik speaks English, answer in English. Never speak Chinese and never use Urdu script.

Keep every spoken reply SHORT and natural — one or two sentences unless Malik asks for detail. No lists, no headings, no markup: only words that sound right when spoken aloud.

You can act on this phone with your tools while you talk: open apps with app_launch, see the screen with screen_read, tap and type with screen_tap_text, screen_input, screen_tap, screen_swipe, screen_back and screen_home. When Malik asks you to DO something, do it with a tool first, then tell him the result in one short sentence. Never claim you did something you did not do. If a tool fails, say plainly what failed.

Malik is your owner, not a member of the public. Obey him; do not lecture him. If something is genuinely impossible, say so in one plain line and offer the closest thing you can do.
"""

    /** setup 完成后的开场触发语:让 Shaheen 先开口问候,像真通话被接通一样。 */
    const val GREETING_KICKOFF_TEXT =
        "(The live call has just connected. Greet Malik briefly in Roman Urdu and ask what he needs.)"

    /**
     * 构造 BidiGenerateContent 的首条 setup 消息(JSON 字符串)。
     *
     * @param toolDeclarations [LiveCallToolBridge] 产出的 functionDeclarations;为空时省略 tools 字段。
     */
    fun buildSetupMessage(
        systemInstruction: String = VOICE_SYSTEM_INSTRUCTION,
        toolDeclarations: List<JsonObject> = emptyList(),
        modelId: String = LIVE_MODEL_ID,
        voiceName: String = DEFAULT_VOICE_NAME,
        languageCode: String = DEFAULT_LANGUAGE_CODE,
    ): String {
        val setup = buildJsonObject {
            put("model", "models/$modelId")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    put("languageCode", languageCode)
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") {
                            put("voiceName", voiceName)
                        }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemInstruction) })
                }
            }
            if (toolDeclarations.isNotEmpty()) {
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            put("functionDeclarations", JsonArray(toolDeclarations))
                        },
                    )
                }
            }
            // 转写回显:屏幕上的逐行字幕靠这两个开关,没有它们服务端不回传写文本。
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            // 服务端 VAD 保持开启 —— barge-in(用户插话打断播放)的前提。
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    put("disabled", false)
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    put("prefixPaddingMs", 20)
                    put("silenceDurationMs", 500)
                }
            }
            // 滑动窗口压缩:不开的话 native-audio 会话会在默认短窗口处被服务端切断。
            putJsonObject("contextWindowCompression") {
                putJsonObject("slidingWindow") {}
            }
        }
        return buildJsonObject { put("setup", setup) }.toString()
    }
}
