package io.zer0.muse.transformer

import io.zer0.muse.tools.ToolCounts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示词工具索引头部的计数文案：只报「本轮可调用」，不再拿注册总数吓模型。 */
class ToolManifestCountSectionTest {

    private val counts = ToolCounts(callableInSchema = 12, sessionVisible = 20, registeredTotal = 33)
    private val section =
        SystemPromptAssembler.toolManifestCountSection(
            counts,
            mapOf("built-in" to 3, "file" to 9),
        )

    @Test
    fun `reports the callable count`() {
        assertTrue("must state the callable-this-turn count", section.contains("callable this turn"))
        assertTrue(section.contains("12 tools callable this turn"))
    }

    @Test
    fun `does not claim the registered total as callable`() {
        assertFalse("must not use registered-total wording", section.contains("registered"))
        assertFalse("must not report the registered total (33) as callable", section.contains("33"))
    }

    @Test
    fun `built-in count comes from the same selection as the total`() {
        assertTrue("category distribution must come from the same selection as N", section.contains("built-in 3"))
        assertTrue("category distribution must carry category names", section.contains("file 9"))
    }

    @Test
    fun `explains the find_tools tier separately`() {
        assertTrue("must explain the wider find_tools tier", section.contains("find_tools can list 20 tools"))
        assertFalse("must not merge the 20 into the callable-this-turn number", section.contains("20 tools callable this turn"))
    }
}
