package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Coding Completeness (Part 3, 2026-11-07): the pure decision
 * layer behind the loop's auto-continue / honest-ending / file-tool
 * re-prompt behaviour. The loop wiring itself (ToolOrchestrator
 * completion branch) is exercised by the factory lane; these tests pin
 * the decisions it delegates.
 */
class CodingCompletenessTest {

    private fun fencedBlock(chars: Int, lang: String = "kotlin"): String =
        "```$lang\n" + "x".repeat(chars) + "\n```"

    // ── Coding-turn classification ─────────────────────────────────────

    @Test
    fun `coding cues in english and roman urdu`() {
        assertTrue(CodingCompleteness.isCodingTurn("mujhe ek app banao jo reminders de"))
        assertTrue(CodingCompleteness.isCodingTurn("ek kotlin function likho jo list sort kare"))
        assertTrue(CodingCompleteness.isCodingTurn("Write a python script to rename files"))
        assertTrue(CodingCompleteness.isCodingTurn("is MainActivity.kt ko fix karo"))
        assertFalse(CodingCompleteness.isCodingTurn("YouTube kholo"))
        assertFalse(CodingCompleteness.isCodingTurn("kal weather kaisa hoga"))
        assertFalse(CodingCompleteness.isCodingTurn(""))
        assertFalse(CodingCompleteness.isCodingTurn(null))
    }

    // ── Inline code measurement ────────────────────────────────────────

    @Test
    fun `largest inline code block is measured in chars`() {
        assertEquals(0, CodingCompleteness.largestInlineCodeBlockChars("no code here"))
        // two content lines: (4+1) + (2+1) = 8
        assertEquals(8, CodingCompleteness.largestInlineCodeBlockChars("```\nabcd\nef\n```"))
        val mixed = "intro\n" + fencedBlock(50) + "\nmiddle\n" + fencedBlock(120) + "\noutro"
        assertEquals(121, CodingCompleteness.largestInlineCodeBlockChars(mixed))
    }

    @Test
    fun `unclosed fence counts the remainder - the classic length-limit cut`() {
        val cut = "```kotlin\n" + "y".repeat(2000)
        assertEquals(2001, CodingCompleteness.largestInlineCodeBlockChars(cut))
        assertTrue(CodingCompleteness.hasLargeInlineCode(cut))
    }

    @Test
    fun `large block threshold`() {
        assertFalse(CodingCompleteness.hasLargeInlineCode(fencedBlock(300)))
        assertTrue(
            CodingCompleteness.hasLargeInlineCode(
                fencedBlock(CodingCompleteness.LARGE_CODE_BLOCK_MIN_CHARS + 100),
            ),
        )
    }

    // ── Auto-continue decisions ────────────────────────────────────────

    @Test
    fun `length-limited coding turn auto-continues within the bound`() {
        assertTrue(
            CodingCompleteness.shouldAutoContinue(
                finishReason = "length",
                userText = "ek app banao",
                replyText = "partial...",
                fileToolsUsedThisTurn = false,
                continuesUsed = 0,
            ),
        )
        assertTrue(
            CodingCompleteness.shouldAutoContinue(
                finishReason = "MAX_TOKENS",
                userText = "ek app banao",
                replyText = "partial...",
                fileToolsUsedThisTurn = false,
                continuesUsed = CodingCompleteness.MAX_AUTO_CONTINUES - 1,
            ),
        )
        // Bound reached: no more continuations.
        assertFalse(
            CodingCompleteness.shouldAutoContinue(
                finishReason = "length",
                userText = "ek app banao",
                replyText = "partial...",
                fileToolsUsedThisTurn = false,
                continuesUsed = CodingCompleteness.MAX_AUTO_CONTINUES,
            ),
        )
    }

    @Test
    fun `non-truncated or non-work turns never auto-continue`() {
        assertFalse(
            CodingCompleteness.shouldAutoContinue(
                finishReason = "stop",
                userText = "ek app banao",
                replyText = "done",
                fileToolsUsedThisTurn = false,
                continuesUsed = 0,
            ),
        )
        assertFalse(
            CodingCompleteness.shouldAutoContinue(
                finishReason = null,
                userText = "ek app banao",
                replyText = "done",
                fileToolsUsedThisTurn = false,
                continuesUsed = 0,
            ),
        )
        // A plain chat answer that hit the limit is NOT a work turn:
        // no coding cue, no file tools, no large block.
        assertFalse(
            CodingCompleteness.shouldAutoContinue(
                finishReason = "length",
                userText = "mujhe ek lambi kahani sunao",
                replyText = "Ek dafa ka zikr hai...",
                fileToolsUsedThisTurn = false,
                continuesUsed = 0,
            ),
        )
    }

    @Test
    fun `a turn already using file tools counts as a work turn`() {
        assertTrue(
            CodingCompleteness.shouldAutoContinue(
                finishReason = "max_tokens",
                userText = "ye file update kar do", // no coding cue in the words
                replyText = "updating...",
                fileToolsUsedThisTurn = true,
                continuesUsed = 0,
            ),
        )
    }

    @Test
    fun `ended truncated is the honest-ending signal`() {
        assertTrue(
            CodingCompleteness.endedTruncated(
                finishReason = "length",
                userText = "code likho",
                replyText = "partial",
                fileToolsUsedThisTurn = false,
            ),
        )
        assertFalse(
            CodingCompleteness.endedTruncated(
                finishReason = "stop",
                userText = "code likho",
                replyText = "complete",
                fileToolsUsedThisTurn = false,
            ),
        )
    }

    // ── File-tool re-prompt decisions ──────────────────────────────────

    @Test
    fun `giant chat-text code on a coding turn re-prompts once`() {
        val reply = "Ye raha code:\n" + fencedBlock(2000)
        assertTrue(
            CodingCompleteness.shouldRepromptToFileTools(
                userText = "mujhe ye code likho",
                replyText = reply,
                fileToolsUsedThisTurn = false,
                alreadyReprompted = false,
            ),
        )
        // One-shot only.
        assertFalse(
            CodingCompleteness.shouldRepromptToFileTools(
                userText = "mujhe ye code likho",
                replyText = reply,
                fileToolsUsedThisTurn = false,
                alreadyReprompted = true,
            ),
        )
        // Already delivered via files: a big block in the summary is fine.
        assertFalse(
            CodingCompleteness.shouldRepromptToFileTools(
                userText = "mujhe ye code likho",
                replyText = reply,
                fileToolsUsedThisTurn = true,
                alreadyReprompted = false,
            ),
        )
        // Small snippets in chat are normal conversation.
        assertFalse(
            CodingCompleteness.shouldRepromptToFileTools(
                userText = "mujhe ye code likho",
                replyText = fencedBlock(200),
                fileToolsUsedThisTurn = false,
                alreadyReprompted = false,
            ),
        )
    }

    @Test
    fun `file write tool set is the workspace writers`() {
        assertTrue(CodingCompleteness.isFileWriteTool("workspace_write"))
        assertTrue(CodingCompleteness.isFileWriteTool("workspace_edit"))
        assertFalse(CodingCompleteness.isFileWriteTool("workspace_read"))
        assertFalse(CodingCompleteness.isFileWriteTool("terminal_exec"))
    }
}
