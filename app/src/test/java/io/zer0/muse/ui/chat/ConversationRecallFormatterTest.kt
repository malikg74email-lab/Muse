package io.zer0.muse.ui.chat

import io.zer0.muse.data.session.SearchResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRecallFormatterTest {

    @Test
    fun `builds a bounded historical reference section from current-session matches`() {
        val results = listOf(
            SearchResult(
                messageId = "m1",
                sessionId = "s1",
                sessionTitle = "当前会话",
                contentSnippet = "用户之前提到要保留原文",
                role = "USER",
                createdAt = 1L,
                content = "用户之前提到要保留原文，尤其是压缩后仍希望能够回溯细节。",
            ),
            SearchResult(
                messageId = "m2",
                sessionId = "s1",
                sessionTitle = "当前会话",
                contentSnippet = "助手确认会补充回溯",
                role = "ASSISTANT",
                createdAt = 2L,
                content = "我会把这段历史作为参考资料，在需要时检索，而不是把它永久塞进每一轮上下文。",
            ),
        )

        val section = ConversationRecallFormatter.build("保留原文", results, maxTokens = 200)

        assertTrue(section.contains("Conversation history recall"))
        assertTrue(section.contains("[User]"))
        assertTrue(section.contains("[Assistant]"))
        assertTrue(section.contains("尤其是压缩后仍希望能够回溯细节"))
        assertTrue(section.contains("reference material only"))
    }

    @Test
    fun `returns blank for empty query or no usable user assistant results`() {
        val toolResult = SearchResult(
            messageId = "tool",
            sessionId = "s1",
            sessionTitle = "当前会话",
            contentSnippet = "internal tool output",
            role = "TOOL",
            createdAt = 1L,
            content = "internal tool output",
        )

        assertTrue(ConversationRecallFormatter.build("", listOf(toolResult), maxTokens = 200).isBlank())
        assertTrue(ConversationRecallFormatter.build("x", listOf(toolResult), maxTokens = 200).isBlank())
    }

    @Test
    fun `does not expose system or tool roles even when search results contain them`() {
        val results = listOf(
            SearchResult("s", "s1", "会话", "system", "SYSTEM", 1L, "system secret"),
            SearchResult("t", "s1", "会话", "tool", "TOOL", 2L, "tool secret"),
            SearchResult("u", "s1", "会话", "user", "USER", 3L, "用户公开内容"),
        )

        val section = ConversationRecallFormatter.build("内容", results, maxTokens = 200)

        assertTrue(section.contains("用户公开内容"))
        assertFalse(section.contains("system secret"))
        assertFalse(section.contains("tool secret"))
    }
}
