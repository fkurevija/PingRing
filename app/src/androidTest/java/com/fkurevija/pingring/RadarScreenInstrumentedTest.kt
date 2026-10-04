package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
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
import com.fkurevija.pingring.data.ChatBubblePalette
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fkurevija.pingring.data.BeaconVibrationPattern
import com.fkurevija.pingring.data.LastKnownPeerDirection
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.SessionState
import com.fkurevija.pingring.ui.screens.RadarScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Real resources, so the assertions below can resolve the strings they match on. */
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Renders the screen with no discovery repository, as it looks before peers arrive.
     *
     * [PausedClockComposeContentTestRule] handles the clock: the radar ring pulses
     * forever, so the frame clock has to be manual or no assertion on this screen
     * would ever be reached.
     */
    private fun showRadar(
        isHost: Boolean = false,
        coordinationError: String? = null,
        sessionState: SessionState = SessionState(),
        chatBubbleColorArgb: Long? = null,
        onChatBubbleColorChange: (Long) -> Unit = {},
        radarScreenReaderSummaryEnabled: Boolean = false,
        connectionRecoveryPromptEnabled: Boolean = true,
        connectionRecoveryPromptDelayMillis: Long = 120_000L,
        hostConnectionLostAtMillis: Long? = null,
        lastKnownPeerDirections: List<LastKnownPeerDirection> = emptyList(),
        isTopDestination: Boolean = true,
        pendingJoinRequests: List<PendingJoinRequest> = emptyList(),
        onApproveJoinRequest: (String) -> Unit = {},
        onDenyJoinRequest: (String) -> Unit = {},
        onEventSettings: () -> Unit = {},
        onLeaveEvent: () -> Unit = {},
        onEndEvent: () -> Unit = {},
    ) {
        composeRule.setScreen {
            // Held in Compose state and fed back, mirroring MainActivity: the
            // selection is persisted and the settings flow reports the new value,
            // so the swatch re-renders as chosen. A callback that only recorded
            // the value would leave the dialog showing no colour as selected,
            // which is exactly the failure this wiring exists to prevent.
            var selectedColor by remember { mutableStateOf(chatBubbleColorArgb) }
            RadarScreen(
                eventId = eventId,
                isHost = isHost,
                coordinationError = coordinationError,
                discoveryRepository = null,
                sessionState = sessionState,
                chatBubbleColorArgb = selectedColor,
                onChatBubbleColorChange = {
                    selectedColor = it
                    onChatBubbleColorChange(it)
                },
                radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
                connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
                connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
                hostConnectionLostAtMillis = hostConnectionLostAtMillis,
                lastKnownPeerDirections = lastKnownPeerDirections,
                isTopDestination = isTopDestination,
                pendingJoinRequests = pendingJoinRequests,
                onApproveJoinRequest = onApproveJoinRequest,
                onDenyJoinRequest = onDenyJoinRequest,
                onEventSettings = onEventSettings,
                onLeaveEvent = onLeaveEvent,
                onEndEvent = onEndEvent,
            )
        }
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
        composeRule.onNodeWithText("New Beacon").assertIsDisplayed()
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

        composeRule.onNodeWithText("New Beacon").performClick()
        composeRule.settle()

        composeRule.onNodeWithText("Select Preset").assertIsDisplayed()
    }

    @Test
    fun chatSettingsAndNewBeaconShareOneRowWithChatSettingsOnTheLeft() {
        // The position is the point. Chat settings used to live on the PingRing
        // settings screen - two destinations and a chat's length away from the
        // chat they change - and it then sat stacked above the beacon button with
        // each filling the width, spending a second row of a short screen on a
        // short control.
        //
        // Asserted from measured bounds rather than from both labels merely
        // existing, because "both are on screen" is exactly what the old stacked
        // layout also satisfied. The order matters too: chat settings on the left.
        showRadar()
        composeRule.settle()

        val chatBounds = composeRule.onNodeWithText("Chat Settings").getUnclippedBoundsInRoot()
        val beaconBounds = composeRule.onNodeWithText("New Beacon").getUnclippedBoundsInRoot()

        assertTrue(
            "Chat Settings should sit left of New Beacon, was " +
                "chat=${chatBounds.left} beacon=${beaconBounds.left}",
            chatBounds.left < beaconBounds.left,
        )
        // Same row rather than stacked: the two vertical spans have to overlap.
        assertTrue(
            "Chat Settings and New Beacon should share one row, was " +
                "chat=${chatBounds.top}-${chatBounds.bottom} " +
                "beacon=${beaconBounds.top}-${beaconBounds.bottom}",
            chatBounds.top < beaconBounds.bottom && beaconBounds.top < chatBounds.bottom,
        )
    }

    @Test
    fun chatSettingsOffersTheTwelvePresetsAndSelectsTheirActualColour() {
        var selected: Long? = null
        showRadar(onChatBubbleColorChange = { selected = it })

        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        ChatBubblePalette.presets.forEach { colorArgb ->
            val label = context.getString(
                R.string.set_chat_bubble_colour_to,
                String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
            )
            composeRule.onNodeWithContentDescription(label)
                .performScrollTo()
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            // The frame clock is paused for this screen, so a click does not by
            // itself drive the recomposition that marks the swatch as chosen.
            // Without settling first, the assertion reads the tree as it was
            // before the tap and reports the control as unselected.
            composeRule.settle()
            composeRule.runOnIdle { assertEquals(colorArgb, selected) }
        }
    }

    @Test
    fun theChatColourAlreadyChosenIsTheOneMarkedAsSelected() {
        // The other half of picking a colour: the swatch for the colour already in
        // use has to read as chosen, or a member cannot tell where they are on the
        // dial before touching it. Asserted from the stored value rather than
        // after a tap because this screen runs on a paused frame clock, where a
        // click does not by itself drive the recomposition that flips the
        // swatch - the assertion then reads the tree as it was before the tap.
        val chosen = ChatBubblePalette.presets[4]
        showRadar(chatBubbleColorArgb = chosen)

        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        fun labelFor(colorArgb: Long) = context.getString(
            R.string.set_chat_bubble_colour_to,
            String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
        )
        composeRule.onNodeWithContentDescription(labelFor(chosen))
            .assertIsDisplayed()
            .assertIsSelected()
        ChatBubblePalette.presets.filter { it != chosen }.forEach { other ->
            composeRule.onNodeWithContentDescription(labelFor(other))
                .performScrollTo()
                .assertIsNotSelected()
        }
    }

    @Test
    fun chatSettingsDoesNotOfferACustomColourOrTheThemeColour() {
        // A member picks a colour so their messages can be found in a busy
        // transcript. Two phones rendering the same message differently defeats
        // that, so neither escape hatch is available.
        showRadar()
        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        composeRule.onNodeWithText(context.getString(R.string.custom)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.use_theme_colour)).assertDoesNotExist()
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

        // Asserted on the sheet title rather than on the old empty-state copy:
        // that text is gone from the app entirely now, so checking it stayed
        // hidden would pass whether or not the sheet had actually closed.
        composeRule.onNodeWithText("Members (1/8)").assertDoesNotExist()
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
