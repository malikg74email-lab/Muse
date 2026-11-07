package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Allow Cards (Part 1, 2026-11-07): gatekeeper decision matrix
 * + the routine-ask suppression predicate.
 *
 * These pure decisions are the gate's suspend/allow/deny behaviour at
 * the seam the coordinator consumes: Allow → the call runs, Deny → the
 * call is dropped with a plain tool result, Ask → the coordinator
 * renders the card and suspends the loop on the existing 30 s
 * timeout-to-Deny wait. The wait mechanics themselves are the
 * pre-existing substrate (ChatToolApprovalCoordinator) and are not
 * re-implemented by Part 1.
 */
class AllowCardGatekeeperTest {

    private val deleteClassification =
        SensitiveToolGate.classify("workspace_delete", mapOf("path" to "a.txt"))!!

    private val mcpClassification =
        SensitiveToolGate.classify("mcp_github__search", emptyMap())!!

    private fun decide(
        classification: GateClassification = deleteClassification,
        memory: AllowCardMemoryView = AllowCardMemoryView(),
        configuredPolicy: ToolApprovalPolicy? = null,
        mode: SessionPermissionMode = SessionPermissionMode.ASK,
        toolAllowedThisSession: Boolean = false,
    ): GateDecision = AllowCardGatekeeper.decide(
        classification = classification,
        memory = memory,
        configuredPolicy = configuredPolicy,
        mode = mode,
        toolAllowedThisSession = toolAllowedThisSession,
    )

    @Test
    fun `unremembered sensitive call asks`() {
        assertEquals(GateDecision.Ask, decide())
    }

    @Test
    fun `always-allowed scope runs without a card`() {
        val memory = AllowCardMemoryView(alwaysAllowedScopes = setOf(deleteClassification.scopeKey))
        assertEquals(GateDecision.Allow, decide(memory = memory))
    }

    @Test
    fun `session-allowed scope runs without a card`() {
        val memory = AllowCardMemoryView(sessionAllowedScopes = setOf(deleteClassification.scopeKey))
        assertEquals(GateDecision.Allow, decide(memory = memory))
    }

    @Test
    fun `session deny drops later calls of the same scope`() {
        val memory = AllowCardMemoryView(sessionDeniedScopes = setOf(deleteClassification.scopeKey))
        val decision = decide(memory = memory)
        assertTrue(decision is GateDecision.Deny)
        assertEquals(
            AllowCardGatekeeper.SESSION_DENY_REASON,
            (decision as GateDecision.Deny).reason,
        )
    }

    @Test
    fun `session deny wins over a remembered allow`() {
        val key = deleteClassification.scopeKey
        val memory = AllowCardMemoryView(
            alwaysAllowedScopes = setOf(key),
            sessionDeniedScopes = setOf(key),
        )
        assertTrue(decide(memory = memory) is GateDecision.Deny)
    }

    @Test
    fun `known mcp server is not gated again`() {
        val memory = AllowCardMemoryView(knownScopes = setOf(mcpClassification.scopeKey))
        assertEquals(GateDecision.Allow, decide(classification = mcpClassification, memory = memory))
    }

    @Test
    fun `known scope does not ungate non-first-use classifications`() {
        // mcp_mgmt_configure is not firstUse: being "known" must not apply.
        val mgmt = SensitiveToolGate.classify("mcp_mgmt_configure", mapOf("server" to "github"))!!
        val memory = AllowCardMemoryView(knownScopes = setOf(mgmt.scopeKey))
        assertEquals(GateDecision.Ask, decide(classification = mgmt, memory = memory))
    }

    @Test
    fun `a different recipient is a different scope and still asks`() {
        val other = SensitiveToolGate.classify("send_sms", mapOf("phone_number" to "999999999"))!!
        val memory = AllowCardMemoryView(knownScopes = setOf(mcpClassification.scopeKey))
        assertEquals(GateDecision.Ask, decide(classification = other, memory = memory))
    }

    @Test
    fun `explicit per-tool always-allow satisfies the gate`() {
        assertEquals(
            GateDecision.Allow,
            decide(configuredPolicy = ToolApprovalPolicy.ALWAYS_ALLOW),
        )
    }

    @Test
    fun `trusted mode satisfies the gate`() {
        assertEquals(GateDecision.Allow, decide(mode = SessionPermissionMode.TRUSTED))
    }

    @Test
    fun `tool-level session grant satisfies the gate`() {
        assertEquals(GateDecision.Allow, decide(toolAllowedThisSession = true))
    }

    @Test
    fun `strict mode still asks`() {
        assertEquals(GateDecision.Ask, decide(mode = SessionPermissionMode.STRICT))
    }

    @Test
    fun `ask-every-time policy alone does not bypass or block the gate`() {
        assertEquals(
            GateDecision.Ask,
            decide(configuredPolicy = ToolApprovalPolicy.ASK_EVERY_TIME),
        )
    }

    // ── Routine ASK-default suppression (owner principle) ──────────────

    @Test
    fun `routine ask-default is suppressible only in the narrow case`() {
        assertTrue(
            AllowCardGatekeeper.isRoutineAskDefaultSuppressible(
                configuredPolicy = null,
                mode = SessionPermissionMode.ASK,
                isDisplayedSession = true,
                paramPolicyEngaged = false,
            ),
        )
        // STRICT is the owner's lockdown: cards stay.
        assertFalse(
            AllowCardGatekeeper.isRoutineAskDefaultSuppressible(
                configuredPolicy = null,
                mode = SessionPermissionMode.STRICT,
                isDisplayedSession = true,
                paramPolicyEngaged = false,
            ),
        )
        // An explicit per-tool policy means the resolver's Pending is deliberate.
        assertFalse(
            AllowCardGatekeeper.isRoutineAskDefaultSuppressible(
                configuredPolicy = ToolApprovalPolicy.ASK_EVERY_TIME,
                mode = SessionPermissionMode.ASK,
                isDisplayedSession = true,
                paramPolicyEngaged = false,
            ),
        )
        // Remote / non-displayed sessions keep the legacy card behaviour
        // (the A2A focus guard is not weakened).
        assertFalse(
            AllowCardGatekeeper.isRoutineAskDefaultSuppressible(
                configuredPolicy = null,
                mode = SessionPermissionMode.ASK,
                isDisplayedSession = false,
                paramPolicyEngaged = false,
            ),
        )
        // A ParamPolicy ask (e.g. open_url unknown scheme) is deliberate.
        assertFalse(
            AllowCardGatekeeper.isRoutineAskDefaultSuppressible(
                configuredPolicy = null,
                mode = SessionPermissionMode.ASK,
                isDisplayedSession = true,
                paramPolicyEngaged = true,
            ),
        )
        // TRUSTED never produces an ASK-default Pending anyway.
        assertFalse(
            AllowCardGatekeeper.isRoutineAskDefaultSuppressible(
                configuredPolicy = null,
                mode = SessionPermissionMode.TRUSTED,
                isDisplayedSession = true,
                paramPolicyEngaged = false,
            ),
        )
    }
}
