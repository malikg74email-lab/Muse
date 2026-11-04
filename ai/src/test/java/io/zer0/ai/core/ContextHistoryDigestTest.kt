package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 Kotlin：上下文窗口外历史摘录的确定性测试。 */
class ContextHistoryDigestTest {
    @Test
    fun `preserves user and assistant natural language`() {
        val dropped =
            listOf(
                UIMessage(role = MessageRole.USER, content = "帮我测试一下通知渠道"),
                UIMessage(role = MessageRole.ASSISTANT, content = "好的，我先检查配置。"),
            )

        val digest = ContextHistoryDigest.build(dropped)

        assertTrue(digest != null)
        assertEquals(MessageRole.SYSTEM, digest!!.role)
        assertTrue(digest.content.contains(ContextHistoryDigest.DIGEST_MARKER))
        assertTrue(digest.content.contains("帮我测试一下通知渠道"))
        assertTrue(digest.content.contains("好的，我先检查配置。"))
    }

    @Test
    fun `compacts tool rounds into deterministic entries`() {
        val call = ToolCall("c1", "search_memory", "{}")
        val dropped =
            listOf(
                UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call)),
                UIMessage(role = MessageRole.TOOL, content = "找到 3 条记忆", toolCallId = "c1"),
            )

        val digest = ContextHistoryDigest.build(dropped)

        assertTrue(digest != null)
        assertTrue(digest!!.content.contains("search_memory"))
        assertTrue(digest.content.contains("Tool result: 找到 3 条记忆"))
    }

    @Test
    fun `respects budget and reports omitted count`() {
        val dropped =
            (1..40).map { index ->
                UIMessage(role = MessageRole.USER, content = "消息$index " + "x".repeat(200))
            }

        val digest = ContextHistoryDigest.build(dropped, maxChars = 1000)

        assertTrue(digest != null)
        // 最新的几条保留,更早的折叠为省略说明
        assertTrue(digest!!.content.contains("消息40"))
        assertFalse(digest.content.contains("消息1 "))
        assertTrue(digest.content.contains("not expanded"))
    }

    @Test
    fun `returns null when nothing digestable`() {
        assertEquals(null, ContextHistoryDigest.build(emptyList()))
        val systemOnly = listOf(UIMessage(role = MessageRole.SYSTEM, content = "system prompt"))
        assertEquals(null, ContextHistoryDigest.build(systemOnly))
    }

    @Test
    fun `keeps newest entries first when budget is tight`() {
        val dropped =
            listOf(
                UIMessage(role = MessageRole.USER, content = "很旧的消息"),
                UIMessage(role = MessageRole.USER, content = "较新的消息"),
            )

        val digest = ContextHistoryDigest.build(dropped, maxChars = 10)

        assertTrue(digest != null)
        assertTrue(digest!!.content.contains("较新的消息"))
        assertFalse(digest.content.contains("很旧的消息"))
    }
}
