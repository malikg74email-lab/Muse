package io.zer0.muse.tools

import kotlinx.serialization.Serializable

/**
 * Shaheen 2 — Auto-Training Pack (Part 2, Malik's order 2026-11-07).
 *
 * The Working Instructions Pack: the SAME working rules for every
 * provider and every model — only the transport differs (the pack rides
 * in the assembled system prompt), never the content. Content source of
 * truth: `SHAHEEN2_INSTRUCTION_PACK_SPEC.md` (Malik's north star: the
 * model works the way Muse works).
 *
 * Delivery model (implemented in ChatToolLoopRunner + [PackDeliveryStore]):
 *  - The pack text below is part of the system prompt every model
 *    receives (SystemPromptAssembler, section after operation_safety),
 *    so it is re-delivered with every session by construction.
 *  - Bookkeeping records, per provider+model, which pack VERSION was
 *    delivered and whether the structured acknowledgement came back.
 *  - When the recorded version is stale (or absent), the next session's
 *    first turn carries a delivery directive asking the model to open
 *    its reply with the ack line; the runner verifies and strips it.
 *
 * HONEST LIMIT (spec §Readiness check): a text acknowledgement is WEAK
 * evidence. It proves the pack text reached the model and the model
 * could echo the expected token — nothing more. It does NOT prove the
 * model will behave accordingly. The real proof is behaviour, which the
 * Phase 1/3 machinery (tool truth, Allow Cards, on-screen verification)
 * enforces mechanically no matter what the model claims. That is why a
 * missing ack is recorded as DELIVERED_UNVERIFIED and never blocks work.
 */
object WorkingInstructionsPack {

    /** Bump when the pack text changes; staleness is judged against this. */
    const val PACK_VERSION = 1

    /** Section markers, in order — the assembly test asserts every one is present. */
    val SECTION_MARKERS: List<String> = listOf(
        "Identity & owner",
        "Tool truth",
        "Approval cards",
        "Settings auto-on",
        "Browser method",
        "Complete → verify → report",
        "Memory & resume",
        "Coding completeness",
        "Modes",
        "Live call conduct",
    )

    /**
     * The canonical pack text. The assets copy
     * (`prompt_templates/working_instructions_en.prompt`) carries the
     * same text for the template-override surface; this constant is the
     * fallback the assembler uses if the asset cannot be loaded, and the
     * text the tests assert against.
     */
    val TEXT: String =
        """
        Working instructions — how Shaheen works (these rules outrank habit; follow them on every task):
        1. Identity & owner: You are Shaheen, Malik's personal agent. Malik is the OWNER of this phone and this agent; his word decides. Converse in Roman Urdu; anything shown on the front (labels, cards, titles) stays English.
        2. Tool truth: Never print a tool call as chat text — call the tool. Never say an action happened without a tool result that proves it; for device actions, verify on the screen in the same turn. If something failed, say so plainly with the real reason. Always ATTEMPT with tools first — the no-fake-success rule governs what you report, never whether you try.
        3. Approval cards: Before a permission or sensitive action — a new Android permission, an MCP server's first use or permission, spending money, a permanent delete, messaging someone new, enabling a sensitive setting — an Allow card must appear in chat and you STOP until the owner taps. The decision is remembered for its scope (this once / this session / always for this kind). No card, no action. Routine work the owner already allows needs no card — do not nag.
        4. Settings auto-on: When a task needs a setting or permission, enable or configure it yourself wherever the system allows. NEVER tell the owner to "go to Settings and find X". Where Android mandates the owner's own tap (accessibility, device admin), the Allow card takes him straight to that exact screen; resume the task when he returns.
        5. Browser method: Perceive, then act, then verify. Read the page first, then act, then confirm the effect on the page. On a login or CAPTCHA, hand over to the owner and continue when he hands back. Never claim a web action worked without seeing its effect.
        6. Complete → verify → report: Finish the whole task, check the outcome yourself, then report in plain words what was done and what (if anything) failed. Background work is fine; when the result is ready, bring the news — don't make the owner ask.
        7. Memory & resume: Keep track of ongoing work. If a task was left incomplete, resume from exactly where it stopped. Never restart silently, and never pretend unfinished work was done.
        8. Coding completeness: When the owner asks for code, write it with the file tools in chunks — write, then append, then verify — never as one huge chat message. If a reply limit cuts you off mid-code, continue automatically until the code is complete and verified working. Demo, basic or stub code is NEVER the final delivery. The owner does not read code: deliver outcomes ("the app does X"), not listings.
        9. Modes: Device mode acts on the owner's real screen only when he asks for it there; internal mode works on your own side (files, workspace, tools). Default is internal — never touch the real screen unasked.
        10. Live call conduct: On a live call, keep spoken replies short and in Roman Urdu. Act with tools first, then report what actually happened. Never fake success out loud either.
        """.trimIndent()

    /** The exact acknowledgement line for a pack version. */
    fun ackLine(version: Int = PACK_VERSION): String = "SHAHEEN_READY PACK_V$version"

    /**
     * The delivery directive appended (request-side only, never shown in
     * the chat UI) to the first user turn of a session whose
     * provider+model record is stale.
     */
    fun deliveryDirective(version: Int = PACK_VERSION): String =
        "[SHAHEEN SYSTEM NOTE — not from the owner] Your Working Instructions Pack v$version " +
            "is part of your system instructions in this app. Before starting the owner's task, " +
            "begin your reply with exactly this line on its own line: ${ackLine(version)}\n" +
            "Then do the task normally. The line confirms the pack reached you; " +
            "it is removed before your reply is shown."

    /** True when [content]'s first non-blank line is the expected ack line. */
    fun verifyAck(content: String, version: Int = PACK_VERSION): Boolean {
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return false
        return firstLine == ackLine(version) || firstLine.startsWith(ackLine(version) + " ")
    }

    /**
     * Remove the ack line (and any blank lines it leaves) from a reply
     * whose ack verified. Returns the content unchanged when the ack is
     * absent — stripping must never eat a real reply.
     */
    fun stripAck(content: String, version: Int = PACK_VERSION): String {
        if (!verifyAck(content, version)) return content
        val lines = content.lines()
        val ackIndex = lines.indexOfFirst { it.isNotBlank() }
        if (ackIndex < 0) return content
        return lines.drop(ackIndex + 1).dropWhile { it.isBlank() }.joinToString("\n")
    }

    /** Delivery-bookkeeping key for a provider+model pair; null when either id is missing. */
    fun deliveryKey(providerId: String?, modelId: String?): String? {
        val p = providerId?.trim().orEmpty()
        val m = modelId?.trim().orEmpty()
        if (p.isEmpty() || m.isEmpty()) return null
        return "$p|$m"
    }

    /**
     * Staleness: delivery is needed when there is no record for the pair
     * (a newly selected model has none — this is what makes delivery
     * happen "on model selection": the first turn with that model IS
     * the delivery point, because selection itself makes no request) or
     * when the recorded version is older than the current pack.
     */
    fun needsDelivery(record: PackDeliveryRecord?, currentVersion: Int = PACK_VERSION): Boolean =
        record == null || record.packVersion < currentVersion
}

/** What the app knows about one provider+model's pack delivery. */
@Serializable
data class PackDeliveryRecord(
    val packVersion: Int,
    val deliveredAtMs: Long,
    val ackStatus: AckStatus,
)

@Serializable
enum class AckStatus {
    /** The model opened its first reply with the exact ack line. Weak evidence — see pack doc. */
    VERIFIED,

    /** The pack was delivered but no valid ack came back. Recorded honestly; work is never blocked on it. */
    DELIVERED_UNVERIFIED,
}
