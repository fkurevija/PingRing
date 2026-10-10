package com.fkurevija.pingring.data.transport

import com.fkurevija.pingring.data.BleIdentityCrypto
import com.fkurevija.pingring.data.MeshPeerAuthentication
import com.fkurevija.pingring.data.MeshRoutingPolicy
import com.fkurevija.pingring.data.RelayCodec
import com.fkurevija.pingring.data.RelayEnvelope
import java.security.PrivateKey
import java.util.Base64
import java.util.UUID

/**
 * Builds the payload envelope used by Nearby mesh sends.
 *
 * Transport state and delivery are deliberately left to the caller. This owns
 * only the common DTO-to-envelope step, including the signature required for
 * payload types that can be relayed through other mesh peers.
 */
internal object NearbyMeshEnvelopeFactory {
    fun build(
        params: Any,
        eventId: String?,
        senderPeerId: String,
        targetPeerId: String? = null,
        privateKeyProvider: (() -> PrivateKey?)? = null,
        onSigningKeyUnavailable: (String) -> Unit = {},
    ): Pair<RelayEnvelope, String>? {
        val encoded = RelayCodec.encode(params) ?: return null
        val baseEnvelope = RelayEnvelope(
            type = encoded.wireType,
            eventId = eventId,
            senderPeerId = senderPeerId,
            targetPeerId = targetPeerId,
            payload = encoded.json,
        )
        if (!MeshRoutingPolicy.isForwardable(encoded.wireType)) {
            return baseEnvelope to encoded.wireType
        }

        val privateKey = privateKeyProvider?.invoke() ?: run {
            onSigningKeyUnavailable(encoded.wireType)
            return null
        }
        val identified = baseEnvelope.copy(
            messageId = UUID.randomUUID().toString(),
            originPeerId = senderPeerId,
        )
        val signed = identified.copy(
            originSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                BleIdentityCrypto.sign(privateKey, MeshPeerAuthentication.envelopeSigningBytes(identified))
            )
        )
        return signed to encoded.wireType
    }
}
