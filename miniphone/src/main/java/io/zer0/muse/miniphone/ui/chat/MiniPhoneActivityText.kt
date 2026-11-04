package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MpActivityKind

/**
 * 生成期间那一行文案。
 *
 * 只按 [MpActivityKind] 取句子:宿主不交出工具名、参数与返回值,这里也没有任何可以显示它们的地方。
 */
internal object MiniPhoneActivityText {

    fun of(kind: MpActivityKind): String = when (kind) {
        MpActivityKind.SEARCHING -> SEARCHING
        MpActivityKind.REMEMBERING -> REMEMBERING
        MpActivityKind.CALENDAR -> CALENDAR
        MpActivityKind.WEATHER -> WEATHER
        MpActivityKind.NOTES -> NOTES
        MpActivityKind.OTHER -> OTHER
    }

    private const val SEARCHING: String = "They're looking for something…"
    private const val REMEMBERING: String = "They remembered something…"
    private const val CALENDAR: String = "They're checking the schedule…"
    private const val WEATHER: String = "They glanced out the window…"
    private const val NOTES: String = "They're leafing through notes…"
    private const val OTHER: String = "They're busy with something else…"
}
