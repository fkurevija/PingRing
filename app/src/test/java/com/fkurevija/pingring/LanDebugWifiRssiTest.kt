package com.fkurevija.pingring

import com.fkurevija.pingring.data.session.InviteSecret
import com.fkurevija.pingring.data.session.InviteSecretPayloadDto
import com.fkurevija.pingring.data.session.JoinProofPayloadDto
import com.fkurevija.pingring.data.LanDebugConnectionsManager
import com.fkurevija.pingring.data.ranging.MockRangingDto
import com.fkurevija.pingring.data.RelayCodec
import com.fkurevija.pingring.data.RelayEnvelope
import com.fkurevija.pingring.data.ranging.RssiKalmanFilter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDebugWifiRssiTest {
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
        val filter = RssiKalmanFilter()
        val distances = reports.mapIndexed { index, report ->
            // Reports are deliberately separated beyond the filter's 500 ms
            // hold interval, so this checks that a returning signal reseeds.
            requireNotNull(filter.add(requireNotNull(report.rssi), index * 2_500L))
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
        handleLine(
            manager,
            RelayEnvelope(
                type = RelayEnvelope.DATA,
                eventId = EVENT_ID,
                senderPeerId = senderId,
                payload = RelayCodec.encodeEnvelope(inner),
            ),
        )
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
