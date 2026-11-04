package io.zer0.muse.tools

import android.content.Context

/**
 * P1-3b 拆域：TTS 工具注册器。
 *
 * 注册 speak_text。实现位于 [TtsToolsImpl.kt]。
 */
class TtsToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = TtsToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "speak_text",
                // v1.0.75 fix (工具审查 02): 补触发场景与返回说明
                description = "Read text aloud with the system TTS (call when the user asks to 'read it out / say it / announce it'). Returns a text confirmation of whether reading succeeded; does not return an audio file.",
                parameters = mapOf(
                    "text" to "Required, the text to read aloud",
                    "language" to "Optional, language code, e.g. zh-CN/en-US, default follows the system",
                    "rate" to "Optional, speech-rate multiplier, e.g. 0.8/1.0/1.5, default 1.0",
                ),
                required = setOf("text"),
                category = "built-in",
                parameterTypes = mapOf("rate" to "number"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.speak(args) }
    }
}
