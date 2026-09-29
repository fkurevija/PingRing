package com.fkurevija.pingring

import com.fkurevija.pingring.data.BeaconPayloadDto
import com.fkurevija.pingring.data.ChatHistoryPayloadDto
import com.fkurevija.pingring.data.ChatPayloadDto
import com.fkurevija.pingring.data.MockRangingDto
import com.fkurevija.pingring.data.NudgePayloadDto
import com.fkurevija.pingring.data.TransportEvents
import com.fkurevija.pingring.data.UwbControllerParametersDto
import com.fkurevija.pingring.data.UwbPeerAddressDto
import com.fkurevija.pingring.data.UwbRangingOfferDto
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

/**
 * Every transport publishes the same flows with the same buffer sizes, because
 * [com.fkurevija.pingring.data.DiscoveryRepository] consumes one contract and
 * would not otherwise be able to tell a deeper buffer from a shallower one -
 * only that a message it expected never arrived.
 *
 * These tests pin that contract: each [TransportEvents] callback lands on the
 * flow named after it, the one replayed flow survives a late subscriber, and the
 * others deliberately do not.
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
     * Every flow here has `replay = 0` except `connectionEstablished`, so an
     * emission made with nobody listening is dropped outright rather than
     * buffered - which is the behaviour the repository relies on for chat, where
     * a message nobody has collected yet is genuinely not wanted. UNDISPATCHED
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
    fun aNudgeArrivesOnTheNudgeFlow() = runTest {
        val events = TransportEvents("test")
        val nudge = NudgePayloadDto("n1", "peer-b", "Bob", null, 1L)
        val received = nextEmission(events.receivedNudge)

        events.onNudge(nudge)

        assertEquals(nudge, received.await())
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
    fun chatHistoryArrivesOnTheHistoryFlow() = runTest {
        val events = TransportEvents("test")
        val history = ChatHistoryPayloadDto(listOf(chat))
        val received = nextEmission(events.receivedChatHistory)

        events.onChatHistory(history)

        assertEquals(history, received.await())
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