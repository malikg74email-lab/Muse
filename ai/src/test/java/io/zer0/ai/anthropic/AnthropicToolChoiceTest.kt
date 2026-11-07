package io.zer0.ai.anthropic

import io.zer0.common.AppJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 3 (diagnosis §9 fix 10): Anthropic tool_choice parity.
 *
 * Device-test audit root cause: `AnthropicRequest` had no `tool_choice` field
 * at all, so the app's `toolChoice="required"` was silently dropped on the
 * Anthropic path (OpenAI honoured it, Gemini was fixed in Phase 1). This test
 * pins the mapping + wire shape so the parity cannot regress silently.
 */
class AnthropicToolChoiceTest {

    @Test
    fun `required maps to any, none to none, auto to auto`() {
        assertEquals("any", anthropicToolChoiceFor("required")?.type)
        assertEquals("any", anthropicToolChoiceFor("REQUIRED")?.type)
        assertEquals("none", anthropicToolChoiceFor("none")?.type)
        assertEquals("auto", anthropicToolChoiceFor("auto")?.type)
    }

    @Test
    fun `null and blank map to null so the field is omitted`() {
        assertNull(anthropicToolChoiceFor(null))
        assertNull(anthropicToolChoiceFor(""))
        assertNull(anthropicToolChoiceFor("   "))
    }

    @Test
    fun `any other value is a named tool force with case preserved`() {
        val choice = anthropicToolChoiceFor("app_launch")
        assertEquals("tool", choice?.type)
        assertEquals("app_launch", choice?.name)
        // Tool names are case-sensitive; keyword matching is not applied to them.
        assertEquals("MyTool", anthropicToolChoiceFor("MyTool")?.name)
    }

    @Test
    fun `tool_choice serializes in the request body`() {
        val json = AppJson.encodeToString(
            AnthropicRequest(
                model = "claude-sonnet-4-5",
                messages = emptyList(),
                max_tokens = 1024,
                tools = listOf(
                    AnthropicTool(
                        name = "app_launch",
                        description = "Launch an app",
                        inputSchema = AppJson.parseToJsonElement("""{"type":"object"}"""),
                    ),
                ),
                tool_choice = anthropicToolChoiceFor("required"),
            ),
        )
        val root = AppJson.parseToJsonElement(json).jsonObject
        val toolChoice = root["tool_choice"]!!.jsonObject
        assertEquals("any", toolChoice["type"]!!.jsonPrimitive.content)
        assertTrue(root.containsKey("tools"))
    }

    @Test
    fun `named tool_choice serializes with its name`() {
        val json = AppJson.encodeToString(
            AnthropicRequest(
                model = "claude-sonnet-4-5",
                messages = emptyList(),
                max_tokens = 1024,
                tool_choice = anthropicToolChoiceFor("app_launch"),
            ),
        )
        val toolChoice = AppJson.parseToJsonElement(json).jsonObject["tool_choice"]!!.jsonObject
        assertEquals("tool", toolChoice["type"]!!.jsonPrimitive.content)
        assertEquals("app_launch", toolChoice["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `request without tool_choice does not emit the field`() {
        val json = AppJson.encodeToString(
            AnthropicRequest(
                model = "claude-sonnet-4-5",
                messages = emptyList(),
                max_tokens = 1024,
            ),
        )
        val root = AppJson.parseToJsonElement(json).jsonObject
        assertTrue(root["tool_choice"] == null)
    }
}
