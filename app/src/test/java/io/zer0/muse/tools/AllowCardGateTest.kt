package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Allow Cards (Part 1, 2026-11-07): sensitive-category
 * classifier fixtures.
 *
 * The negative fixtures matter as much as the positives: Malik's owner
 * principle says routine work (launch, screen taps, workspace writes,
 * replies in existing conversations) must NEVER be gated — a classifier
 * that over-fires recreates the nagging he rejected.
 */
class AllowCardGateTest {

    // ── Destructive ────────────────────────────────────────────────────

    @Test
    fun `workspace delete is destructive`() {
        val c = SensitiveToolGate.classify("workspace_delete", mapOf("path" to "old.txt"))
        assertNotNull(c)
        assertEquals(AllowCardCategory.DESTRUCTIVE, c!!.category)
        assertEquals("DESTRUCTIVE|workspace_delete", c.scopeKey)
        assertFalse(c.firstUse)
    }

    @Test
    fun `uninstall and clear data are destructive`() {
        assertEquals(
            AllowCardCategory.DESTRUCTIVE,
            SensitiveToolGate.classify("app_uninstall", mapOf("app" to "YouTube"))!!.category,
        )
        assertEquals(
            AllowCardCategory.DESTRUCTIVE,
            SensitiveToolGate.classify("app_clear_data", mapOf("app" to "YouTube"))!!.category,
        )
    }

    @Test
    fun `future delete_ tools are destructive by prefix`() {
        assertEquals(
            AllowCardCategory.DESTRUCTIVE,
            SensitiveToolGate.classify("delete_backup", emptyMap())!!.category,
        )
        assertEquals(
            AllowCardCategory.DESTRUCTIVE,
            SensitiveToolGate.classify("delete_memory", emptyMap())!!.category,
        )
    }

    @Test
    fun `force stop is NOT gated - reversible, no data loss`() {
        // Deliberate border call (log §1.1): the app relaunches; nothing is lost.
        assertNull(SensitiveToolGate.classify("app_force_stop", mapOf("app" to "YouTube")))
    }

    // ── MCP ────────────────────────────────────────────────────────────

    @Test
    fun `first use of an mcp server is gated per server`() {
        val c = SensitiveToolGate.classify("mcp_github__search_repos", mapOf("query" to "shaheen"))
        assertNotNull(c)
        assertEquals(AllowCardCategory.MCP_FIRST_USE, c!!.category)
        assertEquals("MCP_FIRST_USE|mcp_github__search_repos|github", c.scopeKey)
        assertTrue(c.firstUse)
        assertTrue(c.whyLine.contains("github"))
    }

    @Test
    fun `mcp management writes are gated but not first-use`() {
        val c = SensitiveToolGate.classify("mcp_mgmt_configure", mapOf("server" to "github"))
        assertNotNull(c)
        assertEquals(AllowCardCategory.MCP_FIRST_USE, c!!.category)
        assertEquals("MCP_FIRST_USE|mcp_mgmt_configure|github", c.scopeKey)
        assertFalse(c.firstUse)
    }

    @Test
    fun `mcp server parsing rejects malformed names`() {
        assertNull(SensitiveToolGate.mcpServerOf("mcp_broken"))
        assertNull(SensitiveToolGate.mcpServerOf("app_launch"))
        assertEquals("github", SensitiveToolGate.mcpServerOf("mcp_github__search"))
    }

    // ── Permission / setting / capability ──────────────────────────────

    @Test
    fun `settings_put is a permission-or-setting change`() {
        val c = SensitiveToolGate.classify("settings_put", mapOf("key" to "adb_enabled"))
        assertEquals(AllowCardCategory.PERMISSION_OR_SETTING, c!!.category)
    }

    @Test
    fun `enabling a skill is gated, disabling is not`() {
        assertEquals(
            AllowCardCategory.PERMISSION_OR_SETTING,
            SensitiveToolGate.classify("skill_manage_toggle", mapOf("skill_id" to "x"))!!.category,
        )
        assertEquals(
            AllowCardCategory.PERMISSION_OR_SETTING,
            SensitiveToolGate.classify(
                "skill_manage_toggle",
                mapOf("skill_id" to "x", "enabled" to true),
            )!!.category,
        )
        assertNull(
            SensitiveToolGate.classify(
                "skill_manage_toggle",
                mapOf("skill_id" to "x", "enabled" to false),
            ),
        )
        assertNull(
            SensitiveToolGate.classify(
                "skill_manage_toggle",
                mapOf("skill_id" to "x", "enabled" to "false"),
            ),
        )
    }

    @Test
    fun `installing a skill is gated`() {
        assertEquals(
            AllowCardCategory.PERMISSION_OR_SETTING,
            SensitiveToolGate.classify("install_skill", emptyMap())!!.category,
        )
    }

    // ── Payment ────────────────────────────────────────────────────────

    @Test
    fun `payment-shaped plugin tools are gated by name segments`() {
        assertEquals(
            AllowCardCategory.PAYMENT,
            SensitiveToolGate.classify("plugin_shop__checkout", emptyMap())!!.category,
        )
        assertEquals(
            AllowCardCategory.PAYMENT,
            SensitiveToolGate.classify("stripe_create_payment_link", emptyMap())!!.category,
        )
    }

    // ── New recipient ──────────────────────────────────────────────────

    @Test
    fun `sms to a new number is gated with a normalized phone scope`() {
        val c = SensitiveToolGate.classify(
            "send_sms",
            mapOf("phone_number" to "+92 300 1234567", "message" to "hello"),
        )
        assertNotNull(c)
        assertEquals(AllowCardCategory.NEW_RECIPIENT_MESSAGE, c!!.category)
        assertEquals("NEW_RECIPIENT_MESSAGE|send_sms|923001234567", c.scopeKey)
        assertTrue(c.firstUse)
    }

    @Test
    fun `email recipient is lowercased in the scope key`() {
        val c = SensitiveToolGate.classify(
            "send_email",
            mapOf("to" to "  Someone@Example.com ", "subject" to "hi"),
        )
        assertEquals("NEW_RECIPIENT_MESSAGE|send_email|someone@example.com", c!!.scopeKey)
    }

    @Test
    fun `message without an extractable recipient is still gated`() {
        val c = SensitiveToolGate.classify("send_channel_message", mapOf("text" to "hi"))
        assertNotNull(c)
        assertEquals("NEW_RECIPIENT_MESSAGE|send_channel_message|unknown", c!!.scopeKey)
    }

    @Test
    fun `phone call to a new number is first contact too`() {
        val c = SensitiveToolGate.classify("make_phone_call", mapOf("number" to "03001234567"))
        assertEquals(AllowCardCategory.NEW_RECIPIENT_MESSAGE, c!!.category)
        assertEquals("NEW_RECIPIENT_MESSAGE|make_phone_call|03001234567", c.scopeKey)
    }

    // ── Routine work is NEVER gated ────────────────────────────────────

    @Test
    fun `routine tools classify to null`() {
        val routine = listOf(
            "app_launch", "open_app", "screen_launch_app", "app_list", "list_installed_apps",
            "ui_click", "ui_set_text", "ui_agent", "screen_read", "screen_tap_text",
            "workspace_write", "workspace_edit", "workspace_read", "workspace_list",
            "channel_reply", "open_url", "open_system_setting", "app_open_settings",
            "toggle_wifi", "terminal_exec", "execute_javascript", "web_search",
            "send_voice", "speak_text", "get_current_time",
        )
        for (tool in routine) {
            assertNull("routine tool must not be gated: $tool", SensitiveToolGate.classify(tool, emptyMap()))
        }
    }

    @Test
    fun `recipient extraction prefers explicit keys and normalizes`() {
        assertEquals(
            "923001234567",
            SensitiveToolGate.extractRecipient(mapOf("phone" to "+92 (300) 123-4567")),
        )
        assertEquals(
            "friend@example.com",
            SensitiveToolGate.extractRecipient(mapOf("to" to "Friend@Example.com")),
        )
        assertNull(SensitiveToolGate.extractRecipient(mapOf("message" to "hello")))
        assertNull(SensitiveToolGate.extractRecipient(emptyMap()))
    }
}
