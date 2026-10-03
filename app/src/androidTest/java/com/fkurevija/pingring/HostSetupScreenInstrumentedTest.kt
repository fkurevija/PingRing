package com.fkurevija.pingring

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.ui.screens.HostSetupScreen
import org.junit.Assert.assertEquals
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
                    savedUsername = "Alex",
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
        composeRule.onAllNodesWithText("PingRing Settings").assertCountEquals(2)
        composeRule.onAllNodesWithText("PingRing Settings")[1].performClick()
        composeRule.waitForIdle()

        assertEquals(1, opened)
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
        composeRule.onAllNodesWithText("PingRing Settings").assertCountEquals(2)
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
}
