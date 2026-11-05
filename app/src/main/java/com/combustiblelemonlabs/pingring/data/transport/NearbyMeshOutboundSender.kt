package com.combustiblelemonlabs.pingring.data.transport

import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.combustiblelemonlabs.pingring.data.MeshDuplicateCache
import com.combustiblelemonlabs.pingring.data.MeshPendingMessageQueue
import com.combustiblelemonlabs.pingring.data.MeshRoutingPolicy
import com.combustiblelemonlabs.pingring.data.RelayCodec
import com.combustiblelemonlabs.pingring.data.RelayEnvelope
import java.security.PrivateKey

/**
 * Builds and routes outbound Nearby mesh envelopes.
 *
 * The manager retains connection lifecycle and inbound dispatch. This helper
 * gets read-only snapshots of routing state and owns the outbound pending queue.
 */
internal class NearbyMeshOutboundSender(
    private val tag: String,
    private val connectionsClient: ConnectionsClient,
    private val state: State,
    private val duplicateCache: MeshDuplicateCache,
    private val pendingMessages: MeshPendingMessageQueue = MeshPendingMessageQueue(),
) {
    class State(
        val eventId: () -> String?,
        val localPeerId: () -> String,
        val privateKey: () -> PrivateKey?,
        val authorizedEndpointIds: () -> Set<String>,
        val connectedEndpointForPeer: (String) -> String?,
        val connectedEndpointIds: () -> Set<String>,
        val endpointPeerId: (String) -> String?,
    )

    fun clearPendingMessages() = pendingMessages.clear()

    fun sendPayloadToEndpoint(endpointId: String, params: Any, onComplete: (() -> Unit)? = null) {
        val (envelope, wireType) = buildSignedMeshEnvelope(params, state.endpointPeerId(endpointId)) ?: run {
            onComplete?.invoke()
            return
        }
        val outgoing = if (MeshRoutingPolicy.isForwardable(wireType)) {
            envelope.copy(hopCount = MeshRoutingPolicy.nextHopCount(0) ?: return)
        } else envelope
        sendEnvelopeToEndpoint(endpointId, outgoing, wireType, onComplete)
    }

    fun sendMeshBroadcast(params: Any, onComplete: (() -> Unit)? = null) {
        val (envelope, wireType) = buildSignedMeshEnvelope(params) ?: run {
            onComplete?.invoke()
            return
        }
        if (!MeshRoutingPolicy.isForwardable(wireType)) {
            val endpoints = state.authorizedEndpointIds().toList()
            if (endpoints.isEmpty()) {
                onComplete?.invoke()
                return
            }
            val remaining = java.util.concurrent.atomic.AtomicInteger(endpoints.size)
            endpoints.forEach { endpointId ->
                sendEnvelopeToEndpoint(endpointId, envelope, wireType) {
                    if (remaining.decrementAndGet() == 0) onComplete?.invoke()
                }
            }
            return
        }
        val messageId = MeshRoutingPolicy.messageId(envelope)
        if (!duplicateCache.markIfNew(messageId)) {
            onComplete?.invoke()
            return
        }
        forwardToMeshNeighbors(envelope, excludedEndpointId = null, onComplete = onComplete)
    }

    fun sendMeshTargetedPayload(peerId: String, params: Any, onComplete: (() -> Unit)? = null) {
        if (peerId == state.localPeerId()) {
            onComplete?.invoke()
            return
        }
        val (envelope, wireType) = buildSignedMeshEnvelope(params, peerId) ?: run {
            onComplete?.invoke()
            return
        }
        if (!MeshRoutingPolicy.isForwardable(wireType)) {
            val endpointId = state.connectedEndpointForPeer(peerId)
            if (endpointId == null) onComplete?.invoke()
            else sendEnvelopeToEndpoint(endpointId, envelope, wireType, onComplete)
            return
        }
        val messageId = MeshRoutingPolicy.messageId(envelope)
        if (!duplicateCache.markIfNew(messageId)) {
            onComplete?.invoke()
            return
        }
        val directEndpoint = state.connectedEndpointForPeer(peerId)
        if (directEndpoint != null && directEndpoint in state.authorizedEndpointIds()) {
            val hopCount = MeshRoutingPolicy.nextHopCount(0) ?: run {
                onComplete?.invoke()
                return
            }
            sendEnvelopeToEndpoint(directEndpoint, envelope.copy(hopCount = hopCount), wireType, onComplete)
        } else {
            forwardToMeshNeighbors(envelope, excludedEndpointId = null, onComplete = onComplete)
        }
    }

    fun forwardToMeshNeighbors(
        envelope: RelayEnvelope,
        excludedEndpointId: String?,
        onComplete: (() -> Unit)? = null,
    ) {
        val hopCount = MeshRoutingPolicy.nextHopCount(envelope.hopCount)
        if (hopCount == null) {
            Log.w(tag, "Dropping mesh message ${MeshRoutingPolicy.messageId(envelope)} at hop limit")
            onComplete?.invoke()
            return
        }
        val wireType = envelope.type
        val outgoing = envelope.copy(senderPeerId = state.localPeerId(), hopCount = hopCount)
        val connectedEndpoints = state.connectedEndpointIds()
        val endpoints = state.authorizedEndpointIds()
            .asSequence()
            .filter { it != excludedEndpointId && it in connectedEndpoints }
            .distinct()
            .toList()
        if (endpoints.isEmpty()) {
            if (!MeshRoutingPolicy.isEphemeral(wireType)) pendingMessages.enqueue(envelope)
            onComplete?.invoke()
            return
        }
        var remaining = endpoints.size
        endpoints.forEach { endpointId ->
            sendEnvelopeToEndpoint(
                endpointId,
                outgoing,
                wireType,
                onComplete = {
                    remaining--
                    if (remaining == 0) onComplete?.invoke()
                },
                onFailure = { if (!MeshRoutingPolicy.isEphemeral(wireType)) pendingMessages.enqueue(envelope) },
            )
        }
    }

    fun replayPendingMessages(endpointId: String) {
        if (endpointId !in state.authorizedEndpointIds()) return
        pendingMessages.snapshot().forEach { envelope ->
            val nextHop = MeshRoutingPolicy.nextHopCount(envelope.hopCount) ?: return@forEach
            val outgoing = envelope.copy(senderPeerId = state.localPeerId(), hopCount = nextHop)
            sendEnvelopeToEndpoint(
                endpointId,
                outgoing,
                envelope.type,
                onSuccess = { pendingMessages.remove(MeshRoutingPolicy.messageId(envelope)) },
            )
        }
    }

    private fun buildSignedMeshEnvelope(
        params: Any,
        targetPeerId: String? = null,
    ): Pair<RelayEnvelope, String>? = NearbyMeshEnvelopeFactory.build(
        params = params,
        eventId = state.eventId(),
        senderPeerId = state.localPeerId(),
        targetPeerId = targetPeerId,
        privateKeyProvider = state.privateKey,
        onSigningKeyUnavailable = { wireType ->
            Log.w(tag, "Dropping $wireType: local mesh signing key is unavailable")
        },
    )

    private fun sendEnvelopeToEndpoint(
        endpointId: String,
        envelope: RelayEnvelope,
        wireType: String,
        onComplete: (() -> Unit)? = null,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) {
        val envelopeJson = RelayCodec.encodeEnvelope(envelope)
        val payload = Payload.fromBytes(envelopeJson.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                Log.d(
                    tag,
                    "Sent $wireType (${envelopeJson.length} chars) to $endpointId " +
                        "[peer=${state.endpointPeerId(endpointId) ?: "?"}]",
                )
                onSuccess?.invoke()
            }
            .addOnFailureListener { error ->
                val status = (error as? ApiException)?.statusCode
                Log.e(tag, "sendPayload($wireType) to $endpointId failed, status=$status: ${error.message}", error)
                onFailure?.invoke()
            }
            .addOnCompleteListener { onComplete?.invoke() }
    }
}
