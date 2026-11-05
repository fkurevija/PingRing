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

class RadarMembershipNavigationInstrumentedTest : RadarScreenTestSupport() {
    @Test
    fun joinRequestsOverlayWithoutMovingRadarContent() {
        val requests = mutableStateOf(emptyList<PendingJoinRequest>())
        showRadar(isHost = true, pendingJoinRequestsProvider = { requests.value })
        val before = composeRule.onNodeWithText("Beacons").getUnclippedBoundsInRoot()

        composeRule.runOnIdle {
            requests.value = listOf(PendingJoinRequest("endpoint-1", "Ana", 1L))
        }
        composeRule.settle()

        composeRule.onNodeWithText("Ana").assertIsDisplayed()
        assertEquals(before, composeRule.onNodeWithText("Beacons").getUnclippedBoundsInRoot())

        composeRule.runOnIdle { requests.value = emptyList() }
        composeRule.settle()
        composeRule.onNodeWithText("Ana").assertDoesNotExist()
        assertEquals(before, composeRule.onNodeWithText("Beacons").getUnclippedBoundsInRoot())
    }

    @Test
    fun aHostSeesWhoIsWaitingToJoin() {
        showRadar(
            isHost = true,
            pendingJoinRequests = listOf(
                PendingJoinRequest("endpoint-1", "Ana", requestedAtMillis = 1L)
            )
        )

        // A request the host cannot see is a guest left waiting on a connection
        // that was never going to be answered.
        composeRule.onNodeWithText("1 device wants to join").assertIsDisplayed()
        composeRule.onNodeWithText("Ana").assertIsDisplayed()
    }

    @Test
    fun severalWaitingDevicesAreAllOfferedAtOnce() {
        // A host working through a queue needs to see the whole queue, not one
        // request at a time with the rest hidden.
        showRadar(
            isHost = true,
            pendingJoinRequests = listOf(
                PendingJoinRequest("endpoint-1", "Ana", requestedAtMillis = 1L),
                PendingJoinRequest("endpoint-2", "Bo", requestedAtMillis = 2L)
            )
        )

        composeRule.onNodeWithText("2 devices want to join").assertIsDisplayed()
        composeRule.onNodeWithText("Ana").assertIsDisplayed()
        composeRule.onNodeWithText("Bo").assertIsDisplayed()
    }

    @Test
    fun allowingAWaitingDeviceReportsIt() {
        var approved: String? = null
        showRadar(
            isHost = true,
            pendingJoinRequests = listOf(
                PendingJoinRequest("endpoint-1", "Ana", requestedAtMillis = 1L)
            ),
            onApproveJoinRequest = { approved = it }
        )

        composeRule.onNodeWithText("Allow").performClick()
        composeRule.settle()

        assertEquals("endpoint-1", approved)
    }

    @Test
    fun turningAwayAWaitingDeviceReportsIt() {
        var denied: String? = null
        showRadar(
            isHost = true,
            pendingJoinRequests = listOf(
                PendingJoinRequest("endpoint-1", "Ana", requestedAtMillis = 1L)
            ),
            onDenyJoinRequest = { denied = it }
        )

        composeRule.onNodeWithText("Deny").performClick()
        composeRule.settle()

        assertEquals("endpoint-1", denied)
    }

    @Test
    fun aGuestIsNeverShownAJoinApproval() {
        // Only the host is ever asked to approve. A guest seeing this would be
        // being asked to make a decision about a ring it does not own.
        showRadar(
            isHost = false,
            pendingJoinRequests = listOf(
                PendingJoinRequest("endpoint-1", "Ana", requestedAtMillis = 1L)
            )
        )

        composeRule.onNodeWithText("1 device wants to join").assertDoesNotExist()
        composeRule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun nothingIsShownWhenNobodyIsWaiting() {
        showRadar(isHost = true)

        composeRule.onNodeWithText("Allow").assertDoesNotExist()
        composeRule.onNodeWithText("Deny").assertDoesNotExist()
    }

    @Test
    fun theEventCodeAndMemberCountAreShown() {
        showRadar()

        // The code is the only way somebody else can join, and the count is what
        // tells a host the ring is filling up. Both are in the title.
        composeRule.onNodeWithText("Code: $eventId").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.member_count, 1, MAX_PARTICIPANTS)).assertIsDisplayed()
    }

    @Test
    fun theRadarTitleIsTheRingNotTheAppName() {
        showRadar()

        // The app name used to sit above the code as the title's first line. It
        // was the same word on every member's screen at the same time, so it spent
        // the most valuable line on screen saying nothing. Asserted as absent
        // rather than merely unasserted, because this is exactly the kind of
        // heading that comes back with the next layout change.
        composeRule.onNodeWithText("PingRing").assertDoesNotExist()
        // And the line it displaced still carries the two things that matter.
        composeRule.onNodeWithText("Code: $eventId").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.member_count, 1, MAX_PARTICIPANTS)).assertIsDisplayed()
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
        // Still on the radar. The title used to be asserted via the app name,
        // which is now gone from this screen; the event summary is both a better
        // proxy for "still here" and something this screen is supposed to show.
        composeRule.onNodeWithText("Code: $eventId").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.member_count, 1, MAX_PARTICIPANTS)).assertIsDisplayed()
    }

    @Test
    fun tappingHostSettingsCallsBack() {
        var opened = 0
        showRadar(isHost = true, onEventSettings = { opened++ })

        composeRule.onNodeWithText("Settings").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun theMemberListCanBeOpenedOnAPhone() {
        // The peers are a permanent second pane only at tablet width. Below that
        // the radar and chat fill the screen and this control is the only route
        // to the list, so before it existed a host on a phone could see the
        // member count in the title but had no way at all to see who was in the
        // ring. The test runs at the device's own width, which is a phone.
        showRadar()

        composeRule.onNodeWithContentDescription("Show members").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show members").performClick()
        // The sheet animates in on the frame clock, which this screen pauses so the
        // radar pulse can never settle. settle() covers the dialogs used
        // elsewhere on this screen; the sheet needs its frames walked explicitly.
        repeat(10) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.waitForIdle()

        // Proof the real list rendered rather than an empty sheet: the count
        // includes this device. Nothing else is on screen - with nobody else
        // present the sheet is empty, and there is no placeholder copy.
        composeRule.onNodeWithText("Members (1/8)").assertIsDisplayed()
        composeRule.onNodeWithText("Your ephemeral ID is private to this event.", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun theMemberListCanBeClosedAgain() {
        // A sheet that only opened would strand the reader on top of the radar,
        // so dismissal is asserted. System back rather than tapping the icon
        // again, because the sheet covers the top bar and tapping a covered
        // control would test nothing.
        showRadar()

        composeRule.onNodeWithContentDescription("Show members").performClick()
        repeat(10) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Members (1/8)").assertIsDisplayed()

        Espresso.pressBack()
        composeRule.settle()

        composeRule.onNodeWithContentDescription("Show members").assertIsDisplayed()
        composeRule.onNodeWithText("Members (1/8)").assertIsDisplayed()
    }

    @Test
    fun theQrPopupLabelsAndChipsTheCodeExactlyAsHostSetupDoes() {
        showRadar()

        composeRule.onNodeWithContentDescription("Show QR code to join this PingRing").performClick()
        composeRule.settle()

        // The dialog used to print the bare event id with letter spacing and
        // nothing naming it, while Host Setup labelled it "PingRing Code" and put
        // it in a chip. The same six characters were shaped two different ways
        // depending on which screen the host was looking at, so a guest told to
        // expect the code under a heading found no heading on the radar.
        composeRule.onNodeWithText("PingRing Code").assertIsDisplayed()
        composeRule.onNodeWithText(eventId).assertIsDisplayed()

        // One occurrence. A label and its value are one unit, and the label
        // repeating itself above the chip is what Host Setup deliberately dropped,
        // so a count is what catches that coming back.
        composeRule.onAllNodesWithText("PingRing Code").assertCountEquals(1)
    }

    @Test
    fun theQrPopupStatesTheCapacitySoAHostCanQuoteItWithoutLeavingTheRadar() {
        showRadar()

        composeRule.onNodeWithContentDescription("Show QR code to join this PingRing").performClick()
        composeRule.settle()

        // Capacity is part of the invitation being read out, not a fact about the
        // host setup screen that happens to state it. A host saying "come to my
        // ring" had no number to quote from anywhere on the radar, which is
        // exactly when the question gets asked.
        composeRule.onNodeWithText("Max Capacity").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.max_capacity_description))
            .assertIsDisplayed()
    }
}
