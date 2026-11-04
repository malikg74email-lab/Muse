package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 助手自管理工具集 —— 让助手能创建/修改/删除助手。
 *
 * ## 限权设计（这是全工具面里最敏感的）
 *
 * AI 改助手的配置可能形成越权闭环（例如把自己绑到更多工具上）。
 * 因此本工具集**只开放安全子集**：
 *
 *  | 操作 | 允许修改的字段 | 禁止修改的字段 |
 *  |---|---|---|
 *  | 创建 | name, systemPrompt, avatarEmoji | toolIdsJson, skillIdsJson, mcpServerIdsJson, modelId, providerId |
 *  | 更新 | systemPrompt（人设文案） | toolIdsJson, skillIdsJson, mcpServerIdsJson, modelId, providerId, reasoningLevel |
 *  | 删除 | 整个助手 | 默认助手不可删；不可删自己当前所属助手 |
 *
 * 不允许改绑定（工具/技能/MCP）——那是用户在助手设置页的显式配置，
 * AI 修改会导致越权（给自己挂更多工具）。模型/供应商同理（改了可能产生费用）。
 *
 * 命名统一 `assistant_manage_`。
 */
class AssistantManageTools(
    private val assistantRepository: AssistantRepository,
) {
    companion object {
        private const val TAG = "AssistantManageTools"
        private const val DEFAULT_ASSISTANT_ID = "default"
    }

    fun register(registry: ToolRegistry) {
        registerList(registry)
        registerCreate(registry)
        registerUpdatePersona(registry)
        registerDelete(registry)
    }

    private fun registerList(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_list",
                description = "List all assistants (id, name, description). Used to confirm an assistant id before modifying/deleting.",
                parameters = emptyMap(),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { _ ->
            runBlocking {
                val all = assistantRepository.getAll()
                if (all.isEmpty()) {
                    "No assistants."
                } else {
                    all.joinToString("\n") { a ->
                        val isDefault = if (a.id == DEFAULT_ASSISTANT_ID) " (default)" else ""
                        "${a.id} | ${a.name}$isDefault"
                    }
                }
            }
        }
    }

    private fun registerCreate(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_create",
                description = "Create a new assistant (only sets the name and persona text; tools/model/bindings are configured by the user on the settings page)." +
                    "id may be omitted (auto-generated).",
                parameters = mapOf(
                    "name" to "Required, assistant name",
                    "system_prompt" to "Optional, persona text (system prompt)",
                    "assistant_id" to "Optional, assistant id (auto-generated when omitted)",
                ),
                required = setOf("name"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                errorJson("Parameter name is missing or empty")
            } else {
                runBlocking {
                    val id = args["assistant_id"]?.trim().takeUnless { it.isNullOrBlank() }
                        ?: "assistant-${System.currentTimeMillis()}"
                    val existing = assistantRepository.getById(id)
                    if (existing != null) {
                        errorJson("Assistant id=$id already exists (name: ${existing.name})")
                    } else {
                        val entity = io.zer0.muse.data.assistant.AssistantEntity(
                            id = id,
                            name = name,
                            systemPrompt = args["system_prompt"]?.trim().orEmpty(),
                        )
                        assistantRepository.upsert(entity)
                        Logger.i(TAG, "Tool created assistant: $id ($name)")
                        okJson("Created assistant \"$name\" (id=$id). Configure tools/model/bindings on the assistant settings page.")
                    }
                }
            }
        }
    }

    private fun registerUpdatePersona(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_update_persona",
                description = "Modify an assistant's persona text (system prompt). Only the persona may be changed;" +
                    "tool bindings/model/provider may not be changed — those are managed by the user on the settings page.",
                parameters = mapOf(
                    "assistant_id" to "Required, assistant id",
                    "system_prompt" to "Required, the new persona text",
                ),
                required = setOf("assistant_id", "system_prompt"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["assistant_id"]?.trim().orEmpty()
            val prompt = args["system_prompt"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("Parameter assistant_id is missing or empty")
                prompt.isBlank() -> errorJson("Parameter system_prompt must not be empty (to clear the persona, use a space as a placeholder)")
                else -> runBlocking {
                    val assistant = assistantRepository.getById(id)
                    if (assistant == null) {
                        errorJson("Assistant $id not found")
                    } else {
                        assistantRepository.upsert(assistant.copy(systemPrompt = prompt))
                        Logger.i(TAG, "Tool changed assistant persona: $id (${assistant.name})")
                        okJson("Changed the persona text of assistant \"${assistant.name}\" ($id).")
                    }
                }
            }
        }
    }

    private fun registerDelete(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_delete",
                description = "Delete an assistant. Irreversible. The default assistant cannot be deleted." +
                    "Confirm the assistant id with assistant_manage_list before deleting.",
                parameters = mapOf(
                    "assistant_id" to "Required, assistant id",
                ),
                required = setOf("assistant_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["assistant_id"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("Parameter assistant_id is missing or empty")
                id == DEFAULT_ASSISTANT_ID -> errorJson("The default assistant cannot be deleted (consistent with the UI-layer protection)")
                else -> runBlocking {
                    val assistant = assistantRepository.getById(id)
                    if (assistant == null) {
                        errorJson("Assistant $id not found")
                    } else {
                        assistantRepository.delete(id)
                        Logger.i(TAG, "Tool deleted assistant: $id (${assistant.name})")
                        okJson("Deleted assistant \"${assistant.name}\" ($id).")
                    }
                }
            }
        }
    }

    private fun okJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(message))
    }.toString()
}

/** 注册器：走 Koin 注入，由 ToolRegistrarBootstrapper 统一引导。 */
class AssistantManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: AssistantManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
