package com.combustiblelemonlabs.pingring.data

import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object MeshPeerAuthentication {
    const val CHALLENGE_BYTES = 32

    fun encodeChallenge(challenge: ByteArray): String {
        require(challenge.size == CHALLENGE_BYTES)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
    }

    fun decodeChallenge(encoded: String): ByteArray? {
        if (encoded.length != 43) return null
        return runCatching {
            Base64.getUrlDecoder().decode(encoded).takeIf { it.size == CHALLENGE_BYTES }
        }.getOrNull()
    }

    fun signingBytes(
        eventCode: String,
        challengerPeerId: String,
        responderPeerId: String,
        challenge: ByteArray,
    ): ByteArray {
        require(eventCode.length == 6)
        require(challengerPeerId.length == 8 && responderPeerId.length == 8)
        require(challenge.size == CHALLENGE_BYTES)
        return ByteBuffer.allocate(DOMAIN_SEPARATOR.size + 6 + 8 + 8 + CHALLENGE_BYTES)
            .put(DOMAIN_SEPARATOR)
            .put(eventCode.toByteArray(StandardCharsets.US_ASCII))
            .put(challengerPeerId.toByteArray(StandardCharsets.US_ASCII))
            .put(responderPeerId.toByteArray(StandardCharsets.US_ASCII))
            .put(challenge)
            .array()
    }

    fun envelopeSigningBytes(envelope: RelayEnvelope): ByteArray {
        val fields = listOf(
            envelope.eventId,
            envelope.type,
            envelope.originPeerId,
            envelope.messageId,
            envelope.targetPeerId,
            envelope.payload,
        )
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(ENVELOPE_DOMAIN_SEPARATOR)
            fields.forEach { field ->
                if (field == null) {
                    output.writeInt(-1)
                } else {
                    val value = field.toByteArray(StandardCharsets.UTF_8)
                    output.writeInt(value.size)
                    output.write(value)
                }
            }
        }
        return bytes.toByteArray()
    }

    private val DOMAIN_SEPARATOR = "PingRing mesh link v1\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val ENVELOPE_DOMAIN_SEPARATOR = "PingRing mesh envelope v1\u0000".toByteArray(StandardCharsets.US_ASCII)
}