package io.zer0.muse.tools

import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/** 普通聊天里的“许愿”：允许当前助手之后主动联系用户。 */
object ProactiveWishTool {
    fun toolDef() = ToolRegistry.ToolDef(
        name = "proactive_message_wish",
        description = "When the user asks the assistant to proactively reach out, greet or check in later, enable proactive messages for the current assistant." +
            "The host automatically binds this wish to the current assistant; do not pass an assistant id.",
        parameters = mapOf(
            "schedule_hint" to
                "Optional. A natural-language time preference, e.g. 'every evening', 'tomorrow morning'." +
                "Only record the wish; do not promise a specific time.",
        ),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(args: Map<String, String>, settings: SettingsRepository, executionContext: ToolExecutionContext): String {
        val assistantId = executionContext.assistantId
            ?.takeIf { it.isNotBlank() }
            ?: executionContext.scope.takeIf { it.isNotBlank() && it != "main" }
            ?: "default"
        val current = settings.proactiveMessageConfigFlow.first()
        settings.saveProactiveMessageConfig(
            current.copy(
                enabled = true,
                agentId = assistantId,
                agentOnly = false,
                lastFailedAt = 0L,
                consecutiveFailures = 0,
                nextTriggerAt = 0L,
            ),
        )
        val hint = args["schedule_hint"]?.trim()?.takeIf { it.isNotBlank() }
        return if (hint == null) {
            "Wish noted. I will proactively contact you at a suitable time later; proactive messages can be sent to ordinary chats, not only Agent sessions."
        } else {
            "Wish noted (time preference: $hint). I will arrange it together with your proactive-message settings and quiet hours, without promising an exact time."
        }
    }
}
