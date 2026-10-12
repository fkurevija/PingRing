package com.fkurevija.pingring

import com.fkurevija.pingring.data.chat.BeaconPayloadDto
import com.fkurevija.pingring.data.session.BleIdentityKeyPayloadDto
import com.fkurevija.pingring.data.session.MemberDeparturePayloadDto
import com.fkurevija.pingring.data.session.MeshPresencePayloadDto
import com.fkurevija.pingring.data.session.MeshLinkAuthenticationPayloadDto
import com.fkurevija.pingring.data.chat.ChatHistoryPayloadDto
import com.fkurevija.pingring.data.chat.ChatHistoryRequestPayloadDto
import com.fkurevija.pingring.data.session.ChatColourClaimPayloadDto
import com.fkurevija.pingring.data.chat.ChatPayloadDto
import com.fkurevija.pingring.data.chat.ChatReceiptPayloadDto
import com.fkurevija.pingring.data.chat.ChatEntryType
import com.fkurevija.pingring.data.DecodedPayload
import com.fkurevija.pingring.data.session.EventControlPayloadDto
import com.fkurevija.pingring.data.session.InviteSecretPayloadDto
import com.fkurevija.pingring.data.session.JoinProofPayloadDto
import com.fkurevija.pingring.data.ranging.MockRangingDto
import com.fkurevija.pingring.data.PayloadSink
import com.fkurevija.pingring.data.RelayCodec
import com.fkurevija.pingring.data.RelayEnvelope
import com.fkurevija.pingring.data.ranging.UwbControllerParametersDto
import com.fkurevija.pingring.data.ranging.UwbPeerAddressDto
import com.fkurevija.pingring.data.ranging.UwbRangingOfferDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fkurevija.pingring.data.chat.ChatRecipientReceipt
import com.fkurevija.pingring.data.chat.MessageStatus
import com.fkurevija.pingring.data.MeshPeerAuthentication
import com.fkurevija.pingring.data.radar.GnssFixDto
import com.fkurevija.pingring.data.radar.PeerRangingPayloadDto

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

    @Test
    fun memberPresenceCarriesRenamedUsernameAndAcceptsLegacyMessages() {
        val presence = MeshPresencePayloadDto("ABC123", "aabbccdd", "Renamed")
        val encoded = requireNotNull(RelayCodec.encode(presence))
        assertEquals(DecodedPayload.MeshPresence(presence), RelayCodec.decode(encoded.wireType, encoded.json)?.getOrThrow())
        assertEquals(
            DecodedPayload.MeshPresence(MeshPresencePayloadDto("ABC123", "aabbccdd")),
            RelayCodec.decode(RelayCodec.MESH_PRESENCE, """{"eventCode":"ABC123","peerId":"aabbccdd"}""")?.getOrThrow(),
        )
    }

    /** Stands in for a transport's flows, so routing can be tested without one. */
    private class RecordingSink : PayloadSink {
        val delivered = mutableListOf<Any>()

        override fun onChatMessage(message: ChatPayloadDto) {
            delivered += message
        }

        override fun onChatHistory(history: ChatHistoryPayloadDto) {
            delivered += history
        }

        override fun onChatHistoryRequest(request: ChatHistoryRequestPayloadDto) {
            delivered += request
        }

        override fun onChatReceipt(receipt: ChatReceiptPayloadDto) {
            delivered += receipt
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

        override fun onMeshPresence(presence: MeshPresencePayloadDto) {
            delivered += presence
        }

        override fun onPeerRanging(ranging: PeerRangingPayloadDto) {
            delivered += ranging
        }

        override fun onChatColourClaim(claim: ChatColourClaimPayloadDto) {
            delivered += claim
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
    private val meshPresence = MeshPresencePayloadDto("4C1A9B", "a1b2c3d4")
    // Both directions of the colour protocol in one DTO: a request has granted
    // null, an answer has true or false. Round-tripping both matters because a
    // member cannot tell a refusal from a lost message if the discriminator does
    // not survive the wire.
    private val colourClaimRequest = ChatColourClaimPayloadDto(
        eventCode = "4C1A9B",
        peerId = "a1b2c3d4",
        colorArgb = 0xFF00695C,
        granted = null,
        requestedAtMillis = 1_700_000_000_000L,
    )
    private val colourClaimGranted = colourClaimRequest.copy(granted = true)
    private val colourClaimRefused = colourClaimRequest.copy(granted = false)
    private val peerRanging = PeerRangingPayloadDto("4C1A9B", "a1b2c3d4", 1L, "02:11:22:33:44:55", GnssFixDto(45.0, 16.0, 13f, 100L))
    private val meshAuthentication = MeshLinkAuthenticationPayloadDto(
        "4C1A9B", "a1b2c3d4", "deadbeef", "challenge",
    )
    private val joinProof = JoinProofPayloadDto("4C1A9B", null)
    private val eventControl = EventControlPayloadDto("4C1A9B", "peer-a", true, true, true, true, false)
    private val chatReceipt = ChatReceiptPayloadDto("4C1A9B", "m1", "peer-c", com.fkurevija.pingring.data.chat.MessageStatus.READ)

    @Test
    fun aChatColourClaimSurvivesTheWireInBothDirections() {
        // Request, grant and refusal all in one place. The refusal is the one that
        // matters: a guest that cannot tell "the host said no" from "the message
        // was lost" is left holding a colour it does not have, with no way to
        // recover except by guessing.
        listOf(colourClaimRequest, colourClaimGranted, colourClaimRefused).forEach { claim ->
            val encoded = RelayCodec.encode(claim)!!
            assertEquals(RelayCodec.CHAT_COLOUR_CLAIM, encoded.wireType)

            assertEquals(claim, payloadOf(decoded(encoded.wireType, encoded.json)))
        }
    }

    @Test
    fun aRefusedClaimStaysARefusalRatherThanDecodingAsARequest() {
        // Called out separately because the failure is silent: granted=false
        // decoding as null would make a refusal look like an unanswered request,
        // and the member's picker would sit waiting instead of offering another.
        val encoded = RelayCodec.encode(colourClaimRefused)!!
        val round = payloadOf(decoded(encoded.wireType, encoded.json)) as ChatColourClaimPayloadDto

        assertEquals(false, round.granted)
    }

    @Test
    fun everyPayloadKindSurvivesItsOwnWireType() {
        val payloads = listOf<Any>(
            chat,
            ChatHistoryPayloadDto(listOf(chat)),
            ChatHistoryRequestPayloadDto("event-1", "peer-b"),
            chatReceipt,
            beacon,
            inviteSecret,
            bleIdentityKey,
            memberDeparture,
            meshPresence,
            colourClaimRequest,
            peerRanging,
            meshAuthentication,
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
            payload = RelayCodec.encodeEnvelope(RelayEnvelope(type = inner.wireType, payload = inner.json)),
            messageId = "event-4C1A9B-message-m1",
            originPeerId = "peer-b",
            hopCount = 2,
        )

        val forwarded = RelayCodec.decodeEnvelope(RelayCodec.encodeEnvelope(outer)).getOrThrow()
        assertEquals(RelayEnvelope.DATA, forwarded.type)
        assertEquals("4C1A9B", forwarded.eventId)
        assertEquals("event-4C1A9B-message-m1", forwarded.messageId)
        assertEquals("peer-b", forwarded.originPeerId)
        assertEquals(2, forwarded.hopCount)

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
            RelayCodec.CHAT, RelayCodec.CHAT_HISTORY, RelayCodec.CHAT_RECEIPT, RelayCodec.BEACON,
            RelayCodec.INVITE_SECRET, RelayCodec.BLE_IDENTITY_KEY, RelayCodec.MEMBER_DEPARTURE,
            RelayCodec.MESH_PRESENCE,
            RelayCodec.PEER_RANGING,
            RelayCodec.MESH_PEER_AUTHENTICATION, RelayCodec.JOIN_PROOF, RelayCodec.EVENT_CONTROL,
            RelayCodec.UWB_ADDRESS, RelayCodec.UWB_PARAMS, RelayCodec.UWB_OFFER, RelayCodec.MOCK_RANGING,
            RelayCodec.CHAT_COLOUR_CLAIM
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
        val groupChat = chat.copy(
            recipientReceipts = listOf(
                com.fkurevija.pingring.data.chat.ChatRecipientReceipt("peer-a", "Alice"),
                com.fkurevija.pingring.data.chat.ChatRecipientReceipt("peer-c", "Chao"),
            ),
        )
        assertEquals(groupChat, payloadOf(decoded(RelayCodec.CHAT, encodedJson(groupChat))))

        val olderChat = payloadOf(
            RelayCodec.decodeUnwrapped(
                """{"id":"m1","senderPeerId":"peer-b","senderName":"Bob","text":"hi","timestamp":1700000000000}"""
            )!!
        ) as ChatPayloadDto
        assertNull("messages from older builds retain the theme color", olderChat.colorArgb)
        assertTrue("legacy messages have no recipient snapshot", olderChat.recipientReceipts.isEmpty())
    }

    @Test
    fun systemChatEntryMetadataSurvivesTheWire() {
        val notice = chat.copy(
            entryType = ChatEntryType.MEMBER_JOINED,
            subjectName = "Ana",
            subjectPeerId = "peer-ana",
        )

        val encoded = RelayCodec.encode(notice)!!

        assertEquals(notice, payloadOf(decoded(encoded.wireType, encoded.json)))
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
        assertTrue(RelayCodec.route(DecodedPayload.ChatReceipt(chatReceipt), sink))
        assertTrue(RelayCodec.route(DecodedPayload.Beacon(beacon), sink))
        assertTrue(RelayCodec.route(DecodedPayload.InviteSecret(inviteSecret), sink))
        assertTrue(RelayCodec.route(DecodedPayload.BleIdentityKey(bleIdentityKey), sink))
        assertTrue(RelayCodec.route(DecodedPayload.MemberDeparture(memberDeparture), sink))
        assertTrue(RelayCodec.route(DecodedPayload.MeshPresence(meshPresence), sink))
        assertTrue(RelayCodec.route(DecodedPayload.ChatColourClaim(colourClaimRequest), sink))
        assertTrue(RelayCodec.route(DecodedPayload.EventControl(eventControl), sink))
        assertEquals(
            listOf<Any>(chat, chatReceipt, beacon, inviteSecret, bleIdentityKey, memberDeparture, meshPresence,
                colourClaimRequest, eventControl),
            sink.delivered,
        )

        // A ranging offer is relayed by a host and consumed by the guest it names,
        // and a scripted range report exists on one transport only: both are the
        // caller's business, which is exactly why nothing is delivered here.
        assertFalse(RelayCodec.route(DecodedPayload.UwbOffer(offer), sink))
        assertFalse(RelayCodec.route(DecodedPayload.JoinProof(joinProof), sink))
        assertFalse(RelayCodec.route(DecodedPayload.MockRanging(report), sink))
        assertFalse(RelayCodec.route(DecodedPayload.MeshPeerAuthentication(meshAuthentication), sink))
        // Only the nine routings above delivered anything; the four refused
        // payloads must not have leaked onto the sink on the way past.
        assertEquals(9, sink.delivered.size)
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
        is DecodedPayload.ChatHistoryRequest -> decoded.request
        is DecodedPayload.ChatReceipt -> decoded.receipt
        is DecodedPayload.Beacon -> decoded.beacon
        is DecodedPayload.InviteSecret -> decoded.secret
        is DecodedPayload.BleIdentityKey -> decoded.identityKey
        is DecodedPayload.MemberDeparture -> decoded.departure
        is DecodedPayload.MeshPresence -> decoded.presence
        is DecodedPayload.ChatColourClaim -> decoded.claim
        is DecodedPayload.PeerRanging -> decoded.ranging
        is DecodedPayload.MeshPeerAuthentication -> decoded.authentication
        is DecodedPayload.JoinProof -> decoded.proof
        is DecodedPayload.EventControl -> decoded.control
        is DecodedPayload.UwbAddress -> decoded.address
        is DecodedPayload.UwbParameters -> decoded.params
        is DecodedPayload.UwbOffer -> decoded.offer
        is DecodedPayload.MockRanging -> decoded.report
    }
}
