package io.zer0.ai.gemini

import io.zer0.common.Logger

/**
 * Shaheen 2 fix (2026-11-08): Gemini HTTP 400 in long conversations.
 *
 * Gemini validates function calling strictly: every `functionResponse` part
 * must answer a `functionCall` from the immediately preceding model turn, and
 * the number/names of the responses must match that turn's calls exactly.
 * A single mismatch is rejected with 400 INVALID_ARGUMENT
 * ("Please ensure that the number of function response parts is equal to the
 * number of function call parts of the function call turn").
 *
 * The UIMessage-level cleanup upstream cannot guarantee this invariant:
 * [io.zer0.ai.core.ProviderPayloadNormalizer] removes orphan TOOL *results*,
 * but nothing repaired *dangling calls* — an assistant turn whose tool results
 * were only partially recorded (interrupted generation), never recorded, or
 * cut away by one of the several history windows/compressors between the
 * database and this provider. Which shape reaches the provider depends on
 * where the (message-count / token-budget) boundary lands on that attempt,
 * which is why the failure looked intermittent and a retry could succeed.
 *
 * This sanitizer runs on the final [GeminiContent] list inside
 * [GeminiProvider.splitSystem] — the one conversion point shared by the
 * streaming and non-streaming paths — and enforces the invariant on whatever
 * actually gets sent:
 *
 *  - Orphan `functionResponse` parts (no pending call with that name) are
 *    dropped; a content left with no parts is dropped entirely.
 *  - Duplicate responses for one call are dropped (first one wins).
 *  - `functionCall` parts that stay unanswered when the next plain turn, the
 *    next call turn, or the end of the history arrives are stripped from
 *    their model content. The model's text (if any) is kept — only the
 *    phantom calls disappear. A model content left with no parts is dropped.
 *  - Consecutive response contents may collectively answer one call turn
 *    (the app stores one TOOL message per result, so parallel calls produce
 *    a run of single-response contents).
 *
 * Well-formed histories pass through unchanged. The input list and the
 * persisted history are never mutated; repairs are logged.
 */
internal object GeminiContentsSanitizer {
    private const val TAG = "GeminiContentsSanitizer"

    fun sanitize(contents: List<GeminiContent>): List<GeminiContent> {
        val hasFunctionParts = contents.any { content ->
            content.parts.any { it.functionCall != null || it.functionResponse != null }
        }
        if (!hasFunctionParts) return contents

        val out = ArrayList<GeminiContent>(contents.size)
        // Index in [out] of the model content whose calls are still unanswered,
        // plus the multiset of call names still waiting for a response.
        var pendingIndex = -1
        val pendingNames = mutableListOf<String>()

        /**
         * Strip the still-unanswered calls from the pending model content.
         * Answered calls (and any text parts) stay; a content that held only
         * unanswered calls is removed.
         */
        fun stripPendingCalls(reason: String) {
            if (pendingIndex < 0) return
            val modelContent = out[pendingIndex]
            val kept = modelContent.parts.filter { part ->
                part.functionCall == null || part.functionCall.name !in pendingNames
            }
            val removedCount = modelContent.parts.size - kept.size
            if (removedCount > 0) {
                Logger.w(
                    TAG,
                    "sanitize: dropped $removedCount unanswered functionCall part(s) ($reason); " +
                        "Gemini would have rejected the request with HTTP 400",
                )
            }
            when {
                kept.isEmpty() -> out.removeAt(pendingIndex)
                kept.size != modelContent.parts.size -> out[pendingIndex] = modelContent.copy(parts = kept)
            }
            pendingIndex = -1
            pendingNames.clear()
        }

        for (content in contents) {
            var effective = content

            // 1) functionResponse parts answer the pending call turn (if any).
            if (effective.parts.any { it.functionResponse != null }) {
                if (pendingNames.isEmpty()) {
                    val kept = effective.parts.filter { it.functionResponse == null }
                    Logger.w(
                        TAG,
                        "sanitize: dropped ${effective.parts.size - kept.size} orphan functionResponse part(s) " +
                            "with no preceding functionCall",
                    )
                    effective = effective.copy(parts = kept)
                } else {
                    // Consume one pending call per kept response: unknown names
                    // and duplicates fall out here.
                    val kept = effective.parts.filter { part ->
                        val response = part.functionResponse
                        response == null || pendingNames.remove(response.name)
                    }
                    val droppedCount = effective.parts.count { it.functionResponse != null } -
                        kept.count { it.functionResponse != null }
                    if (droppedCount > 0) {
                        Logger.w(
                            TAG,
                            "sanitize: dropped $droppedCount functionResponse part(s) that match no pending functionCall",
                        )
                    }
                    effective = effective.copy(parts = kept)
                    if (pendingNames.isEmpty()) pendingIndex = -1
                }
            }

            // 2) functionCall parts open a new call turn.
            val callNames = effective.parts.mapNotNull { it.functionCall?.name }
            if (callNames.isNotEmpty()) {
                // Calls from the previous turn that are still unanswered can
                // never be answered once a new call turn starts.
                if (pendingNames.isNotEmpty()) {
                    stripPendingCalls("a newer function call turn started")
                }
                out += effective
                pendingIndex = out.lastIndex
                pendingNames += callNames
            } else {
                // A plain content (no calls, no responses) closes any open call
                // turn: its calls stay unanswered forever, so strip them.
                // A response-bearing content may answer partially and leave the
                // turn open for the next content, so it never closes the turn.
                if (effective.parts.isNotEmpty() &&
                    effective.parts.none { it.functionResponse != null } &&
                    pendingNames.isNotEmpty()
                ) {
                    stripPendingCalls("the next turn carries no functionResponse")
                }
                if (effective.parts.isNotEmpty()) out += effective
            }
        }

        // 3) The history ends mid-turn (e.g. generation was stopped while tools
        // were running): the trailing calls have no responses at all.
        stripPendingCalls("the history ends before the function results arrive")

        return out
    }
}
