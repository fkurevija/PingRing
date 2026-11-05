package com.combustiblelemonlabs.pingring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.combustiblelemonlabs.pingring.data.BeaconAlertRepeat
import com.combustiblelemonlabs.pingring.data.BeaconAlertSettings
import com.combustiblelemonlabs.pingring.data.session.Event
import com.combustiblelemonlabs.pingring.ui.screens.PingRingSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings screen's beacon alert repetition controls.
 */
@RunWith(AndroidJUnit4::class)
class BeaconAlertSettingsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testEvent = Event(
        id = "TEST01",
        name = "Test Event",
        description = "Description",
        expiryTimestamp = System.currentTimeMillis() + 3600_000L,
        hostPeerId = "host-1",
    )

    private fun show(
        initial: BeaconAlertSettings = BeaconAlertSettings.DEFAULT,
        onChange: (BeaconAlertSettings) -> Unit = {},
    ) {
        composeRule.setContent {
            PingRingSettingsScreen(
                event = testEvent,
                uwbHardwareSupported = false,
                isHost = true,
                beaconAlertSettings = initial,
                onBeaconAlertSettingsChange = onChange,
                onBack = {},
                onOpenAppSettings = {},
                onEventChanged = {},
                onLeaveEvent = {},
                onEndEvent = {},
            )
        }
    }

    @Test
    fun anInstallThatHasNeverChosenAnythingShowsTheQuietDefault() {
        show()

        composeRule.onNodeWithText("Beacon Repetition").assertIsDisplayed()
        composeRule.onNodeWithText("Frequency: Every 5s").assertDoesNotExist()
    }

    @Test
    fun aMemberWhoChoseToRepeatSeesThatChoiceWhenTheyComeBack() {
        show(
            initial = BeaconAlertSettings(
                repeat = BeaconAlertRepeat.REPEAT,
                repeatGapMillis = 10_000L,
            )
        )

        composeRule.onNodeWithText("Frequency: Every 10s").assertIsDisplayed()
    }

    @Test
    fun choosingToRepeatReportsTheChangeAndRevealsTheGap() {
        var reported: BeaconAlertSettings? = null
        show(onChange = { reported = it })

        composeRule.onNodeWithTag("beaconRepetitionSwitch", useUnmergedTree = true)
            .performTouchInput { click(center) }

        assertEquals(BeaconAlertRepeat.REPEAT, reported?.repeat)
        composeRule.onNodeWithText("Frequency: Every 5s").assertIsDisplayed()
    }

    @Test
    fun tappingTheLabelDoesNotToggleRepetition() {
        var reported: BeaconAlertSettings? = null
        show(onChange = { reported = it })

        composeRule.onNodeWithText("Beacon Repetition").performClick()

        assertEquals(null, reported)
        composeRule.onNodeWithText("Frequency: Every 5s").assertDoesNotExist()
    }

    @Test
    fun theGapIsNotOfferedWhileNothingRepeats() {
        show()

        composeRule.onNodeWithText("Frequency: Every 5s").assertDoesNotExist()
    }
}
