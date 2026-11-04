package io.zer0.muse.tools

/**
 * P1-3b 拆域：翻译工具注册器。
 *
 * 注册 translate。实现位于 [TranslateToolsImpl.kt]。
 */
class TranslateToolsRegistrar(
    private val toolRegistry: ToolRegistry,
) {
    private val impl = TranslateToolsImpl()

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "translate",
                // v1.0.75 fix (工具审查 02): 补触发场景(用户明确要求翻译时才用)
                description = "Translate text (via the standalone translation flow; quality is more stable than answering directly). Call when the user explicitly asks to translate/convert a language. Returns the translation result, without explanations.",
                parameters = mapOf(
                    "text" to "Required, the source text to translate",
                    "target_language" to "Required, target language (e.g. 中文/English/日本語 )",
                    "source_language" to "Optional, source language; auto-detected when not given",
                    "style" to "Optional, 通用/学术/商务/口语化 (translation style; describe polish/concision needs in the prompt)",
                ),
                required = setOf("text", "target_language"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execTranslate(args) }
    }
}
