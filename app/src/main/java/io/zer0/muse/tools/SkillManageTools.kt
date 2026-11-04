package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 技能管理工具集 —— 让助手能对技能做日常管理。
 *
 * 此前助手只有 install_skill（新建），列/启停/删全部做不到。
 * 与知识库管理、会话管理同款模式。
 *
 * 命名统一 `skill_manage_`（避免与内置 skill 的 id 冲突）：
 *  - `skill_manage_list`     列出全部技能及启停状态（SAFE）
 *  - `skill_manage_toggle`   启停一个技能（NORMAL）
 *  - `skill_manage_delete`   删除一个自定义技能（HIGH；内置技能不可删）
 *
 * 设计约束：
 *  - 内置技能（BUILT_IN_SKILLS）不可删，只可启停；
 *  - 删除不可撤销，走审批。
 */
class SkillManageTools(
    private val skillRepository: SkillRepository,
) {
    companion object {
        private const val TAG = "SkillManageTools"

        /** 内置技能 id 集合（不可删除，只可启停）。 */
        private val BUILT_IN_SKILL_IDS = setOf(
            "knowledge_search", "web_search", "web_fetch", "http_get", "http_post",
            "arxiv_search", "read_file", "write_file", "install_skill", "author_plugin",
            "delegate_agent", "task_plan", "update_plan_step", "sticker_list", "sticker_send",
        )
    }

    fun register(registry: ToolRegistry) {
        registerList(registry)
        registerToggle(registry)
        registerDelete(registry)
    }

    private fun registerList(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "skill_manage_list",
                description = "List all skills and their enabled state (id, name, category, whether enabled, whether built-in)." +
                    "Used to confirm a skill id before enabling/disabling/deleting.",
                parameters = mapOf(
                    "query" to "Optional, filter by name or category",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            runBlocking {
                val query = args["query"]?.trim().orEmpty()
                val all = skillRepository.listEnabled()
                val filtered = if (query.isBlank()) {
                    all
                } else {
                    all.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.category.contains(query, ignoreCase = true)
                    }
                }
                if (filtered.isEmpty()) {
                    "No matching skills."
                } else {
                    filtered.joinToString("\n") { s ->
                        "${s.id} | ${s.name} | ${s.category} | " +
                            "${if (s.enabled) "enabled" else "disabled"} | " +
                            "${if (s.id in BUILT_IN_SKILL_IDS) "built-in" else "custom"}"
                    }
                }
            }
        }
    }

    private fun registerToggle(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "skill_manage_toggle",
                description = "Enable or disable a skill. After disabling, the assistant can no longer call that skill; the user can re-enable it in Settings → Skill.",
                parameters = mapOf(
                    "skill_id" to "Required, skill id (get it with skill_manage_list)",
                    "enabled" to "true=enable, false=disable; default true",
                ),
                required = setOf("skill_id"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val id = args["skill_id"]?.trim().orEmpty()
            val enabled = args["enabled"]?.trim()?.lowercase() != "false"
            if (id.isBlank()) {
                errorJson("Parameter skill_id is missing or empty")
            } else {
                runBlocking {
                    val skill = skillRepository.getById(id)
                    if (skill == null) {
                        errorJson("Skill $id not found")
                    } else {
                        skillRepository.setEnabled(id, enabled)
                        Logger.i(TAG, "Tool ${if (enabled) "enabled" else "disabled"} skill: $id")
                        okJson("${if (enabled) "Enabled" else "Disabled"} skill \"${skill.name}\" ($id).")
                    }
                }
            }
        }
    }

    private fun registerDelete(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "skill_manage_delete",
                description = "Delete a custom skill (irreversible). Built-in skills cannot be deleted, only enabled/disabled." +
                    "Confirm the skill id with skill_manage_list before executing.",
                parameters = mapOf(
                    "skill_id" to "Required, skill id",
                ),
                required = setOf("skill_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["skill_id"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("Parameter skill_id is missing or empty")
                id in BUILT_IN_SKILL_IDS -> errorJson("Built-in skill $id cannot be deleted (only enabled/disabled), consistent with the UI-layer protection")
                else -> runBlocking {
                    val skill = skillRepository.getById(id)
                    if (skill == null) {
                        errorJson("Skill $id not found")
                    } else {
                        skillRepository.delete(id)
                        Logger.i(TAG, "Tool deleted skill: $id (${skill.name})")
                        okJson("Deleted skill \"${skill.name}\" ($id).")
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
class SkillManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: SkillManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
