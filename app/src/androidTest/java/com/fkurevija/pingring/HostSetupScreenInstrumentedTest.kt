package com.fkurevija.pingring

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.ui.screens.HostSetupScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The screen a host configures a new PingRing on.
 *
 * It used to carry five of the seven event switches, duplicating PingRing
 * Settings, and reached the other two only through a link - so the two screens
 * disagreed and the later additions were invisible from here. The duplicates
 * are gone, and what this asserts is that they stay gone: no event switch may
 * appear on this screen, and the full set must still be reachable.
 *
 * A test per switch would be brittle - renaming one would fail for the wrong
 * reason - so it asserts the *absence* of the switches as a set, which is the
 * property that actually matters.
 */
class HostSetupScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    /**
     * Every event switch PingRing Settings owns. None of them may appear here.
     *
     * Checked as text because that is what a user reads; a switch whose label
     * has drifted is still a switch appearing in the wrong place.
     *
     * Most of these no longer exist at all - discoverable, approve-each-join,
     * track-host and chat notifications were removed along with the behaviour
     * they configured. They stay on this list on purpose: it is the record of
     * what used to be duplicated here, and a row reappearing under one of these
     * names is the regression worth catching.
     */
    private val eventSwitchesOwnedByPingRingSettings = listOf(
        "Prevent New Users From Joining",
        "Ultra-wideband (UWB)",
        "High Bandwidth (Wi-Fi)",
        "Chat Notifications",
        "Discoverable",
        "Approve Each Join",
        "Track Host Only",
    )

    private fun show(
        savedUsername: String = "Alex",
        onOpenSettings: () -> Unit = {},
        pendingJoinRequests: List<PendingJoinRequest> = emptyList(),
        onApproveJoinRequest: (String) -> Unit = {},
        onDenyJoinRequest: (String) -> Unit = {},
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The screen asks the repository only whether this device can range, so
        // a repository is needed but no radio is ever touched. The production
        // transport is used rather than the debug one, which would need a relay
        // host to connect to.
        val repository = DiscoveryRepository(
            context = context,
            sessionManager = com.fkurevija.pingring.data.SessionManager(),
            nearbyConnectionsManager = com.fkurevija.pingring.data.NearbyConnectionsManager(context)
        )
        composeRule.setContent {
            MaterialTheme {
                HostSetupScreen(
                    eventCode = "4C1A9B",
                    savedUsername = savedUsername,
                    discoveryRepository = repository,
                    onEndEvent = {},
                    onSaveUsername = {},
                    onView = {},
                    onOpenSettings = onOpenSettings,
                    pendingJoinRequests = pendingJoinRequests,
                    onApproveJoinRequest = onApproveJoinRequest,
                    onDenyJoinRequest = onDenyJoinRequest,
                )
            }
        }
    }

    @Test
    fun pingRingSettingsReplacesTheRedundantToolbarLink() {
        var opened = 0
        show(onOpenSettings = { opened++ })

        composeRule.onNodeWithText("All Settings").assertDoesNotExist()
        // Exactly one, the button itself. There used to be a heading above it
        // repeating the same words, which this count is what pinned down.
        composeRule.onAllNodesWithText("PingRing Settings").assertCountEquals(1)
        composeRule.onNodeWithText("PingRing Settings").performClick()
        composeRule.waitForIdle()

        assertEquals(1, opened)
    }

    @Test
    fun theSettingsHintCaptionsTheButtonFromBelowRatherThanHeadingIt() {
        // The card holds one control, so the hint belongs under that control
        // where it captions it. Above the button it read as prose to skim past on
        // the way to the thing it was describing.
        show()

        val button = composeRule.onNodeWithText("PingRing Settings").getUnclippedBoundsInRoot()
        val hint = composeRule
            .onNodeWithText("Membership, chat colour, tracking, UWB and notifications are set here.")
            .getUnclippedBoundsInRoot()

        assertTrue(
            "hint should sit below the button, was hintTop=${hint.top} buttonBottom=${button.bottom}",
            hint.top >= button.bottom,
        )
    }

    @Test
    fun noEventSwitchIsDuplicatedHere() {
        show()

        // The regression this screen is most likely to fall back into: someone
        // adding a switch here because it is more convenient at setup time,
        // without noticing PingRing Settings already owns it.
        eventSwitchesOwnedByPingRingSettings.forEach { label ->
            composeRule.onNodeWithText(label, substring = true)
                .assertDoesNotExist()
        }
    }

    @Test
    fun thereIsAVisibleRouteToThem() {
        show()

        // Removing the duplicates only helps if the way in is obvious. The card
        // is the whole point of this screen now, so it has to be findable.
        //
        // One occurrence, the button. It used to be two, a heading repeating the
        // same words above it, so the count is what would catch that returning.
        composeRule.onAllNodesWithText("PingRing Settings").assertCountEquals(1)
    }

    @Test
    fun theEventCodeIsShownSoTheHostCanShareIt() {
        show()

        // Joining is by code or by scanning the host's own QR code, and there is
        // no longer any list of nearby sessions. The code is therefore the only
        // route in besides the QR, so it has to be readable here.
        composeRule.onNodeWithText("4C1A9B").assertIsDisplayed()
    }

    @Test
    fun capacityIsStatedHereWhereTheHostIsDecidingWhoToInvite() {
        show()

        // Moved off the settings screen, where it sat under a heading with no
        // control next to it and read like something adjustable. It is not: eight
        // is the limit, and the host is told while people are still arriving.
        composeRule.onNodeWithText("Max Capacity").assertIsDisplayed()
        composeRule.onNodeWithText(
            "PingRing is limited to 8 members (1 host + 7 guests)."
        ).assertIsDisplayed()
    }

    @Test
    fun aHostCanApproveAJoinWhileStillOnHostSetup() {
        var approved: String? = null
        show(
            pendingJoinRequests = listOf(PendingJoinRequest("endpoint-1", "Ana", 1L)),
            onApproveJoinRequest = { approved = it },
        )

        composeRule.onNodeWithText("Ana").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").performClick()
        composeRule.waitForIdle()

        assertEquals("endpoint-1", approved)
    }

    @Test
    fun aHostCanDenyAJoinWhileStillOnHostSetup() {
        var denied: String? = null
        show(
            pendingJoinRequests = listOf(PendingJoinRequest("endpoint-2", "Sam", 1L)),
            onDenyJoinRequest = { denied = it },
        )

        composeRule.onNodeWithText("Sam").assertIsDisplayed()
        composeRule.onNodeWithText("Deny").performClick()
        composeRule.waitForIdle()

        assertEquals("endpoint-2", denied)
    }

    @Test
    fun theUsernameErrorSitsBelowTheFieldRatherThanInsideIt() {
        // The message used to live in the TextField's supportingText slot: a strip
        // measured inside the field's own bounds, reported cut off at the bottom on
        // a real device. It is now ordinary content laid out after the field.
        //
        // Asserted geometrically, because that is the only thing that actually
        // differs: in the merged tree a supporting message carries the same Text
        // semantics as a plain one, so asking "is there a node with the error on
        // it" passes either way. The field node's bottom edge is where the
        // supporting strip used to end, so the message finishing below it is the
        // layout having changed rather than the text merely existing.
        show()
        composeRule.onNodeWithText("Alex").performTextClearance()
        composeRule.waitForIdle()

        // InputText is what picks the TextField out of the pair: the error
        // message itself contains the word "Username" too, so matching on the
        // text alone is ambiguous once the message is showing.
        val field = composeRule.onNode(
            hasText("Username", substring = true) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.InputText)
        ).getUnclippedBoundsInRoot()
        val message = composeRule.onNodeWithText("Username cannot be empty", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "error should finish below the field, was msgBottom=${message.bottom} " +
                "fieldBottom=${field.bottom}",
            message.bottom > field.bottom,
        )
    }

    @Test
    fun anEmptyUsernameErrorAndThePrimaryActionBothFitWithoutScrolling() {
        // The one promise this screen makes: a host sees all of it on one page.
        // The scroll modifier stays as a guard for the join-request queue, which
        // grows at runtime, so this is what proves it is not needed while resting.
        //
        // Measured against the scroll viewport, NOT the window. The window is the
        // full 923dp of screen; the viewport is what is left once the system bars
        // are taken off, and it is the viewport the button has to fit inside. An
        // earlier version compared against the window and passed while the button
        // was visibly cut in half - which is the exact bug this asserts against.
        show()
        composeRule.onNodeWithText("Alex").performTextClearance()
        composeRule.waitForIdle()

        val action = composeRule.onNodeWithText("Launch Radar").getUnclippedBoundsInRoot()
        val viewport = composeRule.onNode(hasScrollAction()).getUnclippedBoundsInRoot()

        assertTrue(
            "the whole screen should fit without scrolling, was " +
                "actionBottom=${action.bottom} viewportBottom=${viewport.bottom}",
            action.bottom <= viewport.bottom,
        )
        composeRule.onNodeWithText("Username cannot be empty").assertIsDisplayed()
    }

}
