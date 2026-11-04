package io.zer0.muse.data

import io.zer0.common.Logger
import io.zer0.memory.fact.DedupVerdict
import io.zer0.memory.fact.FactDedupJudge
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v12: LLM 事实去重判定器 — 算法层(字符相似度/实体键)无法确定的模糊候选对,
 * 交给大模型判断是否为同一事实。
 *
 * 设计:
 *  - 只在 FactStore 的"同实体键有候选但文本判定失败"低频场景被调用,每次 1 次 LLM 调用;
 *  - LLM 输出严格 JSON,解析失败/超时/异常一律降级为"不同一"(宁可不合并,不阻塞写入);
 *  - 判定缓存由 FactStore 侧维护(同事实对只问一次)。
 */
class LlmFactDedupJudge(
    private val llmClient: MemoryLlmClient,
) : FactDedupJudge {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun judge(a: String, b: String, entityKeyA: String?, entityKeyB: String?): DedupVerdict {
        return try {
            val systemPrompt = buildSystemPrompt()
            val userContent = buildUserContent(a, b, entityKeyA, entityKeyB)
            val raw = llmClient.callText(
                systemPrompt = systemPrompt,
                userContent = userContent,
                temperature = 0.0f,
                maxTokens = 200,
                timeoutMs = 15_000L,
            )
            parse(raw)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v2.6.2（记忆审计）: 显式重抛 —— 否则协程取消（关闭会话/停止后台）会被
            // 当成普通异常吞掉，取消中的自动保存可能继续等 LLM 并写库。
            throw e
        } catch (e: Exception) {
            // 超时/网络/解析失败: 保守降级,不阻塞记忆写入主流程
            Logger.w(TAG, "LLM dedup judgement failed; degrading to treating them as different: ${e.message}")
            DedupVerdict.NOT_SAME
        }
    }

    private fun buildSystemPrompt(): String = """
        You are the memory system's dedup judge. Given two memory facts, decide whether they describe the same fact (the same thing).
        - worded differently but semantically the same (e.g. "the user keeps a corgi" and "he keeps a corgi dog") → same=true
        - different facts about the same entity (e.g. "likes coffee" and "dislikes coriander") → same=false
        - [REDACTED] stands for redacted sensitive information (names/phone numbers/addresses, etc.); treat it as a different writing of the same entity
        - when unsure, same=false (rather not merge than merge wrongly)
        Output strict JSON only, no markdown fences, no extra text:
        {"same": true/false, "confidence": 0.0~1.0, "reason": "short reason"}
    """.trimIndent()

    private fun buildUserContent(a: String, b: String, entityKeyA: String?, entityKeyB: String?): String = buildString {
        append("Fact A: ").append(a).append('\n')
        append("Fact B: ").append(b).append('\n')
        if (!entityKeyA.isNullOrBlank() || !entityKeyB.isNullOrBlank()) {
            append("Entity key A: ").append(entityKeyA ?: "null").append('\n')
            append("Entity key B: ").append(entityKeyB ?: "null").append('\n')
            append("(Identical entity keys usually mean the same entity, but the fact content may still differ; judge by semantics)")
        }
    }

    internal fun parse(raw: String): DedupVerdict {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { json.decodeFromString<JudgeResponse>(trimmed) }.getOrNull()
            ?: runCatching { json.decodeFromString<JudgeResponse>(extractJsonObject(trimmed)) }.getOrNull()
            ?: return DedupVerdict.NOT_SAME
        return DedupVerdict(
            same = obj.same,
            confidence = obj.confidence.coerceIn(0f, 1f),
            reason = obj.reason.orEmpty().take(80),
        )
    }

    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else ""
    }

    @Serializable
    private data class JudgeResponse(
        val same: Boolean = false,
        val confidence: Float = 0f,
        val reason: String? = null,
    )

    private companion object {
        const val TAG = "LlmFactDedupJudge"
    }
}
