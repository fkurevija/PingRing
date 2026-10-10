package com.fkurevija.pingring.data.transport

import com.fkurevija.pingring.data.BleIdentityCrypto
import com.fkurevija.pingring.data.ChatPayloadDto
import com.fkurevija.pingring.data.DecodedPayload
import com.fkurevija.pingring.data.JoinProofPayloadDto
import com.fkurevija.pingring.data.MeshPeerAuthentication
import com.fkurevija.pingring.data.RelayCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyMeshEnvelopeFactoryTest {
    private val chat = ChatPayloadDto(
        id = "message-1",
        senderPeerId = "aabbccdd",
        senderName = "Alice",
        text = "Hello",
        timestamp = 1L,
    )

    @Test
    fun forwardablePayloadGetsSignedEnvelopeWithoutChangingItsBodyOrRoute() {
        val keys = BleIdentityCrypto.generateKeyPair()
        val (envelope, wireType) = requireNotNull(
            NearbyMeshEnvelopeFactory.build(
                params = chat,
                eventId = "ABC123",
                senderPeerId = "aabbccdd",
                targetPeerId = "deadbeef",
                privateKeyProvider = { keys.private },
            )
        )

        assertEquals(RelayCodec.CHAT, wireType)
        assertEquals("ABC123", envelope.eventId)
        assertEquals("aabbccdd", envelope.senderPeerId)
        assertEquals("aabbccdd", envelope.originPeerId)
        assertEquals("deadbeef", envelope.targetPeerId)
        assertTrue(envelope.messageId.orEmpty().isNotBlank())
        assertEquals(0, envelope.hopCount)
        assertEquals(DecodedPayload.Chat(chat), RelayCodec.decode(wireType, envelope.payload!!)?.getOrThrow())
        assertTrue(
            BleIdentityCrypto.verify(
                keys.public,
                MeshPeerAuthentication.envelopeSigningBytes(envelope),
                java.util.Base64.getUrlDecoder().decode(requireNotNull(envelope.originSignature)),
            )
        )
        assertEquals(envelope, RelayCodec.decodeEnvelope(RelayCodec.encodeEnvelope(envelope)).getOrThrow())
    }

    @Test
    fun nonForwardablePayloadDoesNotRequestSigningKeyOrAddMeshFields() {
        var requestedKey = false
        val result = NearbyMeshEnvelopeFactory.build(
            params = JoinProofPayloadDto(
                eventCode = "ABC123",
                inviteSecret = null,
                guestName = "Alice",
            ),
            eventId = "ABC123",
            senderPeerId = "aabbccdd",
            privateKeyProvider = {
                requestedKey = true
                null
            },
        )

        val (envelope, wireType) = requireNotNull(result)
        assertEquals(RelayCodec.JOIN_PROOF, wireType)
        assertFalse(requestedKey)
        assertNull(envelope.messageId)
        assertNull(envelope.originPeerId)
        assertNull(envelope.originSignature)
    }

    @Test
    fun missingSigningKeyDropsForwardablePayloadAndReportsItsWireType() {
        val unavailableTypes = mutableListOf<String>()

        val result = NearbyMeshEnvelopeFactory.build(
            params = chat,
            eventId = "ABC123",
            senderPeerId = "aabbccdd",
            privateKeyProvider = { null },
            onSigningKeyUnavailable = unavailableTypes::add,
        )

        assertNull(result)
        assertEquals(listOf(RelayCodec.CHAT), unavailableTypes)
    }

    @Test
    fun unsupportedPayloadIsIgnoredWithoutRequestingSigningKey() {
        var requestedKey = false

        val result = NearbyMeshEnvelopeFactory.build(
            params = Any(),
            eventId = "ABC123",
            senderPeerId = "aabbccdd",
            privateKeyProvider = {
                requestedKey = true
                null
            },
        )

        assertNull(result)
        assertFalse(requestedKey)
    }
}
