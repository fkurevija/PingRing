package com.fkurevija.pingring

import com.fkurevija.pingring.data.BeaconPayloadDto
import com.fkurevija.pingring.data.BleIdentityKeyPayloadDto
import com.fkurevija.pingring.data.MemberDeparturePayloadDto
import com.fkurevija.pingring.data.ChatHistoryPayloadDto
import com.fkurevija.pingring.data.ChatPayloadDto
import com.fkurevija.pingring.data.DecodedPayload
import com.fkurevija.pingring.data.EventControlPayloadDto
import com.fkurevija.pingring.data.InviteSecretPayloadDto
import com.fkurevija.pingring.data.JoinProofPayloadDto
import com.fkurevija.pingring.data.MockRangingDto
import com.fkurevija.pingring.data.PayloadSink
import com.fkurevija.pingring.data.RelayCodec
import com.fkurevija.pingring.data.RelayEnvelope
import com.fkurevija.pingring.data.UwbControllerParametersDto
import com.fkurevija.pingring.data.UwbPeerAddressDto
import com.fkurevija.pingring.data.UwbRangingOfferDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format is the one thing every device in a PingRing has to agree on,
 * and a disagreement shows up as a message that simply never arrives, with
 * nothing on either device saying why.
 *
 * These tests hold the two halves against each other rather than against a
 * hand-written fixture: what [RelayCodec.encode] writes is what
 * [RelayCodec.decode] has to read back, for every payload kind.
 */
class RelayCodecTest {

    /** Stands in for a transport's flows, so routing can be tested without one. */
    private class RecordingSink : PayloadSink {
        val delivered = mutableListOf<Any>()

        override fun onChatMessage(message: ChatPayloadDto) {
            delivered += message
        }

        override fun onChatHistory(history: ChatHistoryPayloadDto) {
            delivered += history
        }

        override fun onBeacon(beacon: BeaconPayloadDto) {
            delivered += beacon
        }

        override fun onInviteSecret(secret: InviteSecretPayloadDto) {
            delivered += secret
        }

        override fun onBleIdentityKey(identityKey: BleIdentityKeyPayloadDto) {
            delivered += identityKey
        }

        override fun onMemberDeparture(departure: MemberDeparturePayloadDto) {
            delivered += departure
        }

        override fun onEventControl(control: EventControlPayloadDto) {
            delivered += control
        }

        override fun onUwbAddress(address: UwbPeerAddressDto) {
            delivered += address
        }

        override fun onUwbParameters(params: UwbControllerParametersDto) {
            delivered += params
        }
    }

    private val chat = ChatPayloadDto(
        id = "m1",
        senderPeerId = "peer-b",
        senderName = "Bob",
        text = "hi",
        timestamp = 1_700_000_000_000L
    )

    private val beacon = BeaconPayloadDto(
        id = "b1",
        senderPeerId = "peer-b",
        senderName = "Bob",
        title = "Emergency!",
        colorArgb = 0xFFFF1744,
        vibrationPattern = "SOS_URGENT",
        isActive = true,
        timestamp = 1_700_000_000_002L
    )

    private val address = UwbPeerAddressDto(peerId = "peer-b", address = "AA:BB")
    private val params = UwbControllerParametersDto(
        hostPeerId = "peer-a",
        controllerAddress = "CC:DD",
        channel = 9,
        preambleIndex = 2,
        sessionId = 7,
        staticStsKey = "key"
    )
    private val offer = UwbRangingOfferDto(
        fromPeerId = "peer-b",
        targetPeerId = "peer-c",
        controllerAddress = "AA:BB",
        channel = 9,
        preambleIndex = 2,
        sessionId = 7,
        staticStsKey = "key"
    )
    private val report = MockRangingDto(peerId = "peer-b", distance = 2.5f, azimuth = 90f, elevation = 0f, name = "Bob")
    private val inviteSecret = InviteSecretPayloadDto("4C1A9B", "secret", "peer-a")
    private val bleIdentityKey = BleIdentityKeyPayloadDto("4C1A9B", "a1b2c3d4", "encoded-public-key")
    private val memberDeparture = MemberDeparturePayloadDto("4C1A9B", "a1b2c3d4")
    private val joinProof = JoinProofPayloadDto("4C1A9B", null)
    private val eventControl = EventControlPayloadDto("4C1A9B", "peer-a", true, true, true, true, false)

    @Test
    fun everyPayloadKindSurvivesItsOwnWireType() {
        val payloads = listOf<Any>(
            chat,
            ChatHistoryPayloadDto(listOf(chat)),
            beacon,
            inviteSecret,
            bleIdentityKey,
            memberDeparture,
            joinProof,
            eventControl,
            address,
            params,
            offer,
        )

        payloads.forEach { payload ->
            val encoded = RelayCodec.encode(payload) ?: error("nothing encoded for $payload")
            val decoded = RelayCodec.decode(encoded.wireType, encoded.json)
            assertEquals(payload, payloadOf(decoded(encoded.wireType, encoded.json)))
        }
    }

    @Test
    fun anEnvelopeSurvivesTheRelayWrapper() {
        // The LAN transport wraps the app's own envelope in a "data" envelope and
        // the relay forwards the outer one verbatim. Both harnesses that drive the
        // app from a development machine (tools/mock_radar.py and the fake peer)
        // write exactly this shape, so it is pinned here.
        val inner = RelayCodec.encode(chat)!!
        val outer = RelayEnvelope(
            type = RelayEnvelope.DATA,
            eventId = "4C1A9B",
            senderPeerId = "peer-b",
            targetPeerId = null,
            payload = RelayCodec.encodeEnvelope(RelayEnvelope(type = inner.wireType, payload = inner.json))
        )

        val forwarded = RelayCodec.decodeEnvelope(RelayCodec.encodeEnvelope(outer)).getOrThrow()
        assertEquals(RelayEnvelope.DATA, forwarded.type)
        assertEquals("4C1A9B", forwarded.eventId)

        val appEnvelope = RelayCodec.decodeEnvelope(forwarded.payload!!).getOrThrow()
        assertEquals(chat, payloadOf(decoded(appEnvelope.type, appEnvelope.payload!!)))
    }

    @Test
    fun aBodyOfAKnownTypeThatWillNotParseFailsInsteadOfVanishing() {
        val decoded = RelayCodec.decode(RelayCodec.CHAT, "{not json")

        assertTrue("a known type has to report why it failed", decoded?.isFailure == true)
    }

    @Test
    fun anUnknownWireTypeIsReportedAsUnknownRatherThanBroken() {
        assertNull(RelayCodec.decode("from-the-future", "{}"))
        assertFalse("from-the-future" in RelayCodec.knownWireTypes)
    }

    @Test
    fun everyWireTypeTheAppUsesIsInTheKnownSet() {
        listOf(
            RelayCodec.CHAT, RelayCodec.CHAT_HISTORY, RelayCodec.BEACON,
            RelayCodec.INVITE_SECRET, RelayCodec.BLE_IDENTITY_KEY, RelayCodec.MEMBER_DEPARTURE,
            RelayCodec.JOIN_PROOF, RelayCodec.EVENT_CONTROL,
            RelayCodec.UWB_ADDRESS, RelayCodec.UWB_PARAMS, RelayCodec.UWB_OFFER, RelayCodec.MOCK_RANGING
        ).forEach { assertTrue("$it is not a known wire type", it in RelayCodec.knownWireTypes) }
    }

    @Test
    fun aPayloadWithoutItsEnvelopeIsRecognisedByItsShape() {
        val decoded = RelayCodec.decodeUnwrapped(
            """{"id":"m1","senderPeerId":"peer-b","senderName":"Bob","text":"hi","timestamp":1700000000000}"""
        )

        assertEquals(chat, payloadOf(decoded!!))
    }

    @Test
    fun aChatBubbleColorSurvivesTheWireAndOlderMessagesKeepNoColor() {
        val coloredChat = chat.copy(colorArgb = 0xFF00695C)

        assertEquals(coloredChat, payloadOf(decoded(RelayCodec.CHAT, encodedJson(coloredChat))))

        val olderChat = payloadOf(
            RelayCodec.decodeUnwrapped(
                """{"id":"m1","senderPeerId":"peer-b","senderName":"Bob","text":"hi","timestamp":1700000000000}"""
            )!!
        ) as ChatPayloadDto
        assertNull("messages from older builds retain the theme color", olderChat.colorArgb)
    }

    @Test
    fun aScriptedRangeReportNeverSwallowsAnotherPayload() {
        // Every field of a range report is optional, so recognising one has to be
        // kept last and has to demand a peer id: otherwise a message that names a
        // peer would be read as a range report for that peer and never arrive.
        assertEquals(chat, payloadOf(RelayCodec.decodeUnwrapped(encodedJson(chat))!!))
        assertEquals(address, payloadOf(RelayCodec.decodeUnwrapped(encodedJson(address))!!))
        assertNull(RelayCodec.decodeUnwrapped("""{"distance":2.5}"""))
    }

    @Test
    fun routingDeliversWhatTheTransportsShareAndLeavesTheRestToThem() {
        val sink = RecordingSink()

        assertTrue(RelayCodec.route(DecodedPayload.Chat(chat), sink))
        assertTrue(RelayCodec.route(DecodedPayload.Beacon(beacon), sink))
        assertTrue(RelayCodec.route(DecodedPayload.InviteSecret(inviteSecret), sink))
        assertTrue(RelayCodec.route(DecodedPayload.BleIdentityKey(bleIdentityKey), sink))
        assertTrue(RelayCodec.route(DecodedPayload.MemberDeparture(memberDeparture), sink))
        assertTrue(RelayCodec.route(DecodedPayload.EventControl(eventControl), sink))
        assertEquals(listOf<Any>(chat, beacon, inviteSecret, bleIdentityKey, memberDeparture, eventControl), sink.delivered)

        // A ranging offer is relayed by a host and consumed by the guest it names,
        // and a scripted range report exists on one transport only: both are the
        // caller's business, which is exactly why nothing is delivered here.
        assertFalse(RelayCodec.route(DecodedPayload.UwbOffer(offer), sink))
        assertFalse(RelayCodec.route(DecodedPayload.JoinProof(joinProof), sink))
        assertFalse(RelayCodec.route(DecodedPayload.MockRanging(report), sink))
        assertEquals(6, sink.delivered.size)
    }

    private fun encodedJson(payload: Any): String = RelayCodec.encode(payload)!!.json

    /**
     * Decodes a body that is expected to work, failing the test with the reason if
     * it does not, so the assertions below can compare payloads rather than
     * plumbing.
     */
    private fun decoded(wireType: String, json: String): DecodedPayload {
        val result = RelayCodec.decode(wireType, json)
        assertTrue("$wireType is not a wire type this build knows", result != null)
        return result!!.getOrThrow()
    }

    /** The DTO inside a decoded payload, so it can be compared with what was encoded. */
    private fun payloadOf(decoded: DecodedPayload): Any = when (decoded) {
        is DecodedPayload.Chat -> decoded.message
        is DecodedPayload.ChatHistory -> decoded.history
        is DecodedPayload.Beacon -> decoded.beacon
        is DecodedPayload.InviteSecret -> decoded.secret
        is DecodedPayload.BleIdentityKey -> decoded.identityKey
        is DecodedPayload.MemberDeparture -> decoded.departure
        is DecodedPayload.JoinProof -> decoded.proof
        is DecodedPayload.EventControl -> decoded.control
        is DecodedPayload.UwbAddress -> decoded.address
        is DecodedPayload.UwbParameters -> decoded.params
        is DecodedPayload.UwbOffer -> decoded.offer
        is DecodedPayload.MockRanging -> decoded.report
    }
}

