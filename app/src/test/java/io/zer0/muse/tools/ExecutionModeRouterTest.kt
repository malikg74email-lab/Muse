package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 3 (diagnosis §8 fix additions 7–9): per-command mode router.
 *
 * Device-test root cause: there was NO mode decision at all — `ui_agent`
 * defaulted to an invisible virtual screen and headless paths could act
 * without ever touching Malik's real screen. These tests pin the classifier
 * (English + Roman Urdu cues), the verification predicates the orchestrator
 * uses for mode-bound success, and the foreground forcing.
 */
class ExecutionModeRouterTest {

    // ── Classifier ──────────────────────────────────────────────────────────

    @Test
    fun `app open commands are device mode`() {
        assertEquals(ExecutionMode.DEVICE, ExecutionModeRouter.classify("YouTube kholo"))
        assertEquals(ExecutionMode.DEVICE, ExecutionModeRouter.classify("open YouTube"))
        assertEquals(ExecutionMode.DEVICE, ExecutionModeRouter.classify("WhatsApp chalao"))
        assertEquals(
            ExecutionMode.DEVICE,
            ExecutionModeRouter.classify("YouTube khol kar Info 5 ki video like karo"),
        )
    }

    @Test
    fun `explicit on-my-phone phrasing is device mode`() {
        assertEquals(ExecutionMode.DEVICE, ExecutionModeRouter.classify("mere phone par YouTube kholo"))
        assertEquals(ExecutionMode.DEVICE, ExecutionModeRouter.classify("meri screen par jo hai wo parho"))
        assertEquals(ExecutionMode.DEVICE, ExecutionModeRouter.classify("do it on my phone"))
    }

    @Test
    fun `explicit on-your-side phrasing is internal mode`() {
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("apne paas se file bana do"))
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("background mein ye kaam kar do"))
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("do it on your side"))
    }

    @Test
    fun `file and code work is internal mode`() {
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("is code ko workspace mein likho"))
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("aik file banao notes.txt ki"))
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("write code for sorting"))
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("workspace mein file kholo"))
    }

    @Test
    fun `neutral chat defaults to internal so the real screen is never touched unasked`() {
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("hello"))
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("kya haal hai"))
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify(""))
        assertEquals(ExecutionMode.INTERNAL, ExecutionModeRouter.classify("   "))
    }

    // ── Foreground forcing (addition 9) ─────────────────────────────────────

    @Test
    fun `forceForegroundDisplay sets display to foreground`() {
        val out = ExecutionModeRouter.forceForegroundDisplay(
            """{"task":"like the video","display":"auto","package_name":"com.google.android.youtube"}""",
        )
        assertTrue(out.contains("\"display\":\"foreground\""))
        assertTrue("doosre arguments mehfooz rehne chahiye", out.contains("\"package_name\":\"com.google.android.youtube\""))
        assertTrue(out.contains("\"task\":\"like the video\""))
    }

    @Test
    fun `forceForegroundDisplay adds display when missing`() {
        val out = ExecutionModeRouter.forceForegroundDisplay("""{"task":"open it"}""")
        assertTrue(out.contains("\"display\":\"foreground\""))
    }

    @Test
    fun `forceForegroundDisplay leaves bad json untouched`() {
        assertEquals("not json", ExecutionModeRouter.forceForegroundDisplay("not json"))
        assertEquals("[]", ExecutionModeRouter.forceForegroundDisplay("[]"))
    }

    // ── Mode-bound success predicates (addition 8) ──────────────────────────

    @Test
    fun `device action and verification tool sets are disjoint where it matters`() {
        assertTrue(ExecutionModeRouter.isDeviceActionTool("app_launch"))
        assertTrue(ExecutionModeRouter.isDeviceActionTool("screen_tap_text"))
        assertTrue(ExecutionModeRouter.isDeviceActionTool("ui_click"))
        assertTrue(ExecutionModeRouter.isDeviceActionTool("ui_agent"))
        assertFalse(ExecutionModeRouter.isDeviceActionTool("screen_read"))
        assertFalse(ExecutionModeRouter.isDeviceActionTool("workspace_write"))
        assertTrue(ExecutionModeRouter.isVerificationTool("screen_read"))
        assertTrue(ExecutionModeRouter.isVerificationTool("ui_get_page_info"))
        assertTrue(ExecutionModeRouter.isVerificationTool("get_foreground_app"))
        assertFalse(ExecutionModeRouter.isVerificationTool("app_launch"))
    }

    @Test
    fun `verified marker matches only the Phase 1 verified launch strings`() {
        // strings_services.xml ke asal jumlay (Phase 1):
        assertTrue(
            ExecutionModeRouter.resultShowsForegroundVerification(
                "App opened: YouTube — verified: it is in the foreground now.",
            ),
        )
        assertTrue(
            ExecutionModeRouter.resultShowsForegroundVerification(
                "Done: YouTube launch (via intent) — verified: the app is in the foreground now.",
            ),
        )
        // Unverified variants mein marker nahi hona chahiye:
        assertFalse(
            ExecutionModeRouter.resultShowsForegroundVerification(
                "Launch issued for: YouTube — could not verify it is in the foreground; report it as launched, not as confirmed open.",
            ),
        )
        assertFalse(
            ExecutionModeRouter.resultShowsForegroundVerification(
                "Done: YouTube launch (via intent) — note: the launch was issued but could not be verified on screen; report it as launched, not as confirmed open.",
            ),
        )
    }

    @Test
    fun `internal always-on scope covers workspace and execution tools only`() {
        assertTrue(ExecutionModeRouter.isInternalAlwaysAvailable("workspace_write"))
        assertTrue(ExecutionModeRouter.isInternalAlwaysAvailable("execute_shell"))
        assertTrue(ExecutionModeRouter.isInternalAlwaysAvailable("terminal_exec"))
        assertTrue(ExecutionModeRouter.isInternalAlwaysAvailable("subagent_task"))
        assertFalse(ExecutionModeRouter.isInternalAlwaysAvailable("app_launch"))
        assertFalse(ExecutionModeRouter.isInternalAlwaysAvailable("ui_click"))
    }
}
