package io.zer0.muse.livecall

import io.zer0.common.AppJson
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 服务端请求执行的一次工具调用(Live toolCall 或 modelTurn 内的 functionCall)。 */
data class LiveToolCallRequest(
    val id: String,
    val name: String,
    val args: JsonObject,
)

/** 一次工具执行的结果,回送服务端时包装成 functionResponse。 */
data class LiveToolResponse(
    val id: String,
    val name: String,
    val rawResult: String,
)

/** 解码后的服务端事件。一条 WebSocket 消息可以解出多个事件(按到达顺序)。 */
sealed interface LiveServerEvent {

    /** setup 被接受,会话可开始收发音频。 */
    data object SetupComplete : LiveServerEvent

    /** 模型下行音频(24kHz PCM16),直接送播放队列。 */
    data class AudioData(val pcm: ByteArray) : LiveServerEvent {
        override fun equals(other: Any?): Boolean =
            other is AudioData && pcm.contentEquals(other.pcm)

        override fun hashCode(): Int = pcm.contentHashCode()
    }

    /** Malik 说话的转写增量。 */
    data class InputTranscript(val text: String) : LiveServerEvent

    /** Shaheen 说话的转写增量(屏幕字幕逐词生长靠它)。 */
    data class OutputTranscript(val text: String) : LiveServerEvent

    /** 服务端 VAD 判定 Malik 插话 —— 客户端必须立刻清空播放队列(barge-in)。 */
    data object Interrupted : LiveServerEvent

    /** 本轮模型输出结束。 */
    data object TurnComplete : LiveServerEvent

    /** 服务端要求执行工具(执行后以 toolResponse 回送)。 */
    data class ToolCalls(val calls: List<LiveToolCallRequest>) : LiveServerEvent

    /** 之前发出的工具调用被服务端取消(迟到的执行结果应丢弃,不必回送)。 */
    data class ToolCallCancellation(val ids: List<String>) : LiveServerEvent

    /** 服务端预告会话即将被切断(可忽略细节,UI 层按普通断开处理)。 */
    data object GoAway : LiveServerEvent

    /** 服务端错误帧(键错误 / 模型错误等都在这里以纯文本 message 出现)。 */
    data class ServerError(val message: String) : LiveServerEvent
}

/**
 * Shaheen 2 Fix Phase 2: Gemini Live 线协议的纯编解码层(无 Android / 网络依赖)。
 *
 * 上行三件套:音频小包(realtimeInput.audio —— mediaChunks 旧式写法已弃用)、
 * 文本回合(clientContent,仅用于开场问候触发)、工具结果(toolResponse)。
 * 下行一条消息可能同时带音频 / 转写 / 打断标记,因此 [decodeServerMessage]
 * 返回事件列表而不是单个事件,避免丢帧。
 */
object LiveCallProtocol {

    /** 编码一帧上行音频:{"realtimeInput":{"audio":{"data":base64,"mimeType":"audio/pcm;rate=16000"}}} */
    fun encodeAudioChunk(pcm: ByteArray): String =
        buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", Base64.getEncoder().encodeToString(pcm))
                    put("mimeType", LiveCallConfig.INPUT_AUDIO_MIME)
                }
            }
        }.toString()

    /** 编码一条 clientContent 文本回合(开场问候触发用)。 */
    fun encodeClientTextTurn(text: String): String =
        buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", text) })
                            }
                        },
                    )
                }
                put("turnComplete", true)
            }
        }.toString()

    /**
     * 把工具原始结果包成 functionResponse 的 response 对象:
     * 结果本身是 JSON 对象时原样用,否则包成 {"result": "<原文>"} —— 与聊天侧
     * Gemini functionResponse 的约定一致,保证模型在通话里读到的结果形状和聊天里相同。
     */
    fun wrapToolResult(rawResult: String): JsonElement {
        val parsed = runCatching { AppJson.parseToJsonElement(rawResult) }.getOrNull()
        if (parsed is JsonObject) return parsed
        return buildJsonObject { put("result", rawResult) }
    }

    /** 编码一批工具结果:{"toolResponse":{"functionResponses":[...]}} */
    fun encodeToolResponses(responses: List<LiveToolResponse>): String =
        buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    responses.forEach { r ->
                        add(
                            buildJsonObject {
                                put("id", r.id)
                                put("name", r.name)
                                put("response", wrapToolResult(r.rawResult))
                            },
                        )
                    }
                }
            }
        }.toString()

    /**
     * 解码一条服务端消息。解析失败 / 未知形状返回空列表(会话层按未知帧忽略,
     * 绝不因一帧坏数据杀掉整通通话)。
     */
    fun decodeServerMessage(text: String): List<LiveServerEvent> {
        val root = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        val events = mutableListOf<LiveServerEvent>()

        if (root.containsKey("setupComplete")) {
            events += LiveServerEvent.SetupComplete
        }

        (root["error"] as? JsonObject)?.let { err ->
            val message = err["message"]?.jsonPrimitive?.contentOrNull
                ?: err["status"]?.jsonPrimitive?.contentOrNull
                ?: "The voice service returned an error."
            events += LiveServerEvent.ServerError(message)
        }

        (root["toolCall"] as? JsonObject)?.let { toolCall ->
            val calls = parseFunctionCalls(toolCall["functionCalls"])
            if (calls.isNotEmpty()) events += LiveServerEvent.ToolCalls(calls)
        }

        (root["toolCallCancellation"] as? JsonObject)?.let { cancel ->
            val ids = (cancel["ids"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            if (ids.isNotEmpty()) events += LiveServerEvent.ToolCallCancellation(ids)
        }

        if (root.containsKey("goAway")) {
            events += LiveServerEvent.GoAway
        }

        (root["serverContent"] as? JsonObject)?.let { content ->
            if ((content["interrupted"] as? JsonPrimitive)?.contentOrNull == "true") {
                events += LiveServerEvent.Interrupted
            }
            (content["inputTranscription"] as? JsonObject)
                ?.get("text")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += LiveServerEvent.InputTranscript(it) }
            (content["outputTranscription"] as? JsonObject)
                ?.get("text")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += LiveServerEvent.OutputTranscript(it) }
            (content["modelTurn"] as? JsonObject)?.let { turn ->
                val parts = (turn["parts"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                val inlineCalls = mutableListOf<LiveToolCallRequest>()
                for (partElement in parts) {
                    val part = partElement as? JsonObject ?: continue
                    (part["inlineData"] as? JsonObject)?.let { inline ->
                        val data = inline["data"]?.jsonPrimitive?.contentOrNull
                        if (!data.isNullOrEmpty()) {
                            val pcm = runCatching { Base64.getDecoder().decode(data) }.getOrNull()
                            if (pcm != null && pcm.isNotEmpty()) {
                                events += LiveServerEvent.AudioData(pcm)
                            }
                        }
                    }
                    (part["functionCall"] as? JsonObject)?.let { fc ->
                        parseFunctionCall(fc)?.let { inlineCalls += it }
                    }
                }
                if (inlineCalls.isNotEmpty()) events += LiveServerEvent.ToolCalls(inlineCalls)
            }
            if ((content["turnComplete"] as? JsonPrimitive)?.contentOrNull == "true") {
                events += LiveServerEvent.TurnComplete
            }
        }

        return events
    }

    private fun parseFunctionCalls(arrayElement: JsonElement?): List<LiveToolCallRequest> {
        val array = arrayElement as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { el -> (el as? JsonObject)?.let { parseFunctionCall(it) } }
    }

    private fun parseFunctionCall(obj: JsonObject): LiveToolCallRequest? {
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        // Live 的 functionCall 有时不带 id;缺失时以工具名兜底(functionResponse 靠 id+name 对齐)。
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: name
        val args = (obj["args"] as? JsonObject) ?: JsonObject(emptyMap())
        return LiveToolCallRequest(id = id, name = name, args = args)
    }
}
