package io.zer0.ai.registry

import io.zer0.ai.core.KnownModels
import io.zer0.ai.core.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 3 (diagnosis §5 Fix 1.3): `gemini-3.1-flash-lite` —
 * the model id selected on Malik's phone — resolved NOWHERE before this fix:
 * the registry defined Gemini only up to 2.5 (and GEMINI_FLASH excludes "3"
 * via notTokens), and the exact-match catalog held only a `-preview` id.
 * With no TOOL ability resolved, tool use was suppressed for his model.
 */
class Gemini31FlashLiteCatalogTest {

    @Test
    fun `registry resolves tool and reasoning abilities for gemini-3-1-flash-lite`() {
        val abilities = ModelRegistry.resolveAbilities("gemini-3.1-flash-lite")
        assertTrue("TOOL must resolve, got $abilities", ModelAbility.TOOL in abilities)
        assertTrue("REASONING must resolve, got $abilities", ModelAbility.REASONING in abilities)
    }

    @Test
    fun `registry definition for the id is the dedicated 3-1 flash lite one`() {
        val definitions = ModelRegistry.resolveDefinitions("gemini-3.1-flash-lite")
        assertEquals(1, definitions.size)
    }

    @Test
    fun `known models lookup finds the exact id with tool ability`() {
        val info = KnownModels.lookup("gemini-3.1-flash-lite")
        assertNotNull("KnownModels must know gemini-3.1-flash-lite", info)
        assertTrue(ModelAbility.TOOL in info!!.abilities)
        assertEquals(1000000, info.contextWindow)
    }
}
