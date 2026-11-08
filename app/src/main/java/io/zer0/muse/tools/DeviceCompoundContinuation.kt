package io.zer0.muse.tools

/**
 * Shaheen 2 Fix — Compound Device Commands (2026-11-08).
 *
 * Malik's phone retest: "YouTube open krk sharara song open kro" — YouTube
 * REALLY opened (Phase 1 launch + foreground verification works), but the
 * agent stopped after that single step: no search, no play. Diagnosis
 * (SHAHEEN2_FIX_COMPOUND_LOG.md): the loop DID feed the launch result back
 * and DID offer a second round — the model chose to end with a text reply,
 * and the only DEVICE completion gate (Phase 3) was already satisfied,
 * because it measures PER-ACTION verification: app_launch's own result
 * carries the "— verified:" foreground marker, so `deviceVerified` was
 * true and the text reply was accepted as a clean COMPLETED success.
 * Nothing anywhere asked "is the owner's WHOLE command finished?".
 *
 * This object is the pure decision layer for the missing question; the
 * loop (ToolOrchestrator completion branch) owns the counters, mirroring
 * the CodingCompleteness pattern:
 *
 *  1. [isCompoundDeviceCommand] — cue-based classification of the owner's
 *     words (English + Roman Urdu): two or more distinct device goals, or
 *     one device goal chained by a sequencing word ("krk", "phir",
 *     "then"…), or one device goal aimed at a named target beyond the app
 *     itself (a song / gana, a quoted title, a second app named).
 *  2. Continuation check — when a compound DEVICE turn tries to end on a
 *     text reply while the last device action has had NO newer device
 *     action and NO verification after it, the loop re-prompts ONCE:
 *     finish the remaining steps, or verify the end state on screen and
 *     only then report. The model's next tool round clears the debt (a
 *     successful action re-incurs it for the NEXT step; a successful
 *     verification after the last action settles it).
 *  3. Honest partial ending — if the model still ends on text with the
 *     debt outstanding (or no round is left for the check), the final
 *     reply carries [PARTIAL_NOTE_SUFFIX]: unfinished multi-step work is
 *     never a bare "Done".
 *
 * Deliberate limits: single-step device commands ("YouTube kholo") are
 * NOT compound and keep the Phase 3 behaviour byte-for-byte. A false
 * positive costs at most one extra verification round; a false negative
 * loses the guarantee — the cue lists lean inclusive, like
 * ExecutionModeRouter's classifier.
 */
object DeviceCompoundContinuation {

    /** Bounded continuation re-prompts per turn (Phase 1/3 one-shot pattern). */
    const val MAX_CONTINUATION_REPROMPTS = 1

    /**
     * Device-goal verb families (English + Roman Urdu). A command naming
     * goals from TWO OR MORE families is compound by construction.
     * Substring matching is deliberate (house cue style); near-misses stay
     * inside one family, so they cannot manufacture a second goal.
     */
    private val GOAL_FAMILIES: List<List<String>> = listOf(
        // Open / launch something.
        listOf("kholo", "khol do", "kholna", "khol", "open", "launch"),
        // Play / start media.
        listOf("chalao", "chala do", "chalana", "play", "bajao", "baja do", "sunao", "suna do"),
        // Find something inside an app.
        listOf("search", "dhoondo", "dhoond", "dhundo", "find"),
        // Engage with content.
        listOf("like karo", "like kar do", "subscribe", "comment karo", "comment kar do"),
    )

    /** Sequencing words: one goal chained to a step that follows it. */
    private val SEQUENCING_MARKERS = listOf(
        "krk", "karke", "kar ke", "kr ke",
        "ke baad", "uske baad", "is ke baad", "bad mein", "baad mein",
        "phir", "then", "and then",
    )

    /** A named target beyond the app itself: reaching it takes more steps. */
    private val NAMED_TARGET_CUES = listOf(
        "song", "gana", "gaana", "music", "track",
    )

    /** Apps the classifier recognises by name (mirrors the router's cue list). */
    private val KNOWN_APP_NAMES = listOf(
        "youtube", "whatsapp", "instagram", "facebook", "tiktok",
    )

    /**
     * True when the owner's command asks for more than one device step:
     * goals from 2+ families, OR one goal + a sequencing marker, OR one
     * goal + a named target (song/gana cue, a quoted title, or a second
     * app named alongside the first). Blank / goal-less text is never
     * compound.
     */
    fun isCompoundDeviceCommand(userText: String?): Boolean {
        val text = userText?.lowercase()?.trim().orEmpty()
        if (text.isEmpty()) return false
        val familiesHit = GOAL_FAMILIES.count { family -> family.any { text.contains(it) } }
        if (familiesHit >= 2) return true
        if (familiesHit == 0) return false
        if (SEQUENCING_MARKERS.any { text.contains(it) }) return true
        if (NAMED_TARGET_CUES.any { text.contains(it) }) return true
        if (text.contains('"') || text.contains('“') || text.contains('”')) return true
        if (KNOWN_APP_NAMES.count { text.contains(it) } >= 2) return true
        return false
    }

    /**
     * The continuation check fires when ALL of these hold: the command is
     * compound, at least one device action ran this turn, the latest
     * action still has no newer action/verification after it
     * ([actionSinceCheck]), the one-shot budget is unused, and the loop
     * can actually run another round ([canReprompt] = tools not
     * force-closed and a round remains).
     */
    fun shouldRequestContinuation(
        isCompound: Boolean,
        deviceActionExecuted: Boolean,
        actionSinceCheck: Boolean,
        continuationRepromptsUsed: Int,
        canReprompt: Boolean,
    ): Boolean =
        isCompound &&
            deviceActionExecuted &&
            actionSinceCheck &&
            continuationRepromptsUsed < MAX_CONTINUATION_REPROMPTS &&
            canReprompt

    /**
     * The honest partial ending applies when the same debt is outstanding
     * but the check can no longer run — either it already ran once and
     * the model still stopped, or no round is available for it
     * ([canReprompt] false). Never fires when nothing was executed or
     * when a verification/action after the last action settled the debt.
     */
    fun shouldMarkPartial(
        isCompound: Boolean,
        deviceActionExecuted: Boolean,
        actionSinceCheck: Boolean,
        continuationRepromptsUsed: Int,
        canReprompt: Boolean,
    ): Boolean =
        isCompound &&
            deviceActionExecuted &&
            actionSinceCheck &&
            (continuationRepromptsUsed >= MAX_CONTINUATION_REPROMPTS || !canReprompt)

    /** The one bounded continuation check, sent as a user-side loop note. */
    const val CONTINUATION_REPROMPT_TEXT: String =
        "The owner's command had more than one step, and only part of it is done on the device so far. " +
            "Do NOT finish yet. Continue with the next step of the original command now, using the tools " +
            "(for example app_deep_link for an in-app page or a search, or the screen tools to tap and type). " +
            "Only when every part of the command is complete, verify the final state on screen " +
            "(screen_read, screen_current_app or ui_get_page_info) and then report. " +
            "If a step is truly blocked, say exactly what blocked it instead of reporting the whole task as done."

    /** Appended to a final reply whose compound command could not be confirmed finished. */
    const val PARTIAL_NOTE_SUFFIX: String =
        "\n\nNote: this command had more than one step, and the later steps could not be confirmed " +
            "finished in this turn — the work above may be incomplete. Please check your phone; " +
            "if something is missing, ask me to continue."
}
