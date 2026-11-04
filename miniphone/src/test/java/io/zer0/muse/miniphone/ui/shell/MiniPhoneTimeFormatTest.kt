package io.zer0.muse.miniphone.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 时间文本:相对时间(朋友圈、评论、消息列表共用)与状态栏、聊天分隔这两处另有口径的格式。
 */
class MiniPhoneTimeFormatTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun missingTimestampRendersEmpty() {
        assertEquals("", relativeTimeOf(epochMs = 0L, now = at(2026, 10, 29, 21, 51)))
    }

    @Test
    fun withinAMinuteShowsJustNow() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("Just now", relativeTimeOf(epochMs = now - 30_000L, now = now))
        assertEquals("差几秒也算刚刚", "Just now", relativeTimeOf(epochMs = now - 59_999L, now = now))
    }

    @Test
    fun withinAnHourShowsMinutesAgo() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("1 minutes ago", relativeTimeOf(epochMs = now - 60_000L, now = now))
        assertEquals("12 minutes ago", relativeTimeOf(epochMs = now - 12L * 60_000L, now = now))
        assertEquals("59 minutes ago", relativeTimeOf(epochMs = now - 59L * 60_000L, now = now))
    }

    @Test
    fun earlierTodayShowsHoursAgo() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("3 hours ago", relativeTimeOf(epochMs = at(2026, 10, 29, 18, 0), now = now))
        assertEquals("跨过整点也按小时算", "11 hours ago", relativeTimeOf(epochMs = at(2026, 10, 29, 10, 0), now = now))
    }

    @Test
    fun yesterdayCarriesTheClockIncludingBothEdges() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("Yesterday 21:30", relativeTimeOf(epochMs = at(2026, 10, 28, 21, 30), now = now))
        assertEquals("昨天最早的一刻", "Yesterday 00:00", relativeTimeOf(epochMs = at(2026, 10, 28, 0, 0), now = now))
        assertEquals("昨天最后一刻", "Yesterday 23:59", relativeTimeOf(epochMs = at(2026, 10, 28, 23, 59), now = now))
    }

    @Test
    fun olderDateInTheSameYearShowsMonthDayAndClock() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("10/26 08:00", relativeTimeOf(epochMs = at(2026, 10, 26, 8, 0), now = now))
        assertEquals("1/1 00:00", relativeTimeOf(epochMs = at(2026, 1, 1, 0, 0), now = now))
    }

    @Test
    fun previousYearShowsFullDateWithoutClock() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("2025/12/31", relativeTimeOf(epochMs = at(2025, 12, 31, 20, 0), now = now))
        assertEquals("2025/1/1", relativeTimeOf(epochMs = at(2025, 1, 1, 9, 30), now = now))
    }

    @Test
    fun clockUsesTwoDigitHourAndMinute() {
        assertEquals("09:05", MiniPhoneTimeFormat.clock(at(2026, 10, 29, 9, 5)))
        assertEquals("21:51", MiniPhoneTimeFormat.clock(at(2026, 10, 29, 21, 51)))
    }

    @Test
    fun separatorCarriesDateOnlyWhenTheDayDiffers() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("09:05", MiniPhoneTimeFormat.separator(epochMs = at(2026, 10, 29, 9, 5), now = now))
        assertEquals("Yesterday 23:30", MiniPhoneTimeFormat.separator(epochMs = at(2026, 10, 28, 23, 30), now = now))
        assertEquals("10/26 08:00", MiniPhoneTimeFormat.separator(epochMs = at(2026, 10, 26, 8, 0), now = now))
        assertEquals(
            "2025/12/31 20:00",
            MiniPhoneTimeFormat.separator(epochMs = at(2025, 12, 31, 20, 0), now = now),
        )
    }
}
