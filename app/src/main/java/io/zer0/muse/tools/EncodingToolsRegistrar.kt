package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R

/**
 * P1-3b 拆域：文本/编码工具注册器。
 *
 * 从 ToolRegistry.kt 抽出的纯计算工具（URL / Base64 / 哈希 / UUID / 随机数）。
 * 只依赖 Context（字符串资源），不持有 ToolRegistry 内部状态。
 */
class EncodingToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "url_encode",
                description = "URL-encode text.",
                parameters = mapOf("text" to "Required, the text to encode"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                context.getString(R.string.tool_url_encoded, java.net.URLEncoder.encode(text, "UTF-8"))
            }.onError { msg, _ -> Logger.w("EncodingTools", "URL encoding failed: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_url_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "url_decode",
                description = "Decode URL-encoded text.",
                parameters = mapOf("text" to "Required, the text to decode"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                context.getString(R.string.tool_url_decoded, java.net.URLDecoder.decode(text, "UTF-8"))
            }.onError { msg, _ -> Logger.w("EncodingTools", "URL decoding failed: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_url_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "base64_encode",
                description = "Base64-encode text.",
                parameters = mapOf("text" to "Required, the text to encode"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                context.getString(
                    R.string.tool_base64_encoded,
                    android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP),
                )
            }.onError { msg, _ -> Logger.w("EncodingTools", "Base64 encoding failed: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_url_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "base64_decode",
                description = "Decode Base64 text.",
                parameters = mapOf("text" to "Required, the Base64 text to decode"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                val bytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                context.getString(R.string.tool_base64_decoded, String(bytes, Charsets.UTF_8))
            }.onError { msg, _ -> Logger.w("EncodingTools", "Base64 decoding failed: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_base64_failed)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "hash_text",
                description = "Compute a text hash. Supports MD5, SHA-1, SHA-256; default SHA-256.",
                parameters = mapOf(
                    "text" to "Required, the text to hash",
                    "algorithm" to "Optional, MD5/SHA-1/SHA-256, default SHA-256",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_hash_missing)
            val algo = args["algorithm"]?.uppercase() ?: "SHA-256"
            if (algo !in setOf("MD5", "SHA-1", "SHA-256")) {
                return@register context.getString(R.string.tool_hash_unsupported, algo)
            }
            resultOf {
                val digest = java.security.MessageDigest.getInstance(algo)
                val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                context.getString(R.string.tool_hash_result, algo, hash)
            }.onError { msg, _ -> Logger.w("EncodingTools", "Hash computation failed: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_hash_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_uuid",
                description = "Generate a random UUID (universally unique identifier).",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            context.getString(R.string.tool_uuid_result, java.util.UUID.randomUUID().toString())
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "random_number",
                description = "Generate a random integer within the specified range (inclusive).",
                parameters = mapOf(
                    "min" to "Optional, minimum value, default 0",
                    "max" to "Optional, maximum value, default 100",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("min" to "integer", "max" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            var min = args["min"]?.toIntOrNull() ?: 0
            var max = args["max"]?.toIntOrNull() ?: 100
            if (min > max) {
                min = max.also { max = min }
            }
            context.getString(R.string.tool_random_number_result, kotlin.random.Random.nextInt(min, max + 1))
        }
    }
}
