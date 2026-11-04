package io.zer0.memory.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

class RollingSummaryPromptTest {

    @Test
    fun promptKeepsAssistantCommitmentsAndVibeInsideConversationTimeline() {
        val prompt = RollingSummaryPrompt.buildSystemPrompt(locale = "zh-CN")

        assertTrue(prompt.contains("explicit assistant commitments"))
        assertTrue(prompt.contains("Vibe"))
        assertTrue(prompt.contains("assistant self-reported Vibe"))
        assertTrue(prompt.contains("Sparks"))
        assertTrue(prompt.contains("Reflections"))
        assertTrue(prompt.contains("Will"))
    }
}
