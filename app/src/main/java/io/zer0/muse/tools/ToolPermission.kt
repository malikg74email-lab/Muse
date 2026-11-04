package io.zer0.muse.tools

/**
 * v2.x: 工具运行环境权限需求。
 *
 * 与 [ToolRiskLevel](审批风险)正交:风险等级回答"要不要问用户",
 * 本枚举回答"运行环境是否具备执行条件"(Shizuku/Root 通道、无障碍、Termux),
 * 供工具清单展示与执行前预检使用。
 */
enum class ToolPermission {
    /** 无障碍服务(独立 Provider 通道)。 */
    ACCESSIBILITY,

    /** 设备命令通道:Shizuku 或 Root 任一到手即可(execTiered 分层执行)。 */
    SHELL_TIER,

    /** Termux 外部命令通道(用户安装 Termux 并完成授权)。 */
    TERMUX,
}

/** 中文短标签(错误提示与日志)。 */
fun ToolPermission.shortLabel(): String = when (this) {
    ToolPermission.ACCESSIBILITY -> "Accessibility"
    ToolPermission.SHELL_TIER -> "Shizuku/Root"
    ToolPermission.TERMUX -> "Termux"
}

/** 英文短标签(find_tools 等模型可读输出)。 */
fun ToolPermission.enLabel(): String = when (this) {
    ToolPermission.ACCESSIBILITY -> "Accessibility"
    ToolPermission.SHELL_TIER -> "Shizuku/Root"
    ToolPermission.TERMUX -> "Termux"
}

/**
 * v2.x: 运行环境授权状态快照。
 *
 * 由启动引导注入 [ToolRegistry.permissionStatusProvider],数据来源:
 *  - [accessibility]/[shellTier]:AutomationManager.permissionState
 *  - [termux]:TermuxChannel.availability
 */
data class ToolPermissionStatus(
    val accessibility: Boolean,
    val shellTier: Boolean,
    val termux: Boolean,
) {
    /** 需求集合为空 → 无需授权;否则"任一就绪"即视为可执行(如 screen_* 可走无障碍或设备通道)。 */
    fun satisfiedAny(required: Set<ToolPermission>): Boolean {
        if (required.isEmpty()) return true
        return required.any { permission ->
            when (permission) {
                ToolPermission.ACCESSIBILITY -> accessibility
                ToolPermission.SHELL_TIER -> shellTier
                ToolPermission.TERMUX -> termux
            }
        }
    }

    /** 需求集合的中文描述(如"无障碍 或 Shizuku/Root")。 */
    fun describe(required: Set<ToolPermission>): String = required.joinToString(" or ") { it.shortLabel() }

    /** 英文描述(find_tools 输出)。 */
    fun describeEn(required: Set<ToolPermission>): String = required.joinToString(" or ") { it.enLabel() }
}
