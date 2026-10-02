package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
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
import com.fkurevija.pingring.data.ChatBubblePalette
import com.fkurevija.pingring.ui.screens.PingRingSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PingRingSettingsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun showSettings(
        event: Event,
        isHost: Boolean,
        onChatBubbleColorChange: (Long?) -> Unit = {},
        onSave: (Event) -> Unit,
    ) {
        composeRule.setContent {
            MaterialTheme {
                PingRingSettingsScreen(
                    event = event,
                    localPeerId = "a1b2c3d4",
                    chatBubbleColorArgb = null,
                    onChatBubbleColorChange = onChatBubbleColorChange,
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

    @Test
    fun randomizedPresetsDisplayAndSelectTheirActualColors() {
        val event = Event.create("4C1A9B")
        val colors = ChatBubblePalette.forMember(event.id, "a1b2c3d4")
        var selected: Long? = null
        showSettings(event, isHost = false, onChatBubbleColorChange = { selected = it }, onSave = {})

        colors.forEach { colorArgb ->
            val label = context.getString(
                R.string.set_chat_bubble_colour_to,
                String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
            )
            composeRule.onNodeWithContentDescription(label)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            composeRule.runOnIdle { assertEquals(colorArgb, selected) }
        }
    }
}