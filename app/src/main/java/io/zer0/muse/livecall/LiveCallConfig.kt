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

    /**
     * 注意:这里故意没有「默认语种代码」常量。native-audio 模型在 setup 阶段会
     * 拒绝显式的 speechConfig.languageCode 并当场关掉会话 —— 2026-11-08 真机
     * 复测的原样报错:"Unsupported language code 'ur-PK' for model
     * models/gemini-2.5-flash-native-audio-preview-12-2025"。这类模型自动识别
     * 语种,语音人格已要求 Roman Urdu,不带代码照样说乌尔都语;以后谁想再把
     * 代码加回来,先看 [LiveLanguageFallback] 与单测钉住的契约。
     */

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

    /**
     * setup 看门狗时限:socket 打开后若迟迟等不到 setupComplete(服务端静默卡住、
     * setup 被无声拒绝等),超过此时限就落到带原因的 ERROR,绝不让界面无限停在
     * 「连接中」。20 秒远长于正常 setup 往返(亚秒级),不会误杀健康连接。
     */
    const val SETUP_TIMEOUT_MS = 20_000L

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
     * @param languageCode 默认 null = setup 里**不带** languageCode 字段 —— 当前
     *  native-audio 模型拒绝显式语种代码(见上方说明),且语种靠自动识别。只有
     *  调用方明确传入时才把该字段写进 speechConfig。
     */
    fun buildSetupMessage(
        systemInstruction: String = VOICE_SYSTEM_INSTRUCTION,
        toolDeclarations: List<JsonObject> = emptyList(),
        modelId: String = LIVE_MODEL_ID,
        voiceName: String = DEFAULT_VOICE_NAME,
        languageCode: String? = null,
    ): String {
        val setup = buildJsonObject {
            put("model", "models/$modelId")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    // 只在显式传入时才写这个字段;默认省略(理由见参数文档)。
                    if (languageCode != null) {
                        put("languageCode", languageCode)
                    }
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

/**
 * 语种代码回退的纯决策(与 [LiveSetupWatchdog] 同一种写法,JVM 可测):
 *
 * 背景:2026-11-08 真机复测里,带 languageCode 的 setup 被服务端以
 * "Unsupported language code 'ur-PK' for model …" 当场拒绝并关连接,界面上的
 * Retry 只会把同一条 setup 原样再发一遍,永远救不回来。当时的正解是 setup
 * 根本不要带代码(已在 [LiveCallConfig.buildSetupMessage] 落实,默认不带)。
 *
 * 这张决策表是**安全网**:万一以后有调用方重新显式带上语种代码、又被服务端
 * 以同样的理由拒绝,引擎自动用**不带代码**的 setup 重连一次,不用人手点
 * Retry。三个条件缺一不可:
 *  - 服务端的话确实指向语种代码([isUnsupportedLanguageError]);
 *  - 刚发出的 setup 确实带了代码(没带代码还被这样拒,重连也不会有区别);
 *  - 本次通话还没用过回退(最多一次,绝不无限重连)。
 */
object LiveLanguageFallback {

    /** 服务端这句话是不是在抱怨语种代码(大小写不敏感地匹配两种说法)。 */
    fun isUnsupportedLanguageError(serverMessage: String?): Boolean {
        if (serverMessage.isNullOrBlank()) return false
        val text = serverMessage.lowercase()
        return "unsupported language" in text || "language code" in text
    }

    /** 是否应当自动改用「不带语种代码」的 setup 重连一次。 */
    fun shouldRetryWithoutLanguageCode(
        serverMessage: String?,
        sentWithLanguageCode: Boolean,
        alreadyRetried: Boolean,
    ): Boolean =
        sentWithLanguageCode && !alreadyRetried && isUnsupportedLanguageError(serverMessage)
}
