package io.zer0.muse.tools

import io.zer0.muse.channel.ChannelManager
import kotlinx.coroutines.runBlocking

/**
 * v1.0.92 拆域：消息渠道工具注册器。
 *
 * 把 [ChannelManager] 的发送能力暴露为 LLM 工具:
 *  - [TOOL_SEND_CHANNEL] 向已配置的外部渠道(飞书/QQ 等)发送一条文本消息(HIGH,走审批);
 *  - [TOOL_LIST_CHANNELS] 列出已配置渠道。
 *
 * 渠道配置由引导页/配置 UI 维护;发送前先刷新一次(读盘开销为一次文件读,低频操作可接受)。
 */
class ChannelToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val channelManager: ChannelManager,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_SEND_CHANNEL,
                description = "Send a text message to a configured external messaging channel (Feishu/QQ, etc.)." +
                    "First use channel_list to query the available channels and their ids; suitable for pushing results or notifications to the user's IM.",
                parameters = mapOf(
                    "channel_id" to "Required, channel id (see channel_list)",
                    "text" to "Required, the text content to send",
                ),
                required = setOf("channel_id", "text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val channelId = args["channel_id"]?.trim().orEmpty()
            val text = args["text"].orEmpty()
            if (channelId.isBlank()) return@register "Missing channel_id"
            if (text.isBlank()) return@register "Missing text"
            runBlocking {
                channelManager.refresh()
                val result = channelManager.sendText(channelId, text)
                if (result.ok) result.detail else "Send failed: ${result.detail}"
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_LIST_CHANNELS,
                description = "List the configured external messaging channels (Feishu/QQ, etc.) and their id / platform / enabled status.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            runBlocking {
                channelManager.refresh()
                val list = channelManager.channels.value
                if (list.isEmpty()) {
                    "No channels configured yet."
                } else {
                    list.joinToString("\n") { c ->
                        "${c.id} | ${c.platform.name} | ${c.name.ifBlank { "-" }} | " +
                            if (c.enabled) "enabled" else "disabled"
                    }
                }
            }
        }
    }

    companion object {
        /** 向外部渠道发送消息(审批后执行)。 */
        const val TOOL_SEND_CHANNEL = "send_channel_message"

        /** 列出已配置渠道。 */
        const val TOOL_LIST_CHANNELS = "channel_list"
    }
}
