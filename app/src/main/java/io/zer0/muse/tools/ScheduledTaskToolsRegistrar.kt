package io.zer0.muse.tools

import android.content.Context

/**
 * P1-3b 拆域：定时任务工具注册器。
 *
 * 注册 scheduled_task_create / list / update / delete / execute / get_history。
 * 实现位于 [ScheduledTaskToolsImpl.kt]。
 */
class ScheduledTaskToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = ScheduledTaskToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_create",
                // v1.0.75 fix (工具审查 01): 补 cron 条件必填说明与示例
                // v2.6.7: 删掉「仅 always 可靠」——条件参数已补齐,这句会让模型主动避开所有条件
                description = "Create a scheduled task; when the time comes, the AI executes the prompt." +
                    "E.g. a reminder every day at 9: interval='daily' or interval='cron' + cron_expr='0 9 * * *'." +
                    "Execution can be skipped by condition (condition_type); the condition parameters must all be given together, otherwise creation is rejected.",
                parameters = mapOf(
                    "name" to "Required, task name",
                    "prompt" to "Required, task prompt",
                    "interval" to "Optional, once/hourly/daily/weekly/cron, default daily",
                    "cron_expr" to "Optional, required when interval='cron'. Standard 5-field cron, e.g. '0 9 * * *' (every day at 9), '0 */2 * * *' (every 2 hours). Ignored in non-cron modes",
                    "assistant_id" to "Optional, executing assistant, default default",
                    "action_type" to "Optional, ai_prompt/create_quick_note/call_tool/notify",
                    "condition_type" to CONDITION_TYPE_DESC,
                    "condition_start_hour" to "Optional, required when condition_type=time_range, 0-23",
                    "condition_end_hour" to "Optional, required when condition_type=time_range, 0-23",
                    "condition_keyword" to "Optional, required when condition_type=contains; for quick_note_exists, give at least one of this and condition_tag",
                    "condition_tag" to "Optional, the note tag for condition_type=quick_note_exists",
                    "condition_min_level" to "Optional, required when condition_type=battery_level, 1-100",
                ),
                required = setOf("name", "prompt"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskCreate(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_list",
                description = "List scheduled tasks.",
                parameters = mapOf(
                    "enabled" to "Optional, true/false to filter by enabled state",
                    "limit" to "Optional, number to return, default 20",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execScheduledTaskList(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_update",
                description = "Update a scheduled task. Pass only the fields to change; when changing condition_type you must also give all of that type's condition parameters.",
                parameters = mapOf(
                    "id" to "Required, task id",
                    "name" to "Optional, new name",
                    "prompt" to "Optional, new prompt",
                    "interval" to "Optional, new interval",
                    "cron_expr" to "Optional, new cron expression",
                    "enabled" to "Optional, true/false",
                    "condition_type" to CONDITION_TYPE_DESC,
                    "condition_start_hour" to "Optional, required for time_range, 0-23",
                    "condition_end_hour" to "Optional, required for time_range, 0-23",
                    "condition_keyword" to "Optional, required for contains; for quick_note_exists, give at least one of this and condition_tag",
                    "condition_tag" to "Optional, the note tag for quick_note_exists",
                    "condition_min_level" to "Optional, required for battery_level, 1-100",
                ),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskUpdate(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_delete",
                description = "Delete a scheduled task.",
                parameters = mapOf("id" to "Required, task id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskDelete(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_execute",
                description = "Trigger a scheduled task immediately.",
                parameters = mapOf("id" to "Required, task id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskExecute(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_get_history",
                description = "Query a scheduled task's execution history.",
                parameters = mapOf(
                    "id" to "Required, task id",
                    "limit" to "Optional, number to return, default 10",
                ),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execScheduledTaskGetHistory(args) }
    }

    private companion object {
        /**
         * 条件类型说明。
         *
         * 必须和 ScheduledTaskToolsImpl.buildConditionJson 支持的类型一一对应 ——
         * 列出引擎不支持的类型会让模型建出永远不满足条件的任务。
         */
        const val CONDITION_TYPE_DESC =
            "Optional, default always. always=always execute; network_available=when there is network;" +
                "time_range=within the specified period (needs condition_start_hour/end_hour);" +
                "contains=when the prompt contains the keyword (needs condition_keyword);" +
                "quick_note_exists=when a matching note exists (needs condition_keyword or condition_tag);" +
                "battery_level=when the battery is above the threshold (needs condition_min_level); charging=when charging"
    }
}
