package io.zer0.muse.tools

import io.zer0.ai.core.ChatStopReason

/**
 * Shaheen 2 — Coding Completeness (Part 3, Malik's order 2026-11-07).
 *
 * Malik does not read code and must never receive half of it: when he
 * asks for coding, the delivery is complete working code or an honest
 * "not finished" — never a demo, never a truncated reply dressed up as
 * done. Three loop-level mechanisms, all wired in ToolOrchestrator's
 * no-tool-calls completion branch, mirroring the Phase 1 text-detector
 * re-prompt pattern:
 *
 *  1. Auto-continue: a coding turn whose model output was cut by the
 *     output length limit is continued automatically (bounded by
 *     [MAX_AUTO_CONTINUES]) instead of ending as if complete.
 *  2. Honest ending: if the limit keeps cutting after the bound, the
 *     final reply carries [TRUNCATED_FINAL_SUFFIX] — truncated work is
 *     never reported as finished. (The runner additionally appends its
 *     own "(Reply truncated due to length limit)" marker at stream
 *     level, so no path is silent.)
 *  3. File-tool re-prompt: a coding turn that ends with a giant code
 *     block pasted as chat text — and no file tool used — is re-prompted
 *     ONCE toward the file tools (write → append → verify), reusing the
 *     Phase 1 detector's conservative one-shot pattern.
 *
 * This object is the pure decision layer; the loop owns the counters.
 */
object CodingCompleteness {

    /** Bounded auto-continue rounds per turn for length-limited coding output. */
    const val MAX_AUTO_CONTINUES = 3

    /** A single fenced code block at or above this size should have been file-written. */
    const val LARGE_CODE_BLOCK_MIN_CHARS = 1200

    /** The workspace file tools that count as "the model used file tools this turn". */
    private val FILE_WRITE_TOOLS = setOf("workspace_write", "workspace_edit")

    fun isFileWriteTool(toolName: String): Boolean = toolName in FILE_WRITE_TOOLS

    /**
     * Coding-turn cues in the owner's words (English + Roman Urdu).
     * Deliberately cue-based, like ExecutionModeRouter: a false positive
     * costs at most an extra continuation round on a truncated long
     * answer about code; a false negative loses the guarantee.
     */
    private val CODING_CUES = listOf(
        "code", "coding", "script", "program", "function", "implement", "refactor",
        "debug", "compile", "kotlin", "python", "javascript", "typescript",
        "app banao", "app bana", "code likho", "code banao", "program banao",
        "script banao", "website banao", "plugin banao",
        ".kt", ".py", ".js", ".ts", ".html", ".css", ".json", ".xml",
        "gradle", "algorithm", "class bana", "api banao",
    )

    fun isCodingTurn(userText: String?): Boolean {
        val text = userText?.lowercase()?.trim().orEmpty()
        if (text.isEmpty()) return false
        return CODING_CUES.any { text.contains(it) }
    }

    /**
     * Char count of the largest fenced (```) code block in [text];
     * 0 when there is no complete fenced block. An unclosed fence (the
     * classic length-limit cut mid-block) counts the remainder of the
     * text — that is exactly the shape auto-continue exists for.
     */
    fun largestInlineCodeBlockChars(text: String): Int {
        if (!text.contains("```")) return 0
        var largest = 0
        var inBlock = false
        var current = 0
        for (line in text.lineSequence()) {
            if (line.trimStart().startsWith("```")) {
                if (inBlock) {
                    if (current > largest) largest = current
                    current = 0
                    inBlock = false
                } else {
                    inBlock = true
                    current = 0
                }
            } else if (inBlock) {
                current += line.length + 1
            }
        }
        if (inBlock && current > largest) largest = current
        return largest
    }

    /** True when [text] holds a code block big enough that it belonged in a file. */
    fun hasLargeInlineCode(text: String): Boolean =
        largestInlineCodeBlockChars(text) >= LARGE_CODE_BLOCK_MIN_CHARS

    /**
     * Should this length-limited round be auto-continued? Only for work
     * turns: a coding request, a turn already using file tools, or a
     * reply holding a large code block. Ordinary chat replies that hit
     * the limit keep the existing stream-level truncation marker.
     */
    fun shouldAutoContinue(
        finishReason: String?,
        userText: String?,
        replyText: String,
        fileToolsUsedThisTurn: Boolean,
        continuesUsed: Int,
    ): Boolean {
        if (!ChatStopReason.isLengthLimited(finishReason)) return false
        if (continuesUsed >= MAX_AUTO_CONTINUES) return false
        return isCodingTurn(userText) || fileToolsUsedThisTurn || hasLargeInlineCode(replyText)
    }

    /**
     * True when a turn ended STILL length-limited after the continuation
     * bound: the reply must carry the honest not-finished marker.
     */
    fun endedTruncated(
        finishReason: String?,
        userText: String?,
        replyText: String,
        fileToolsUsedThisTurn: Boolean,
    ): Boolean {
        if (!ChatStopReason.isLengthLimited(finishReason)) return false
        return isCodingTurn(userText) || fileToolsUsedThisTurn || hasLargeInlineCode(replyText)
    }

    /**
     * Should this finished coding reply be re-prompted once toward the
     * file tools? Only when the model pasted a giant code block as chat
     * text AND never used a file tool this turn — if it already wrote
     * files, a big block in the summary is the owner's copy, not a
     * violation.
     */
    fun shouldRepromptToFileTools(
        userText: String?,
        replyText: String,
        fileToolsUsedThisTurn: Boolean,
        alreadyReprompted: Boolean,
    ): Boolean {
        if (alreadyReprompted || fileToolsUsedThisTurn) return false
        if (!isCodingTurn(userText) && !hasLargeInlineCode(replyText)) return false
        return hasLargeInlineCode(replyText)
    }

    /** Continuation instruction for a length-limited round (USER role, loop-internal). */
    const val CONTINUE_TEXT: String =
        "[System note: your previous reply was cut off by the output length limit in the middle of the work. " +
            "Continue EXACTLY where you stopped — do not restart, do not repeat what is already written, and do not " +
            "summarize the missing part. If you were writing code, write it with the file tools in chunks " +
            "(workspace_write for the first chunk, workspace_edit to append/extend), then verify the file is complete. " +
            "Finish the whole task; partial or demo code is not a delivery.]"

    /** One-shot re-prompt when code arrived as a giant chat text block instead of file writes. */
    const val FILE_TOOL_REPROMPT_TEXT: String =
        "[System note: you pasted a large code block as chat text. The owner does not read code in chat — " +
            "deliver the code as FILES instead: write it with workspace_write, extend it with workspace_edit in " +
            "chunks, read it back to verify it is complete, and then report only the outcome in plain words " +
            "(what was built and whether it works). Do that now for the code you just produced.]"

    /** Honest tail marker when continuations are exhausted and the work is still cut. */
    const val TRUNCATED_FINAL_SUFFIX: String =
        "\n\n(Note: this reply hit the output length limit and the work above is NOT finished. " +
            "Say \"continue\" and I will pick up exactly where this stopped.)"
}
