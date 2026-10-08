package com.fkurevija.pingring.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ChatTimestampPolicy {
    fun format(
        timestampMillis: Long,
        nowMillis: Long,
        locale: Locale,
        timeZone: TimeZone,
        todayLabel: String,
        yesterdayLabel: String,
        tomorrowLabel: String,
    ): String {
        val time = SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }
            .format(Date(timestampMillis))
        val relation = relation(timestampMillis, nowMillis, timeZone)
        val prefix = when (relation) {
            DayRelation.TODAY -> todayLabel
            DayRelation.YESTERDAY -> yesterdayLabel
            DayRelation.TOMORROW -> tomorrowLabel
            DayRelation.OTHER -> {
                return SimpleDateFormat("MMM d, HH:mm", locale)
                    .apply { this.timeZone = timeZone }
                    .format(Date(timestampMillis))
            }
        }
        return "$prefix $time"
    }

    private fun relation(timestampMillis: Long, nowMillis: Long, timeZone: TimeZone): DayRelation {
        val messageDay = Calendar.getInstance(timeZone).apply { timeInMillis = timestampMillis }
        val today = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (sameDay(messageDay, today)) return DayRelation.TODAY

        val yesterday = today.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_MONTH, -1)
        if (sameDay(messageDay, yesterday)) return DayRelation.YESTERDAY

        val tomorrow = today.clone() as Calendar
        tomorrow.add(Calendar.DAY_OF_MONTH, 1)
        if (sameDay(messageDay, tomorrow)) return DayRelation.TOMORROW

        return DayRelation.OTHER
    }

    private fun sameDay(first: Calendar, second: Calendar): Boolean =
        first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
            first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)

    private enum class DayRelation {
        TODAY,
        YESTERDAY,
        TOMORROW,
        OTHER,
    }
}
