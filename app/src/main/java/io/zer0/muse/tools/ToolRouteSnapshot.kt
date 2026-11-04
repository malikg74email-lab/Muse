package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity

/** Immutable routing decision captured for one model turn. Definitions and execution share this snapshot. */
data class ToolRouteSnapshot(
    val definitions: List<ToolDefinition>,
    val routes: Map<String, Route>,
) {
    sealed interface Route {
        data object Local : Route

        data class Skill(val skill: SkillEntity) : Route
    }

    fun routeFor(name: String): Route? = routes[name]

    fun isExposed(name: String): Boolean = name in routes
}

/** Builds a deterministic per-turn table. Local tools always win over same-named skills. */
object RouteTable {
    /**
     * v2.x: 提示词技能统一工具 schema — 追加可选 input 参数。
     * 无参 schema 会让模型无法向提示词技能传入具体请求("装了但用不上"的体验根源之一)。
     */
    private const val PROMPT_SKILL_INPUT_SCHEMA =
        "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"," +
            "\"description\":\"The input or request text passed to this skill; substituted into the {{input}}/{{args}} placeholders in the instructions, or appended to the end of the instructions when there are no placeholders\"}},\"required\":[]}"

    fun snapshot(localDefinitions: List<ToolDefinition>, skills: Collection<SkillEntity>): ToolRouteSnapshot {
        val localByName = localDefinitions.distinctBy { it.name }.associateBy { it.name }
        val skillByName =
            skills
                .distinctBy { it.id }
                .filterNot { it.id in localByName }
                .associateBy { it.id }
        val definitions =
            localByName.values +
                skillByName.values.map { skill ->
                    ToolDefinition(
                        name = skill.id,
                        description = skill.description,
                        // v2.x: 提示词技能追加可选 input 参数(指令无自带参数时的输入口)
                        parametersJsonSchema =
                        if (SkillImporter.isPromptSkill(skill.implementationKotlin)) {
                            PROMPT_SKILL_INPUT_SCHEMA
                        } else {
                            skill.parametersJson
                        },
                    )
                }
        val routes =
            localByName.keys.associateWith { ToolRouteSnapshot.Route.Local } +
                skillByName.mapValues { (_, skill) -> ToolRouteSnapshot.Route.Skill(skill) }
        return ToolRouteSnapshot(definitions = definitions, routes = routes)
    }
}
