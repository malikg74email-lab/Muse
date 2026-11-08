package io.zer0.muse.session

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 fix (2026-11-08): [WarmupHistory.trimToBudget] cut purely by
 * token count and could land between an ASSISTANT tool_call and its TOOL
 * result — the kept history then started with an orphan result, which
 * providers reject with HTTP 400 (Gemini strictly). The cut now walks back
 * to a clean turn boundary, mirroring splitContextWindow in the ai module.
 * The pre-existing WarmupHistoryTest cases use plain USER messages only and
 * are unaffected; these tests pin the tool-pair behavior in a separate file.
 */
class WarmupHistoryToolPairingTest {

    private fun user(text: String) = UIMessage(role = MessageRole.USER, content = text)

    private fun assistantCall(id: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "calling tool",
        toolCalls = listOf(ToolCall(id = id, name = "tool_$id", arguments = "{}")),
    )

    private fun toolResult(id: String) =
        UIMessage(role = MessageRole.TOOL, content = "result $id", toolCallId = id)

    private fun historyWithPair() = listOf(
        user("u0a"),
        user("u0b"),
        assistantCall("1"),
        toolResult("1"),
        user("u2"),
        UIMessage(role = MessageRole.ASSISTANT, content = "a2"),
        user("u3"),
    )

    @Test
    fun `token cut never splits a tool call from its result`() {
        // 7 messages x 100 tokens; budget 450 -> the naive cut keeps the last
        // 4 and would start at the TOOL result. The pair-aware cut walks back
        // past the call to the preceding plain user message.
        val result = WarmupHistory.trimToBudget(historyWithPair(), budgetTokens = 450) { 100 }

        assertTrue(result.truncated)
        assertEquals(6, result.history.size)
        assertEquals("u0b", result.history.first().content)
        val callIndex = result.history.indexOfFirst { !it.toolCalls.isNullOrEmpty() }
        assertTrue("the tool call must be kept", callIndex >= 0)
        assertEquals(
            "the result must immediately follow its call",
            MessageRole.TOOL,
            result.history[callIndex + 1].role,
        )
    }

    @Test
    fun `cut inside a pair at the head keeps the whole history instead of splitting`() {
        // Budget covers only part of the pair region: walking back reaches
        // the start of the list, so nothing is truncated (integrity over
        // exact budget, the same extreme case splitContextWindow documents).
        val history = listOf(user("u1"), assistantCall("1"), toolResult("1"))
        val result = WarmupHistory.trimToBudget(history, budgetTokens = 250) { 100 }

        assertFalse(result.truncated)
        assertEquals(3, result.history.size)
        assertEquals(MessageRole.TOOL, result.history.last().role)
    }

    @Test
    fun `history without tools still trims from the oldest as before`() {
        val history = (1..10).map { user("m$it") }
        val result = WarmupHistory.trimToBudget(history, budgetTokens = 350) { 100 }

        assertTrue(result.truncated)
        assertEquals(3, result.history.size)
        assertEquals("m8", result.history.first().content)
    }
}
