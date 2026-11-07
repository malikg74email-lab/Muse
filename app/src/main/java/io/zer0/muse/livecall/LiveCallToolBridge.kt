package io.zer0.muse.livecall

import io.zer0.ai.core.ToolDefinition
import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具执行通道:会话层不直接依赖 ToolRegistry,只认这个函数式接口;
 * 控制器把它接到注册表的 executeFromJson —— 与聊天完全同一条执行链,
 * 不是为通话另起的平行实现。
 */
fun interface LiveToolExecutor {
    suspend fun execute(name: String, argumentsJson: String): String
}

/**
 * Shaheen 2 Fix Phase 2: 聊天工具集 → 通话工具集的桥(纯逻辑)。
 *
 * 通话里只开放「启动 + 屏幕操作」这一组设备工具:
 *  - 精确名单:[EXACT_TOOL_NAMES](app_launch / app_list / open_app / list_installed_apps)
 *  - 前缀名单:全部 screen_* (read / current_app / tap / tap_text / input / swipe /
 *    back / home / launch_app / wait / permission_status …)
 * 破坏性工具(卸载 / 强停 / 清数据等)刻意不进语音面:口头误听不能触发不可逆动作,
 * 这类操作 Malik 仍可在聊天里完成(见 PHASE2 日志的范围说明)。
 */
object LiveCallToolBridge {

    val EXACT_TOOL_NAMES: Set<String> = setOf(
        "app_launch",
        "app_list",
        "open_app",
        "list_installed_apps",
    )

    const val SCREEN_TOOL_PREFIX = "screen_"

    fun isVoiceTool(name: String): Boolean =
        name in EXACT_TOOL_NAMES || name.startsWith(SCREEN_TOOL_PREFIX)

    /** 从注册表的全量定义里筛出通话子集(保持注册表原始顺序,输出确定性)。 */
    fun selectVoiceTools(definitions: List<ToolDefinition>): List<ToolDefinition> =
        definitions.filter { isVoiceTool(it.name) }

    /**
     * ToolDefinition → Gemini functionDeclaration,转换规则与聊天侧
     * GeminiProvider.toGeminiDeclaration 完全一致(name / description /
     * parametersJsonSchema 解析为 JSON 元素;schema 解析失败时省略 parameters)。
     */
    fun toFunctionDeclarations(definitions: List<ToolDefinition>): List<JsonObject> =
        definitions.map { def ->
            buildJsonObject {
                put("name", def.name)
                put("description", def.description)
                val schema = runCatching {
                    AppJson.parseToJsonElement(def.parametersJsonSchema)
                }.getOrNull()
                if (schema != null) put("parameters", schema)
            }
        }

    /** 一步到位:全量定义 → 通话子集的 functionDeclarations。 */
    fun buildVoiceDeclarations(definitions: List<ToolDefinition>): List<JsonObject> =
        toFunctionDeclarations(selectVoiceTools(definitions))
}
