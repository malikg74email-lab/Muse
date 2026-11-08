package io.zer0.muse.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Shaheen 2 Fix Phase 3 (diagnosis §8 fix additions 7–9): per-command execution mode.
 *
 * Malik's standing rule: ONE agent, TWO execution places, chosen per command —
 *  - [DEVICE]: kaam Malik ke REAL phone / home screen / asal apps mein ho
 *    (YouTube kholo, video like karo, screen par tap karo).
 *  - [INTERNAL]: kaam agent ki apni side par ho, Muse ki tarah
 *    (workspace files, code, scripts, sub-agents).
 *
 * Pehle koi mode router tha hi nahi: `isAgentMode` sirf aik UI tab tha, aur
 * `ui_agent` ka `display=auto` default virtual screen ko tarjeeh deta tha —
 * yani "mere phone par karo" wala kaam bhi invisible screen par ho sakta tha.
 *
 * Ye object teen cheezon ka single source hai:
 *  1. [classify] — user ke jumlay se mode (pure, JVM-testable).
 *  2. Tool-scope sets — [isInternalAlwaysAvailable] (Phase 1 ke
 *     [ToolExposurePolicy.isAgentAlwaysAvailable] ka internal counterpart).
 *  3. Mode-bound success ke predicates + [forceForegroundDisplay]
 *     (ToolOrchestrator inhein istemal karta hai).
 *
 * Classifier ka usool: asal screen par amal ke liye DEVICE ka wazeh cue
 * chahiye; warna default INTERNAL hai — bina kahe Malik ki screen ko
 * chhoona ghalat direction hai. Device ACTIONS (app kholna, like, tap)
 * khud DEVICE cue hain, chahe "mere phone par" na bhi kaha gaya ho.
 */
enum class ExecutionMode { DEVICE, INTERNAL }

object ExecutionModeRouter {

    // ── 1. Classifier ────────────────────────────────────────────────────────

    /** Wazeh internal ishare: kaam agent ki apni side par ho. */
    private val EXPLICIT_INTERNAL_PHRASES = setOf(
        "apne paas", "apne pas", "apni side", "apni taraf",
        "background mein", "background me", "background main",
        "on your side", "your side", "internally", "apne system",
        "workspace mein", "workspace me",
    )

    /** Wazeh device ishare: kaam Malik ke phone / screen par ho. */
    private val EXPLICIT_DEVICE_PHRASES = setOf(
        "mere phone par", "mere phone pe", "meri screen par", "meri screen pe",
        "mere mobile par", "mere mobile pe", "phone par", "phone pe",
        "screen par", "screen pe", "home screen",
        "on my phone", "on my screen", "my phone", "my screen",
    )

    /** Device action ke ishare: kholna chalana, like/comment, tap/scroll, mashhoor apps. */
    private val DEVICE_ACTION_CUES = setOf(
        "kholo", "khol do", "kholna", "chalao", "chala do",
        "open", "launch", "start karo",
        "like karo", "like kar do", "comment karo", "comment kar do", "subscribe",
        "tap", "swipe", "scroll",
        "youtube", "whatsapp", "instagram", "facebook", "tiktok",
    )

    /** Internal action ke ishare: file/code/script/workspace ka kaam. */
    private val INTERNAL_ACTION_CUES = setOf(
        "file banao", "file bana", "file likho", "file create",
        "code likho", "code likh", "code banao", "write code", "script",
        "workspace", ".txt", ".md", ".kt", ".py", ".json", ".xml",
    )

    /**
     * User ke jumlay se mode. Tartib: wazeh ishare pehle (internal, phir
     * device), phir action cues (device, phir internal), aakhir mein
     * default INTERNAL (asal screen ke liye cue lazmi hai).
     */
    fun classify(userText: String): ExecutionMode {
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return ExecutionMode.INTERNAL
        if (EXPLICIT_INTERNAL_PHRASES.any { normalized.contains(it) }) return ExecutionMode.INTERNAL
        if (EXPLICIT_DEVICE_PHRASES.any { normalized.contains(it) }) return ExecutionMode.DEVICE
        if (DEVICE_ACTION_CUES.any { normalized.contains(it) }) return ExecutionMode.DEVICE
        if (INTERNAL_ACTION_CUES.any { normalized.contains(it) }) return ExecutionMode.INTERNAL
        return ExecutionMode.INTERNAL
    }

    // ── 2. Tool scopes ───────────────────────────────────────────────────────

    /** INTERNAL mode ka always-on set (Phase 1 ke device set ka counterpart). */
    private val INTERNAL_ALWAYS_AVAILABLE_TOOLS = setOf(
        "subagent_task", "subagent_run", "terminal_exec", "termux_exec",
    )

    /** `workspace_*` / `execute_*` prefix families + [INTERNAL_ALWAYS_AVAILABLE_TOOLS]. */
    fun isInternalAlwaysAvailable(toolName: String): Boolean =
        toolName in INTERNAL_ALWAYS_AVAILABLE_TOOLS ||
            toolName.startsWith("workspace_") ||
            toolName.startsWith("execute_")

    // ── 3. Mode-bound success (addition 8) + foreground forcing (addition 9) ──

    /** Wo tools jin ka chalna "device par amal" kehlata hai. */
    private val LAUNCH_TOOLS = setOf(
        "app_launch", "app_open_settings", "app_force_stop", "app_clear_data",
        "app_uninstall", "open_app", "screen_launch_app", "ui_agent",
        // Deep Links batch (2026-11-08): app_deep_link ka landed-verified
        // report "— verified:" marker carry karta hai aur us batch ki
        // design yahi thi ke mode router use pick up kare — lekin ye set
        // update reh gaya tha, is liye deep-link-only turn na
        // deviceActionExecuted set karta tha na us ka verified marker
        // parha jata tha. Compound-commands fix (2026-11-08) mein band.
        "app_deep_link",
    )

    /** Screen/UI ke action tools (verification reads in mein shamil nahi). */
    private val UI_ACTION_TOOLS = setOf(
        "ui_click", "ui_long_press", "ui_swipe", "ui_set_text",
        "ui_back", "ui_home", "ui_global_action",
    )

    /** Wo tools jin ki kamyabi "screen par verify ho gaya" kehlati hai. */
    private val VERIFICATION_TOOLS = setOf(
        "screen_read", "screen_current_app", "ui_get_page_info",
        "ui_get_current_app", "ui_screenshot", "get_foreground_app",
        "screen_permission_status",
    )

    fun isDeviceActionTool(toolName: String): Boolean =
        toolName in LAUNCH_TOOLS ||
            toolName in UI_ACTION_TOOLS ||
            (toolName.startsWith("screen_") && toolName !in VERIFICATION_TOOLS)

    fun isVerificationTool(toolName: String): Boolean = toolName in VERIFICATION_TOOLS

    /**
     * Phase 1 ke verified launch results mein ye marker hota hai
     * (strings_services.xml: `tool_app_opened_verified` /
     * `app_control_ok_verified` — "… — verified: it is in the foreground now.").
     * Unverified variants mein ye substring nahi hoti, is liye ye aik
     * reliable foreground-proof hai.
     */
    private const val FOREGROUND_VERIFIED_MARKER = "— verified:"

    fun resultShowsForegroundVerification(finalToolResult: String): Boolean =
        finalToolResult.contains(FOREGROUND_VERIFIED_MARKER)

    /** Verification na mile to model ko aik bounded re-prompt (addition 8). */
    const val VERIFY_REPROMPT_TEXT: String =
        "You performed an action on the user's real device this turn, but there is no " +
            "verification result yet. Before you report success, call a verification tool now " +
            "(screen_read, screen_current_app or ui_get_page_info) and then report ONLY what " +
            "it actually shows. Do not claim the action succeeded unless the verification " +
            "shows it on screen."

    /** Aakhir tak verify na ho to final jawab ke saath ye plain note lagta hai. */
    const val UNVERIFIED_NOTE_SUFFIX: String =
        "\n\nNote: the device action above could not be verified on screen this turn, " +
            "so it is not confirmed. Please check your phone; if it did not happen, ask me again."

    /**
     * Addition 9: device mode mein `ui_agent` ke arguments ka `display`
     * "foreground" par force karta hai — `display=auto` ka virtual-screen
     * default Malik ki asal screen wale kaam ko invisible bana deta tha.
     * Parse fail ho to input waisa ka waisa (forcing kabhi call ko torte nahi).
     */
    fun forceForegroundDisplay(argumentsJson: String): String {
        val obj = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }
            .getOrNull() ?: return argumentsJson
        if ((obj["display"] as? JsonPrimitive)?.content == "foreground") return argumentsJson
        val rebuilt = buildJsonObject {
            var written = false
            for ((k, v) in obj) {
                if (k == "display") {
                    put("display", JsonPrimitive("foreground"))
                    written = true
                } else {
                    put(k, v)
                }
            }
            if (!written) put("display", JsonPrimitive("foreground"))
        }
        return rebuilt.toString()
    }
}
