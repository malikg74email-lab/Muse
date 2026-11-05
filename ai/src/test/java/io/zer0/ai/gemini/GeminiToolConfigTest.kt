package io.zer0.ai.gemini

import io.zer0.common.AppJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 1 (§5 Fix 1.1): Gemini toolConfig parity.
 *
 * Device-test root cause: `GeminiRequest` had no `toolConfig` field at all, so the
 * app's `toolChoice="required"` was silently dropped on the Gemini path and the
 * model wrote its tool call as text instead of a native functionCall.
 */
class GeminiToolConfigTest {

    @Test
    fun `required maps to ANY, none to NONE, everything else to AUTO`() {
        assertEquals("ANY", geminiToolConfigFor("required").functionCallingConfig?.mode)
        assertEquals("ANY", geminiToolConfigFor("REQUIRED").functionCallingConfig?.mode)
        assertEquals("NONE", geminiToolConfigFor("none").functionCallingConfig?.mode)
        assertEquals("AUTO", geminiToolConfigFor("auto").functionCallingConfig?.mode)
        assertEquals("AUTO", geminiToolConfigFor(null).functionCallingConfig?.mode)
    }

    @Test
    fun `toolConfig serializes as functionCallingConfig in the request body`() {
        val json = AppJson.encodeToString(
            GeminiRequest(
                contents = emptyList(),
                tools = listOf(
                    GeminiTool(
                        functionDeclarations = listOf(
                            GeminiFunctionDeclaration(
                                name = "app_launch",
                                description = "Launch an app",
                                parameters = AppJson.parseToJsonElement("""{"type":"object"}"""),
                            ),
                        ),
                    ),
                ),
                toolConfig = geminiToolConfigFor("required"),
            ),
        )
        val root = AppJson.parseToJsonElement(json).jsonObject
        val toolConfig = root["toolConfig"]!!.jsonObject
        val fcc = toolConfig["functionCallingConfig"]!!.jsonObject
        assertEquals("\"ANY\"", fcc["mode"].toString())
        assertTrue(root.containsKey("tools"))
    }

    @Test
    fun `request without toolConfig does not emit the field`() {
        val json = AppJson.encodeToString(GeminiRequest(contents = emptyList()))
        val root = AppJson.parseToJsonElement(json).jsonObject
        assertTrue(root["toolConfig"] == null)
    }
}
