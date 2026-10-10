package com.fkurevija.pingring.data.transport

import android.util.Log
import com.fkurevija.pingring.data.BleIdentityCrypto
import com.fkurevija.pingring.data.BleIdentityKeyPayloadDto
import com.fkurevija.pingring.data.DecodedPayload
import com.fkurevija.pingring.data.InviteSecretPayloadDto
import com.fkurevija.pingring.data.JoinProofPayloadDto
import com.fkurevija.pingring.data.MemberDeparturePayloadDto
import com.fkurevija.pingring.data.MeshDuplicateCache
import com.fkurevija.pingring.data.MeshLinkAuthenticationPayloadDto
import com.fkurevija.pingring.data.MeshPeerAuthentication
import com.fkurevija.pingring.data.MeshRoutingPolicy
import com.fkurevija.pingring.data.MessageStatus
import com.fkurevija.pingring.data.PayloadSink
import com.fkurevija.pingring.data.RelayCodec
import com.fkurevija.pingring.data.RelayEnvelope
import com.fkurevija.pingring.data.UwbRangingOfferDto
import com.fkurevija.pingring.data.isMemberDepartureOriginAuthorized
import com.fkurevija.pingring.data.isPayloadSenderAuthorized
import java.util.Base64

/**
 * Validates and routes one decoded envelope received by Nearby Connections.
 *
 * The dispatcher owns envelope authentication and payload policy; connection
 * lifecycle, authorization state, and delivery side effects remain with the
 * transport through narrowly-scoped accessors and callbacks.
 */
internal class NearbyInboundMeshDispatcher(
    private val tag: String,
    private val state: State,
    private val events: PayloadSink,
    private val duplicateCache: MeshDuplicateCache,
    private val callbacks: Callbacks,
) {
    class State(
        val isLocalHost: () -> Boolean,
        val localEventId: () -> String?,
        val localPeerId: () -> String,
        val authenticatedHostEndpoint: () -> String?,
        val hostPeerId: () -> String?,
        val isEndpointAuthorized: (String) -> Boolean,
        val endpointPeerId: (String?) -> String?,
        val meshIdentityKey: (String) -> BleIdentityKeyPayloadDto?,
    )

    class Callbacks(
        val receiveJoinProof: (JoinProofPayloadDto, String) -> Boolean,
        val receiveInviteSecret: (InviteSecretPayloadDto, String) -> Boolean,
        val receiveMeshPeerAuthentication: (MeshLinkAuthenticationPayloadDto, String) -> Boolean,
        val receiveBleIdentityKey: (BleIdentityKeyPayloadDto, String) -> Boolean,
        val receiveMemberDeparture: (MemberDeparturePayloadDto, String) -> Boolean,
        val broadcastMemberDeparture: (MemberDeparturePayloadDto) -> Unit,
        val forwardMeshEnvelope: (RelayEnvelope, String) -> Unit,
        val relayOffer: (UwbRangingOfferDto, String) -> Boolean,
        val onUwbRangingOfferReceived: (UwbRangingOfferDto) -> Unit,
    )

    fun dispatch(envelope: RelayEnvelope, endpointId: String): Boolean {
        val body = envelope.payload ?: return false
        val decoded = RelayCodec.decode(envelope.type, body)
        if (decoded == null) {
            Log.w(tag, "Unknown payload type '${envelope.type}' from $endpointId; dropped")
            return false
        }
        decoded.onFailure { error ->
            Log.e(tag, "Could not parse a '${envelope.type}' payload from $endpointId: ${error.message}", error)
        }
        val payload = decoded.getOrNull() ?: return false
        when (payload) {
            is DecodedPayload.JoinProof -> return callbacks.receiveJoinProof(payload.proof, endpointId)
            is DecodedPayload.InviteSecret -> return callbacks.receiveInviteSecret(payload.secret, endpointId)
            is DecodedPayload.MeshPeerAuthentication ->
                return callbacks.receiveMeshPeerAuthentication(payload.authentication, endpointId)
            is DecodedPayload.BleIdentityKey ->
                if (state.isLocalHost() || endpointId == state.authenticatedHostEndpoint()) {
                    return callbacks.receiveBleIdentityKey(payload.identityKey, endpointId)
                }
            else -> Unit
        }
        if (MeshRoutingPolicy.isForwardable(envelope.type)) {
            return receiveMeshEnvelope(envelope, payload, endpointId)
        }
        if (deliver(payload, envelope, endpointId)) return true
        Log.w(tag, "Payload of known type '${envelope.type}' from $endpointId could not be delivered; dropped")
        return false
    }

    private fun receiveMeshEnvelope(
        envelope: RelayEnvelope,
        payload: DecodedPayload,
        endpointId: String,
    ): Boolean {
        if (!state.isEndpointAuthorized(endpointId) || envelope.eventId != state.localEventId()) return false
        if (envelope.hopCount !in 1..MeshRoutingPolicy.MAX_HOPS) return false
        if (envelope.senderPeerId != state.endpointPeerId(endpointId)) return false
        val originPeerId = envelope.originPeerId ?: return false
        val messageId = envelope.messageId?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return false
        val identityKey = state.meshIdentityKey(originPeerId) ?: return false
        if (identityKey.eventCode != state.localEventId() || identityKey.peerId != originPeerId) return false
        val publicKey = BleIdentityCrypto.decodePublicKey(identityKey.publicKey) ?: return false
        val signature = runCatching { Base64.getUrlDecoder().decode(envelope.originSignature.orEmpty()) }
            .getOrNull() ?: return false
        if (!BleIdentityCrypto.verify(publicKey, MeshPeerAuthentication.envelopeSigningBytes(envelope), signature)) {
            return false
        }
        if (envelope.targetPeerId != null && state.meshIdentityKey(envelope.targetPeerId) == null) return false
        if (!duplicateCache.markIfNew(messageId)) return true

        val isForThisPeer = MeshRoutingPolicy.shouldDeliver(envelope.targetPeerId, state.localPeerId())
        if (isForThisPeer && !deliverMeshPayload(payload, originPeerId, envelope)) return false
        if (!isForThisPeer || envelope.targetPeerId == null) {
            callbacks.forwardMeshEnvelope(envelope, endpointId)
        }
        return true
    }

    private fun deliverMeshPayload(
        payload: DecodedPayload,
        originPeerId: String,
        envelope: RelayEnvelope,
    ): Boolean = when (payload) {
        is DecodedPayload.Chat -> payload.message.senderPeerId == originPeerId && RelayCodec.route(payload, events)
        is DecodedPayload.ChatReceipt ->
            payload.receipt.recipientPeerId == originPeerId &&
                payload.receipt.eventCode == state.localEventId() &&
                payload.receipt.messageId.isNotBlank() &&
                payload.receipt.status != MessageStatus.SENT &&
                envelope.targetPeerId == state.localPeerId() &&
                RelayCodec.route(payload, events)
        is DecodedPayload.Beacon -> payload.beacon.senderPeerId == originPeerId && RelayCodec.route(payload, events)
        is DecodedPayload.ChatHistory ->
            originPeerId == state.hostPeerId() && RelayCodec.route(payload, events)
        is DecodedPayload.BleIdentityKey -> originPeerId == state.hostPeerId() && run {
            if (BleIdentityCrypto.decodePublicKey(payload.identityKey.publicKey) == null) return false
            events.onBleIdentityKey(payload.identityKey)
            true
        }
        is DecodedPayload.MemberDeparture ->
            payload.departure.eventCode == state.localEventId() &&
                isMemberDepartureOriginAuthorized(
                    originPeerId,
                    payload.departure.peerId,
                    state.hostPeerId(),
                ) && run {
                if (state.isLocalHost()) callbacks.broadcastMemberDeparture(payload.departure)
                events.onMemberDeparture(payload.departure)
                true
            }
        is DecodedPayload.MeshPresence -> originPeerId == payload.presence.peerId &&
            payload.presence.eventCode == state.localEventId() && run {
                events.onMeshPresence(payload.presence)
                true
            }
        is DecodedPayload.PeerRanging -> originPeerId == payload.ranging.peerId &&
            payload.ranging.eventCode == state.localEventId() && payload.ranging.sequence >= 0 &&
            RelayCodec.route(payload, events)
        is DecodedPayload.EventControl -> originPeerId == state.hostPeerId() &&
            payload.control.hostPeerId == originPeerId && payload.control.eventCode == state.localEventId() &&
            RelayCodec.route(payload, events)
        is DecodedPayload.UwbAddress -> payload.address.peerId == originPeerId && RelayCodec.route(payload, events)
        is DecodedPayload.UwbParameters -> originPeerId == state.hostPeerId() &&
            payload.params.hostPeerId == originPeerId && RelayCodec.route(payload, events)
        is DecodedPayload.UwbOffer -> payload.offer.fromPeerId == originPeerId &&
            payload.offer.targetPeerId == envelope.targetPeerId && payload.offer.targetPeerId == state.localPeerId() && run {
                callbacks.onUwbRangingOfferReceived(payload.offer)
                true
            }
        else -> false
    }

    private fun deliver(payload: DecodedPayload, envelope: RelayEnvelope, endpointId: String): Boolean = when (payload) {
        is DecodedPayload.JoinProof -> callbacks.receiveJoinProof(payload.proof, endpointId)
        is DecodedPayload.InviteSecret -> callbacks.receiveInviteSecret(payload.secret, endpointId)
        is DecodedPayload.BleIdentityKey -> callbacks.receiveBleIdentityKey(payload.identityKey, endpointId)
        is DecodedPayload.MemberDeparture -> callbacks.receiveMemberDeparture(payload.departure, endpointId)
        is DecodedPayload.MeshPeerAuthentication ->
            callbacks.receiveMeshPeerAuthentication(payload.authentication, endpointId)
        else -> {
            if (!state.isEndpointAuthorized(endpointId)) return false
            val endpointPeerId = state.endpointPeerId(endpointId) ?: return false
            when (payload) {
                is DecodedPayload.Chat ->
                    if (isPayloadSenderAuthorized(
                            state.isLocalHost(),
                            endpointPeerId,
                            state.endpointPeerId(state.authenticatedHostEndpoint()),
                            payload.message.senderPeerId,
                        )
                    ) RelayCodec.route(payload, events) else false
                is DecodedPayload.Beacon ->
                    if (isPayloadSenderAuthorized(
                            state.isLocalHost(),
                            endpointPeerId,
                            state.endpointPeerId(state.authenticatedHostEndpoint()),
                            payload.beacon.senderPeerId,
                        )
                    ) RelayCodec.route(payload, events) else false
                is DecodedPayload.ChatHistory ->
                    !state.isLocalHost() && endpointId == state.authenticatedHostEndpoint() &&
                        RelayCodec.route(payload, events)
                is DecodedPayload.ChatReceipt ->
                    payload.receipt.recipientPeerId == endpointPeerId &&
                        payload.receipt.eventCode == state.localEventId() &&
                        payload.receipt.messageId.isNotBlank() &&
                        payload.receipt.status != MessageStatus.SENT &&
                        envelope.targetPeerId == state.localPeerId() &&
                        RelayCodec.route(payload, events)
                is DecodedPayload.UwbAddress ->
                    isPayloadSenderAuthorized(
                        state.isLocalHost(),
                        endpointPeerId,
                        state.endpointPeerId(state.authenticatedHostEndpoint()),
                        payload.address.peerId,
                    ) && RelayCodec.route(payload, events)
                is DecodedPayload.UwbParameters ->
                    (!state.isLocalHost() && endpointId == state.authenticatedHostEndpoint() &&
                        payload.params.hostPeerId == endpointPeerId) && RelayCodec.route(payload, events)
                is DecodedPayload.ChatColourClaim ->
                    if (isPayloadSenderAuthorized(
                            state.isLocalHost(),
                            endpointPeerId,
                            state.endpointPeerId(state.authenticatedHostEndpoint()),
                            payload.claim.peerId,
                        )
                    ) RelayCodec.route(payload, events) else false
                is DecodedPayload.EventControl ->
                    (!state.isLocalHost() && endpointId == state.authenticatedHostEndpoint() &&
                        payload.control.eventCode == state.localEventId() &&
                        payload.control.hostPeerId == endpointPeerId) && RelayCodec.route(payload, events)
                is DecodedPayload.UwbOffer -> if (state.isLocalHost()) {
                    payload.offer.fromPeerId == endpointPeerId && callbacks.relayOffer(payload.offer, endpointId)
                } else if (endpointId == state.authenticatedHostEndpoint() &&
                    payload.offer.targetPeerId == state.localPeerId()
                ) {
                    callbacks.onUwbRangingOfferReceived(payload.offer)
                    true
                } else false
                is DecodedPayload.InviteSecret, is DecodedPayload.BleIdentityKey,
                is DecodedPayload.MemberDeparture, is DecodedPayload.MeshPeerAuthentication,
                is DecodedPayload.MeshPresence, is DecodedPayload.PeerRanging,
                is DecodedPayload.JoinProof, is DecodedPayload.MockRanging -> false
            }
        }
    }
}
