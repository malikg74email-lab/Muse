package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFrameDiagnosticsTest {
    @Test
    fun `malformed frame error exposes provider and size without leaking payload`() {
        val payload = """{"secret":"user message","unexpected":}"""
        val cause = IllegalArgumentException("decode failed")

        val event = malformedStreamFrameError("OpenAI", payload, cause)

        assertEquals(cause, event.throwable)
        assertTrue(event.message.contains("OpenAI"))
        assertTrue(event.message.contains("frameLength=${payload.length}"))
        assertTrue(event.message.contains("please retry"))
        assertFalse(event.message.contains("user message"))
        assertFalse(event.message.contains("secret"))
    }
}
