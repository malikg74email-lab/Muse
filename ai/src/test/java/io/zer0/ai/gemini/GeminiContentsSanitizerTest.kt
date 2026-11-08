package io.zer0.ai.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 fix (2026-11-08): Gemini HTTP 400 in long conversations.
 *
 * Gemini rejects a request whose functionCall / functionResponse parts do not
 * pair up exactly (400 INVALID_ARGUMENT). These tests pin the invariant on
 * the final contents list: no orphan responses, no dangling calls, and
 * well-formed histories pass through untouched.
 */
class GeminiContentsSanitizerTest {

    private fun userText(text: String) =
        GeminiContent(role = "user", parts = listOf(GeminiPart(text = text)))

    private fun modelText(text: String) =
        GeminiContent(role = "model", parts = listOf(GeminiPart(text = text)))

    private fun modelCalls(vararg names: String, text: String? = null) =
        GeminiContent(
            role = "model",
            parts = buildList {
                names.forEach { add(GeminiPart(functionCall = GeminiFunctionCall(name = it))) }
                text?.let { add(GeminiPart(text = it)) }
            },
        )

    private fun userResponse(name: String) =
        GeminiContent(
            role = "user",
            parts = listOf(GeminiPart(functionResponse = GeminiFunctionResponse(name = name))),
        )

    /** Gemini's pairing rule, checked independently of the sanitizer's internals. */
    private fun assertPairingValid(contents: List<GeminiContent>) {
        val pending = mutableListOf<String>()
        for (content in contents) {
            val responses = content.parts.mapNotNull { it.functionResponse }
            val calls = content.parts.mapNotNull { it.functionCall }
            if (responses.isEmpty() && calls.isEmpty()) {
                assertTrue("plain turn while calls are unanswered: $pending", pending.isEmpty())
            }
            for (response in responses) {
                assertTrue(
                    "orphan functionResponse '${response.name}'",
                    pending.remove(response.name),
                )
            }
            pending += calls.map { it.name }
        }
        assertTrue("dangling functionCalls left over: $pending", pending.isEmpty())
    }

    @Test
    fun `well formed tool history passes through unchanged`() {
        val history = listOf(
            userText("open YouTube and like the video"),
            modelCalls("app_launch", "screen_tap", text = "Doing it now"),
            userResponse("app_launch"),
            userResponse("screen_tap"),
            modelText("Done - YouTube is open and the video is liked."),
            userText("thanks"),
        )
        val sanitized = GeminiContentsSanitizer.sanitize(history)
        assertEquals(history, sanitized)
        assertPairingValid(sanitized)
    }

    @Test
    fun `history without function parts is returned as-is`() {
        val plain = listOf(userText("hi"), modelText("hello"))
        assertSame(plain, GeminiContentsSanitizer.sanitize(plain))
    }

    @Test
    fun `naive cut between a call and its response is repaired`() {
        // (a) A message-count window that keeps only the tail of this history
        // starts at the second response — the call it answers was cut away.
        val full = listOf(
            userText("first question"),
            modelCalls("search"),
            userResponse("search"),
            modelText("answer one"),
            userText("second question"),
            modelCalls("search"),
            userResponse("search"),
            modelText("answer two"),
        )
        val cut = full.takeLast(2)
        assertEquals("search", cut.first().parts.single().functionResponse?.name)

        val sanitized = GeminiContentsSanitizer.sanitize(cut)
        assertPairingValid(sanitized)
        assertEquals(listOf(modelText("answer two")), sanitized)
    }

    @Test
    fun `orphan response is dropped but sibling text in the same content survives`() {
        // (b) Orphan functionResponse with no preceding call at all.
        val orphan = GeminiContent(
            role = "user",
            parts = listOf(
                GeminiPart(text = "hello"),
                GeminiPart(functionResponse = GeminiFunctionResponse(name = "ghost")),
            ),
        )
        val sanitized = GeminiContentsSanitizer.sanitize(listOf(orphan))
        assertEquals(
            listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = "hello")))),
            sanitized,
        )
        assertPairingValid(sanitized)
    }

    @Test
    fun `response whose name matches no pending call is dropped`() {
        val history = listOf(
            userText("go"),
            modelCalls("search"),
            userResponse("serach"), // typo'd name - matches nothing
            userResponse("search"),
            modelText("done"),
        )
        val sanitized = GeminiContentsSanitizer.sanitize(history)
        assertEquals(
            listOf(userText("go"), modelCalls("search"), userResponse("search"), modelText("done")),
            sanitized,
        )
        assertPairingValid(sanitized)
    }

    @Test
    fun `duplicate response for the same call is dropped`() {
        val history = listOf(
            userText("go"),
            modelCalls("search"),
            userResponse("search"),
            userResponse("search"),
            modelText("done"),
        )
        val sanitized = GeminiContentsSanitizer.sanitize(history)
        assertEquals(
            listOf(userText("go"), modelCalls("search"), userResponse("search"), modelText("done")),
            sanitized,
        )
        assertPairingValid(sanitized)
    }

    @Test
    fun `dangling call is stripped when the next turn is a plain user message`() {
        // (c) The tool result was never recorded (e.g. generation stopped),
        // then the user simply sent a new message.
        val history = listOf(
            userText("check the weather"),
            modelCalls("get_weather", text = "Checking now"),
            userText("never mind, tell me a joke"),
        )
        val sanitized = GeminiContentsSanitizer.sanitize(history)
        assertEquals(
            listOf(
                userText("check the weather"),
                modelText("Checking now"),
                userText("never mind, tell me a joke"),
            ),
            sanitized,
        )
        assertPairingValid(sanitized)
    }

    @Test
    fun `partially answered turn keeps the answered call and strips the rest`() {
        // (c) Two calls, only one result recorded before the conversation moved on.
        val history = listOf(
            userText("do both things"),
            modelCalls("first_tool", "second_tool"),
            userResponse("first_tool"),
            userText("ok, continue"),
        )
        val sanitized = GeminiContentsSanitizer.sanitize(history)
        assertEquals(
            listOf(
                userText("do both things"),
                modelCalls("first_tool"),
                userResponse("first_tool"),
                userText("ok, continue"),
            ),
            sanitized,
        )
        assertPairingValid(sanitized)
    }

    @Test
    fun `incomplete final turn is dropped or repaired`() {
        // (d) History ends on a model call turn with no results at all -
        // the shape left behind when generation is stopped mid-tool-loop.
        val noText = listOf(userText("go"), modelCalls("search"))
        assertEquals(listOf(userText("go")), GeminiContentsSanitizer.sanitize(noText))

        val withText = listOf(userText("go"), modelCalls("search", text = "Working on it"))
        val sanitized = GeminiContentsSanitizer.sanitize(withText)
        assertEquals(listOf(userText("go"), modelText("Working on it")), sanitized)
        assertPairingValid(sanitized)
    }

    @Test
    fun `a newer call turn supersedes unanswered calls from the previous one`() {
        val history = listOf(
            userText("go"),
            modelCalls("stale_tool"),
            modelCalls("fresh_tool"),
            userResponse("fresh_tool"),
            modelText("done"),
        )
        val sanitized = GeminiContentsSanitizer.sanitize(history)
        assertEquals(
            listOf(userText("go"), modelCalls("fresh_tool"), userResponse("fresh_tool"), modelText("done")),
            sanitized,
        )
        assertPairingValid(sanitized)
    }
}
