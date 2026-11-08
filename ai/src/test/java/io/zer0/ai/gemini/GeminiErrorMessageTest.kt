package io.zer0.ai.gemini

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 fix (2026-11-08): a Gemini 400 must surface the server's own
 * `error.message` in the error text. Before this fix the message was cut to
 * a 200-char excerpt (and, with the 500-char body read cap, long bodies
 * failed JSON decoding entirely), leaving a bare "HTTP 400" that hid the
 * actual cause — e.g. the functionCall/functionResponse mismatch sentence.
 */
class GeminiErrorMessageTest {

    private val provider = GeminiProvider(
        ProviderConfig(
            id = "gemini-test",
            displayName = "Gemini Test",
            type = ProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "test-key",
            specific = ProviderSpecificConfig.Gemini(),
        ),
    )

    @Test
    fun `400 keeps the server's full INVALID_ARGUMENT message`() {
        // ~330 chars: longer than the old 200-char excerpt, shorter than the
        // new 600-char bound, so it must survive whole.
        val serverMessage =
            "Please ensure that the number of function response parts is equal to the " +
                "number of function call parts of the function call turn. The function call " +
                "turn specifies 2 function call(s) but the following turn contains only 1 " +
                "function response(s), so the conversation history is invalid and must be " +
                "repaired before resending."
        val body = """{"error":{"code":400,"message":"$serverMessage","status":"INVALID_ARGUMENT"}}"""

        val parsed = provider.parseErrorMessage(400, body)

        assertTrue(parsed.startsWith("HTTP 400 (INVALID_ARGUMENT)"))
        assertTrue(
            "the server message must survive whole, got: $parsed",
            parsed.contains(serverMessage),
        )
    }

    @Test
    fun `very long server message stays bounded`() {
        val longMessage = "x".repeat(5000)
        val body = """{"error":{"code":400,"message":"$longMessage","status":"INVALID_ARGUMENT"}}"""

        val parsed = provider.parseErrorMessage(400, body)

        assertTrue("error text must stay bounded, was ${parsed.length} chars", parsed.length <= 700)
        assertFalse(parsed.contains("x".repeat(601)))
    }

    @Test
    fun `blank body still yields the bare status line`() {
        assertEquals("HTTP 400", provider.parseErrorMessage(400, ""))
    }

    @Test
    fun `non-JSON body does not crash and keeps the status line`() {
        val parsed = provider.parseErrorMessage(400, "<html>Bad Request</html>")
        assertEquals("HTTP 400", parsed)
    }
}
