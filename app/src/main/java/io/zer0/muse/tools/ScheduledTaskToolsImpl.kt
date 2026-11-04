package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.schedule.ScheduledTaskRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * P1-3b 拆域：定时任务工具实现（从 ToolRegistry.kt 原样迁移）。
 * 由 ScheduledTaskToolsRegistrar 注册到 ToolRegistry。
 */
class ScheduledTaskToolsImpl(private val context: Context) {
    private val FMT_DATETIME_MIN = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // ── v1.0.17: 定时任务工具 ──────────────────────────────────────────

    /** 格式化时间戳为可读字符串(<=0 视为已禁用)。 */
    fun formatTimestamp(ts: Long): String {
        return if (ts > 0) FMT_DATETIME_MIN.get()?.format(Date(ts)) ?: "Unknown" else "Disabled"
    }

    /** 格式化定时任务为单条摘要。 */
    fun formatScheduledTask(t: ScheduledTaskEntity): String {
        val intervalDesc = when (t.interval) {
            "once" -> "One-time"
            "hourly" -> "Hourly"
            "daily" -> "Daily"
            "weekly" -> "Weekly"
            "cron" -> "Cron: ${t.cronExpr}"
            else -> t.interval
        }
        return "• ${t.name}[id=${t.id}]\n  Interval: $intervalDesc | Next: ${formatTimestamp(t.nextRunAt)} | ${if (t.enabled) "enabled" else "disabled"}"
    }

    /** 创建定时任务。 */
    suspend fun execScheduledTaskCreate(args: Map<String, String>): String {
        val name = args["name"]?.takeIf { it.isNotBlank() }
            ?: return "Missing required parameter: name"
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "Missing required parameter: prompt"
        val interval = args["interval"]?.takeIf { it.isNotBlank() } ?: "daily"
        val cronExpr = args["cron_expr"] ?: ""
        val assistantId = args["assistant_id"]?.takeIf { it.isNotBlank() } ?: "default"
        val actionType = args["action_type"]?.takeIf { it.isNotBlank() } ?: "ai_prompt"
        val conditionType = args["condition_type"]?.takeIf { it.isNotBlank() } ?: "always"

        if (interval == "cron" && cronExpr.isBlank()) {
            return "interval=cron requires cron_expr to be provided"
        }

        val conditionJson =
            buildConditionJson(
                conditionType = conditionType,
                params = conditionParamsOf(args),
            ) ?: return conditionErrorHint(conditionType)

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val task = ScheduledTaskEntity(
            id = id,
            name = name,
            prompt = prompt,
            assistantId = assistantId,
            interval = interval,
            cronExpr = cronExpr,
            enabled = true,
            nextRunAt = ScheduledTaskRunner.initialNextRunAt(interval, cronExpr, now),
            createdAt = now,
            updatedAt = now,
            actionType = actionType,
            conditionJson = conditionJson,
            createdBy = "assistant",
        )
        MuseDb.get(context).scheduledTaskDao().upsert(task)
        return "Created scheduled task: ${task.name}[id=$id] (interval=$interval, condition=${describeCondition(conditionJson)}, next run=${formatTimestamp(task.nextRunAt)})"
    }

    /** 列出定时任务。 */
    suspend fun execScheduledTaskList(args: Map<String, String>): String {
        val enabledOnly = args["enabled"]?.let { it.equals("true", ignoreCase = true) }
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val all = MuseDb.get(context).scheduledTaskDao().getAll()
        val filtered = when (enabledOnly) {
            true -> all.filter { it.enabled }
            false -> all.filter { !it.enabled }
            null -> all
        }.take(limit)
        if (filtered.isEmpty()) return "No scheduled tasks yet"
        val sb = StringBuilder("Scheduled tasks (${filtered.size}):\n")
        filtered.forEach { sb.appendLine(formatScheduledTask(it)) }
        return sb.toString().trimEnd()
    }

    /** 更新定时任务。仅传需要修改的字段,未传字段保留原值。 */
    suspend fun execScheduledTaskUpdate(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Missing required parameter: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "Task not found: $id"
        val name = args["name"] ?: existing.name
        val prompt = args["prompt"] ?: existing.prompt
        val interval = args["interval"] ?: existing.interval
        val cronExpr = args["cron_expr"] ?: existing.cronExpr
        val enabled = args["enabled"]?.let { it.equals("true", ignoreCase = true) } ?: existing.enabled

        if (interval == "cron" && cronExpr.isBlank()) {
            return "interval=cron requires cron_expr to be provided"
        }

        val now = System.currentTimeMillis()
        // 条件:显式传 condition_type 才改;传了就要求参数齐全,不允许只写 type。
        val conditionUpdate = resolveConditionUpdate(args, existing.conditionJson)
        if (conditionUpdate.error != null) return conditionUpdate.error
        val newConditionJson = conditionUpdate.json
        // 间隔或 cron 表达式变化时重新计算下次执行时间;仅切换 enabled 不重算
        val nextRunAt = if (interval != existing.interval || cronExpr != existing.cronExpr) {
            ScheduledTaskRunner.initialNextRunAt(interval, cronExpr, now)
        } else {
            existing.nextRunAt
        }
        val updated = existing.copy(
            name = name,
            prompt = prompt,
            interval = interval,
            cronExpr = cronExpr,
            enabled = enabled,
            nextRunAt = nextRunAt,
            updatedAt = now,
            conditionJson = newConditionJson,
        )
        dao.upsert(updated)
        return "Updated scheduled task: ${updated.name}[id=$id]"
    }

    /** 条件更新的解析结果:要么给出新 JSON,要么给出拒绝原因。 */
    private data class ConditionUpdate(val json: String, val error: String? = null)

    /** 解析 update 里的条件改动。未传 condition_type 时保持原值。 */
    private fun resolveConditionUpdate(args: Map<String, String>, current: String): ConditionUpdate {
        val type = args["condition_type"]?.takeIf { it.isNotBlank() }
        val json = type?.let { buildConditionJson(conditionType = it, params = conditionParamsOf(args)) }
        return when {
            type == null -> ConditionUpdate(current)
            json == null -> ConditionUpdate(current, conditionErrorHint(type))
            else -> ConditionUpdate(json)
        }
    }

    /** 删除定时任务。 */
    suspend fun execScheduledTaskDelete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Missing required parameter: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "Task not found: $id"
        dao.delete(id)
        return "Deleted scheduled task: ${existing.name}[id=$id]"
    }

    /** 立即执行一次定时任务(把 next_run_at 设为现在,下一轮轮询执行)。 */
    suspend fun execScheduledTaskExecute(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Missing required parameter: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "Task not found: $id"
        if (!existing.enabled) return "The task is disabled; please enable it before executing: $id"
        // 触发下一轮轮询执行(最多 60 秒内由 ScheduledTaskRunner 拾取)
        dao.triggerNextTasks(listOf(id))
        return "Task execution triggered: ${existing.name}[id=$id]; it will run in the next polling round"
    }

    /** 查询定时任务执行历史。 */
    suspend fun execScheduledTaskGetHistory(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Missing required parameter: id"
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 10
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "Task not found: $id"
        val records = MuseDb.get(context).scheduledTaskExecutionDao().queryByTaskId(id)
        if (records.isEmpty()) return "Task ${existing.name}[id=$id] has no execution history yet"
        val sb = StringBuilder("Execution history (task=${existing.name}):\n")
        records.take(limit).forEach { e ->
            val time = FMT_DATETIME_MIN.get()?.format(Date(e.executedAt)) ?: "Unknown"
            val detail = when (e.status) {
                "success" -> "Success: ${e.replySummary.take(80)}"
                "failed" -> "Failed: ${e.errorMessage}"
                "skipped" -> "Skipped (condition not met)"
                else -> e.status
            }
            sb.appendLine("• [$time] $detail")
        }
        return sb.toString().trimEnd()
    }

    companion object {
        private val A = io.zer0.muse.data.schedule.AutomationConfig.Condition

        /** 从工具参数里取条件参数。 */
        internal fun conditionParamsOf(args: Map<String, String>): ConditionParams = ConditionParams(
            startHour = args["condition_start_hour"],
            endHour = args["condition_end_hour"],
            keyword = args["condition_keyword"],
            tag = args["condition_tag"],
            minLevel = args["condition_min_level"],
        )

        /** 条件参数集合。用具名对象而非 6 个并列参数,调用点不会错位。 */
        internal data class ConditionParams(
            val startHour: String? = null,
            val endHour: String? = null,
            val keyword: String? = null,
            val tag: String? = null,
            val minLevel: String? = null,
        )

        /**
         * 按条件类型拼 conditionJson。
         *
         * 此前所有非 always 类型都只写 `{"type":"x"}`,参数全缺:
         * time_range 缺 startHour/endHour 会退化成 0..23(恒真),contains 缺 keyword 直接恒假。
         * 模型以为设了条件,实际没设 —— 属于「以为能用结果用不了」。
         *
         * @return null 表示该类型缺必填参数,调用方应拒绝创建并提示怎么补。
         */
        internal fun buildConditionJson(conditionType: String, params: ConditionParams = ConditionParams()): String? {
            val type = conditionType.trim().ifBlank { A.ALWAYS }
            return if (type == A.ALWAYS) "" else buildNonAlwaysCondition(type, params)
        }

        /** 非 always 分支:参数不合格返回 null,合格返回完整 JSON。 */
        private fun buildNonAlwaysCondition(type: String, params: ConditionParams): String? {
            val config = configFor(type, params) ?: return null
            return kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive(type))
                put("config", configJson(config))
            }.toString()
        }

        /** 条件参数对象;数值参数写成数字,Runtime 侧按 toString 再解析,两种都能读。 */
        private fun configJson(config: Map<String, String>): kotlinx.serialization.json.JsonObject =
            kotlinx.serialization.json.buildJsonObject {
                config.forEach { (k, v) ->
                    val asInt = v.toIntOrNull()
                    put(
                        k,
                        if (asInt != null) {
                            kotlinx.serialization.json.JsonPrimitive(asInt)
                        } else {
                            kotlinx.serialization.json.JsonPrimitive(v)
                        },
                    )
                }
            }

        /**
         * 各条件类型的参数校验与取值。
         *
         * @return null 表示参数不合格;空 Map 表示该类型不需要额外参数。
         */
        private fun configFor(type: String, params: ConditionParams): Map<String, String>? = when (type) {
            A.TIME_RANGE -> timeRangeConfig(params)
            A.CONTAINS -> params.keyword?.trim().takeUnless { it.isNullOrEmpty() }?.let { mapOf("keyword" to it) }
            A.QUICK_NOTE_EXISTS -> quickNoteConfig(params)
            A.BATTERY_LEVEL -> batteryConfig(params)
            A.CHARGING, A.NETWORK_AVAILABLE -> emptyMap()
            else -> null
        }

        private fun timeRangeConfig(params: ConditionParams): Map<String, String>? {
            val start = params.startHour?.trim()?.toIntOrNull()
            val end = params.endHour?.trim()?.toIntOrNull()
            val valid = start != null && end != null && start in 0..23 && end in 0..23
            return if (valid) mapOf("startHour" to start.toString(), "endHour" to end.toString()) else null
        }

        private fun quickNoteConfig(params: ConditionParams): Map<String, String>? {
            val keyword = params.keyword?.trim().orEmpty()
            val tag = params.tag?.trim().orEmpty()
            // 两者至少有其一,否则「存在任意笔记」等于恒真
            if (keyword.isEmpty() && tag.isEmpty()) return null
            return buildMap {
                if (keyword.isNotEmpty()) put("keyword", keyword)
                if (tag.isNotEmpty()) put("tag", tag)
            }
        }

        private fun batteryConfig(params: ConditionParams): Map<String, String>? {
            val level = params.minLevel?.trim()?.toIntOrNull()
            return level?.takeIf { it in 1..100 }?.let { mapOf("minLevel" to it.toString()) }
        }

        /** 条件参数缺失时给模型可执行指引,而不是一句「参数不合法」。 */
        private fun conditionErrorHint(conditionType: String): String = when (conditionType.trim()) {
            A.TIME_RANGE ->
                "condition_type=time_range requires condition_start_hour and condition_end_hour (0-23)"
            A.CONTAINS ->
                "condition_type=contains requires condition_keyword"
            A.QUICK_NOTE_EXISTS ->
                "condition_type=quick_note_exists requires at least one of condition_keyword or condition_tag"
            A.BATTERY_LEVEL ->
                "condition_type=battery_level requires condition_min_level (1-100)"
            else -> "Unsupported condition type: $conditionType (available: always/network_available/time_range/contains/quick_note_exists/battery_level/charging)"
        }

        /** 给用户/模型看的一行条件摘要。 */
        private fun describeCondition(conditionJson: String): String {
            if (conditionJson.isBlank()) return "Always runs"
            return runCatching {
                val cond = A.serializer()
                val parsed = io.zer0.common.AppJson.decodeFromString(cond, conditionJson)
                val cfg = parsed.config.entries.joinToString(",") { "${it.key}=${it.value.toString().trim('"')}" }
                if (cfg.isBlank()) parsed.type else "${parsed.type}($cfg)"
            }.getOrDefault("Configured")
        }
    }
}
