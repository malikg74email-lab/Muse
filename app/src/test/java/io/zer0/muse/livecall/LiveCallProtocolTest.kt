package io.zer0.muse.livecall

import io.zer0.common.AppJson
import java.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 2: Live 线协议编解码测试(录制的服务端帧形状作 fixture)。
 */
class LiveCallProtocolTest {

    @Test
    fun `audio chunk encodes base64 pcm under realtimeInput audio`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val json = AppJson.parseToJsonElement(LiveCallProtocol.encodeAudioChunk(pcm)).jsonObject
        val audio = json.getValue("realtimeInput").jsonObject.getValue("audio").jsonObject
        assertEquals("audio/pcm;rate=16000", audio.getValue("mimeType").jsonPrimitive.contentOrNull)
        assertTrue(Base64.getDecoder().decode(audio.getValue("data").jsonPrimitive.contentOrNull).contentEquals(pcm))
    }

    @Test
    fun `client text turn is a completed user turn`() {
        val json = AppJson.parseToJsonElement(
            LiveCallProtocol.encodeClientTextTurn("hello"),
        ).jsonObject
        val clientContent = json.getValue("clientContent").jsonObject
        assertEquals("true", clientContent.getValue("turnComplete").jsonPrimitive.contentOrNull)
        val turn = clientContent.getValue("turns").let {
            (it as kotlinx.serialization.json.JsonArray).single().jsonObject
        }
        assertEquals("user", turn.getValue("role").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `tool result wraps plain text but keeps json objects intact`() {
        val wrapped = LiveCallProtocol.wrapToolResult("YouTube is open").jsonObject
        assertEquals("YouTube is open", wrapped.getValue("result").jsonPrimitive.contentOrNull)

        val rawJson = """{"opened":true,"package":"com.google.android.youtube"}"""
        val kept = LiveCallProtocol.wrapToolResult(rawJson).jsonObject
        assertEquals("true", kept.getValue("opened").jsonPrimitive.contentOrNull)
        assertEquals(
            "com.google.android.youtube",
            kept.getValue("package").jsonPrimitive.contentOrNull,
        )
    }

    @Test
    fun `tool responses encode ids names and wrapped results`() {
        val encoded = LiveCallProtocol.encodeToolResponses(
            listOf(LiveToolResponse(id = "fc-1", name = "app_launch", rawResult = "opened")),
        )
        val responses = AppJson.parseToJsonElement(encoded).jsonObject
            .getValue("toolResponse").jsonObject
            .getValue("functionResponses") as kotlinx.serialization.json.JsonArray
        val first = responses.single().jsonObject
        assertEquals("fc-1", first.getValue("id").jsonPrimitive.contentOrNull)
        assertEquals("app_launch", first.getValue("name").jsonPrimitive.contentOrNull)
        assertEquals(
            "opened",
            first.getValue("response").jsonObject.getValue("result").jsonPrimitive.contentOrNull,
        )
    }

    @Test
    fun `decode setupComplete`() {
        val events = LiveCallProtocol.decodeServerMessage("""{"setupComplete":{}}""")
        assertEquals(listOf(LiveServerEvent.SetupComplete), events)
    }

    @Test
    fun `decode model turn audio part to pcm bytes`() {
        val pcm = byteArrayOf(9, 8, 7, 6)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val frame = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]}}}"""
        val events = LiveCallProtocol.decodeServerMessage(frame)
        assertEquals(1, events.size)
        val audio = events.single() as LiveServerEvent.AudioData
        assertTrue(audio.pcm.contentEquals(pcm))
    }

    @Test
    fun `decode transcriptions interruption and turn complete in one frame`() {
        val frame = """
            {"serverContent":{
              "inputTranscription":{"text":"YouTube kholo"},
              "outputTranscription":{"text":"Ji Malik, "},
              "interrupted":true,
              "turnComplete":true
            }}
        """.trimIndent()
        val events = LiveCallProtocol.decodeServerMessage(frame)
        assertTrue(LiveServerEvent.Interrupted in events)
        assertTrue(LiveServerEvent.InputTranscript("YouTube kholo") in events)
        assertTrue(LiveServerEvent.OutputTranscript("Ji Malik, ") in events)
        assertTrue(LiveServerEvent.TurnComplete in events)
    }

    @Test
    fun `decode top level toolCall with args`() {
        val frame = """
            {"toolCall":{"functionCalls":[
              {"id":"fc-9","name":"app_launch","args":{"app":"YouTube"}}
            ]}}
        """.trimIndent()
        val events = LiveCallProtocol.decodeServerMessage(frame)
        val toolCalls = events.filterIsInstance<LiveServerEvent.ToolCalls>().single()
        val call = toolCalls.calls.single()
        assertEquals("fc-9", call.id)
        assertEquals("app_launch", call.name)
        assertEquals("YouTube", call.args.getValue("app").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `decode functionCall inside model turn parts, id falls back to name`() {
        val frame = """
            {"serverContent":{"modelTurn":{"parts":[
              {"functionCall":{"name":"screen_read","args":{}}}
            ]}}}
        """.trimIndent()
        val events = LiveCallProtocol.decodeServerMessage(frame)
        val call = events.filterIsInstance<LiveServerEvent.ToolCalls>()
            .single().calls.single()
        assertEquals("screen_read", call.name)
        assertEquals("screen_read", call.id)
    }

    @Test
    fun `decode tool call cancellation and goAway and server error`() {
        val cancel = LiveCallProtocol.decodeServerMessage(
            """{"toolCallCancellation":{"ids":["a","b"]}}""",
        )
        assertEquals(listOf(LiveServerEvent.ToolCallCancellation(listOf("a", "b"))), cancel)

        val goAway = LiveCallProtocol.decodeServerMessage("""{"goAway":{"timeLeft":"10s"}}""")
        assertEquals(listOf(LiveServerEvent.GoAway), goAway)

        val error = LiveCallProtocol.decodeServerMessage(
            """{"error":{"code":400,"message":"API key not valid"}}""",
        )
        assertEquals(listOf(LiveServerEvent.ServerError("API key not valid")), error)
    }

    @Test
    fun `cancelled tool responses are dropped before sending`() {
        val responses = listOf(
            LiveToolResponse(id = "fc-1", name = "app_launch", rawResult = "opened"),
            LiveToolResponse(id = "fc-2", name = "screen_read", rawResult = "text"),
        )
        // 执行途中到达的取消:对应结果必须被丢掉,其余照常发送。
        val kept = LiveCallProtocol.dropCancelledResponses(responses, setOf("fc-1"))
        assertEquals(listOf("fc-2"), kept.map { it.id })
        // 全部取消 → 一条都不发。
        assertTrue(
            LiveCallProtocol.dropCancelledResponses(responses, setOf("fc-1", "fc-2")).isEmpty(),
        )
        // 无取消 → 原样返回。
        assertEquals(responses, LiveCallProtocol.dropCancelledResponses(responses, emptySet()))
    }

    @Test
    fun `decode garbage or unknown frames yields no events`() {
        assertTrue(LiveCallProtocol.decodeServerMessage("not json").isEmpty())
        assertTrue(LiveCallProtocol.decodeServerMessage("""{"usageMetadata":{}}""").isEmpty())
        assertTrue(LiveCallProtocol.decodeServerMessage("""{"serverContent":{}}""").isEmpty())
    }
}
