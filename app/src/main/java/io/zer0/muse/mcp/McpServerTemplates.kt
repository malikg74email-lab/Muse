package io.zer0.muse.mcp

/**
 * MCP Server 配置模板。
 *
 * 模板只提供连接方式和用途提示,不内置第三方服务地址、账号或令牌。
 * 这样既能减少首次配置成本,也不会把某个厂商的 endpoint 当成永久契约。
 */
data class McpServerTemplate(
    val id: String,
    val displayName: String,
    val summary: String,
    val transportType: McpTransportType,
    val urlPlaceholder: String,
    val defaultHeaders: Map<String, String> = emptyMap(),
    /** STDIO 模板的默认命令（transportType == STDIO 时使用）。 */
    val commandPlaceholder: String = "",
)

/**
 * 国内常用办公/知识库软件的 MCP 配置模板。
 *
 * 其中“远程 MCP”可直接填写厂商提供的 HTTP endpoint；
 * “桥接”表示需要在电脑或服务器上运行一个 MCP adapter,再把它暴露为 SSE/Streamable HTTP。
 */
object McpServerTemplates {
    const val CUSTOM_ID = "custom"

    val custom = McpServerTemplate(
        id = CUSTOM_ID,
        displayName = "Custom MCP Server",
        summary = "Fill in the endpoint, transport type and authorisation info manually",
        transportType = McpTransportType.STREAMABLE_HTTP,
        urlPlaceholder = "https://server.example.com/mcp",
    )

    val all: List<McpServerTemplate> = listOf(
        // P1-A: 本地 MCP（内置 Node 运行时，stdio 传输；首次运行需联网下载依赖包）
        McpServerTemplate(
            id = "local_filesystem",
            displayName = "Local file system",
            summary = "Local MCP: lets the AI read and write folders on the phone (built-in runtime)",
            transportType = McpTransportType.STDIO,
            urlPlaceholder = "",
            commandPlaceholder = "npx -y @modelcontextprotocol/server-filesystem /sdcard/Documents",
        ),
        McpServerTemplate(
            id = "local_memory",
            displayName = "Memory knowledge graph",
            summary = "Local MCP: knowledge-graph style long-term memory (built-in runtime)",
            transportType = McpTransportType.STDIO,
            urlPlaceholder = "",
            commandPlaceholder = "npx -y @modelcontextprotocol/server-memory",
        ),
        McpServerTemplate(
            id = "local_sequential_thinking",
            displayName = "Sequential thinking",
            summary = "Local MCP: step-by-step reasoning aid (built-in runtime)",
            transportType = McpTransportType.STDIO,
            urlPlaceholder = "",
            commandPlaceholder = "npx -y @modelcontextprotocol/server-sequential-thinking",
        ),
        McpServerTemplate(
            id = "feishu_remote",
            displayName = "Feishu knowledge base",
            summary = "Remote MCP: search and read Feishu cloud documents",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Paste the Feishu remote MCP service address",
            // 默认只读,需要写入记忆时由用户主动在高级字段追加 update/create 工具。
            defaultHeaders = mapOf(
                "X-Lark-MCP-Allowed-Tools" to
                    "search-doc,fetch-doc,list-docs,fetch-file,get-comments",
            ),
        ),
        McpServerTemplate(
            id = "feishu_cli_bridge",
            displayName = "Feishu CLI bridge",
            summary = "Local CLI turned into HTTP/SSE through a desktop bridge",
            transportType = McpTransportType.SSE,
            urlPlaceholder = "http://your-computer-address:port/sse",
        ),
        McpServerTemplate(
            id = "dingtalk_knowledge",
            displayName = "DingTalk knowledge base",
            summary = "Remote MCP or self-hosted bridge: read DingTalk documents and knowledge bases",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Paste the DingTalk MCP/bridge service address",
        ),
        McpServerTemplate(
            id = "wecom_knowledge",
            displayName = "WeCom knowledge base",
            summary = "Self-hosted bridge: connect WeCom documents, group bots or app APIs",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Paste the WeCom MCP bridge address",
        ),
        McpServerTemplate(
            id = "yuque_knowledge",
            displayName = "Yuque knowledge base",
            summary = "Self-hosted bridge: wrap search and reading tools using the Yuque open API",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Paste the Yuque MCP bridge address",
        ),
        McpServerTemplate(
            id = "tencent_docs",
            displayName = "Tencent Docs",
            summary = "Self-hosted bridge: expose the Tencent Docs search API as MCP",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Paste the Tencent Docs MCP bridge address",
        ),
    )

    /** 按 id 查找模板,找不到时返回自定义模板。 */
    fun find(id: String): McpServerTemplate = all.firstOrNull { it.id == id } ?: custom
}
