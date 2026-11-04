package io.zer0.muse.channel

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ChannelAgentCheckpointTest {
    @Test
    fun `checkpoint round trip preserves tool calls and encrypts conversation context`() = runBlocking {
        val originalCipher = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = TestCipher()
            val checkpoint = ChannelAgentCheckpoint(
                dispatchId = "dispatch-1",
                workingMessages = listOf(
                    UIMessage(role = MessageRole.USER, content = "private conversation text"),
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        content = "",
                        toolCalls = listOf(ToolCall("call-1", "quick_note_add", """{"title":"idea"}""")),
                    ),
                ),
                inFlightToolCallId = "call-1",
            )

            val encrypted = encryptChannelAgentCheckpoint(checkpoint)

            assertFalse(encrypted.contains("private conversation text"))
            assertFalse(encrypted.contains("quick_note_add"))
            assertEquals(checkpoint, decryptChannelAgentCheckpoint(encrypted))
        } finally {
            SecureKeyStore.delegate = originalCipher
        }
    }

    @Test
    fun `unreadable checkpoint is classified as terminal recovery failure`() = runBlocking {
        val originalCipher = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = TestCipher()
            try {
                decryptChannelAgentCheckpoint("corrupted-checkpoint")
                throw AssertionError("corrupted checkpoint must not be treated as a fresh run")
            } catch (error: ChannelAgentCheckpointUnreadableException) {
                assertTrue(error.message.orEmpty().contains("unreadable"))
            }
        } finally {
            SecureKeyStore.delegate = originalCipher
        }
    }

    @Test
    fun `tool execution failure marks the checkpoint outcome as unknown`() {
        assertTrue(
            channelToolOutcomeUnknown(
                previousUnknown = false,
                recoveringUncertainCall = false,
                executionOutcomeUnknown = true,
            ),
        )
        assertTrue(
            channelToolOutcomeUnknown(
                previousUnknown = true,
                recoveringUncertainCall = false,
                executionOutcomeUnknown = false,
            ),
        )
        assertTrue(
            channelToolOutcomeUnknown(
                previousUnknown = false,
                recoveringUncertainCall = true,
                executionOutcomeUnknown = false,
            ),
        )
        assertFalse(
            channelToolOutcomeUnknown(
                previousUnknown = false,
                recoveringUncertainCall = false,
                executionOutcomeUnknown = false,
            ),
        )
        assertTrue(
            channelToolResultUnknown(
                risk = ToolRiskLevel.NORMAL,
                executionThrew = true,
                result = "",
            ),
        )
        assertTrue(
            channelToolResultUnknown(
                risk = ToolRiskLevel.NORMAL,
                executionThrew = false,
                result = "Error: external action failed",
            ),
        )
        assertFalse(
            channelToolResultUnknown(
                risk = ToolRiskLevel.SAFE,
                executionThrew = false,
                result = "Error: malformed input",
            ),
        )
    }

    private class TestCipher : SecureKeyCipher {
        override suspend fun encrypt(plain: String): String {
            val encoded = Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
            return "test-encrypted:$encoded"
        }

        override suspend fun decrypt(stored: String): String = decryptOrNull(stored).orEmpty()

        override suspend fun decryptOrNull(stored: String): String? {
            if (!stored.startsWith("test-encrypted:")) return null
            val bytes = Base64.getDecoder().decode(stored.removePrefix("test-encrypted:"))
            return String(bytes, Charsets.UTF_8)
        }
    }
}
