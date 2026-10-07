package com.fkurevija.pingring

import com.fkurevija.pingring.data.LanDebugConnectionsManager
import com.fkurevija.pingring.data.DecodedPayload
import com.fkurevija.pingring.data.InviteSecret
import com.fkurevija.pingring.data.JoinProofPayloadDto
import com.fkurevija.pingring.data.InviteSecretPayloadDto
import com.fkurevija.pingring.data.EventControlPayloadDto
import com.fkurevija.pingring.data.MemberDeparturePayloadDto
import com.fkurevija.pingring.data.MockRangingDto
import com.fkurevija.pingring.data.MeshPresencePayloadDto
import com.fkurevija.pingring.data.RssiSmoother
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
    fun wifiPollingIntervalIsOneSecond() {
        assertEquals(1_000L, LanDebugConnectionsManager.RSSI_SAMPLE_INTERVAL_MILLIS)
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

    @Test
    fun wifiRssiDrivesOnlyAdmittedLocalGuestsWithoutANameOrDistanceOverride() = runTest {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        val reports = mutableListOf<MockRangingDto>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.receivedMockRanging.toList(reports)
        }
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        manager.publishWifiRssi(-60)
        testScheduler.runCurrent()
        assertTrue(reports.isEmpty())

        manager.approveJoinRequest(GUEST_ID)
        manager.publishWifiRssi(-73)
        testScheduler.runCurrent()

        assertEquals(listOf(MockRangingDto(peerId = GUEST_ID, rssi = -59)), reports)
    }

    @Test
    fun aGuestUsesLocalWifiRssiForItsAdmittedHost() = runTest {
        val manager = manager(RelayEnvelope.ROLE_MEMBER)
        deliverPayload(manager, InviteSecretPayloadDto(EVENT_ID, InviteSecret.generate(), "host-id"), "host-id")
        val report = async(start = CoroutineStart.UNDISPATCHED) { manager.receivedMockRanging.first() }

        manager.publishWifiRssi(-81)

        assertEquals(MockRangingDto(peerId = "host-id", rssi = -59), report.await())
    }

    @Test
    fun theSameWifiProxyIsAppliedToAllConnectedPeersButNotTheLocalDevice() = runTest {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        for (peerId in listOf(GUEST_ID, "second-id", "local-id")) {
            deliverPayload(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"), peerId)
            manager.approveJoinRequest(peerId)
        }
        val reports = mutableListOf<MockRangingDto>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.receivedMockRanging.toList(reports)
        }

        manager.publishWifiRssi(-72)
        testScheduler.runCurrent()

        assertEquals(
            setOf(MockRangingDto(peerId = GUEST_ID, rssi = -59), MockRangingDto(peerId = "second-id", rssi = -59)),
            reports.toSet(),
        )
        assertEquals(2, reports.size)
    }

    @Test
    fun missingOrInvalidWifiRssiNeverProducesRandomSamplesAndCanRecover() = runTest {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        manager.approveJoinRequest(GUEST_ID)
        val reports = mutableListOf<MockRangingDto>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.receivedMockRanging.toList(reports)
        }

        listOf(null, -127, -101, -19, 0).forEach(manager::publishWifiRssi)
        testScheduler.runCurrent()
        assertTrue(reports.isEmpty())
        manager.publishWifiRssi(-65)
        testScheduler.runCurrent()
        assertEquals(listOf(MockRangingDto(peerId = GUEST_ID, rssi = -59)), reports)
    }

    @Test
    fun scriptsTakePrecedenceAndDisconnectedPeersStopReceivingSamples() = runTest {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        manager.approveJoinRequest(GUEST_ID)
        val reports = mutableListOf<MockRangingDto>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.receivedMockRanging.toList(reports)
        }
        val script = MockRangingDto(peerId = GUEST_ID, distance = 3f, rssi = -70)
        val scriptEnvelope = RelayEnvelope(
            type = RelayCodec.MOCK_RANGING,
            payload = """{"peerId":"$GUEST_ID","distance":3.0,"rssi":-70}""",
        )
        handleLine(manager, RelayEnvelope(
            type = RelayEnvelope.DATA,
            eventId = EVENT_ID,
            senderPeerId = "script",
            payload = RelayCodec.encodeEnvelope(scriptEnvelope),
        ))
        manager.publishWifiRssi(-50)
        testScheduler.runCurrent()
        assertEquals(listOf(script), reports)

        handleLine(manager, RelayEnvelope(type = RelayEnvelope.PEER_LEFT, senderPeerId = GUEST_ID))
        manager.publishWifiRssi(-60)
        testScheduler.runCurrent()
        assertEquals(listOf(script), reports)

        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        manager.approveJoinRequest(GUEST_ID)
        manager.publishWifiRssi(-61)
        testScheduler.runCurrent()
        assertEquals(MockRangingDto(peerId = GUEST_ID, rssi = -59), reports.last())

        manager.stopAll()
        manager.publishWifiRssi(-62)
        testScheduler.runCurrent()
        assertEquals(2, reports.size)
    }

    @Test
    fun strongWifiStartsAtOneMeterAndDistanceChangesAsSignalWeakens() = runTest {
        val manager = manager(RelayEnvelope.ROLE_HOST)
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        manager.approveJoinRequest(GUEST_ID)
        val reports = mutableListOf<MockRangingDto>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.receivedMockRanging.toList(reports)
        }

        for (rssi in listOf(-41, null, -127, -47, -52, -41, -35)) {
            manager.publishWifiRssi(rssi)
            testScheduler.runCurrent()
        }

        assertEquals(listOf(-59, -65, -70, -59, -53), reports.map { it.rssi })
        val smoother = RssiSmoother()
        val distances = reports.mapIndexed { index, report ->
            requireNotNull(smoother.add(requireNotNull(report.rssi), index * 2_500L))
        }
        assertEquals(1f, distances[0], 0.01f)
        assertTrue(distances[1] > 1.8f)
        assertTrue(distances[2] > 3.1f)
        assertEquals(1f, distances[3], 0.01f)
        assertTrue(distances[4] in 0.5f..0.6f)
        assertTrue(reports.all { it.distance == null && it.azimuth == null && it.name == null })

        handleLine(manager, RelayEnvelope(type = RelayEnvelope.PEER_LEFT, senderPeerId = GUEST_ID))
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        manager.approveJoinRequest(GUEST_ID)
        manager.publishWifiRssi(-52)
        testScheduler.runCurrent()
        assertEquals(-59, reports.last().rssi)

        manager.stopAll()
        deliverProof(manager, JoinProofPayloadDto(EVENT_ID, null, "Ana"))
        manager.approveJoinRequest(GUEST_ID)
        manager.publishWifiRssi(-35)
        testScheduler.runCurrent()
        assertEquals(-59, reports.last().rssi)
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
        manager.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
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
