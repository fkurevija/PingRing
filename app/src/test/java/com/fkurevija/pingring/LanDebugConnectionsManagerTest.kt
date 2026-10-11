package com.fkurevija.pingring

import com.fkurevija.pingring.data.LanDebugConnectionsManager
import com.fkurevija.pingring.data.DecodedPayload
import com.fkurevija.pingring.data.session.InviteSecret
import com.fkurevija.pingring.data.session.JoinProofPayloadDto
import com.fkurevija.pingring.data.session.InviteSecretPayloadDto
import com.fkurevija.pingring.data.session.EventControlPayloadDto
import com.fkurevija.pingring.data.session.MemberDeparturePayloadDto
import com.fkurevija.pingring.data.ranging.MockRangingDto
import com.fkurevija.pingring.data.session.MeshPresencePayloadDto
import com.fkurevija.pingring.data.RelayCodec
import com.fkurevija.pingring.data.RelayEnvelope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.StringWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LanDebugConnectionsManagerTest {

    @Test
    fun wifiPollingIntervalIs250Milliseconds() {
        assertEquals(250L, LanDebugConnectionsManager.RSSI_SAMPLE_INTERVAL_MILLIS)
    }

    @Test
    fun testPendingJoinRequestsCollection() {
        val manager = LanDebugConnectionsManager("127.0.0.1")
        assertEquals(0, manager.pendingJoinRequests.value.size)
    }

    @Test
    fun repeatedProofsWithoutANameKeepTheGuestUsername() {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        val original = manager.pendingJoinRequests.value.single()

        for (name in listOf(null, "", "   ")) {
            deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, name))
            assertEquals(original, manager.pendingJoinRequests.value.single())
        }
    }

    @Test
    fun anUnnamedGuestIsNeverLabelledWithTheirRoutingId() {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null))

        assertEquals("Guest device", manager.pendingJoinRequests.value.single().deviceName)
    }

    @Test
    fun approvingAPeerPublishesThePreservedUsername() = runTest {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null))
        val name = async(start = CoroutineStart.UNDISPATCHED) { manager.peerNames.first() }

        manager.approveJoinRequest(GUEST_ID)

        assertEquals(GUEST_ID to "Ana", name.await())
        assertTrue(manager.pendingJoinRequests.value.isEmpty())
    }

    @Test
    fun hostRemovalAnnouncesDepartureAndTargetsClosureOnlyAtRemovedMember() = runTest {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        @Suppress("UNCHECKED_CAST")
        val authorizedPeers = manager.javaClass.getDeclaredField("authorizedPeers")
            .apply { isAccessible = true }
            .get(manager) as MutableSet<String>
        authorizedPeers.add(GUEST_ID)

        val sent = LinkedBlockingQueue<String>()
        val output = StringWriter()
        setField(manager, "writer", object : BufferedWriter(output) {
            override fun flush() {
                super.flush()
                sent.add(output.toString().trim())
                output.buffer.setLength(0)
            }
        })
        val departure = MemberDeparturePayloadDto(EVENT_ID, GUEST_ID)
        val removalControl = EventControlPayloadDto(
            eventCode = EVENT_ID,
            hostPeerId = "local-id",
            useUwb = false,
            useWifiDirect = true,
            acceptsNewMembers = true,
            isFull = false,
            isClosed = true,
        )

        try {
            manager.removeMember(GUEST_ID, departure, removalControl)

            val departureMessage = decodeSentPayload(requireNotNull(sent.poll(5, TimeUnit.SECONDS)))
            assertEquals(null, departureMessage.first.targetPeerId)
            assertEquals(DecodedPayload.MemberDeparture(departure), departureMessage.second)

            val removalMessage = decodeSentPayload(requireNotNull(sent.poll(5, TimeUnit.SECONDS)))
            assertEquals(GUEST_ID, removalMessage.first.targetPeerId)
            assertEquals(DecodedPayload.EventControl(removalControl), removalMessage.second)
        } finally {
            manager.stopAll()
        }
    }

    @Test
    fun hostRemovalUpdatesLocalSessionWhenGuestIsAlreadyUnauthorized() = runTest {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        val receivedDeparture = async(start = CoroutineStart.UNDISPATCHED) {
            manager.receivedMemberDeparture.first()
        }
        val sent = LinkedBlockingQueue<String>()
        val output = StringWriter()
        setField(manager, "writer", object : BufferedWriter(output) {
            override fun flush() {
                super.flush()
                sent.add(output.toString().trim())
                output.buffer.setLength(0)
            }
        })
        val departure = MemberDeparturePayloadDto(EVENT_ID, GUEST_ID)
        val removalControl = EventControlPayloadDto(
            eventCode = EVENT_ID,
            hostPeerId = "local-id",
            useUwb = false,
            useWifiDirect = true,
            acceptsNewMembers = true,
            isFull = false,
            isClosed = true,
        )

        try {
            manager.removeMember(GUEST_ID, departure, removalControl)

            assertEquals("local removal must not wait for guest authorization", departure, receivedDeparture.await())
            assertEquals(
                DecodedPayload.MemberDeparture(departure),
                decodeSentPayload(requireNotNull(sent.poll(5, TimeUnit.SECONDS))).second,
            )
            assertEquals(
                DecodedPayload.EventControl(removalControl),
                decodeSentPayload(requireNotNull(sent.poll(5, TimeUnit.SECONDS))).second,
            )
        } finally {
            manager.stopAll()
        }
    }

    @Test
    fun memberNamesAreReceivedOnlyFromMatchingAdmittedPeersInTheCurrentEvent() = runTest {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        val received = mutableListOf<MeshPresencePayloadDto>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.receivedMeshPresence.toList(received)
        }
        val presence = MeshPresencePayloadDto(EVENT_ID, GUEST_ID, "Renamed guest")

        deliverPayload(manager, presence, GUEST_ID)
        testScheduler.runCurrent()
        assertTrue(received.isEmpty())

        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        manager.approveJoinRequest(GUEST_ID)
        deliverPayload(manager, presence.copy(peerId = "another-peer"), GUEST_ID)
        deliverPayload(manager, presence.copy(eventCode = "87654321"), GUEST_ID)
        deliverPayload(manager, presence, "another-peer")
        testScheduler.runCurrent()
        assertTrue(received.isEmpty())

        deliverPayload(manager, presence, GUEST_ID)
        testScheduler.runCurrent()
        assertEquals(listOf(presence), received)
        manager.stopAll()
    }

    @Test
    fun usernameBroadcastsTargetOnlyAdmittedPeers() {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        manager.approveJoinRequest(GUEST_ID)
        deliverPayload(manager, JoinProofPayloadDto(EVENT_ID, null, "Waiting"), "waiting-peer")
        val sent = LinkedBlockingQueue<String>()
        val output = StringWriter()
        setField(manager, "writer", object : BufferedWriter(output) {
            override fun flush() {
                super.flush()
                sent.add(output.toString().trim())
                output.buffer.setLength(0)
            }
        })

        try {
            val presence = MeshPresencePayloadDto(EVENT_ID, "local-id", "New host name")
            manager.broadcastMemberPresence(presence)

            val line = sent.poll(5, TimeUnit.SECONDS)
            assertNotNull("Expected a member-presence broadcast", line)
            val outer = RelayCodec.decodeEnvelope(requireNotNull(line)).getOrThrow()
            assertEquals(GUEST_ID, outer.targetPeerId)
            val inner = RelayCodec.decodeEnvelope(requireNotNull(outer.payload)).getOrThrow()
            val decoded = RelayCodec.decode(inner.type, requireNotNull(inner.payload))?.getOrThrow()
            assertTrue(decoded is DecodedPayload.MeshPresence)
            require(decoded is DecodedPayload.MeshPresence)
            assertEquals(presence, decoded.presence)
            assertTrue(sent.isEmpty())
        } finally {
            manager.stopAll()
        }
    }

    @Test
    fun initialAndPeerJoinedProofsBothIncludeTheUsername() {
        val manager = manager(RelayEnvelope.ROLE_MEMBER)
        manager.userNameProvider = { "Ana" }
        val secret = InviteSecret.generate()
        manager.inviteSecretProvider = { secret }
        val sent = LinkedBlockingQueue<String>()
        val output = StringWriter()
        setField(manager, "writer", object : BufferedWriter(output) {
            override fun flush() {
                super.flush()
                sent.add(output.toString().trim())
                output.buffer.setLength(0)
            }
        })

        try {
            val sendProof = manager.javaClass.getDeclaredMethod("sendLocalJoinProof", String::class.java)
            sendProof.isAccessible = true
            sendProof.invoke(manager, null)
            assertProof(sent, null, secret)

            handleLine(manager, RelayEnvelope(
                type = RelayEnvelope.PEER_JOINED,
                eventId = EVENT_ID,
                senderPeerId = "host-id",
            ))
            assertProof(sent, "host-id", secret)
        } finally {
            manager.stopAll()
        }
    }

    private fun assertProof(sent: LinkedBlockingQueue<String>, target: String?, secret: String) {
        val line = sent.poll(5, TimeUnit.SECONDS)
        assertNotNull("Expected a join proof to be sent", line)
        val outer = RelayCodec.decodeEnvelope(requireNotNull(line)).getOrThrow()
        assertEquals(target, outer.targetPeerId)
        val inner = RelayCodec.decodeEnvelope(requireNotNull(outer.payload)).getOrThrow()
        val payload = RelayCodec.decode(inner.type, requireNotNull(inner.payload))?.getOrThrow()
        assertTrue(payload is DecodedPayload.JoinProof)
        require(payload is DecodedPayload.JoinProof)
        assertEquals(JoinProofPayloadDto(EVENT_ID, secret, "Ana"), payload.proof)
    }

    private fun decodeSentPayload(line: String): Pair<RelayEnvelope, DecodedPayload> {
        val outer = RelayCodec.decodeEnvelope(line).getOrThrow()
        val inner = RelayCodec.decodeEnvelope(requireNotNull(outer.payload)).getOrThrow()
        val payload = RelayCodec.decode(inner.type, requireNotNull(inner.payload))?.getOrThrow()
        return outer to requireNotNull(payload)
    }

    private fun manager(role: String): LanDebugConnectionsManager =
        LanDebugConnectionsManager("127.0.0.1").also {
            setField(it, "localRole", role)
            setField(it, "localEventId", EVENT_ID)
            setField(it, "localPeerId", "local-id")
        }

    private fun setField(manager: LanDebugConnectionsManager, name: String, value: Any) {
        if (name == "writer") {
            val lifecycle = manager.javaClass.getDeclaredField("socketLifecycle")
                .apply { isAccessible = true }
                .get(manager)
            lifecycle.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(lifecycle, value)
        } else {
            manager.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
        }
    }

    private fun deliverProof(manager: LanDebugConnectionsManager, proof: JoinProofPayloadDto) {
        deliverPayload(manager, proof, GUEST_ID)
    }

    private fun deliverPayload(manager: LanDebugConnectionsManager, payload: Any, senderId: String) {
        val encoded = requireNotNull(RelayCodec.encode(payload))
        val inner = RelayEnvelope(type = encoded.wireType, payload = encoded.json)
        handleLine(manager, RelayEnvelope(
            type = RelayEnvelope.DATA,
            eventId = EVENT_ID,
            senderPeerId = senderId,
            payload = RelayCodec.encodeEnvelope(inner),
        ))
    }

    private fun handleLine(manager: LanDebugConnectionsManager, envelope: RelayEnvelope) {
        manager.javaClass.getDeclaredMethod("handleLine", String::class.java)
            .apply { isAccessible = true }
            .invoke(manager, RelayCodec.encodeEnvelope(envelope))
    }

    companion object {
        private const val EVENT_ID = "12345678"
        private const val GUEST_ID = "e9f24a1c"
    }
}
