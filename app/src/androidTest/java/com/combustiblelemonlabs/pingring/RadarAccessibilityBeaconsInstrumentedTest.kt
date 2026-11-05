package com.combustiblelemonlabs.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.combustiblelemonlabs.pingring.data.chat.ChatBubblePalette
import com.combustiblelemonlabs.pingring.data.session.MAX_PARTICIPANTS
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.combustiblelemonlabs.pingring.data.chat.BeaconVibrationPattern
import com.combustiblelemonlabs.pingring.data.session.ChatMessage
import com.combustiblelemonlabs.pingring.data.radar.LastKnownPeerDirection
import com.combustiblelemonlabs.pingring.data.PendingJoinRequest
import com.combustiblelemonlabs.pingring.data.session.SessionState
import com.combustiblelemonlabs.pingring.ui.screens.RadarScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository
import com.combustiblelemonlabs.pingring.data.session.Event

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

class RadarAccessibilityBeaconsInstrumentedTest : RadarScreenTestSupport() {
    @Test
    fun aLostHostShowsTheRecoveryPromptAndLeavingCallsBack() {
        var left = 0
        showRadar(
            hostConnectionLostAtMillis = System.currentTimeMillis() - 121_000L,
            onLeaveEvent = { left++ },
        )

        composeRule.settle()
        composeRule.onNodeWithText("PingRing connection unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Leave PingRing").performClick()
        composeRule.settle()

        assertEquals(1, left)
    }

    @Test
    fun recoveryPromptCanBeSnoozed() {
        showRadar(hostConnectionLostAtMillis = System.currentTimeMillis() - 121_000L)

        composeRule.settle()
        composeRule.onNodeWithText("PingRing connection unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Remind me in 2 min").performClick()
        composeRule.settle()

        composeRule.onNodeWithText("PingRing connection unavailable").assertDoesNotExist()
    }

    @Test
    fun recoveryPromptCanBeSilencedForTheCurrentRing() {
        showRadar(hostConnectionLostAtMillis = System.currentTimeMillis() - 121_000L)

        composeRule.settle()
        composeRule.onNodeWithText("PingRing connection unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Silence for this PingRing").performClick()
        composeRule.settle()

        composeRule.onNodeWithText("PingRing connection unavailable").assertDoesNotExist()
    }

    @Test
    fun lastKnownUwbDirectionIsMarkedWithItsAge() {
        showRadar(
            lastKnownPeerDirections = listOf(
                LastKnownPeerDirection(
                    peerId = "peer-a",
                    name = "Ana",
                    worldDirectionX = 0f,
                    worldDirectionY = 1f,
                    distance = 4f,
                    lastDetectedAtMillis = System.currentTimeMillis() - 15_000L,
                    recordedAtMillis = System.currentTimeMillis(),
                )
            )
        )

        composeRule.onNodeWithText("Last known direction to Ana:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("they may have moved", substring = true).assertIsDisplayed()
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
    fun theBeaconAndChatControlsAreReachableWithNobodyElseInTheRing() {
        showRadar()

        // How a member signals without typing, and how they change how their
        // messages look - all three have to be present before anybody joins.
        composeRule.onNodeWithText(context.getString(R.string.beacons)).assertIsDisplayed()
        composeRule.onNodeWithText("Chat Settings").assertIsDisplayed()
    }

    @Test
    fun theRadarCanvasScreenReaderSummaryIsHiddenByDefault() {
        // A screen reader reading the whole radar canvas out loud on every
        // recomposition is unusable, so the summary is opt-in.
        showRadar()

        composeRule.onNodeWithContentDescription(
            "Radar. No tracked member. Direction unavailable."
        ).assertDoesNotExist()
    }

    @Test
    fun theRadarCanvasScreenReaderSummaryIsOptIn() {
        // Split from the default case rather than rendered after it: a test rule
        // accepts setContent once per test, so a second showRadar in one method
        // throws before either assertion runs.
        showRadar(radarScreenReaderSummaryEnabled = true)

        composeRule.onNodeWithContentDescription(
            "Radar. No tracked member. Direction unavailable."
        ).assertIsDisplayed()
    }

    @Test
    fun theBeaconScreenOpens() {
        showRadar()

        composeRule.onNodeWithText(context.getString(R.string.beacons)).performClick()
        composeRule.settle()

        composeRule.onNodeWithText("Select Preset").assertIsDisplayed()
    }

    @Test
    fun theQuickBeaconButtonIsGone() {
        // It duplicated the beacon dialog: a second place to configure a beacon
        // from, with no way to see both at once.
        showRadar()
        composeRule.settle()

        composeRule.onNodeWithText("Quick Beacon").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.set_quick_beacon)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.remove_quick_beacon)).assertDoesNotExist()
    }
}
