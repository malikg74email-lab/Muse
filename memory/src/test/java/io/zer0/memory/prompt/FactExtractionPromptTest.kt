package io.zer0.memory.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

class FactExtractionPromptTest {

    @Test
    fun promptExcludesAssistantVibeAndCommitmentsFromUserFacts() {
        val prompt = FactExtractionPrompt.buildSystemPrompt(locale = "zh-CN")

        assertTrue(prompt.contains("the assistant's self-reported Vibe"))
        assertTrue(prompt.contains("commitments"))
        assertTrue(prompt.contains("not user facts"))
    }
}
