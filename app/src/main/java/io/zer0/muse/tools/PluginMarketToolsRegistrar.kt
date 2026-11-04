package io.zer0.muse.tools

import io.zer0.muse.data.plugin.market.PluginMarketToolGateway

/**
 * v2.0.1: 插件市场工具注册器 — 让助手直接检索插件市场并按审批安装插件。
 *
 *  - [TOOL_MARKET_SEARCH] 只读检索（SAFE）：列出市场插件与安装/信任状态；
 *  - [TOOL_MARKET_INSTALL] 下载并安装（HIGH，必须走会话审批）：发行者未受信任时
 *    先返回待确认信息，用户确认后带 trust_publisher=true 重试；
 *  - [TOOL_MARKET_UNINSTALL] 卸载已安装插件（HIGH）：注册表、目录、归属技能与保留版本一并清理；
 *  - [TOOL_MARKET_SET_ENABLED] 启用/停用已安装插件（HIGH）：停用保留安装，可随时恢复。
 *
 * 安全：安装复用插件管理页同一套审查链路（目录验签、发行者签名、内容摘要、
 * 安装协调器），不提供绕过用户审批或信任根的任何路径。见
 * [io.zer0.muse.data.plugin.market.PluginMarketToolGateway]。
 */
class PluginMarketToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val marketService: PluginMarketToolGateway,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_SEARCH,
                description = "Search for plugins in the Shaheen 2 plugin marketplace (external tools/declarative skin packs). Returns the plugin id, name, version, description," +
                    "publisher and install/trust status. After finding a suitable plugin, show the key information to the user, then install it with plugin_market_install" +
                    "(installing pops up an approval card).",
                parameters = mapOf("query" to "Optional, keyword (matches id/name/description); leave empty to list all"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            marketService.search(args["query"].orEmpty())
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_INSTALL,
                description = "Install a plugin from the plugin marketplace (get plugin_id with plugin_market_search). Execution downloads the installation package," +
                    "verifies the publisher signature and content digest, then installs; an approval card pops up for the user before installing." +
                    "When the publisher is not yet trusted, this tool returns information to confirm: show the information to the user first; after the user confirms, call again with " +
                    "trust_publisher=true.",
                parameters = mapOf(
                    "plugin_id" to "Required, plugin id (see plugin_market_search)",
                    "trust_publisher" to "Optional, true/false: pass true when the user has confirmed trusting this plugin's publisher (needed for the first install of a plugin from an untrusted publisher)",
                ),
                required = setOf("plugin_id"),
                category = "built-in",
                parameterTypes = mapOf("trust_publisher" to "boolean"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pluginId = args["plugin_id"].orEmpty()
            if (pluginId.isBlank()) return@register "Installation failed: missing plugin_id."
            val trustPublisher = args["trust_publisher"]?.equals("true", ignoreCase = true) == true
            marketService.install(pluginId, trustPublisher)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_UNINSTALL,
                description = "Uninstall an installed plugin (registry, plugin directory, owned skills and retained versions are all cleaned up; unrecoverable)." +
                    "Get plugin_id with plugin_market_search, or have the user confirm it on the plugin management page. Execution pops up an approval card.",
                parameters = mapOf("plugin_id" to "Required, the installed plugin's id"),
                required = setOf("plugin_id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            marketService.uninstall(args["plugin_id"].orEmpty())
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_SET_ENABLED,
                description = "Enable or disable an installed plugin. Disabling (enabled=false) only freezes the plugin and its skills; the installation is kept and can be restored at any time;" +
                    "if you want it gone entirely, use plugin_market_uninstall. Execution pops up an approval card.",
                parameters = mapOf(
                    "plugin_id" to "Required, the installed plugin's id",
                    "enabled" to "Required, true=enable, false=disable",
                ),
                required = setOf("plugin_id", "enabled"),
                category = "built-in",
                parameterTypes = mapOf("enabled" to "boolean"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pluginId = args["plugin_id"].orEmpty()
            if (pluginId.isBlank()) return@register "Operation failed: missing plugin_id."
            val enabled = args["enabled"]?.equals("true", ignoreCase = true) == true
            marketService.setEnabled(pluginId, enabled)
        }
    }

    companion object {
        /** 检索插件市场（只读）。 */
        const val TOOL_MARKET_SEARCH = "plugin_market_search"

        /** 安装插件（HIGH，走审批）。 */
        const val TOOL_MARKET_INSTALL = "plugin_market_install"

        /** 卸载已安装插件（HIGH，走审批）。 */
        const val TOOL_MARKET_UNINSTALL = "plugin_market_uninstall"

        /** 启用/停用已安装插件（HIGH，走审批）。 */
        const val TOOL_MARKET_SET_ENABLED = "plugin_market_set_enabled"
    }
}
