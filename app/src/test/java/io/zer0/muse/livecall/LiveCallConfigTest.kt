package io.zer0.muse.livecall

import io.zer0.common.AppJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 2: Live 会话 setup 消息的离线契约测试。
 *
 * 钉死四件事:模型是 native-audio Live 模型(不是 Malik 的聊天模型)、输出模态是
 * AUDIO 且语音/语种正确、系统指令是语音人格(含 Roman Urdu 与身份规则)、
 * 工具声明与转写开关都进了 setup。这些字段错一个,真机上就是「打不通/听不懂」。
 */
class LiveCallConfigTest {

    private fun setupJson(toolDeclarations: List<kotlinx.serialization.json.JsonObject> = emptyList()) =
        AppJson.parseToJsonElement(
            LiveCallConfig.buildSetupMessage(toolDeclarations = toolDeclarations),
        ).jsonObject.getValue("setup").jsonObject

    @Test
    fun `setup uses the native audio live model, never the chat model`() {
        val setup = setupJson()
        assertEquals(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            setup.getValue("model").jsonPrimitive.contentOrNull,
        )
        assertEquals(LiveCallConfig.LIVE_MODEL_ID, "gemini-2.5-flash-native-audio-preview-12-2025")
    }

    @Test
    fun `setup requests audio responses with the configured voice and language`() {
        val setup = setupJson()
        val generationConfig = setup.getValue("generationConfig").jsonObject
        val modalities = generationConfig.getValue("responseModalities").jsonArray
            .map { it.jsonPrimitive.contentOrNull }
        assertEquals(listOf("AUDIO"), modalities)
        val speechConfig = generationConfig.getValue("speechConfig").jsonObject
        assertEquals("ur-PK", speechConfig.getValue("languageCode").jsonPrimitive.contentOrNull)
        val voiceName = speechConfig.getValue("voiceConfig").jsonObject
            .getValue("prebuiltVoiceConfig").jsonObject
            .getValue("voiceName").jsonPrimitive.contentOrNull
        assertEquals(LiveCallConfig.DEFAULT_VOICE_NAME, voiceName)
    }

    @Test
    fun `setup carries the voice persona with Roman Urdu and identity rules`() {
        val setup = setupJson()
        val parts = setup.getValue("systemInstruction").jsonObject
            .getValue("parts").jsonArray
        val text = parts.single().jsonObject.getValue("text").jsonPrimitive.contentOrNull.orEmpty()
        assertTrue(text.contains("Shaheen 2"))
        assertTrue(text.contains("Roman Urdu"))
        assertTrue(text.contains("Azhar Farooq"))
        assertTrue(text.contains("Never say you are Muse"))
    }

    @Test
    fun `setup enables both transcriptions and server VAD for barge-in`() {
        val setup = setupJson()
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
        val vad = setup.getValue("realtimeInputConfig").jsonObject
            .getValue("automaticActivityDetection").jsonObject
        assertEquals("false", vad.getValue("disabled").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `setup omits tools when no declarations are given`() {
        assertFalse(setupJson().containsKey("tools"))
    }

    @Test
    fun `web socket url targets the bidi endpoint and carries the key`() {
        val url = LiveCallConfig.webSocketUrl("TEST_KEY")
        assertTrue(url.startsWith("wss://generativelanguage.googleapis.com/ws/"))
        assertTrue(url.contains("BidiGenerateContent"))
        assertTrue(url.endsWith("?key=TEST_KEY"))
    }

    @Test
    fun `audio constants match the live api contract`() {
        assertEquals(16_000, LiveCallConfig.INPUT_SAMPLE_RATE)
        assertEquals(24_000, LiveCallConfig.OUTPUT_SAMPLE_RATE)
        // 100ms @ 16kHz 16-bit mono = 3200 bytes per uplink frame.
        assertEquals(3_200, LiveCallConfig.INPUT_FRAME_BYTES)
        assertEquals("audio/pcm;rate=16000", LiveCallConfig.INPUT_AUDIO_MIME)
    }
}
