package com.fkurevija.pingring

import com.fkurevija.pingring.data.chat.BeaconPayloadDto
import com.fkurevija.pingring.data.chat.ChatHistoryPayloadDto
import com.fkurevija.pingring.data.chat.ChatHistoryRequestPayloadDto
import com.fkurevija.pingring.data.chat.ChatPayloadDto
import com.fkurevija.pingring.data.ranging.MockRangingDto
import com.fkurevija.pingring.data.session.MemberDeparturePayloadDto
import com.fkurevija.pingring.data.session.MeshPresencePayloadDto
import com.fkurevija.pingring.data.TransportEvents
import com.fkurevija.pingring.data.ranging.UwbControllerParametersDto
import com.fkurevija.pingring.data.ranging.UwbPeerAddressDto
import com.fkurevija.pingring.data.ranging.UwbRangingOfferDto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.fkurevija.pingring.data.DiscoveryRepository

/**
 * Every transport publishes the same flows with the same buffer sizes, because
 * [com.fkurevija.pingring.data.DiscoveryRepository] consumes one contract and
 * would not otherwise be able to tell a deeper buffer from a shallower one -
 * only that a message it expected never arrived.
 *
 * These tests pin that contract: each [TransportEvents] callback lands on the
 * flow named after it. Connection establishment and history requests survive a
 * late subscriber; transient updates deliberately do not.
 *
 * Plain JUnit, not Robolectric: the only Android call in this path is
 * `android.util.Log.i`, and `isReturnDefaultValues` makes that a no-op, so the
 * flows themselves are exercised for real.
 */
class TransportEventsTest {

    private val chat = ChatPayloadDto(
        id = "m1",
        senderPeerId = "peer-b",
        senderName = "Bob",
        text = "hi",
        timestamp = 1_700_000_000_000L
    )

    /**
     * Subscribes before the caller emits.
     *
     * Most flows have `replay = 0`, so an emission made with nobody listening
     * is dropped outright rather than buffered. History requests are replayed
     * because they must survive startup racing their collector. UNDISPATCHED
     * matters: it runs the collector up to its first suspension point
     * immediately, so the subscription is in place before the emit below rather
     * than after the test coroutine next yields.
     */
    private fun <T> TestScope.nextEmission(flow: SharedFlow<T>): Deferred<T> =
        backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { flow.first() }

    @Test
    fun aChatMessageArrivesOnTheChatFlow() = runTest {
        val events = TransportEvents("test")
        val received = nextEmission(events.receivedChatMessage)

        events.onChatMessage(chat)

        assertEquals(chat, received.await())
    }

    @Test
    fun aBeaconArrivesOnTheBeaconFlow() = runTest {
        val events = TransportEvents("test")
        val beacon = BeaconPayloadDto("b1", "peer-b", "Bob", "Emergency!", 0xFFFF1744, "SOS_URGENT", true, 1L)
        val received = nextEmission(events.receivedBeacon)

        events.onBeacon(beacon)

        assertEquals(beacon, received.await())
    }

    @Test
    fun aConfirmedMemberDepartureArrivesOnItsFlow() = runTest {
        val events = TransportEvents("test")
        val departure = MemberDeparturePayloadDto("4C1A9B", "a1b2c3d4")
        val received = nextEmission(events.receivedMemberDeparture)

        events.onMemberDeparture(departure)

        assertEquals(departure, received.await())
    }

    @Test
    fun signedMeshPresenceArrivesOnItsFlow() = runTest {
        val events = TransportEvents("test")
        val presence = MeshPresencePayloadDto("4C1A9B", "a1b2c3d4")
        val received = nextEmission(events.receivedMeshPresence)

        events.onMeshPresence(presence)

        assertEquals(presence, received.await())
    }

    @Test
    fun chatHistoryArrivesOnTheHistoryFlow() = runTest {
        val events = TransportEvents("test")
        val history = ChatHistoryPayloadDto(listOf(chat))
        val received = nextEmission(events.receivedChatHistory)

        events.onChatHistory(history)

        assertEquals(history, received.await())
    }

    @Test
    fun chatHistoryRequestsAreRetainedUntilTheHostCollectorStarts() = runTest {
        val events = TransportEvents("test")
        val request = ChatHistoryRequestPayloadDto("event-1", "peer-b")

        events.onChatHistoryRequest(request)

        assertEquals(request, events.receivedChatHistoryRequest.first())
    }

    @Test
    fun aPeerAddressArrivesOnTheAddressFlow() = runTest {
        val events = TransportEvents("test")
        val address = UwbPeerAddressDto("peer-b", "AA:BB")
        val received = nextEmission(events.receivedPeerAddress)

        events.onUwbAddress(address)

        assertEquals(address, received.await())
    }

    @Test
    fun controllerParametersArriveOnTheParametersFlow() = runTest {
        val events = TransportEvents("test")
        val params = UwbControllerParametersDto("peer-a", "CC:DD", 9, 2, 7, "key")
        val received = nextEmission(events.receivedControllerParams)

        events.onUwbParameters(params)

        assertEquals(params, received.await())
    }
    @Test
    fun aRangingOfferArrivesOnTheOfferFlow() = runTest {
        val events = TransportEvents("test")
        val offer = UwbRangingOfferDto("peer-b", "peer-c", "AA:BB", 9, 2, 7, "key")
        val received = nextEmission(events.receivedUwbRangingOffer)

        events.onUwbRangingOfferReceived(offer)

        assertEquals(offer, received.await())
    }

    @Test
    fun aScriptedRangeReportArrivesOnTheMockRangingFlow() = runTest {
        val events = TransportEvents("test")
        val report = MockRangingDto("peer-b", distance = 2.5f, azimuth = 90f)
        val received = nextEmission(events.receivedMockRanging)

        events.onMockRanging(report)

        assertEquals(report, received.await())
    }

    @Test
    fun aConnectionIsReplayedToASubscriberThatArrivesLate() = runTest {
        val events = TransportEvents("test")

        // The one flow with replay = 1. DiscoveryRepository builds the transport
        // and may subscribe a moment after a peer has already connected, and a
        // member that silently never reaches the radar is exactly the failure the
        // replay exists to prevent.
        events.onPeerConnected("peer-b")

        assertEquals("peer-b", events.connectionEstablished.first())
    }

    @Test
    fun aDisconnectIsNotReplayed() = runTest {
        val events = TransportEvents("test")

        // Deliberately asymmetric with connectionEstablished: a peer that has
        // already gone must not be re-reported as dropping to whoever subscribes
        // next, or the radar would resurrect a member that left. withTimeout is
        // virtual-time under runTest, so the "never arrives" case costs no wall
        // clock and still fails loudly if an envelope ever does show up.
        events.onPeerDisconnected("peer-c")

        assertNull(runCatching { withTimeout(500) { events.peerDisconnected.first() } }.getOrNull())
    }

    @Test
    fun theHostClosingTheEventIsAnnouncedOnItsOwnFlow() = runTest {
        val events = TransportEvents("test")
        val received = nextEmission(events.eventClosed)

        events.onEventClosed()

        received.await()
    }

    @Test
    fun aChatMessageEmittedWithNoSubscriberIsNotDeliveredLater() = runTest {
        val events = TransportEvents("test")

        events.onChatMessage(chat)

        // Documents why the other tests subscribe first: chat is not replayed, so
        // a message that arrives before the repository is listening is gone.
        assertNull(runCatching { withTimeout(500) { events.receivedChatMessage.first() } }.getOrNull())
    }

    @Test
    fun theChatBufferDeliversEveryMessageUpToItsCapacity() = runTest {
        val events = TransportEvents("test")
        val seen = mutableListOf<ChatPayloadDto>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.receivedChatMessage.collect { seen += it }
        }

        // Documented capacity is 10; nothing may be dropped below it.
        repeat(10) { index -> events.onChatMessage(chat.copy(id = "m$index")) }
        // tryEmit hands the value to the collector coroutine rather than running
        // it, so the scheduler has to turn for the messages to be recorded.
        runCurrent()

        assertEquals(10, seen.size)
        assertEquals("m0", seen.first().id)
        assertEquals("m9", seen.last().id)
    }
}