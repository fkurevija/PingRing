package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.ui.screens.PingRingSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PingRingSettingsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun showSettings(event: Event, isHost: Boolean, onSave: (Event) -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                PingRingSettingsScreen(
                    event = event,
                    chatBubbleColorArgb = null,
                    onChatBubbleColorChange = {},
                    uwbHardwareSupported = false,
                    isHost = isHost,
                    onBack = {},
                    onSave = onSave,
                    onEndEvent = {},
                    onLeaveEvent = {},
                )
            }
        }
    }

    private fun verifyHostToggle(initialValue: Boolean) {
        val event = Event.create("4C1A9B").copy(trackHost = initialValue)
        var saved: Event? = null
        showSettings(event, isHost = true) { saved = it }

        val toggle = composeRule.onNodeWithContentDescription(context.getString(R.string.track_host_only))
        toggle.performScrollTo()
        if (initialValue) toggle.assertIsOn() else toggle.assertIsOff()
        toggle.performClick()
        if (initialValue) toggle.assertIsOff() else toggle.assertIsOn()
        composeRule.onNodeWithText(context.getString(R.string.save_changes)).performClick()

        composeRule.runOnIdle {
            assertEquals(!initialValue, saved?.trackHost)
            assertEquals(event.id, saved?.id)
        }
    }

    @Test
    fun hostCanEnableHostOnlyTrackingAndSave() = verifyHostToggle(false)

    @Test
    fun hostCanDisableHostOnlyTrackingAndSave() = verifyHostToggle(true)

    @Test
    fun guestSeesHostOnlyTrackingReadOnlyAndSavePreservesIt() {
        val event = Event.create("4C1A9B").copy(trackHost = true)
        var saved: Event? = null
        showSettings(event, isHost = false) { saved = it }

        composeRule.onNodeWithContentDescription(context.getString(R.string.track_host_only))
            .performScrollTo()
            .assertIsOn()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.save_changes)).performClick()

        composeRule.runOnIdle { assertEquals(true, saved?.trackHost) }
    }
}