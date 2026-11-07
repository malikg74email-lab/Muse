package io.zer0.muse.tools

/**
 * Shaheen 2 — Allow Cards, Part 1 (Malik's order, 2026-11-07).
 *
 * A sensitive-CATEGORY layer on top of the existing approval substrate
 * ([ToolPermissionResolver] + per-tool [ToolApprovalPolicy] + the chat
 * approval card). The substrate already suspends execution, renders the
 * card and times out to Deny; what it never had was the notion of a
 * *category* of action that must be gated exactly once per scope —
 * first use of an MCP server, a message to a NEW recipient, a permanent
 * delete, a permission/setting change, a payment — while ROUTINE work
 * (launch, read, screen navigation, workspace writes, owner's own
 * standing commands) flows without nagging. Malik is the OWNER; cards
 * gate genuinely new/sensitive scope, never routine allowed work.
 *
 * This file is pure (no Android, no I/O) so the factory JVM lane can
 * test it: [SensitiveToolGate] classifies a tool call, and
 * [AllowCardGatekeeper] decides Allow / Deny / Ask from the remembered
 * decisions supplied by `AllowCardGateStore`.
 */

/** The sensitive categories an Allow Card can gate. */
enum class AllowCardCategory {
    /** Permanent, irreversible removal of apps, data or files. */
    DESTRUCTIVE,

    /** Grants/enables a system permission, a sensitive setting, or a new capability. */
    PERMISSION_OR_SETTING,

    /** First use of an MCP server (per server), and MCP server configuration changes. */
    MCP_FIRST_USE,

    /** Anything that spends money. No built-in tool matches today; plugin/MCP tools can. */
    PAYMENT,

    /** Sending a message (or placing a call) to a recipient the owner has not approved before. */
    NEW_RECIPIENT_MESSAGE,
}

/**
 * One classified sensitive call.
 *
 * @param scopeKey the memory key decisions are remembered under:
 *   `category|tool` normally, `category|tool|server` for MCP,
 *   `category|tool|recipient` for first-contact messaging. An "Always
 *   allow this kind" decision is stored against exactly this key, so
 *   the granularity the owner approved is the granularity honoured.
 * @param whyLine one plain-English line for the card: why this needs
 *   the owner's tap. Front text is English by Malik's standing rule.
 * @param firstUse true when the gate exists only until the scope is
 *   known (MCP server, message recipient): any approval marks the
 *   scope known permanently, whatever scope the owner picked.
 */
data class GateClassification(
    val category: AllowCardCategory,
    val toolName: String,
    val scopeKey: String,
    val whyLine: String,
    val firstUse: Boolean = false,
)

object SensitiveToolGate {

    /**
     * Permanent-delete tools, from the built-in catalogue.
     * `app_force_stop` is deliberately ABSENT: force-stopping an app is
     * reversible (the app relaunches, no data is lost), so under the
     * owner principle it is routine work, not a destructive action.
     */
    private val DESTRUCTIVE_TOOLS = setOf(
        "app_uninstall",
        "app_clear_data",
        "workspace_delete",
        "delete_file",
        "delete_memory",
        "session_delete",
        "assistant_manage_delete",
        "knowledge_delete_doc",
        "knowledge_delete_kb",
        "quick_note_delete",
        "resource_delete",
        "scheduled_task_delete",
        "skill_manage_delete",
        "uninstall_skill",
    )

    /** MCP management writes: they change which servers exist / are bound. */
    private val MCP_MANAGEMENT_WRITE_TOOLS = setOf(
        "mcp_mgmt_configure",
        "mcp_mgmt_bind_assistant",
        "mcp_mgmt_remove",
    )

    /** Messaging / calling tools whose recipient can be a first contact. */
    private val MESSAGE_TOOLS = setOf(
        "send_sms",
        "send_email",
        "send_channel_message",
        "make_phone_call",
    )

    /** Argument keys a recipient may arrive under, in priority order. */
    private val RECIPIENT_ARG_KEYS = listOf(
        "to", "recipient", "phone", "phone_number", "number",
        "email", "address", "contact", "target", "chat_id", "chatId", "user_id",
    )

    /** Name segments that mark a payment/purchase tool (plugin/MCP tools included). */
    private val PAYMENT_SEGMENTS = setOf("pay", "payment", "purchase", "checkout", "buy", "billing")

    /**
     * Classify a tool call. Returns null for routine work — the caller
     * then follows the normal (non-gated) approval path.
     */
    fun classify(toolName: String, args: Map<String, Any?> = emptyMap()): GateClassification? {
        val name = toolName.trim()
        if (name.isEmpty()) return null

        // MCP first use: mcp_<server>__<tool> (management tools excluded —
        // they start with mcp_mgmt_ and are classified below).
        if (name.startsWith("mcp_") && !name.startsWith("mcp_mgmt_")) {
            val server = mcpServerOf(name)
            if (server != null) {
                return GateClassification(
                    category = AllowCardCategory.MCP_FIRST_USE,
                    toolName = name,
                    scopeKey = scope(AllowCardCategory.MCP_FIRST_USE, name, server),
                    whyLine = "First use of the MCP server \"$server\". Allow Shaheen to use its tools?",
                    firstUse = true,
                )
            }
        }
        if (name in MCP_MANAGEMENT_WRITE_TOOLS) {
            val server = firstArg(args, "server", "server_id", "serverId", "name")
            return GateClassification(
                category = AllowCardCategory.MCP_FIRST_USE,
                toolName = name,
                scopeKey = scope(AllowCardCategory.MCP_FIRST_USE, name, server ?: "management"),
                whyLine = "This changes an MCP server's configuration. MCP servers can run tools on your phone.",
                firstUse = false,
            )
        }

        // Permanent deletes (exact names + the delete_ family for future tools).
        if (name in DESTRUCTIVE_TOOLS || name.startsWith("delete_")) {
            return GateClassification(
                category = AllowCardCategory.DESTRUCTIVE,
                toolName = name,
                scopeKey = scope(AllowCardCategory.DESTRUCTIVE, name),
                whyLine = "This permanently deletes data and cannot be undone.",
            )
        }

        // Permission / sensitive-setting / new-capability changes.
        if (name == "settings_put") {
            return GateClassification(
                category = AllowCardCategory.PERMISSION_OR_SETTING,
                toolName = name,
                scopeKey = scope(AllowCardCategory.PERMISSION_OR_SETTING, name),
                whyLine = "This changes an Android system setting on your phone.",
            )
        }
        if (name == "install_skill") {
            return GateClassification(
                category = AllowCardCategory.PERMISSION_OR_SETTING,
                toolName = name,
                scopeKey = scope(AllowCardCategory.PERMISSION_OR_SETTING, name),
                whyLine = "This installs a new skill — new code Shaheen can run.",
            )
        }
        if (name == "skill_manage_toggle" && argsIndicateEnable(args)) {
            return GateClassification(
                category = AllowCardCategory.PERMISSION_OR_SETTING,
                toolName = name,
                scopeKey = scope(AllowCardCategory.PERMISSION_OR_SETTING, name),
                whyLine = "This enables a skill, giving Shaheen a new capability.",
            )
        }

        // Payment / purchase (no built-in tool matches today — see log §1.1).
        if (name.split('_').any { it in PAYMENT_SEGMENTS }) {
            return GateClassification(
                category = AllowCardCategory.PAYMENT,
                toolName = name,
                scopeKey = scope(AllowCardCategory.PAYMENT, name),
                whyLine = "This involves a payment or purchase.",
            )
        }

        // First contact with a new recipient.
        if (name in MESSAGE_TOOLS) {
            val recipient = extractRecipient(args)
            return GateClassification(
                category = AllowCardCategory.NEW_RECIPIENT_MESSAGE,
                toolName = name,
                scopeKey = scope(AllowCardCategory.NEW_RECIPIENT_MESSAGE, name, recipient ?: "unknown"),
                whyLine = if (recipient != null) {
                    "First time contacting $recipient. Allow Shaheen to reach this recipient?"
                } else {
                    "This sends a message to a recipient that could not be identified in advance."
                },
                firstUse = true,
            )
        }

        return null
    }

    /** Parse the server id out of `mcp_<server>__<tool>`; null when malformed. */
    fun mcpServerOf(toolName: String): String? {
        if (!toolName.startsWith("mcp_")) return null
        val rest = toolName.removePrefix("mcp_")
        val sep = rest.indexOf("__")
        if (sep <= 0) return null
        return rest.substring(0, sep).takeIf { it.isNotBlank() }
    }

    /**
     * Extract and normalize a message recipient from tool args.
     * Phones normalize to digits (so `+92 300…` and `0300…` formats of
     * the same number share one memory entry per exact digit string);
     * everything else is trimmed + lowercased. Null when no plausible
     * recipient value exists.
     */
    fun extractRecipient(args: Map<String, Any?>): String? {
        for (key in RECIPIENT_ARG_KEYS) {
            val raw = args[key]?.toString()?.trim().orEmpty()
            if (raw.isBlank()) continue
            val digitCount = raw.count { it.isDigit() }
            return if (digitCount >= 5 && raw.all { it.isDigit() || it in "+- ()" }) {
                raw.filter { it.isDigit() }
            } else {
                raw.lowercase()
            }
        }
        return null
    }

    private fun argsIndicateEnable(args: Map<String, Any?>): Boolean {
        return when (val v = args["enabled"]) {
            null -> true // the tool's own default is "enable"
            is Boolean -> v
            else -> v.toString().trim().lowercase() != "false"
        }
    }

    private fun firstArg(args: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val v = args[key]?.toString()?.trim()
            if (!v.isNullOrBlank()) return v.lowercase()
        }
        return null
    }

    private fun scope(category: AllowCardCategory, toolName: String, qualifier: String? = null): String =
        if (qualifier.isNullOrBlank()) {
            "${category.name}|$toolName"
        } else {
            "${category.name}|$toolName|$qualifier"
        }
}

/** A read-only view of the remembered Allow Card decisions. */
data class AllowCardMemoryView(
    /** Scope keys the owner allowed with "Always allow this kind" (persisted). */
    val alwaysAllowedScopes: Set<String> = emptySet(),
    /** Scope keys allowed for the current app session only (in-memory). */
    val sessionAllowedScopes: Set<String> = emptySet(),
    /** Scope keys the owner denied; honoured for the current app session (in-memory). */
    val sessionDeniedScopes: Set<String> = emptySet(),
    /** First-use scopes already known (MCP servers / recipients), persisted. */
    val knownScopes: Set<String> = emptySet(),
)

/** The gatekeeper's verdict for one classified call. */
sealed interface GateDecision {
    /** Run it — a remembered decision or an explicit owner setting covers this scope. */
    data object Allow : GateDecision

    /** Drop it — the owner already denied this scope for this session. */
    data class Deny(val reason: String) : GateDecision

    /** Show the Allow Card and wait for the owner's tap. */
    data object Ask : GateDecision
}

object AllowCardGatekeeper {

    const val SESSION_DENY_REASON = "Owner declined this kind of action earlier in this session"

    /**
     * Decide a classified call BEFORE any card is shown.
     *
     * Hard denies are NOT decided here: the caller runs the legacy
     * resolver first and returns any Denied (per-tool ALWAYS_DENY,
     * ParamPolicy hard deny, STRICT-mode phone-call deny) before
     * consulting the gate, so an owner's explicit lockdown always wins
     * over a remembered allow.
     *
     * @param configuredPolicy the owner's explicit per-tool policy, if any.
     * @param mode the session permission mode; TRUSTED is the owner's own
     *   run-wide "I trust this session" choice and is honoured as an allow.
     * @param toolAllowedThisSession the pre-existing per-tool session grant
     *   (SessionPermissionStore): if the owner already allowed this tool
     *   for the session from an earlier card, the gate never asks again
     *   on top of that grant.
     */
    fun decide(
        classification: GateClassification,
        memory: AllowCardMemoryView,
        configuredPolicy: ToolApprovalPolicy?,
        mode: SessionPermissionMode,
        toolAllowedThisSession: Boolean,
    ): GateDecision {
        val key = classification.scopeKey
        if (key in memory.sessionDeniedScopes) return GateDecision.Deny(SESSION_DENY_REASON)
        if (toolAllowedThisSession) return GateDecision.Allow
        if (key in memory.sessionAllowedScopes) return GateDecision.Allow
        if (key in memory.alwaysAllowedScopes) return GateDecision.Allow
        if (classification.firstUse && key in memory.knownScopes) return GateDecision.Allow
        if (configuredPolicy == ToolApprovalPolicy.ALWAYS_ALLOW) return GateDecision.Allow
        if (mode == SessionPermissionMode.TRUSTED) return GateDecision.Allow
        return GateDecision.Ask
    }

    /**
     * Owner-default posture for NON-sensitive calls (Part 1, log §1.2):
     * when the legacy resolver wants a card ONLY because the default
     * ASK mode prompts on NORMAL/HIGH risk — no explicit per-tool
     * policy, mode not STRICT — routine work on the session the owner
     * is looking at proceeds without a card. STRICT mode, explicit
     * ASK_EVERY_TIME / ALWAYS_DENY policies and ParamPolicy results
     * are decided by the resolver itself and never reach this helper
     * as suppressible. Non-displayed (remote/A2A) sessions pass
     * [isDisplayedSession] = false and keep the legacy behaviour.
     * A Pending produced by a ParamPolicy (e.g. open_url with an
     * unknown scheme) is a deliberate ask, so callers pass
     * [paramPolicyEngaged] = true and the card still shows.
     */
    fun isRoutineAskDefaultSuppressible(
        configuredPolicy: ToolApprovalPolicy?,
        mode: SessionPermissionMode,
        isDisplayedSession: Boolean,
        paramPolicyEngaged: Boolean,
    ): Boolean =
        isDisplayedSession &&
            configuredPolicy == null &&
            !paramPolicyEngaged &&
            mode == SessionPermissionMode.ASK
}
