package com.fkurevija.pingring

import com.fkurevija.pingring.data.ChatTimestampPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ChatTimestampPolicyTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun instant(year: Int, month: Int, day: Int, hour: Int = 14, minute: Int = 5): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private fun format(timestamp: Long, now: Long): String =
        ChatTimestampPolicy.format(
            timestampMillis = timestamp,
            nowMillis = now,
            locale = Locale.US,
            timeZone = utc,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
            tomorrowLabel = "Tomorrow",
        )

    @Test
    fun formatsTodayYesterdayAndTomorrowWithRelativeLabels() {
        val today = instant(2026, 10, 8)

        assertEquals("Today 14:05", format(today, instant(2026, 10, 8, 23)))
        assertEquals("Yesterday 14:05", format(instant(2026, 10, 7), today))
        assertEquals("Tomorrow 14:05", format(instant(2026, 10, 9), today))
    }

    @Test
    fun relativeLabelsWorkAcrossYearBoundaries() {
        assertEquals(
            "Yesterday 14:05",
            format(instant(2025, 12, 31), instant(2026, 1, 1)),
        )
        assertEquals(
            "Tomorrow 14:05",
            format(instant(2026, 1, 1), instant(2025, 12, 31)),
        )
    }

    @Test
    fun olderDatesIncludeTheirCalendarDate() {
        assertEquals(
            "Oct 6, 14:05",
            format(instant(2026, 10, 6), instant(2026, 10, 8)),
        )
    }
}
