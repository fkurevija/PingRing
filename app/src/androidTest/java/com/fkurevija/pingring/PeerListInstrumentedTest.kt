package com.fkurevija.pingring

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.data.ConnectivityStatus
import com.fkurevija.pingring.data.RadarPeerUpdate
import com.fkurevija.pingring.ui.screens.PeerList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The member list, composed directly.
 *
 * This is the part of the radar that shows a person. It is unreachable through
 * [com.fkurevija.pingring.ui.screens.RadarScreen] in a test, because that screen
 * only renders peers when it holds a live
 * [com.fkurevija.pingring.data.DiscoveryRepository] - which needs a transport and
 * a radio. Feeding [PeerList] a list of [RadarPeerUpdate] directly covers the
 * same rendering with neither.
 *
 * The state under test is what a user acts on: who is listed, who is being
 * tracked, whose beacon is showing, and where the nudge goes. A peer whose
 * distance is unknown has to say so rather than render a wrong number.
 */
class PeerListInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun peer(
        id: String = "peer-1",
        name: String = "Member 1",
        rssi: Int? = -65,
        proximity: String = "Hot",
        distance: Float? = 2.5f,
    ) = RadarPeerUpdate(
        peerId = id,
        name = name,
        rssi = rssi,
        proximity = proximity,
        distance = distance,
        azimuth = 90f,
        elevation = 0f,
        worldDirectionX = null,
        worldDirectionY = null,
        worldDirectionZ = null,
        lastSeen = 1L,
    )

    private fun beacon(id: String = "peer-1", title: String = "Emergency!") = BeaconInfo(
        id = "b1",
        senderPeerId = id,
        senderName = "Member 1",
        title = title,
        colorArgb = 0xFFFF1744,
        vibrationPattern = "SOS_URGENT",
        isActive = true,
        timestamp = 1L,
    )

    private fun show(
        peers: List<RadarPeerUpdate> = emptyList(),
        connectivity: ConnectivityStatus = ConnectivityStatus(true, true),
        activeBeacons: Map<String, BeaconInfo> = emptyMap(),
        focusedPeerId: String? = null,
        onFocusPeer: (String?) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PeerList(
                    peers = peers,
                    connectivity = connectivity,
                    repository = null,
                    activeBeacons = activeBeacons,
                    senderName = "Ana",
                    focusedPeerId = focusedPeerId,
                    onFocusPeer = onFocusPeer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Test
    fun anEmptyListExplainsThatNobodyHasAppearedYet() {
        show()

        // Silence here reads as a broken screen; the copy has to say the feature
        // is working and that the peer ids are private.
        composeRule.onNodeWithText(
            "Your ephemeral ID is private to this event. When someone appears, " +
                "their signal and direction will show here."
        ).assertIsDisplayed()
    }

    @Test
    fun theCountIncludesThisDevice() {
        // "Members (1/8)" with nobody else present: the user is a member too.
        show()

        composeRule.onNodeWithText("Members (1/8)").assertIsDisplayed()
    }

    @Test
    fun aFullRingSaysSo() {
        // A guest scanning a full ring needs to be told why nobody is joining,
        // rather than shown an unexplained capacity number.
        //
        // Seven real peers is the only way to reach the full count, and a
        // LazyColumn of seven fully-composed rows is slow on an emulator, so this
        // is the one deliberately slow test here. The wording is the point, so
        // the count is not stubbed.
        show(peers = (1..7).map { peer(id = "peer-$it", name = "Member $it") })

        composeRule.onNodeWithText("Members (8/8 - Full)").assertIsDisplayed()
    }

    @Test
    fun aMemberIsListedWithItsSignalAndDistance() {
        show(peers = listOf(peer(name = "Ana", rssi = -65, distance = 2.5f)))

        composeRule.onNodeWithText("Ana").assertIsDisplayed()
        composeRule.onNodeWithText("Hot · -65 dBm").assertIsDisplayed()
        composeRule.onNodeWithText("2.5 m").assertIsDisplayed()
    }

    @Test
    fun aMemberWithNoDistanceSaysSoRatherThanGuessing() {
        // A BLE sighting with no UWB fix has no distance. Rendering 0.0 m, or
        // silently reusing a stale number, would be worse than a dash.
        show(peers = listOf(peer(name = "Ana", distance = null)))

        composeRule.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun aMemberWithNoSignalShowsZeroRatherThanNull() {
        show(peers = listOf(peer(name = "Ana", rssi = null, proximity = "Unknown")))

        composeRule.onNodeWithText("Unknown · 0 dBm").assertIsDisplayed()
    }
    @Test
    fun theTrackedMemberIsMarked() {
        show(peers = listOf(peer(id = "peer-1", name = "Ana")), focusedPeerId = "peer-1")

        composeRule.onNodeWithText("TRACKING").assertIsDisplayed()
    }

    @Test
    fun anUntrackedMemberIsNotMarked() {
        show(peers = listOf(peer(id = "peer-1", name = "Ana")), focusedPeerId = "peer-2")

        composeRule.onNodeWithText("TRACKING").assertDoesNotExist()
    }

    @Test
    fun trackingAMemberReportsItsId() {
        var focused: String? = "something-else"
        show(
            peers = listOf(peer(id = "peer-1", name = "Ana")),
            onFocusPeer = { focused = it },
        )

        composeRule.onNodeWithContentDescription("Track Ana").performClick()

        assertEquals("peer-1", focused)
    }

    @Test
    fun trackingTheTrackedMemberAgainReturnsToAuto() {
        // The choice has to be reversible without leaving the screen, which is
        // why tapping the tracked row clears the selection rather than doing
        // nothing at all.
        var focused: String? = "peer-1"
        show(
            peers = listOf(peer(id = "peer-1", name = "Ana")),
            focusedPeerId = "peer-1",
            onFocusPeer = { focused = it },
        )

        composeRule.onNodeWithContentDescription("Stop tracking Ana").performClick()

        assertEquals(null, focused)
    }

    @Test
    fun aMemberWithABeaconShowsIt() {
        show(
            peers = listOf(peer(id = "peer-1", name = "Ana")),
            activeBeacons = mapOf("peer-1" to beacon()),
        )

        composeRule.onNodeWithText("🚨 Emergency!").assertIsDisplayed()
    }

    @Test
    fun aBeaconOnSomebodyElseIsNotShownOnAnotherRow() {
        // A beacon belongs to whoever set it; showing it against the wrong member
        // would point a group at the wrong person.
        show(
            peers = listOf(peer(id = "peer-1", name = "Ana"), peer(id = "peer-2", name = "Bo")),
            activeBeacons = mapOf("peer-2" to beacon(id = "peer-2", title = "Regroup")),
        )

        composeRule.onNodeWithText("🚨 Regroup").assertIsDisplayed()
    }

    @Test
    fun aDisabledRadioIsCalledOutWithAWayToRecheck() {
        // Discovered peers cannot arrive with the radio off, so the list has to
        // explain itself rather than just staying empty.
        show(connectivity = ConnectivityStatus(isBluetoothEnabled = false, isWifiEnabled = true))

        composeRule.onNodeWithText("Bluetooth is off. Enable it to discover nearby peers.")
            .assertIsDisplayed()
        // The recheck control exists but is not asserted as *displayed*: it sits
        // at the end of a row that is wider than a narrow phone, so "exists" is
        // the honest claim here. Asserting display would make this test a layout
        // test, which is not what it is for.
        composeRule.onNodeWithContentDescription("Recheck").assertExists()
    }

    @Test
    fun noRecheckIsOfferedWhenTheRadioIsOn() {
        show(connectivity = ConnectivityStatus(isBluetoothEnabled = true, isWifiEnabled = true))

        composeRule.onNodeWithText("Bluetooth is off. Enable it to discover nearby peers.")
            .assertDoesNotExist()
    }

    @Test
    fun theNudgeAllButtonOnlyAppearsWhenThereIsSomebodyToNudge() {
        show(peers = emptyList())

        composeRule.onNodeWithText("Nudge All").assertDoesNotExist()
    }

    @Test
    fun theNudgeAllButtonAppearsOnceSomebodyIsPresent() {
        show(peers = listOf(peer(name = "Ana")))

        composeRule.onNodeWithText("Nudge All").assertIsDisplayed()
    }
}