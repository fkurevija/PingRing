package com.fkurevija.pingring.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.chat.ChatTimestampPolicy
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun chatHistoryTimestamp(timestampMillis: Long): String {
    val today = stringResource(R.string.today)
    val yesterday = stringResource(R.string.yesterday)
    val tomorrow = stringResource(R.string.tomorrow)
    val locale = Locale.getDefault()
    val timeZone = TimeZone.getDefault()
    return remember(timestampMillis, today, yesterday, tomorrow, locale, timeZone) {
        ChatTimestampPolicy.format(
            timestampMillis = timestampMillis,
            nowMillis = System.currentTimeMillis(),
            locale = locale,
            timeZone = timeZone,
            todayLabel = today,
            yesterdayLabel = yesterday,
            tomorrowLabel = tomorrow,
        )
    }
}
