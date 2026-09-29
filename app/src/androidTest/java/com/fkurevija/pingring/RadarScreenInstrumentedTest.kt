package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fkurevija.pingring.data.BeaconVibrationPattern
import com.fkurevija.pingring.data.QuickBeacon
import com.fkurevija.pingring.data.SessionState
import com.fkurevija.pingring.ui.screens.RadarScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The screen a live PingRing actually runs on, driven on a real device.
 *
 * This is the largest class in the app (644 lines) and none of it can be reached
 * from the JVM: a `@Composable` body does not execute until Compose composes it.
 * These tests cover it with `discoveryRepository = null`, which the screen
 * supports - every `?.` on it is deliberate, because the screen has to render
 * before discovery has produced anything. That makes the whole layout testable
 * with no radio, no BLE and no Play Services.
 *
 * The assertions are about what a user can see and do: the role-dependent
 * labels, and that leaving always asks first. Pixel geometry is deliberately not
 * asserted - the ring is drawn on a Canvas, and an angle or colour check would
 * break on any Compose upgrade without catching a real defect.
 */
class RadarScreenInstrumentedTest {

    @get:Rule
    val composeRule = PausedClockComposeContentTestRule()

    private val eventId = "PING42"

    /**
     * Renders the screen with no discovery repository, as it looks before peers arrive.
     *
     * [PausedClockComposeContentTestRule] handles the clock: the radar ring pulses
     * forever, so the frame clock has to be manual or no assertion on this screen
     * would ever be reached.
     */
    private fun showRadar(
        isHost: Boolean = false,
        trackHost: Boolean = false,
        coordinationError: String? = null,
        sessionState: SessionState = SessionState(),
        quickBeacon: QuickBeacon? = null,
        onQuickBeaconChange: (QuickBeacon?) -> Unit = {},
        onEventSettings: () -> Unit = {},
        onLeaveEvent: () -> Unit = {},
        onEndEvent: () -> Unit = {},
    ) {
        composeRule.setScreen {
            RadarScreen(
                eventId = eventId,
                isHost = isHost,
                trackHost = trackHost,
                coordinationError = coordinationError,
                discoveryRepository = null,
                sessionState = sessionState,
                quickBeacon = quickBeacon,
                onQuickBeaconChange = onQuickBeaconChange,
                onEventSettings = onEventSettings,
                onLeaveEvent = onLeaveEvent,
                onEndEvent = onEndEvent,
            )
        }
    }

    @Test
    fun theEventCodeAndMemberCountAreShown() {
        showRadar()

        // The code is the only way somebody else can join, and the count is what
        // tells a host the ring is filling up. Both are in the title.
        composeRule.onNodeWithText("PingRing").assertIsDisplayed()
        composeRule.onNodeWithText("Code: $eventId · 1/8 Members").assertIsDisplayed()
    }

    @Test
    fun anEmptyChatSaysSoRatherThanShowingNothing() {
        showRadar()

        composeRule.onNodeWithText("No messages yet. Say hello!").assertIsDisplayed()
    }

    @Test
    fun theSendButtonIsDisabledUntilSomethingIsTyped() {
        showRadar()

        // An always-enabled send button either drops an empty message or posts
        // one on every stray tap.
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun aGuestIsOfferedLeavingRatherThanEnding() {
        showRadar(isHost = false)

        // A guest cannot end the ring, and the label is the only thing telling
        // the user which action they are about to take.
        composeRule.onNodeWithContentDescription("Leave PingRing").assertIsDisplayed()
    }

    @Test
    fun aHostIsOfferedEnding() {
        showRadar(isHost = true)

        composeRule.onNodeWithContentDescription("End PingRing").assertIsDisplayed()
    }

    @Test
    fun onlyAHostSeesTheEventSettings() {
        showRadar(isHost = true)

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun aGuestDoesNotSeeTheEventSettings() {
        showRadar(isHost = false)

        // Settings is where End/Leave live, and a guest has neither.
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
    }
    @Test
    fun tappingBackAsksBeforeLeavingThePingRing() {
        var asked = 0
        showRadar(isHost = false, onLeaveEvent = { asked++ })

        composeRule.onNodeWithContentDescription("Leave PingRing").performClick()
        composeRule.settle()

        // Back must not drop the user out of the app while members are still
        // being tracked; it asks first.
        composeRule.onNodeWithText("Leave this PingRing?").assertIsDisplayed()
        composeRule.onNodeWithText("Leave").assertIsDisplayed()
        assertEquals(0, asked)
    }

    @Test
    fun confirmingTheLeaveCallsBack() {
        var left = 0
        showRadar(isHost = false, onLeaveEvent = { left++ })

        composeRule.onNodeWithContentDescription("Leave PingRing").performClick()
        composeRule.settle()
        composeRule.onNodeWithText("Leave").performClick()
        composeRule.settle()

        assertEquals(1, left)
    }

    @Test
    fun cancellingTheLeaveStaysOnTheRadar() {
        var left = 0
        showRadar(isHost = false, onLeaveEvent = { left++ })

        composeRule.onNodeWithContentDescription("Leave PingRing").performClick()
        composeRule.settle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.settle()

        assertEquals(0, left)
        composeRule.onNodeWithText("PingRing").assertIsDisplayed()
    }

    @Test
    fun theQrCodeCanBeShownAndClosed() {
        showRadar()

        composeRule.onNodeWithContentDescription("Show QR code to join this PingRing").performClick()
        composeRule.settle()

        composeRule.onNodeWithText("Scan to Join").assertIsDisplayed()
        // The code is rendered as an image, so its content description is what
        // carries the event id to a screen reader.
        composeRule.onNodeWithContentDescription("QR code for PingRing $eventId")
            .assertIsDisplayed()
        composeRule.onNodeWithText(eventId).assertIsDisplayed()

        composeRule.onNodeWithText("Close").performClick()
        composeRule.settle()
        composeRule.onNodeWithText("Scan to Join").assertDoesNotExist()
    }

    @Test
    fun aCoordinationErrorIsShownRatherThanSwallowed() {
        showRadar(coordinationError = "Could not reach the host")

        // Silent failure here leaves members believing they are in a ring when
        // the ring is not actually being coordinated.
        composeRule.onNodeWithText("Could not reach the host").assertIsDisplayed()
    }

    @Test
    fun theQuickBeaconAndNewBeaconControlsAreReachable() {
        showRadar()

        // Both are how a member signals without typing, so they have to be
        // present with nobody else in the ring.
        composeRule.onNodeWithText("Quick Beacon").assertIsDisplayed()
        composeRule.onNodeWithText("New Beacon").assertIsDisplayed()
    }

    @Test
    fun theBeaconScreenOpens() {
        showRadar()

        composeRule.onNodeWithText("New Beacon").performClick()
        composeRule.settle()

        composeRule.onNodeWithText("Select Preset").assertIsDisplayed()
    }

    @Test
    fun quickBeaconWithoutOneSetOpensTheBeaconScreen() {
        // With nothing set there is no beacon to send. The tap still has to
        // land somewhere useful, and it toasts on the way to explain itself:
        // opening a beacon dialog unprompted otherwise reads as a misfire.
        showRadar()

        composeRule.onNodeWithText("Quick Beacon").performClick()
        composeRule.settle()

        composeRule.onNodeWithText("Select Preset").assertIsDisplayed()
    }

    @Test
    fun quickBeaconWithOneSetSendsWithoutAskingQuestions() {
        // A set beacon is what the button exists for: one tap sends it, with no
        // screen in between.
        showRadar(
            quickBeacon = QuickBeacon("Regroup at my location", 0xFF00E5FF, BeaconVibrationPattern.RAPID_ALERT)
        )

        composeRule.onNodeWithText("Quick Beacon").performClick()
        composeRule.settle()

        composeRule.onNodeWithText("Select Preset").assertDoesNotExist()
    }

    @Test
    fun theBeaconScreenSetsTheCurrentSelectionAsTheQuickBeacon() {
        var saved: QuickBeacon? = null
        showRadar(onQuickBeaconChange = { saved = it })

        composeRule.onNodeWithText("New Beacon").performClick()
        composeRule.settle()
        composeRule.onNodeWithText("Set as Quick Beacon").performScrollTo().performClick()
        composeRule.settle()

        // The untouched SOS preset defaults, saved as one configuration: the
        // message, the colour and the rhythm the Quick Beacon button will send.
        assertEquals(
            QuickBeacon("Emergency!", 0xFFFF1744, BeaconVibrationPattern.SOS_URGENT),
            saved
        )
    }

    @Test
    fun tappingHostSettingsCallsBack() {
        var opened = 0
        showRadar(isHost = true, onEventSettings = { opened++ })

        composeRule.onNodeWithText("Settings").performClick()

        assertEquals(1, opened)
    }
}