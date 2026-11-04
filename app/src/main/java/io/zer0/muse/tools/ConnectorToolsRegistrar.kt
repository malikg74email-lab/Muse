package io.zer0.muse.tools

import io.zer0.ai.core.OAuthConfig
import io.zer0.muse.auth.OAuthManager
import io.zer0.muse.connector.ConnectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v2.0: 连接器工具注册器 — 把已授权的 OAuth 连接器暴露为 LLM 工具。
 *
 *  - [TOOL_LIST_CONNECTORS] 列出已配置连接器及授权状态;
 *  - [TOOL_CALL_CONNECTOR] 以连接器凭据调用外部服务 HTTP API(HIGH,走审批):
 *    access_token 自动附带,过期时经 [OAuthManager.refreshTokenIfNeeded] 自动刷新。
 *
 * 安全约束:目标 url 必须与连接器 token 端点同注册域(取末两段域名近似判定),
 * 防止 access_token 被引导发送到无关主机。
 */
class ConnectorToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val connectorStore: ConnectorStore,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_LIST_CONNECTORS,
                description = "List the configured OAuth connectors (external service authorisations) and their id / name / whether authorised." +
                    "Use this tool to get the connector_id before calling an external service.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            runBlocking {
                val connectors = connectorStore.load()
                if (connectors.isEmpty()) {
                    "No connectors configured yet."
                } else {
                    val lines = mutableListOf<String>()
                    for (c in connectors) {
                        val authorized = OAuthManager.getStoredToken("connector_${c.id}") != null
                        lines += "${c.id} | ${c.name.ifBlank { "-" }} | " +
                            if (authorized) "Authorised" else "Not authorised"
                    }
                    lines.joinToString("\n")
                }
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_CALL_CONNECTOR,
                description = "Call an external service's HTTP API with an authorised connector's credentials (automatically attaches Bearer <redacted>; refreshes automatically on expiry)." +
                    "First query the available connector ids with connector_list; the url must be on the same domain as the connector service.",
                parameters = mapOf(
                    "connector_id" to "Required, connector id (see connector_list)",
                    "url" to "Required, full request URL (e.g. https://api.github.com/user)",
                    "method" to "Optional, HTTP method: GET (default)/POST/PUT/PATCH/DELETE",
                    "body" to "Optional, request body (JSON text, used for POST/PUT/PATCH)",
                ),
                required = setOf("connector_id", "url"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            runBlocking { executeCall(args) }
        }
    }

    private suspend fun executeCall(args: Map<String, String>): String = withContext(Dispatchers.IO) {
        val connectorId = args["connector_id"]?.trim().orEmpty()
        val url = args["url"]?.trim().orEmpty()
        val method = args["method"]?.trim()?.uppercase().orEmpty().ifBlank { "GET" }
        val body = args["body"]
        if (connectorId.isBlank()) return@withContext "Missing connector_id"
        if (url.isBlank()) return@withContext "Missing url"
        if (method !in ALLOWED_METHODS) return@withContext "Unsupported method: $method"
        val connector = connectorStore.load().firstOrNull { it.id == connectorId }
            ?: return@withContext "Connector does not exist: $connectorId"
        // 同域校验:防止 token 被发送到无关主机
        if (!sameSite(url, connector.tokenUrl)) {
            return@withContext "Refused: the url's domain does not match the connector service domain" +
                "(${hostOf(url) ?: "?"} does not belong to the ${hostOf(connector.tokenUrl) ?: "?"} site)"
        }
        val oauthConfig = OAuthConfig(
            clientId = connector.clientId,
            clientSecret = connector.clientSecret.takeIf { it.isNotBlank() },
            authorizeUrl = connector.authorizeUrl,
            tokenUrl = connector.tokenUrl,
            redirectUri = "io.zer0.muse://oauth/callback",
            scope = connector.scope,
        )
        val token = OAuthManager.refreshTokenIfNeeded("connector_${connector.id}", oauthConfig)
            .getOrElse { e ->
                return@withContext "Connector not authorised or token refresh failed: ${e.message}" +
                    "(Please complete the authorisation in Settings - Connectors first)"
            }
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                else -> builder.method(method, (body ?: "").toRequestBody(JSON_MEDIA))
            }
            HTTP.newCall(builder.build()).execute().use { resp ->
                val text = resp.body.string()
                formatConnectorResponse(resp.code, text)
            }
        }.getOrElse { e -> "Request failed: ${e.message}" }
    }

    /** 同注册域判定:取末两段域名比较(github.com / api.github.com → github.com)。 */
    private fun sameSite(a: String, b: String): Boolean {
        val hostA = hostOf(a) ?: return false
        val hostB = hostOf(b) ?: return false
        return registeredDomain(hostA) == registeredDomain(hostB)
    }

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase()
    }.getOrNull()

    private fun registeredDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    companion object {
        /** 列出已配置连接器(只读)。 */
        const val TOOL_LIST_CONNECTORS = "connector_list"

        /** 以连接器凭据调用外部服务 API(审批后执行)。 */
        const val TOOL_CALL_CONNECTOR = "call_connector"

        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val HTTP: OkHttpClient by lazy {
            io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal fun formatConnectorResponse(statusCode: Int, body: String): String = "HTTP $statusCode\n$body"
