package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 1 (§5 Fix 1.4): text-fallback detector fixtures.
 *
 * The two positive fixtures are Malik's EXACT screenshot outputs from the
 * 2026-11-05 device test — before the fix, both completed silently with zero
 * executed tool calls.
 */
class TextToolCallDetectorTest {

    private val toolNames = setOf("app_launch", "app_list", "list_installed_apps", "open_app", "screen_read")

    @Test
    fun `screenshot fixture 1 - tool_code block for app_launch is detected`() {
        val text = """
            <tool_code>
            // Opening the YouTube app.
            app_launch({package_name: "com.google.android.youtube"});
            </tool_code>
            Malik, YouTube khol raha hoon.
        """.trimIndent()
        val detection = TextToolCallDetector.detect(text, toolNames)
        assertNotNull(detection)
        assertEquals("app_launch", detection!!.toolName)
        assertTrue(detection.viaTag)
    }

    @Test
    fun `screenshot fixture 2 - whole reply is a tool_code block`() {
        val text = """
            <tool_code>
            // List apps to verify the package name for YouTube.
            list_installed_apps();
            </tool_code>
        """.trimIndent()
        val detection = TextToolCallDetector.detect(text, toolNames)
        assertNotNull(detection)
        assertEquals("list_installed_apps", detection!!.toolName)
    }

    @Test
    fun `fenced code invocation without the tag is detected`() {
        val text = "```\napp_launch({app: \"YouTube\"})\n```"
        val detection = TextToolCallDetector.detect(text, toolNames)
        assertNotNull(detection)
        assertEquals("app_launch", detection!!.toolName)
    }

    @Test
    fun `ordinary prose mentioning a tool name is not detected`() {
        val text = "You can open YouTube yourself from the home screen; " +
            "the app_launch tool normally handles that, and screen_read shows the result."
        assertNull(TextToolCallDetector.detect(text, toolNames))
    }

    @Test
    fun `prose with a parenthesised tool mention is not detected`() {
        val text = "If you tap the button, the helper (app_launch) opens the app for you."
        assertNull(TextToolCallDetector.detect(text, toolNames))
    }

    @Test
    fun `normal answer and blank text are not detected`() {
        assertNull(TextToolCallDetector.detect("Walaikum Assalam Malik! Kya haal hai?", toolNames))
        assertNull(TextToolCallDetector.detect("", toolNames))
        assertNull(TextToolCallDetector.detect("   ", toolNames))
    }

    @Test
    fun `failure text never claims success`() {
        assertTrue(TextToolCallDetector.FAILURE_TEXT.contains("nothing was done"))
    }
}
