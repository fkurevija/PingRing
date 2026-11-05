package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.BleIdentityCrypto
import com.combustiblelemonlabs.pingring.data.MeshPeerAuthentication
import com.combustiblelemonlabs.pingring.data.RelayEnvelope
import java.security.SecureRandom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPeerAuthenticationTest {
    @Test
    fun registeredMemberSignatureBindsEventBothPeersAndFreshNonce() {
        val keys = BleIdentityCrypto.generateKeyPair()
        val challenge = ByteArray(MeshPeerAuthentication.CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        val signed = MeshPeerAuthentication.signingBytes("4C1A9B", "a1b2c3d4", "deadbeef", challenge)
        val signature = BleIdentityCrypto.sign(keys.private, signed)

        assertTrue(BleIdentityCrypto.verify(keys.public, signed, signature))
        assertFalse(
            BleIdentityCrypto.verify(
                keys.public,
                MeshPeerAuthentication.signingBytes("4C1A9B", "a1b2c3d4", "deadbeef", challenge.copyOf().also { it[0]++ }),
                signature,
            )
        )
        assertFalse(
            BleIdentityCrypto.verify(
                keys.public,
                MeshPeerAuthentication.signingBytes("4C1A9B", "deadbeef", "a1b2c3d4", challenge),
                signature,
            )
        )
    }

    @Test
    fun challengeEncodingAcceptsOnlyThirtyTwoBytes() {
        val challenge = ByteArray(MeshPeerAuthentication.CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        val encoded = MeshPeerAuthentication.encodeChallenge(challenge)

        assertNotNull(MeshPeerAuthentication.decodeChallenge(encoded))
        assertFalse(MeshPeerAuthentication.decodeChallenge("bad") != null)
    }

    @Test
    fun envelopeSignatureCoversPayloadAndRouteButAllowsRelayHopIncrement() {
        val keys = BleIdentityCrypto.generateKeyPair()
        val envelope = RelayEnvelope(
            type = "chat",
            eventId = "4C1A9B",
            originPeerId = "a1b2c3d4",
            messageId = "m1",
            targetPeerId = null,
            payload = "signed body",
            hopCount = 0,
        )
        val signed = MeshPeerAuthentication.envelopeSigningBytes(envelope)
        val signature = BleIdentityCrypto.sign(keys.private, signed)

        assertTrue(signed.contentEquals(MeshPeerAuthentication.envelopeSigningBytes(envelope.copy(hopCount = 1))))
        assertTrue(
            BleIdentityCrypto.verify(
                keys.public,
                MeshPeerAuthentication.envelopeSigningBytes(envelope.copy(hopCount = 1)),
                signature,
            )
        )
        assertFalse(signed.contentEquals(MeshPeerAuthentication.envelopeSigningBytes(envelope.copy(payload = "changed"))))
        assertFalse(signed.contentEquals(MeshPeerAuthentication.envelopeSigningBytes(envelope.copy(targetPeerId = "peer-b"))))
        assertFalse(
            BleIdentityCrypto.verify(
                keys.public,
                MeshPeerAuthentication.envelopeSigningBytes(envelope.copy(payload = "changed")),
                signature,
            )
        )
    }
}