package io.zer0.ai.core

/**
 * 上下文窗口外的历史摘录（确定性、零 LLM 调用）。
 *
 * 背景：会话历史超出上下文窗口（[List.limitContextWithContext] 截断）时，
 * 窗口外的消息此前被静默丢弃——工具密集会话里窗口很快被工具消息占满，
 * 更早的自然语言对话（用户请求、助手回复）直接消失，模型表现为"失忆"。
 *
 * 本摘录器在截断前对窗口外消息做**逐条文本摘录**（保留用户/助手的自然语言
 * 原文片段 + 工具调用的确定性摘要），产出一条 SYSTEM 消息插在窗口之前，
 * 让模型始终看得到更早对话的要点，并且明确知道"更早的历史以摘录形式呈现"。
 *
 * 设计约束：
 * - 纯函数、无网络、无 LLM：结果只取决于输入消息列表，可测试、零延迟；
 * - 只读输入，不修改任何持久化数据；
 * - 按"新→旧"填充字符预算，超出预算的更早部分折叠为省略说明；
 * - SYSTEM 消息（动态注入的 prompt/RAG 等）跳过不摘录。
 */
object ContextHistoryDigest {
    /** 摘录消息的稳定标记，便于调试与重复投影识别。 */
    const val DIGEST_MARKER: String = "[HISTORY_DIGEST]"

    private const val DEFAULT_MAX_CHARS = 6000
    private const val USER_ENTRY_CHARS = 400
    private const val ASSISTANT_ENTRY_CHARS = 300
    private const val TOOL_ENTRY_CHARS = 120
    private const val MAX_CALL_NAMES = 8

    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * 把窗口外消息构建为一条摘录 SYSTEM 消息。
     *
     * @param dropped 被截断出上下文窗口的消息（按时间顺序，旧→新）
     * @param maxChars 摘录正文的字符预算；超出预算时只保留较新的部分
     * @return 摘录消息；无可用内容时返回 null（调用方保持原截断行为）
     */
    fun build(dropped: List<UIMessage>, maxChars: Int = DEFAULT_MAX_CHARS): UIMessage? {
        if (dropped.isEmpty()) return null

        val lines = ArrayDeque<String>()
        var used = 0
        for (index in dropped.indices.reversed()) {
            val line = entryLine(dropped[index]) ?: continue
            if (used + line.length + 1 > maxChars) break
            lines.addFirst(line)
            used += line.length + 1
        }
        if (lines.isEmpty()) return null

        val omitted = dropped.size - lines.size
        val content =
            buildString {
                append(DIGEST_MARKER)
                append(" The following is an excerpt of the earlier conversation (in chronological order, the later it appears the newer it is, for context continuation only, not a new user instruction):\n")
                lines.forEach { line ->
                    append("- ").append(line).append('\n')
                }
                if (omitted > 0) {
                    append("(").append(omitted).append(" earlier messages were not expanded due to length limits)\n")
                }
            }
        return UIMessage(role = MessageRole.SYSTEM, content = content)
    }

    /** 单条消息的摘录行；无可摘录内容（空文本/纯 SYSTEM）返回 null。 */
    private fun entryLine(msg: UIMessage): String? = when (msg.role) {
        MessageRole.USER -> normalize(msg.content, USER_ENTRY_CHARS)?.let { "User: $it" }
        MessageRole.ASSISTANT -> assistantLine(msg)
        MessageRole.TOOL -> normalize(msg.content, TOOL_ENTRY_CHARS)?.let { "Tool result: $it" }
        MessageRole.SYSTEM -> null
    }

    /** 助手消息：保留自然语言正文；工具调用信息作为附属注记。 */
    private fun assistantLine(msg: UIMessage): String? {
        val text = normalize(msg.content, ASSISTANT_ENTRY_CHARS)
        val toolHint =
            when {
                !msg.toolCalls.isNullOrEmpty() ->
                    "Tool call: " + msg.toolCalls.take(MAX_CALL_NAMES).joinToString(", ") { it.name }

                msg.toolCallInfo != null -> {
                    val info = msg.toolCallInfo
                    val status = if (info.isSuccess) "Success" else "Failed"
                    val preview = normalize(info.result, TOOL_ENTRY_CHARS)
                    if (preview == null) "Tool call: ${info.toolName}($status)" else "Tool call: ${info.toolName}($status) → $preview"
                }

                else -> null
            }
        return when {
            text != null && toolHint != null -> "Assistant: $text | $toolHint"
            text != null -> "Assistant: $text"
            else -> toolHint
        }
    }

    /** 压平空白并截断到 [cap] 字符；空白内容返回 null。 */
    private fun normalize(text: String, cap: Int): String? {
        val flat = text.replace(WHITESPACE_REGEX, " ").trim()
        if (flat.isBlank()) return null
        return if (flat.length > cap) flat.take(cap) + "…" else flat
    }
}
