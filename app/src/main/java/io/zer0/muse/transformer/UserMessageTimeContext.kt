package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Provides stable message-time anchors so tool latency is not mistaken for user-message elapsed time. */
internal object UserMessageTimeContext {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)

    fun build(messages: List<UIMessage>, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val userMessages = messages.filter { it.role == MessageRole.USER && it.createdAt > 0L }
        val current = userMessages.lastOrNull() ?: return ""
        val currentTimestamp = formatTimestamp(current.createdAt, zoneId)
        val previous = userMessages.getOrNull(userMessages.lastIndex - 1)
        return if (previous == null) {
            "User message time anchor (device time zone  ${zoneId.id}): the current user message was sent at  $currentTimestamp；" +
                "There is no previous user message to compare with. Do not infer the interval between messages from assistant thinking, tool execution or API call time."
        } else {
            val previousTimestamp = formatTimestamp(previous.createdAt, zoneId)
            val elapsedMs = if (current.createdAt >= previous.createdAt) {
                current.createdAt - previous.createdAt
            } else {
                previous.createdAt - current.createdAt
            }
            val elapsed = formatElapsed(elapsedMs)
            "User message time anchor (device time zone  ${zoneId.id}): the previous user message was sent at  $previousTimestamp；" +
                "the current user message was sent at  $currentTimestamp; interval between the two user messages: $elapsed。" +
                "When answering questions about the interval between messages, you must use this interval; do not substitute assistant thinking time, tool execution time or API request time."
        }
    }

    private fun formatTimestamp(timestamp: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).format(timestampFormatter)

    private fun formatElapsed(elapsedMs: Long): String {
        if (elapsedMs < 1_000L) return "$elapsedMs  ms"
        val totalSeconds = elapsedMs / 1_000L
        val days = totalSeconds / 86_400L
        val hours = totalSeconds % 86_400L / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return buildList {
            if (days > 0) add("$days  days")
            if (hours > 0) add("$hours  hours")
            if (minutes > 0) add("$minutes  min")
            if (seconds > 0) add("$seconds  sec")
        }.joinToString(" ").ifBlank { "0 sec" }
    }
}
