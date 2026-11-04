package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * P1-3b 拆域：网络/文本工具注册器。
 *
 * 从 ToolRegistry.kt 抽出的 ping_host / dns_lookup / get_public_ip / json_pretty / generate_password，
 * 只依赖 Context 字符串资源与标准网络/序列化库。
 */
class NetworkTextToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "ping_host",
                description = "Ping the specified domain or IP to test network reachability.",
                parameters = mapOf(
                    "host" to "Required, target domain or IP, e.g. baidu.com",
                    "timeout_ms" to "Optional, timeout in milliseconds, default 3000",
                ),
                required = setOf("host"),
                category = "built-in",
                parameterTypes = mapOf("timeout_ms" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val host = args["host"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_url_missing)
            val timeout = args["timeout_ms"]?.toIntOrNull()?.coerceAtLeast(100) ?: 3000
            resultOf {
                withContext(Dispatchers.IO) {
                    val address = java.net.InetAddress.getByName(host)
                    val reachable = address.isReachable(timeout)
                    context.getString(
                        R.string.tool_ping_host_result,
                        host,
                        address.hostAddress ?: "unknown",
                        if (reachable) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
                        "$timeout",
                    )
                }
            }.onError { msg, _ -> Logger.w("NetworkTools", "Ping failed: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_ping_host_failed, host)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "dns_lookup",
                description = "Resolve all IP addresses for a domain (A records).",
                parameters = mapOf("host" to "Required, target domain, e.g. google.com"),
                required = setOf("host"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val host = args["host"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                withContext(Dispatchers.IO) {
                    val addresses = java.net.InetAddress.getAllByName(host)
                    val ips = addresses.mapNotNull { it.hostAddress }
                    if (ips.isEmpty()) {
                        context.getString(R.string.tool_dns_lookup_empty, host)
                    } else {
                        context.getString(R.string.tool_dns_lookup_result, host, ips.size, ips.joinToString("\n"))
                    }
                }
            }.onError { msg, _ -> Logger.w("NetworkTools", "DNS resolution failed: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_dns_lookup_failed, host)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_public_ip",
                description = "Get the current device's public IP address.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            resultOf {
                withContext(Dispatchers.IO) {
                    val endpoints = listOf(
                        "https://api.ipify.org",
                        "https://checkip.amazonaws.com",
                    )
                    for (endpoint in endpoints) {
                        try {
                            val url = java.net.URL(endpoint)
                            val ip = url.openStream().bufferedReader(Charsets.UTF_8).use { it.readLine()?.trim() }
                            if (!ip.isNullOrBlank()) {
                                return@withContext context.getString(R.string.tool_public_ip_result, ip)
                            }
                        } catch (_: Exception) { /* try next */ }
                    }
                    context.getString(R.string.tool_public_ip_failed)
                }
            }.onError { msg, _ -> Logger.w("NetworkTools", "Get public IP failed: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_public_ip_failed)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "json_pretty",
                description = "Format a JSON string into a readable form (beautify/minify).",
                parameters = mapOf(
                    "json" to "Required, the JSON string to format",
                    "indent" to "Optional, true/false whether to expand indentation, default true",
                ),
                required = setOf("json"),
                category = "built-in",
                parameterTypes = mapOf("indent" to "boolean"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val input = args["json"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_url_missing)
            val indent = args["indent"]?.equals("true", ignoreCase = true) ?: true
            resultOf {
                val prettyJson = Json {
                    prettyPrint = indent
                    ignoreUnknownKeys = true
                }
                val element = Json.parseToJsonElement(input)
                prettyJson.encodeToString(JsonElement.serializer(), element)
            }.onError { msg, _ -> Logger.w("NetworkTools", "JSON formatting failed: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_json_pretty_failed)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_password",
                description = "Generate a random password. You can specify the length and whether to include uppercase/lowercase letters, digits and symbols.",
                parameters = mapOf(
                    "length" to "Optional, password length, default 16, range 4-64",
                    "uppercase" to "Optional, true/false include uppercase letters, default true",
                    "lowercase" to "Optional, true/false include lowercase letters, default true",
                    "digits" to "Optional, true/false include digits, default true",
                    "symbols" to "Optional, true/false include special symbols, default true",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("length" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val length = args["length"]?.toIntOrNull()?.coerceIn(4, 64) ?: 16
            val useUpper = args["uppercase"]?.equals("false", ignoreCase = true) != true
            val useLower = args["lowercase"]?.equals("false", ignoreCase = true) != true
            val useDigits = args["digits"]?.equals("false", ignoreCase = true) != true
            val useSymbols = args["symbols"]?.equals("false", ignoreCase = true) != true
            val pool = buildString {
                if (useUpper) append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                if (useLower) append("abcdefghijklmnopqrstuvwxyz")
                if (useDigits) append("0123456789")
                if (useSymbols) append("!@#$%^&*()-_=+[]{}|;:,.<>?")
            }
            if (pool.isEmpty()) return@register context.getString(R.string.tool_password_empty_pool)
            val random = java.security.SecureRandom()
            val password = CharArray(length) { pool[random.nextInt(pool.length)] }.concatToString()
            context.getString(R.string.tool_password_result, length, password)
        }
    }
}
