package com.fkurevija.pingring

import com.fkurevija.pingring.data.MeshDuplicateCache
import com.fkurevija.pingring.data.MeshPendingMessageQueue
import com.fkurevija.pingring.data.MeshRoutingPolicy
import com.fkurevija.pingring.data.RelayCodec
import com.fkurevija.pingring.data.RelayEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRoutingPolicyTest {
    @Test
    fun meshPathIsBoundedByTheMaximumRingSize() {
        assertEquals(7, MeshRoutingPolicy.MAX_HOPS)
        assertEquals(1, MeshRoutingPolicy.nextHopCount(0))
        assertEquals(7, MeshRoutingPolicy.nextHopCount(6))
        assertEquals(null, MeshRoutingPolicy.nextHopCount(7))
        assertEquals(null, MeshRoutingPolicy.nextHopCount(-1))
    }

    @Test
    fun onlyGroupTrafficMayBeForwarded() {
        assertTrue(MeshRoutingPolicy.isForwardable(RelayCodec.CHAT))
        assertTrue(MeshRoutingPolicy.isForwardable(RelayCodec.BEACON))
        assertTrue(MeshRoutingPolicy.isForwardable(RelayCodec.UWB_OFFER))
        assertTrue(MeshRoutingPolicy.isForwardable(RelayCodec.MESH_PRESENCE))
        assertTrue(MeshRoutingPolicy.isForwardable(RelayCodec.PEER_RANGING))
        assertFalse(MeshRoutingPolicy.isForwardable(RelayCodec.JOIN_PROOF))
        assertFalse(MeshRoutingPolicy.isForwardable(RelayCodec.INVITE_SECRET))
        assertFalse(MeshRoutingPolicy.isForwardable(RelayCodec.MESH_PEER_AUTHENTICATION))
    }

    @Test
    fun targetedPayloadsAreDeliveredOnlyAtTheirDestination() {
        assertTrue(MeshRoutingPolicy.shouldDeliver(null, "peer-a"))
        assertTrue(MeshRoutingPolicy.shouldDeliver("peer-a", "peer-a"))
        assertFalse(MeshRoutingPolicy.shouldDeliver("peer-b", "peer-a"))
    }

    @Test
    fun staleLocationAndRadioAddressesMustNeverEnterThePartitionQueue() {
        assertTrue(MeshRoutingPolicy.isEphemeral(RelayCodec.PEER_RANGING))
        assertTrue(MeshRoutingPolicy.isEphemeral(RelayCodec.MESH_PRESENCE))
        assertFalse(MeshRoutingPolicy.isEphemeral(RelayCodec.CHAT))
        assertFalse(MeshRoutingPolicy.isEphemeral(RelayCodec.EVENT_CONTROL))
    }

    @Test
    fun duplicateMessageIdsAreSuppressedAndCacheRemainsBounded() {
        val cache = MeshDuplicateCache(capacity = 2)

        assertTrue(cache.markIfNew("m1"))
        assertFalse(cache.markIfNew("m1"))
        assertTrue(cache.markIfNew("m2"))
        assertTrue(cache.markIfNew("m3"))
        assertTrue(cache.markIfNew("m1"))
        assertFalse(cache.markIfNew(" "))
    }

    @Test
    fun duplicateCacheClearsAtRingEnd() {
        val cache = MeshDuplicateCache()
        assertTrue(cache.markIfNew("event-a/message-1"))

        cache.clear()

        assertTrue(cache.markIfNew("event-a/message-1"))
    }

    @Test
    fun discoveryConnectsGuestsToHostOrAdmittedPeersButNeverAdmitsThroughRelays() {
        assertTrue(MeshRoutingPolicy.shouldConnectToDiscoveredPeer("guest-a", "host", false, "host", false))
        assertTrue(MeshRoutingPolicy.shouldConnectToDiscoveredPeer("guest-a", "guest-b", false, "host", true))
        assertFalse(MeshRoutingPolicy.shouldConnectToDiscoveredPeer("guest-a", "unknown", false, "host", false))
        assertFalse(MeshRoutingPolicy.shouldConnectToDiscoveredPeer("host", "guest-b", true, "host", true))
        assertTrue(MeshRoutingPolicy.shouldConnectToDiscoveredPeer("guest-b", "host", false, "host", true))
        assertTrue(MeshRoutingPolicy.shouldConnectToDiscoveredPeer("guest-a", "host", false, "host", false))
        assertFalse(MeshRoutingPolicy.shouldConnectToDiscoveredPeer("host", "unknown", true, "host", false))
        assertFalse(MeshRoutingPolicy.shouldConnectToDiscoveredPeer("guest-a", "guest-a", false, "host", true))
    }

    @Test
    fun pendingMessagesAreDeduplicatedBoundedAndReplayInQueueOrder() {
        val queue = MeshPendingMessageQueue(capacity = 2, maxBytes = 1_024)
        val first = RelayEnvelope(type = "chat", messageId = "one", payload = "first")
        val second = RelayEnvelope(type = "chat", messageId = "two", payload = "second")
        val third = RelayEnvelope(type = "chat", messageId = "three", payload = "third")

        assertTrue(queue.enqueue(first))
        assertTrue(queue.enqueue(first))
        assertTrue(queue.enqueue(second))
        assertTrue(queue.enqueue(third))

        assertEquals(listOf("two", "three"), queue.snapshot().map { it.messageId })
        queue.remove("two")
        assertEquals(listOf("three"), queue.snapshot().map { it.messageId })
        assertTrue(queue.enqueue(first))
        assertEquals(listOf("three", "one"), queue.snapshot().map { it.messageId })
        queue.clear()
        assertTrue(queue.snapshot().isEmpty())
    }
}