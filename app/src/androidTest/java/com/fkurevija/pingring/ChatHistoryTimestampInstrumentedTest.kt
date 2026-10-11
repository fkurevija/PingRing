package com.fkurevija.pingring

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.fkurevija.pingring.data.session.BeaconInfo
import com.fkurevija.pingring.data.chat.ChatTimestampPolicy
import com.fkurevija.pingring.ui.screens.ActiveBeaconEntry
import com.fkurevija.pingring.ui.screens.chatHistoryTimestamp
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ChatHistoryTimestampInstrumentedTest : RadarScreenTestSupport() {
    @Test
    fun sharedHistoryTimestampAndBeaconIncludeTheDateWithTheirTime() {
        val timestamp = System.currentTimeMillis() - 60_000L
        val beacon = BeaconInfo(
            id = "beacon",
            senderPeerId = "ana",
            senderName = "Ana",
            title = "Regroup",
            colorArgb = 0xFFFF1744,
            vibrationPattern = "SILENT",
            isActive = true,
            timestamp = timestamp,
        )
        composeRule.setScreen {
            Column {
                Text(chatHistoryTimestamp(timestamp))
                ActiveBeaconEntry(beacon, silenced = false, onToggleSilenced = {})
            }
        }
        composeRule.settle()

        val now = System.currentTimeMillis()
        val expectedTimestamp = ChatTimestampPolicy.format(
            timestampMillis = timestamp,
            nowMillis = now,
            locale = Locale.getDefault(),
            timeZone = TimeZone.getDefault(),
            todayLabel = context.getString(R.string.today),
            yesterdayLabel = context.getString(R.string.yesterday),
            tomorrowLabel = context.getString(R.string.tomorrow),
        )

        composeRule.onAllNodesWithText(expectedTimestamp).assertCountEquals(2)
    }
}
