package io.zer0.muse.tools

import io.zer0.memory.ticker.MemoryConfig
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 记忆系统参数工具。
 *
 * 此前助手能读写记忆**内容**（save_memory / search_memory / pin_memory），
 * 却改不了记忆**怎么工作**——token 预算、遗忘速度、编译阈值这些只能在设置页手拖。
 *
 * 这两个工具补上配置侧：一个看现状，一个改参数。改完的配置仍要过
 * [MemoryConfig.validate]，非法组合会被拒绝并返回原因，不会写进 DataStore。
 */
object MemoryConfigTool {

    const val NAME_GET = "memory_config_get"
    const val NAME_SET = "memory_config_set"

    fun getDef() = ToolRegistry.ToolDef(
        name = NAME_GET,
        description =
        "Read the memory system's current parameters (token budget / forgetting speed / compile threshold, etc.) and their meanings." +
            "Call it to see the current state before adjusting memory behaviour; do not change things by guessing.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    fun setDef() = ToolRegistry.ToolDef(
        name = NAME_SET,
        description =
        "Adjust memory system parameters. Only the fields passed in are changed; the rest stay unchanged. After changing, validity is checked; if invalid, the whole change is rejected." +
            "Common scenarios: memory keeps forgetting things → lower decay_per_day or raise hit_bonus;" +
            "memory content too long and crowding the context → lower token_budget;" +
            "only want to remember important things → raise compile_threshold.",
        parameters =
        mapOf(
            "token_budget" to "Optional, the token budget for injecting memory.md into the system prompt, a positive integer, default 2500.",
            "decay_per_day" to "Optional, decay coefficient λ (per day); larger forgets faster. 0.01≈zero in 19 days / 0.02=default / 0.04≈10 days.",
            "hit_bonus" to "Optional, the bonus points for each hit, default 5. Raising it makes frequently mentioned memories harder to lose.",
            "base_importance" to "Optional, a new memory's initial score, must be > 0, default 10.",
            "compile_threshold" to "Optional, scores below this do not enter memory.md, default 4.5. Raising it keeps only important memories.",
            "forget_speed" to "Optional, forgetting multiplier, 1.0 normal, 2.0 forgets twice as fast, default 1.0.",
            "conversation_recall" to
                "Optional, true/false. Whether the memory pipeline may re-read the full original text of conversations (rather than only the current window).",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun get(settings: SettingsRepository): String {
        val c = settings.memoryConfigFlow.first()
        return buildString {
            appendLine("Current memory parameters:")
            appendLine("token_budget = ${c.tokenBudget}  (memory.md injection budget, default 2500)")
            appendLine("decay_per_day = ${c.decayPerDay}  (decay coefficient, default 0.02; larger forgets faster)")
            appendLine("hit_bonus = ${c.hitBonus}  (hit bonus, default 5)")
            appendLine("base_importance = ${c.baseImportance}  (new memory initial score, default 10)")
            appendLine("compile_threshold = ${c.compileThreshold}  (minimum score to enter memory.md, default 4.5)")
            appendLine("forget_speed = ${c.forgetSpeed}  (forgetting multiplier, default 1.0)")
            append("conversation_recall = ${c.conversationRecallEnabled}  (whether to re-read conversation originals)")
        }
    }

    /** 读一个可选参数；缺省返回 null，解析失败把原因记进 [errors]。 */
    private inline fun <T> pick(args: Map<String, String>, key: String, errors: MutableList<String>, parse: (String) -> T?): T? {
        val raw = args[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = parse(raw)
        if (parsed == null) errors.add("$key value could not be parsed: $raw")
        return parsed
    }

    /** 把 args 叠加到 [current] 上，返回新配置与解析错误。 */
    private fun merge(args: Map<String, String>, current: MemoryConfig): Pair<MemoryConfig, List<String>> {
        val errors = mutableListOf<String>()
        val next =
            MemoryConfig(
                tokenBudget = pick(args, "token_budget", errors) { it.toIntOrNull() } ?: current.tokenBudget,
                conversationRecallEnabled =
                pick(args, "conversation_recall", errors) { it.toBooleanStrictOrNull() }
                    ?: current.conversationRecallEnabled,
                decayPerDay = pick(args, "decay_per_day", errors) { it.toFloatOrNull() } ?: current.decayPerDay,
                hitBonus = pick(args, "hit_bonus", errors) { it.toFloatOrNull() } ?: current.hitBonus,
                baseImportance =
                pick(args, "base_importance", errors) { it.toFloatOrNull() } ?: current.baseImportance,
                compileThreshold =
                pick(args, "compile_threshold", errors) { it.toFloatOrNull() } ?: current.compileThreshold,
                forgetSpeed = pick(args, "forget_speed", errors) { it.toFloatOrNull() } ?: current.forgetSpeed,
            )
        return next to errors
    }

    /** 列出相对 [current] 发生变化的行。 */
    private fun diff(current: MemoryConfig, next: MemoryConfig): List<String> = buildList {
        if (next.tokenBudget != current.tokenBudget) {
            add("- token_budget: ${current.tokenBudget} → ${next.tokenBudget}")
        }
        if (next.decayPerDay != current.decayPerDay) {
            add("- decay_per_day: ${current.decayPerDay} → ${next.decayPerDay}")
        }
        if (next.hitBonus != current.hitBonus) {
            add("- hit_bonus: ${current.hitBonus} → ${next.hitBonus}")
        }
        if (next.baseImportance != current.baseImportance) {
            add("- base_importance: ${current.baseImportance} → ${next.baseImportance}")
        }
        if (next.compileThreshold != current.compileThreshold) {
            add("- compile_threshold: ${current.compileThreshold} → ${next.compileThreshold}")
        }
        if (next.forgetSpeed != current.forgetSpeed) {
            add("- forget_speed: ${current.forgetSpeed} → ${next.forgetSpeed}")
        }
        if (next.conversationRecallEnabled != current.conversationRecallEnabled) {
            add(
                "- conversation_recall: ${current.conversationRecallEnabled} → " +
                    "${next.conversationRecallEnabled}",
            )
        }
    }

    suspend fun set(args: Map<String, String>, settings: SettingsRepository): String {
        val current = settings.memoryConfigFlow.first()
        val (next, errors) = merge(args, current)
        val invalid = MemoryConfig.validate(next)

        return when {
            errors.isNotEmpty() -> "Error: ${errors.joinToString("; ")}"
            invalid != null -> "Error: parameter combination is invalid; not saved — $invalid"
            next == current -> "OK: parameters are identical to the current ones; no change needed."
            else -> {
                settings.saveMemoryConfig(next)
                val changes = diff(current, next)
                buildString {
                    appendLine("OK: memory parameters updated.")
                    changes.forEach { appendLine(it) }
                    append("The parameters take effect at the next memory compilation round.")
                }
            }
        }
    }
}
