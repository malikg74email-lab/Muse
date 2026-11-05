package io.zer0.muse.tools

/**
 * Shaheen 2 Fix Phase 1 (diagnosis §5 Fix 1 item 4) — text-fallback detector.
 *
 * Masla (device test 2026-11-05): model ne tool call ko native function call ki
 * bajaye plain text mein likh diya —
 *
 * ```
 * <tool_code>
 * // Opening the YouTube app.
 * app_launch({package_name: "com.google.android.youtube"});
 * </tool_code>
 * ```
 *
 * App mein koi text parser maujood nahi hai: tool calls sirf structured provider
 * events se execute hote hain. Detector ka kaam text ko execute karna NAHI hai —
 * sirf ye pehchanna hai ke final text "call jaisi" hai taake agent loop khamoshi
 * se COMPLETED na ho jaye: pehle aik dafa native call ki re-prompt, phir bhi na
 * ho to plain lasting error (fake success kabhi nahi).
 *
 * Detection jaan-bujh kar conservative hai taake aam guftagu (jisme tool ka naam
 * zikr ho) ghalat trigger na ho:
 *  1. `<tool_code>` tag maujood ho, ya
 *  2. kisi registered tool ka naam foran `(` ya `({` se pehle aye
 *     (code-style invocation, masalan `app_launch({` ya `list_installed_apps()`).
 */
object TextToolCallDetector {

    /** Detection ka nateeja: konsa tool text mein call ki shakal mein likha gaya. */
    data class Detection(val toolName: String?, val viaTag: Boolean)

    private const val TOOL_CODE_TAG = "<tool_code>"

    /**
     * @param text model ki final text (assistant message content)
     * @param toolNames is turn ke registered/known tool names
     * @return detection, ya null agar text aam jawab hai
     */
    fun detect(text: String, toolNames: Set<String>): Detection? {
        if (text.isBlank()) return null
        val lowered = text.lowercase()
        // Sab se mazboot signal: <tool_code> block. Uske andar ka pehla known tool
        // naam bhi nikaal lo (log/re-prompt ke liye); na mile to bhi detection hai.
        if (lowered.contains(TOOL_CODE_TAG)) {
            val named = toolNames.firstOrNull { name ->
                invocationRegex(name).containsMatchIn(text)
            }
            return Detection(toolName = named, viaTag = true)
        }
        // Code-style invocation: known tool naam + "(" — lekin aam prose mein naam ka
        // zikr ("you can call app_launch()") ghalat trigger na ho, is liye ghair-tag
        // surat mein aik aur code signal lazmi hai: code fence, argument object
        // `({`, ya text ka aaghaz hi us invocation se ho.
        val named = toolNames.firstOrNull { name ->
            if (name.isBlank() || !invocationRegex(name).containsMatchIn(text)) {
                false
            } else {
                text.contains("```") ||
                    Regex("""(?<![A-Za-z0-9_])${Regex.escape(name)}\s*\(\s*\{""").containsMatchIn(text) ||
                    text.trimStart().startsWith(name)
            }
        }
        return named?.let { Detection(toolName = it, viaTag = false) }
    }

    /** `toolName(` / `toolName ({` jaisi shakal ka regex (naam ko literal escape karke). */
    private fun invocationRegex(toolName: String): Regex =
        Regex("""(?<![A-Za-z0-9_])${Regex.escape(toolName)}\s*\(""")

    /** Re-prompt jo model ko native call par wapas bulata hai (aik dafa, bounded). */
    fun repromptText(detection: Detection): String {
        val target = detection.toolName?.let { " (the call you wrote as text was: $it)" } ?: ""
        return "[System note: your previous reply wrote a tool call as plain text$target. " +
            "Text is never executed — nothing was done. You must now emit that same call as a " +
            "native tool/function call in this turn, with the exact parameter names from the " +
            "tool's schema. Do not describe the call in prose and do not claim success until " +
            "the tool result confirms it.]"
    }

    /** Dusri nakami par turn ka aakhri, plain aur lasting error text. */
    const val FAILURE_TEXT: String =
        "I understood the command, but I could not run it: the tool call was written as " +
            "text instead of being executed, so nothing was done. Please send the command again."
}
