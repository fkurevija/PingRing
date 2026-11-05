package com.combustiblelemonlabs.pingring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.combustiblelemonlabs.pingring.data.session.BeaconInfo
import com.combustiblelemonlabs.pingring.data.chat.ChatTimestampPolicy
import com.combustiblelemonlabs.pingring.ui.screens.ActiveBeaconEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.TimeZone

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

    private fun show(
        silenced: Boolean,
        beacon: BeaconInfo = this.beacon,
        onToggle: (Boolean) -> Unit = {},
    ) {
        composeRule.setScreen {
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
    fun beaconShowsItsTimestampInChat() {
        show(silenced = false)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val timestamp = ChatTimestampPolicy.format(
            timestampMillis = beacon.timestamp,
            nowMillis = System.currentTimeMillis(),
            locale = Locale.getDefault(),
            timeZone = TimeZone.getDefault(),
            todayLabel = context.getString(R.string.today),
            yesterdayLabel = context.getString(R.string.yesterday),
            tomorrowLabel = context.getString(R.string.tomorrow),
        )
        composeRule.onNodeWithText(timestamp).assertIsDisplayed()
    }

    @Test
    fun endedBeaconHasNoSilenceControlOrSilencedNote() {
        show(silenced = true, beacon = beacon.copy(isActive = false))

        composeRule.onNodeWithText("Regroup Here").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Silence the alarm for Regroup Here")
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Turn the alarm back on for Regroup Here")
            .assertDoesNotExist()
        composeRule.onNodeWithText(
            "Alarm silenced on this phone. The beacon is still shown."
        ).assertDoesNotExist()
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