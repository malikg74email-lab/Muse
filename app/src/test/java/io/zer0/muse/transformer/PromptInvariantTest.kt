package io.zer0.muse.transformer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词回归锚点的测试。
 *
 * 覆盖助手自审两项「靠临场判断、不同轮结果不一致」的问题：
 * - 第 11 条：artifact 与 show_card 的边界老在摇摆，同一类内容不同轮给不同形式。
 * - 第 12 条：搜索是三级优先但没有「够不够用」的阈值，经常过度抓网页或拿摘要就作答。
 *
 * 这类问题的修法只能是提示词 —— 给出**可判定的顺序**而不是「凭感觉」。
 * 本测试钉住这些判据不被后续编辑删掉：它们不是文案，是行为约束。
 */
class PromptInvariantTest {

    private val invariants = SystemPromptAssembler.PROMPT_INVARIANTS

    @Test
    fun `invariant list is not empty`() {
        assertTrue("The invariant list was emptied; the prompt constraints lose their guard", invariants.isNotEmpty())
    }

    @Test
    fun `artifact vs show_card decision is based on what the user does with it`() {
        val joined = invariants.joinToString("\n")

        assertTrue("artifact is judged by taking away", joined.contains("Will the user copy it away or save it?"))
        assertTrue("show_card is judged by operating on it", joined.contains("Will the user tap it, drag it, or change it?"))
    }

    @Test
    fun `duplicating the same content in both forms is forbidden`() {
        val joined = invariants.joinToString("\n")

        assertTrue(
            "Duplicating the same content in both forms is a real user complaint and must be explicitly forbidden",
            joined.contains("Do not** produce both a card and an artifact for the same content"),
        )
    }

    @Test
    fun `search has both stop and continue criteria`() {
        val joined = invariants.joinToString("\n")

        assertTrue("must have a stop criterion", joined.contains("Can already answer the user's question directly"))
        assertTrue("multiple agreeing sources mean stop", joined.contains("Multiple sources agree on the same fact"))
        assertTrue("must have a continue criterion", joined.contains("Key numbers/dates/names are still missing"))
    }

    @Test
    fun `summary alone is not treated as verified`() {
        val joined = invariants.joinToString("\n")

        assertTrue(
            "Treating a search summary as verified is the other half of item 12",
            joined.contains("do not treat a summary as verified"),
        )
    }

    @Test
    fun `every invariant actually appears in the assembled prompt sources`() {
        // 防止「锚点表里写了但提示词正文没写」——那样测试绿了但行为没变。
        val source =
            java.io.File("src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt")
                .takeIf { it.exists() }
                ?.readText()
        if (source == null) return // 不在模块根目录运行时跳过（CI 里会跑到）

        invariants.forEach { phrase ->
            // 锚点表自身会出现一次；正文里必须还有至少一次。
            val occurrences = Regex(Regex.escape(phrase)).findAll(source).count()
            assertTrue(
                "Invariant '$phrase' appears only in the invariant list, not in the prompt body - behaviour would not change",
                occurrences >= 2,
            )
        }
    }
}
