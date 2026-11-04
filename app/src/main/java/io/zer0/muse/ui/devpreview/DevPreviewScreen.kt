package io.zer0.muse.ui.devpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.ui.MessageBubble
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 开发期预览页：用真实 [MessageBubble] 渲染一份带 MOOD / 思考 / 工具调用的消息。
 *
 * 存在的唯一目的：在没有配置供应商、也没有真实会话的情况下，
 * 也能在模拟器上看到消息气泡的实际排版，用来验收 MOOD 分节、
 * 思考卡摘要行等改动。不参与正式导航，只能由 adb 直接拉起。
 *
 * 上线前应连同路由一起删除。
 */
@Composable
fun DevPreviewScreen() {
    var moodExpanded by remember { mutableStateOf(true) }
    var reasoningExpanded by remember { mutableStateOf(true) }

    val prefs = remember {
        ChatPreferences(
            showMoodBlock = true,
            showReasoning = true,
            moodExpandedByDefault = true,
            reasoningExpandedByDefault = true,
            showTokenEstimate = true,
            showModelName = true,
        )
    }

    val sample = remember { sampleMessage() }

    val toolSample = remember { sampleToolMessage() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = MusePaddings.sectionGap),
    ) {
        Text(
            text = "MOOD / Thinking card preview (for development)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 8.dp),
        )
        MessageBubble(
            msg = sample,
            isStreaming = false,
            isLastAssistant = false,
            isTranslating = false,
            onEdit = {},
            onQuote = {},
            onRegenerate = {},
            onTranslate = {},
            modelName = "muse-preview",
            chatPrefs = prefs,
            isMoodExpanded = moodExpanded,
            isReasoningExpanded = reasoningExpanded,
            onToggleMoodExpanded = { moodExpanded = !moodExpanded },
            onToggleReasoningExpanded = { reasoningExpanded = !reasoningExpanded },
        )
        MessageBubble(
            msg = toolSample,
            isStreaming = false,
            isLastAssistant = false,
            isTranslating = false,
            onEdit = {},
            onQuote = {},
            onRegenerate = {},
            onTranslate = {},
            modelName = "muse-preview",
            chatPrefs = prefs,
            isReasoningExpanded = false,
            onToggleReasoningExpanded = {},
        )
        // 过程动画：与聊天页消息流末尾同一组件
        io.zer0.muse.ui.ThinkingBubble(progressText = null)
    }
}

/** 预览用样本：带 MOOD 四段 + 思考正文。 */
private fun sampleMessage(): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    content = "I reorganized the settings page into 11 groups by user intent, and top-level entries grew from 34 to 48.\n\n" +
        "Also fixed two settings pages that were completely unreachable before.",
    reasoning = "First check whether the existing hierarchy matches the real field counts." +
        "Found only 7 top-level groups and 34 visible entries, but 275 persisted fields," +
        "which means the UI hierarchy projects the code module structure, not user intent.",
    mood = SAMPLE_MOOD,
    modelId = "preview",
)

/** 预览用样本：只有思考 + 一条工具调用，用来展示过程摘要行。 */
private fun sampleToolMessage(): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    content = "",
    reasoning = "Read through the existing settings page grouping first, then decide how to merge.",
    modelId = "preview",
    toolCallInfo = io.zer0.ai.core.ToolCallInfo(
        toolName = "update_settings",
        arguments = "{\"group\":\"look\"}",
        result = "ok",
        isSuccess = true,
        durationMs = 1_200L,
    ),
)

private const val SAMPLE_MOOD = "Vibe: With this version done, the chat page finally no longer looks like a pile of cards\n" +
    "Sparks:\n" +
    "  - Once the four MOOD sections are split, the three Sparks entries have room to stand\n" +
    "  - The thinking card and tool cards should flow like a stream, not sit like two blocks\n" +
    "  - A summary line like Thought for a while reads more human than truncating the first 40 characters\n" +
    "Reflections:\n" +
    "  - Collapsed titles carry operation counts, so users see at a glance how much each step did\n" +
    "  - Keeping the plan card separate is right; it is the thing to watch while working\n" +
    "Will:\n" +
    "  - Want to stabilize this layout\n" +
    "  - Do not want to add new cards anymore"
