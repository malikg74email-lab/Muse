package io.zer0.ai.registry

import io.zer0.ai.core.KnownModels
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 fix (2026-11-08): `gemini-3.6-flash` — the model selected on
 * Malik's phone during the HTTP-400 retest — had the same catalog gap that
 * Phase 3 fixed for `gemini-3.1-flash-lite`: GEMINI_FLASH excludes it via
 * notTokens("3"), the 2.5 definitions require "2"/"5", the 3.1 Lite
 * definition requires "1"/"lite", and neither exact-match catalog held the
 * id, so abilities resolved empty and the context window fell back to the
 * brand default instead of an explicit entry.
 */
class Gemini36FlashCatalogTest {

    @Test
    fun `registry resolves tool and reasoning abilities for gemini-3-6-flash`() {
        val abilities = ModelRegistry.resolveAbilities("gemini-3.6-flash")
        assertTrue("TOOL must resolve, got $abilities", ModelAbility.TOOL in abilities)
        assertTrue("REASONING must resolve, got $abilities", ModelAbility.REASONING in abilities)
    }

    @Test
    fun `registry definition for the id is the dedicated 3-6 flash one`() {
        val definitions = ModelRegistry.resolveDefinitions("gemini-3.6-flash")
        assertEquals(1, definitions.size)
    }

    @Test
    fun `known models lookup finds the exact id with tool ability`() {
        val info = KnownModels.lookup("gemini-3.6-flash")
        assertNotNull("KnownModels must know gemini-3.6-flash", info)
        assertTrue(ModelAbility.TOOL in info!!.abilities)
        assertEquals(1000000, info.contextWindow)
    }

    @Test
    fun `context window registry resolves gemini-3-6-flash to 1M`() {
        assertEquals(1000000, ModelContextWindowRegistry.lookup("gemini-3.6-flash"))
    }
}
