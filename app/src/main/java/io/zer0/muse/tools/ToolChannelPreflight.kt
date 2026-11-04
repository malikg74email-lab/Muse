package io.zer0.muse.tools

/**
 * 工具执行前的通道预检。
 *
 * ## 为什么需要它
 *
 * 助手自审第 4 条：「无障碍 / Shizuku / Root 三档通道未就绪时，工具是快速失败，
 * 但失败信息和『该怎么开通』之间没衔接。用户看到报错往往不知道下一步去哪开。」
 *
 * 现状：`ToolRequirements` 已经声明了每个工具依赖哪条通道，`tool_status` 也会用，
 * 但**执行路径不用**。于是模型照常调用、工具照常失败，返回的是底层报错
 * （「无障碍服务未连接」之类），既不告诉模型该换路，也不告诉用户去哪开。
 *
 * 这里把预检做成执行前的一道闸：通道缺失时不执行工具，直接返回带开通路径的结果。
 * 注意预检**不替代**执行时的权限判断（TOCTOU：用户可能在预检之后才撤销授权），
 * 它只是把注定失败的调用提前变成一句可执行的话。
 */
object ToolChannelPreflight {

    /**
     * 从 AutomationManager 读出当前就绪的通道。
     *
     * 只覆盖三档「设备控制」通道 —— 运行时权限（联系人/短信/定位…）在这里不做探测：
     * 它们由各工具在执行时自行检查并返回明确错误，预检再加一层反而会重复且容易误判。
     */
    suspend fun channelsOf(automation: io.zer0.muse.automation.core.AutomationManager): Set<ToolRequirements.Channel> {
        val state = automation.refreshPermissions()
        return buildSet {
            if (state.accessibilityEnabled) add(ToolRequirements.Channel.ACCESSIBILITY)
            if (state.shellEnabled) add(ToolRequirements.Channel.SHELL)
            if (state.rootEnabled) add(ToolRequirements.Channel.ROOT)
        }
    }

    /** 预检结论。 */
    sealed interface Result {
        /** 可以执行。 */
        data object Proceed : Result

        /** 缺通道，不该执行。 */
        data class Blocked(val missing: Set<ToolRequirements.Channel>, val message: String) : Result
    }

    /**
     * 预检。
     *
     * @param toolName 工具名
     * @param availableChannels 当前就绪通道；**null 表示宿主没提供信息，跳过预检**
     */
    fun check(toolName: String, availableChannels: Set<ToolRequirements.Channel>?): Result {
        val req = ToolRequirements.of(toolName)
        val missing = if (availableChannels == null || req.isUnconditional) {
            emptySet()
        } else {
            missingChannels(req, availableChannels)
        }
        return if (missing.isEmpty()) Result.Proceed else Result.Blocked(missing, buildMessage(toolName, missing))
    }

    /**
     * v2.6.7 fix (AUDIT F3): 生产路径入口 —— 只对「宿主真会上报」的通道下结论。
     *
     * [channelsOf] 只探测无障碍 / Shell / Root 三档。联系人、短信、日历、
     * 定位、使用情况、通知使用权等从未进入该集合；若把它们当作「缺失」，
     * 这些工具会在用户已授权的情况下被恒拦，且重试也不会变好。
     *
     * 这些通道的判定交给已有真源：`ToolRegistry.permissionStatusProvider`
     * 与各工具自己的执行时检查。
     */
    fun checkPreflightable(toolName: String, availableChannels: Set<ToolRequirements.Channel>?): Result {
        val req = ToolRequirements.of(toolName)
        val missing = if (availableChannels == null || req.isUnconditional) {
            null
        } else {
            val probeableAllOf = req.allOf.filter { it in PREFLIGHT_CHANNELS }.toSet()
            val probeableAnyOf = req.anyOf.filter { it in PREFLIGHT_CHANNELS }.toSet()
            // 要求里没有任何可探测通道 → 交回工具自身检查（missing 为 null）。
            if (probeableAllOf.isEmpty() && probeableAnyOf.isEmpty()) {
                null
            } else {
                missingOf(probeableAllOf, probeableAnyOf, availableChannels)
            }
        }
        return missing?.let { Result.Blocked(it, buildMessage(toolName, it)) } ?: Result.Proceed
    }

    /** 只对可探测通道算缺失集；满足时返回 null。 */
    private fun missingOf(
        probeableAllOf: Set<ToolRequirements.Channel>,
        probeableAnyOf: Set<ToolRequirements.Channel>,
        available: Set<ToolRequirements.Channel>,
    ): Set<ToolRequirements.Channel>? {
        val missingAll = probeableAllOf.filterNot { it in available }.toSet()
        if (missingAll.isNotEmpty()) return missingAll
        val anyUnsatisfied = probeableAnyOf.isNotEmpty() && probeableAnyOf.none { it in available }
        return if (anyUnsatisfied) probeableAnyOf else null
    }

    /** 预检能够下结论的通道 —— 必须与 [channelsOf] 的上报范围保持一致。 */
    private val PREFLIGHT_CHANNELS: Set<ToolRequirements.Channel> = setOf(
        ToolRequirements.Channel.ACCESSIBILITY,
        ToolRequirements.Channel.SHELL,
        ToolRequirements.Channel.ROOT,
    )

    /** 缺哪些通道。allOf 缺任一即失败；anyOf 全缺才算失败。 */
    private fun missingChannels(
        req: ToolRequirements.Requirement,
        available: Set<ToolRequirements.Channel>,
    ): Set<ToolRequirements.Channel> {
        val missingAll = req.allOf.filterNot { it in available }.toSet()
        if (missingAll.isNotEmpty()) return missingAll
        val anySatisfied = req.anyOf.isEmpty() || req.anyOf.any { it in available }
        return if (anySatisfied) emptySet() else req.anyOf
    }

    /**
     * 拼给模型看的失败说明。
     *
     * 三个要素：缺什么、去哪开、现在能做什么替代。最后一条很关键 ——
     * 只说「不可用」会让模型反复试，给了替代路径它才知道该怎么继续。
     */
    private fun buildMessage(toolName: String, missing: Set<ToolRequirements.Channel>): String {
        val labels = missing.joinToString("、") { it.openHint() }
        val alternatives = missing.flatMap { it.alternatives() }.distinct()
        return buildString {
            append("[Channel not ready] The device channel that tool $toolName depends on is currently unavailable: $labels.")
            append("It was not executed this time; retrying will not make it available.")
            if (alternatives.isNotEmpty()) {
                append("You can switch to: ${alternatives.joinToString(", ")} first;")
            }
            append("or ask the user to enable the corresponding channel in settings and then call tool_status again to confirm.")
        }
    }

    /** 通道 → 面向用户的名称 + 开通路径。 */
    private fun ToolRequirements.Channel.openHint(): String = when (this) {
        ToolRequirements.Channel.ACCESSIBILITY -> "Accessibility service (Settings → Accessibility → Downloaded services → Shaheen 2)"
        ToolRequirements.Channel.SHELL -> "Shizuku / ADB authorisation (Settings → Advanced → Shizuku)"
        ToolRequirements.Channel.ROOT -> "Root permission"
        ToolRequirements.Channel.USAGE_STATS -> "Usage access (Settings → Apps → Special permissions)"
        ToolRequirements.Channel.NOTIFICATION_LISTENER -> "Notification access (Settings → Notifications → Notification access)"
        ToolRequirements.Channel.CONTACTS -> "Contacts permission"
        ToolRequirements.Channel.SMS -> "SMS permission"
        ToolRequirements.Channel.PHONE_CALL -> "Phone permission"
        ToolRequirements.Channel.CALENDAR -> "Calendar permission"
        ToolRequirements.Channel.LOCATION -> "Location permission"
        ToolRequirements.Channel.RUNTIME -> "Runtime permission"
    }

    /** 该通道缺位时模型可用的替代手段（写具体工具名，别说空话）。 */
    private fun ToolRequirements.Channel.alternatives(): List<String> = when (this) {
        ToolRequirements.Channel.ACCESSIBILITY -> listOf("ui_screenshot (if Shell is available)", "shell tools (if authorised)")
        ToolRequirements.Channel.SHELL -> listOf("accessibility tools (if enabled)")
        ToolRequirements.Channel.ROOT -> listOf("Shell / accessibility tools (if ready)")
        ToolRequirements.Channel.USAGE_STATS -> listOf("current_status (the in-app alternative for viewing the foreground app)")
        else -> emptyList()
    }
}
