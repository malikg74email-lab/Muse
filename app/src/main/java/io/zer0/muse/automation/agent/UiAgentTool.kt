package io.zer0.muse.automation.agent

import io.zer0.muse.tools.ToolOutcome
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * v2.2.1: 把 GUI Agent 环注册为 `ui_agent` 工具(HIGH,强制审批)。
 */
class UiAgentTool(private val runner: UiAgentRunner) {

    fun register(registry: ToolRegistry) {
        registry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "ui_agent",
                description = "GUI Agent loop: uses a vision model to watch the screen and operate the phone step by step to complete multi-step cross-app tasks" +
                    "(e.g. 'open WeChat and send a message to Zhang San'). Internal automatic loop: screenshot → vision decision → tap/swipe/input, at most 15 steps by default." +
                    "display=auto (default) prefers isolated execution on a separate virtual screen when package_name is given and a separate virtual screen is available, otherwise degrades to foreground accessibility;" +
                    "display=foreground forces the current main screen; display=virtual forces a separate screen and requires Shizuku/Root." +
                    "Requires vision assistance to be configured (Settings → Vision assistance). Each step screenshots the current execution interface and sends it to the selected vision model for analysis; screenshots may contain sensitive information such as chats or accounts; call only within the task scope the user has explicitly authorised." +
                    "For single-step operations, use the screen_* tools directly; use this tool for multi-step flows.",
                parameters = mapOf(
                    "task" to "Required. The task description to complete, as specific as possible (target app, object, operation intent)",
                    "max_steps" to "Optional. Maximum steps 3-30, default 15",
                    "display" to "Optional. auto (default) prefers a separate virtual screen when a target package name is given, otherwise the foreground; can also specify foreground or virtual",
                    "package_name" to "Optional target app package name, e.g. com.example.app; required in virtual mode; in auto mode, when provided, isolated running is tried first",
                ),
                required = setOf("task"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val task = args["task"]?.trim().orEmpty()
            if (task.isBlank()) return@registerOutcome ToolOutcome.error("Error: missing task parameter")
            val maxSteps = args["max_steps"]?.toIntOrNull() ?: UiAgentRunner.DEFAULT_MAX_STEPS
            val displayMode = when (args["display"]?.trim()?.lowercase()) {
                null, "", "auto" -> UiAgentRunner.DisplayMode.AUTO
                "foreground" -> UiAgentRunner.DisplayMode.FOREGROUND
                "virtual" -> UiAgentRunner.DisplayMode.VIRTUAL
                else -> return@registerOutcome ToolOutcome.error("Error: display only supports auto, foreground or virtual")
            }
            val result = runner.run(task, maxSteps, displayMode = displayMode, packageName = args["package_name"])
            val content = buildString {
                append(if (result.finished) "Task finished:" else "Task not completed:")
                append('\n')
                append(result.summary)
            }
            if (result.finished) {
                ToolOutcome.ok(content, mapOf("finished" to true))
            } else {
                // A vision/screenshot/channel/max-step failure must not be reported to the
                // outer Agent loop as a successful tool invocation.
                ToolOutcome.error(content, mapOf("finished" to false))
            }
        }
    }
}
