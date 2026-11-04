package io.zer0.muse.tools

import io.zer0.muse.data.assistant.AssistantEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * v1.200: Agent 能力标签常量与辅助函数。
 *
 * 每个 Assistant 可在 AssistantDetailPages 中被标注一组能力标签,
 * 供 AgentRouter 在用户未显式指定 assistantId 时自动匹配最合适的子助手。
 */
object AgentCapability {

    // ── 通用能力 ──
    const val CHAT = "chat"
    const val REASONING = "reasoning"
    const val WRITING = "writing"
    const val CREATIVE = "creative"
    const val TRANSLATION = "translation"
    const val REVIEW = "review"

    // ── 专业领域 ──
    const val CODE = "code"
    const val MATH = "math"
    const val RESEARCH = "research"
    const val DATA = "data"
    const val LEGAL = "legal"
    const val MEDICAL = "medical"
    const val FINANCE = "finance"
    const val EDUCATION = "education"

    // ── 工具/模态 ──
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val WEB_SEARCH = "web_search"
    const val MEMORY = "memory"
    const val SCHEDULE = "schedule"
    const val KNOWLEDGE = "knowledge"

    /** 所有可选能力标签,按分类排序,用于 UI 展示。 */
    val ALL_CAPABILITIES = listOf(
        CHAT, REASONING, WRITING, CREATIVE, TRANSLATION, REVIEW,
        CODE, MATH, RESEARCH, DATA, LEGAL, MEDICAL, FINANCE, EDUCATION,
        IMAGE, VIDEO, WEB_SEARCH, MEMORY, SCHEDULE, KNOWLEDGE,
    )

    /** 能力标签的友好中文名。 */
    fun displayName(capability: String): String = when (capability) {
        CHAT -> "Chat"
        REASONING -> "Reasoning"
        WRITING -> "Writing"
        CREATIVE -> "Creative"
        TRANSLATION -> "Translation"
        REVIEW -> "Review"
        CODE -> "Programming"
        MATH -> "Math"
        RESEARCH -> "Research"
        DATA -> "Data processing"
        LEGAL -> "Legal"
        MEDICAL -> "Medical"
        FINANCE -> "Finance"
        EDUCATION -> "Education"
        IMAGE -> "Image generation"
        VIDEO -> "Video generation"
        WEB_SEARCH -> "Web search"
        MEMORY -> "Memory organisation"
        SCHEDULE -> "Schedule management"
        KNOWLEDGE -> "Knowledge base"
        else -> capability
    }

    /** 从 AssistantEntity 解析能力标签列表。 */
    fun fromEntity(entity: AssistantEntity): List<String> {
        return parseCapabilitiesJson(entity.capabilitiesJson)
    }

    /** 把能力标签列表序列化为 JSON 字符串。 */
    fun toJson(capabilities: List<String>): String {
        return io.zer0.common.AppJson.encodeToString(
            ListSerializer(String.serializer()),
            capabilities.distinct().filter { it.isNotBlank() },
        )
    }

    /** 解析能力标签 JSON 字符串。 */
    fun parseCapabilitiesJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(
                ListSerializer(String.serializer()),
                json,
            )
        }.getOrElse { emptyList() }
    }
}
