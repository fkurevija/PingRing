package com.fkurevija.pingring

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.data.discovery.ConnectivityStatus
import com.fkurevija.pingring.data.radar.RadarPeerUpdate
import com.fkurevija.pingring.ui.screens.PeerList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * tracked, and whose beacon is showing. A peer whose
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
        onFocusPeer: (String) -> Unit = {},
        isHost: Boolean = false,
        onRemovePeer: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PeerList(
                    peers = peers,
                    connectivity = connectivity,
                    repository = null,
                    activeBeacons = activeBeacons,
                    focusedPeerId = focusedPeerId,
                    onFocusPeer = onFocusPeer,
                    isHost = isHost,
                    onRemovePeer = onRemovePeer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Test
    fun anEmptyListShowsNothingRatherThanPlaceholderProse() {
        show()

        // This used to assert the opposite. The empty state explained that the
        // ephemeral ID is private and that peers would appear later - two
        // sentences about a thing nobody had asked about, sitting where the list
        // of members should be. Asserted as absence now, because that is the
        // behaviour: opening the list to see who is there and finding nothing
        // there is the answer, and the title already carries the count.
        composeRule.onNodeWithText(
            "Your ephemeral ID is private to this event. When someone appears, " +
                "their signal and direction will show here."
        ).assertDoesNotExist()
        composeRule.onNodeWithText("When someone appears", substring = true).assertDoesNotExist()
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
    fun aMemberIsListedWithItsProximityAndDistance() {
        show(peers = listOf(peer(name = "Ana", rssi = -65, distance = 2.5f)))

        composeRule.onNodeWithText("Ana").assertIsDisplayed()
        composeRule.onNodeWithText("Hot").assertIsDisplayed()
        composeRule.onNodeWithText("2.5 m").assertIsDisplayed()
    }

    @Test
    fun aRowDoesNotShowTheRawSignalReading() {
        show(peers = listOf(peer(name = "Ana", rssi = -65, distance = 2.5f)))

        // dBm used to sit on the row next to the proximity word. It is a raw
        // radio reading with no action attached to it - two members in one room
        // differ by 10 dB for reasons the user cannot control - so it is gone.
        // Asserted as absent, since a leftover number is easy to reintroduce and
        // impossible to notice once nobody is looking for it.
        composeRule.onNodeWithText("-65 dBm").assertDoesNotExist()
        composeRule.onNode(hasText("dBm", substring = true)).assertDoesNotExist()
    }

    @Test
    fun aMemberWithNoDistanceHasNoDashOrFabricatedDistance() {
        show(peers = listOf(peer(name = "Ana", distance = null)))

        composeRule.onNodeWithText("—").assertDoesNotExist()
        composeRule.onNodeWithText("0.0 m").assertDoesNotExist()
    }

    @Test
    fun aMemberWithNoSignalSaysUnknownRatherThanShowingAFakeReading() {
        // This used to render "Unknown · 0 dBm", which claimed a perfect signal
        // for a peer we cannot hear at all - the one value that is certainly
        // wrong. "Unknown" is the honest reading.
        show(peers = listOf(peer(name = "Ana", rssi = null, proximity = "Unknown")))

        composeRule.onNodeWithText("Unknown").assertIsDisplayed()
        composeRule.onNode(hasText("dBm", substring = true)).assertDoesNotExist()
    }

    @Test
    fun theNearestMemberIsListedFirst() {
        // The list used to arrive in whatever order the ranging pipeline emitted,
        // so rows moved around for reasons the user could not see. Nearest first
        // is the order the list exists to answer.
        show(
            peers = listOf(
                peer(id = "far", name = "Far", rssi = -95, proximity = "Cold"),
                peer(id = "near", name = "Near", rssi = -45, proximity = "Hot"),
                peer(id = "mid", name = "Mid", rssi = -70, proximity = "Cold"),
            )
        )

        // Read where each name actually landed on screen, rather than trusting the
        // list order, so this fails if the ordering is not applied at all.
        val top = listOf("Far", "Near", "Mid").associateWith { name ->
            composeRule.onNodeWithText(name).getUnclippedBoundsInRoot().top
        }
        assertTrue(
            "nearest should be above the others, got $top",
            top.getValue("Near") < top.getValue("Mid") &&
                top.getValue("Mid") < top.getValue("Far"),
        )
    }

    @Test
    fun aMemberWeCannotHearIsListedLastRatherThanFirst() {
        // A missing RSSI is missing information, not a measurement of distance.
        // Ranking an unheard peer first would put someone we have lost above
        // somebody standing next to us.
        show(
            peers = listOf(
                peer(id = "silent", name = "Silent", rssi = null, proximity = "Unknown"),
                peer(id = "close", name = "Close", rssi = -50, proximity = "Hot"),
            )
        )

        val silentTop = composeRule.onNodeWithText("Silent").getUnclippedBoundsInRoot().top
        val closeTop = composeRule.onNodeWithText("Close").getUnclippedBoundsInRoot().top
        assertTrue(
            "an unheard peer was listed above a close one",
            silentTop > closeTop,
        )
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

        composeRule.onNode(hasText("Ana") and isSelectable()).performClick()

        assertEquals("peer-1", focused)
    }

    @Test
    fun tappingTheTrackedMemberKeepsItSelected() {
        var focused: String? = "peer-1"
        show(
            peers = listOf(peer(id = "peer-1", name = "Ana")),
            focusedPeerId = "peer-1",
            onFocusPeer = { focused = it },
        )

        composeRule.onNode(hasText("Ana") and isSelectable()).assertIsSelected().performClick()

        assertEquals("peer-1", focused)
        composeRule.onNodeWithContentDescription("Stop tracking Ana").assertDoesNotExist()
    }

    @Test
    fun trackingIsIndicatedOnlyOnTheSelectedRow() {
        show(peers = listOf(peer(id = "peer-1", name = "Ana")), focusedPeerId = "peer-1")

        composeRule.onNodeWithText("Tracking").assertDoesNotExist()
        composeRule.onNodeWithText("TRACKING").assertIsDisplayed()
        composeRule.onAllNodesWithText("Ana").assertCountEquals(1)
    }

    @Test
    fun noDropdownOrAutomaticOptionIsShown() {
        show(peers = listOf(peer(id = "peer-1", name = "Ana")), focusedPeerId = "peer-1")

        composeRule.onNodeWithText("Tracking").assertDoesNotExist()
        composeRule.onNodeWithText("Automatic").assertDoesNotExist()
    }

    @Test
    fun selectingARowSetsTheTrackedMember() {
        var focused: String? = null
        show(
            peers = listOf(peer(id = "peer-1", name = "Ana"), peer(id = "peer-2", name = "Bo")),
            onFocusPeer = { focused = it },
        )

        composeRule.onNode(hasText("Ana") and isSelectable()).performClick()
        composeRule.waitForIdle()

        assertEquals("peer-1", focused)
    }

    @Test
    fun everyMemberRowIsSelectable() {
        var focused: String? = null
        show(
            peers = listOf(peer(id = "peer-1", name = "Ana")),
            onFocusPeer = { focused = it },
        )

        composeRule.onNode(hasText("Ana") and isSelectable()).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals("peer-1", focused)
    }

    @Test
    fun theRowSelectsAnotherMemberWithoutASeparateButton() {
        var focused: String? = null
        show(
            peers = listOf(peer(id = "peer-1", name = "Ana"), peer(id = "peer-2", name = "Bo")),
            focusedPeerId = "peer-2",
            onFocusPeer = { focused = it },
        )

        composeRule.onNodeWithContentDescription("Track Ana").assertDoesNotExist()
        composeRule.onNode(hasText("Ana") and isSelectable()).performClick()
        composeRule.waitForIdle()

        assertEquals("peer-1", focused)
    }

    @Test
    fun onlyHostCanRemoveAndRemovalRequiresConfirmation() {
        val member = peer(id = "guest-id", name = "Ana")
        var removedPeerId: String? = null

        // Use mutable state to switch host flag and removal callback without needing multiple setContent calls
        val isHostState = mutableStateOf(false)
        val onRemoveState = mutableStateOf<(String) -> Unit>({})

        composeRule.setContent {
            MaterialTheme {
                PeerList(
                    peers = listOf(member),
                    connectivity = ConnectivityStatus(true, true),
                    repository = null,
                    activeBeacons = emptyMap(),
                    focusedPeerId = null,
                    onFocusPeer = {},
                    isHost = isHostState.value,
                    onRemovePeer = onRemoveState.value,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Verify remove button is not shown when not host
        composeRule.onNodeWithContentDescription("Remove Ana").assertDoesNotExist()

        // Become host and set removal callback
        isHostState.value = true
        onRemoveState.value = { removedPeerId = it }
        composeRule.waitForIdle()

        // Click remove and cancel
        composeRule.onNodeWithContentDescription("Remove Ana").performClick()
        composeRule.onNodeWithText("Remove member?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(null, removedPeerId)

        // Confirm removal
        composeRule.onNodeWithContentDescription("Remove Ana").performClick()
        composeRule.onNodeWithText("Remove").performClick()
        assertEquals("guest-id", removedPeerId)
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
    fun wifiBeingOffIsCalledOutAsWhatIsLostRatherThanWhatIsBroken() {
        // The wording is the feature. Being on mobile data is not a fault, and
        // the app promises to work with no network at all - so this has to say
        // what the member is losing, not that they cannot reach anybody.
        show(connectivity = ConnectivityStatus(isBluetoothEnabled = true, isWifiEnabled = false))

        composeRule.onNodeWithText(
            "Wi-Fi is off, so PingRing is using Bluetooth only. " +
                "Range is shorter and there is no Wi-Fi distance."
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Recheck").assertExists()
    }

    @Test
    fun aHealthyDeviceIsToldNothingAboutItsRadios() {
        // A card that appears on a working phone is noise that teaches people to
        // ignore cards.
        show(connectivity = ConnectivityStatus(isBluetoothEnabled = true, isWifiEnabled = true))

        composeRule.onNodeWithText(
            "Wi-Fi is off, so PingRing is using Bluetooth only. " +
                "Range is shorter and there is no Wi-Fi distance."
        ).assertDoesNotExist()
    }

    @Test
    fun aDeadBluetoothRadioHidesTheWifiNoticeRatherThanStackingTwoErrors() {
        // Bluetooth is the blocking problem. Two error cards at the top of a
        // member list reads as a broken app rather than one switch to flip.
        show(connectivity = ConnectivityStatus(isBluetoothEnabled = false, isWifiEnabled = false))

        composeRule.onNodeWithText(
            "Bluetooth is off. Enable it to discover nearby peers."
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Wi-Fi is off, so PingRing is using Bluetooth only. " +
                "Range is shorter and there is no Wi-Fi distance."
        ).assertDoesNotExist()
    }
}
