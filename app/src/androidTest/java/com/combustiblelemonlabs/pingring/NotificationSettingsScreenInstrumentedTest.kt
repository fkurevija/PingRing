package com.combustiblelemonlabs.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.combustiblelemonlabs.pingring.ui.screens.NotificationSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationSettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun notificationSwitchesHaveIndependentCallbacks() {
        var chat by mutableStateOf(true)
        var sound by mutableStateOf(true)
        var vibration by mutableStateOf(true)

        composeRule.setContent {
            MaterialTheme {
                NotificationSettingsScreen(
                    chatNotificationsEnabled = chat,
                    onChatNotificationsEnabledChange = { chat = it },
                    notificationSoundEnabled = sound,
                    onNotificationSoundEnabledChange = { sound = it },
                    notificationVibrationEnabled = vibration,
                    onNotificationVibrationEnabledChange = { vibration = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_push_notifications))
            .performScrollTo().assertIsOn().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(context.getString(R.string.notification_sound))
            .performScrollTo().assertIsOn().performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(false, chat)
            assertEquals(false, sound)
            assertEquals(true, vibration)
        }
    }
}
