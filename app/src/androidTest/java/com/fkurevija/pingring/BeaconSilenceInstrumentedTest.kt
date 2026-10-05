package com.fkurevija.pingring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.ui.screens.ActiveBeaconEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Silencing one beacon without being able to hide it.
 *
 * The safety property is the whole point of this feature, so it is asserted on
 * screen rather than only in the policy: a silenced beacon must still be in the
 * transcript, with its title and sender, and must carry a note saying so.
 */
@RunWith(AndroidJUnit4::class)
class BeaconSilenceInstrumentedTest {

    @get:Rule
    val composeRule = PausedClockComposeContentTestRule()

    private val beacon = BeaconInfo(
        id = "b1",
        senderPeerId = "peer-ana",
        senderName = "Ana",
        title = "Regroup Here",
        colorArgb = 0xFFFF1744,
        vibrationPattern = "SOS_URGENT",
        isActive = true,
        timestamp = 1L,
    )

    private fun show(silenced: Boolean, onToggle: (Boolean) -> Unit = {}) {
        composeRule.setContent {
            ActiveBeaconEntry(
                beacon = beacon,
                silenced = silenced,
                onToggleSilenced = onToggle,
            )
        }
        composeRule.settle()
    }

    @Test
    fun aBeaconThatIsNotSilencedOffersToSilenceIt() {
        show(silenced = false)

        composeRule.onNodeWithContentDescription("Silence the alarm for Regroup Here")
            .assertIsDisplayed()
        // Nothing claims to be silenced yet.
        composeRule.onNodeWithText(
            "Alarm silenced on this phone. The beacon is still shown."
        ).assertDoesNotExist()
    }

    @Test
    fun aSilencedBeaconIsStillFullyVisibleInTheTranscript() {
        // The invariant that makes the feature safe: muting changes what the
        // phone does, never what it shows. If this ever hides the beacon, a member
        // can mute themselves out of seeing a real emergency.
        show(silenced = true)

        composeRule.onNodeWithText("Regroup Here").assertIsDisplayed()
        composeRule.onNodeWithText("Ana · SOS / Urgent").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Alarm silenced on this phone. The beacon is still shown."
        ).assertIsDisplayed()
    }

    @Test
    fun tappingSilencesAndNamesTheBeaconItWouldStop() {
        var reported: Boolean? = null
        show(silenced = false, onToggle = { reported = it })

        composeRule.onNodeWithContentDescription("Silence the alarm for Regroup Here")
            .performClick()
        composeRule.settle()

        assertEquals(true, reported)
    }

    @Test
    fun tappingASilencedBeaconTurnsTheAlarmBackOn() {
        var reported: Boolean? = null
        show(silenced = true, onToggle = { reported = it })

        composeRule.onNodeWithContentDescription("Turn the alarm back on for Regroup Here")
            .performClick()
        composeRule.settle()

        assertEquals(false, reported)
    }
}