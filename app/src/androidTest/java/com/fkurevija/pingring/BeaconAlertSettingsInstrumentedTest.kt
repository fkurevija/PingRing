package com.fkurevija.pingring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fkurevija.pingring.data.BeaconAlertRepeat
import com.fkurevija.pingring.data.BeaconAlertSettings
import com.fkurevija.pingring.data.DEFAULT_BEACON_REPEAT_GAP_MILLIS
import com.fkurevija.pingring.ui.screens.BeaconCustomizationDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The beacon sheet's alert repetition controls.
 *
 * A dialog is composed directly rather than through RadarScreen because the
 * sheet is where the member makes this choice, and the two things worth proving
 * are both local to it: the saved choice is what is shown, and the gap control
 * appears only when it can actually do something.
 */
@RunWith(AndroidJUnit4::class)
class BeaconAlertSettingsInstrumentedTest {

    @get:Rule
    // The repo's own rule, which supplies settle(). The dropdowns open and close
    // through an animation, and asserting in the middle of one reads the tree
    // before the recomposition that the tap triggers.
    val composeRule = PausedClockComposeContentTestRule()

    private fun show(
        initial: BeaconAlertSettings = BeaconAlertSettings.DEFAULT,
        onChange: (BeaconAlertSettings) -> Unit = {},
    ) {
        composeRule.setContent {
            BeaconCustomizationDialog(
                localBeacon = null,
                initialAlertSettings = initial,
                onAlertSettingsChange = onChange,
                onDismiss = {},
                onActivate = { _, _, _ -> },
                onStop = {},
                onTestVibrate = {},
            )
        }
        composeRule.settle()
    }

    @Test
    fun anInstallThatHasNeverChosenAnythingShowsTheQuietDefault() {
        // Once, not repeat. A member who upgrades must not find their phone
        // suddenly buzzing forever on the first beacon somebody raises.
        show()

        composeRule.onNodeWithText("Alert repetition").assertIsDisplayed()
        composeRule.onNodeWithText("Once").assertIsDisplayed()
        composeRule.onNodeWithText("Repeat until stopped").assertDoesNotExist()
    }

    @Test
    fun aMemberWhoChoseToRepeatSeesThatChoiceWhenTheyComeBack() {
        // The dialog has to open on what was saved, not on the default, or every
        // visit silently discards the choice until they touch it again.
        show(
            initial = BeaconAlertSettings(
                repeat = BeaconAlertRepeat.REPEAT,
                repeatGapMillis = 10_000L,
            )
        )

        composeRule.onNodeWithText("Repeat until stopped").assertIsDisplayed()
        composeRule.onNodeWithText("Every 10 seconds").assertIsDisplayed()
    }

    @Test
    fun choosingToRepeatReportsTheChangeAndRevealsTheGap() {
        var reported: BeaconAlertSettings? = null
        show(onChange = { reported = it })

        composeRule.onNodeWithText("Once").performClick()
        composeRule.settle()
        composeRule.onNodeWithText("Repeat until stopped").performClick()
        composeRule.settle()

        assertEquals(BeaconAlertRepeat.REPEAT, reported?.repeat)
        // The gap appears only now: it controls the repeat, so offering it while
        // nothing repeats is a setting that does nothing.
        composeRule.onNodeWithText("Every 5 seconds").assertIsDisplayed()
    }

    @Test
    fun theGapIsNotOfferedWhileNothingRepeats() {
        show()

        // Nothing to space out yet. A greyed-out control still reads as a setting,
        // so it is absent rather than disabled.
        composeRule.onNodeWithText("Every 5 seconds").assertDoesNotExist()
    }

    @Test
    fun choosingAFrequencyReportsIt() {
        var reported: BeaconAlertSettings? = null
        show(
            initial = BeaconAlertSettings(repeat = BeaconAlertRepeat.REPEAT),
            onChange = { reported = it },
        )

        composeRule.onNodeWithText("Every ${DEFAULT_BEACON_REPEAT_GAP_MILLIS / 1000} seconds").performClick()
        composeRule.settle()
        composeRule.onNodeWithText("Every 2 seconds").performClick()
        composeRule.settle()

        assertEquals(BeaconAlertRepeat.REPEAT, reported?.repeat)
        assertEquals(2_000L, reported?.repeatGapMillis)
    }
}