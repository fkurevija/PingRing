package com.fkurevija.pingring.data.transport

import com.fkurevija.pingring.data.EventControlPayloadDto
import com.fkurevija.pingring.data.InviteSecret
import com.fkurevija.pingring.data.InviteSecretPayloadDto
import com.fkurevija.pingring.data.JoinProofPayloadDto
import com.fkurevija.pingring.data.JoinRequestDecision
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.RelayEnvelope
import com.fkurevija.pingring.data.decideJoinRequest
import com.fkurevija.pingring.data.joinRequiresHostApproval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * LAN relay admission and invite-secret handling, kept separate from socket
 * framing so the transport facade can delegate membership decisions.
 */
internal class LanDebugJoinAdmission(
    private val state: () -> State,
    private val authorizedPeers: MutableSet<String>,
    private val callbacks: Callbacks,
) {
    private val _pendingJoinRequests = MutableStateFlow<List<PendingJoinRequest>>(emptyList())
    val pendingJoinRequests: StateFlow<List<PendingJoinRequest>> = _pendingJoinRequests.asStateFlow()
    private val pendingJoinProofs = mutableMapOf<String, JoinProofPayloadDto>()

    fun approveJoinRequest(endpointId: String) {
        val current = state()
        callbacks.logInfo("Host approved join request for $endpointId")
        _pendingJoinRequests.update { requests -> requests.filterNot { it.endpointId == endpointId } }
        pendingJoinProofs.remove(endpointId)?.guestName?.let { callbacks.onPeerName(endpointId, it) }
        authorizedPeers.add(endpointId)
        current.inviteSecretProvider()?.let {
            callbacks.sendPayload(endpointId, InviteSecretPayloadDto(current.localEventId, current.localPeerId, it))
        }
        callbacks.onPeerConnected(endpointId)
    }

    fun denyJoinRequest(endpointId: String) {
        val current = state()
        callbacks.logInfo("Host denied join request for $endpointId")
        _pendingJoinRequests.update { requests -> requests.filterNot { it.endpointId == endpointId } }
        pendingJoinProofs.remove(endpointId)
        callbacks.sendPayload(
            endpointId,
            EventControlPayloadDto(
                eventCode = current.localEventId,
                hostPeerId = current.localPeerId,
                useUwb = true,
                useWifiDirect = true,
                acceptsNewMembers = current.acceptsNewMembers,
                isFull = false,
                isClosed = true,
            ),
        )
    }

    fun sendLocalJoinProof(targetPeerId: String?) {
        val current = state()
        callbacks.sendPayload(
            targetPeerId,
            JoinProofPayloadDto(
                eventCode = current.localEventId,
                inviteSecret = current.inviteSecretProvider(),
                guestName = current.userNameProvider(),
            ),
        )
    }

    fun receiveJoinProof(proof: JoinProofPayloadDto, senderPeerId: String) {
        val current = state()
        if (current.localRole != RelayEnvelope.ROLE_HOST ||
            proof.eventCode != current.localEventId ||
            senderPeerId.isBlank()
        ) return

        if (senderPeerId in authorizedPeers) {
            current.inviteSecretProvider()?.let {
                callbacks.sendPayload(
                    senderPeerId,
                    InviteSecretPayloadDto(current.localEventId, current.localPeerId, it),
                )
            }
            callbacks.onPeerConnected(senderPeerId)
            return
        }

        val expectedSecret = current.inviteSecretProvider()
        val hasValidInvite = expectedSecret != null &&
            proof.inviteSecret == expectedSecret &&
            InviteSecret.decode(proof.inviteSecret.orEmpty()) != null
        val decision = decideJoinRequest(
            isIncoming = true,
            requiresApproval = joinRequiresHostApproval(hasValidInvite),
            acceptsNewMembers = current.acceptsNewMembers,
            connectedPeerCount = authorizedPeers.size + _pendingJoinRequests.value.size,
        )

        when (decision) {
            JoinRequestDecision.Refuse -> {
                callbacks.logInfo("Refusing join request from $senderPeerId")
                callbacks.sendPayload(
                    senderPeerId,
                    EventControlPayloadDto(
                        eventCode = current.localEventId,
                        hostPeerId = current.localPeerId,
                        useUwb = true,
                        useWifiDirect = true,
                        acceptsNewMembers = current.acceptsNewMembers,
                        isFull = true,
                        isClosed = true,
                    ),
                )
            }
            JoinRequestDecision.Admit -> {
                callbacks.logInfo("Auto-admitting $senderPeerId (valid QR invite secret)")
                authorizedPeers.add(senderPeerId)
                proof.guestName?.let { callbacks.onPeerName(senderPeerId, it) }
                current.inviteSecretProvider()?.let {
                    callbacks.sendPayload(
                        senderPeerId,
                        InviteSecretPayloadDto(current.localEventId, current.localPeerId, it),
                    )
                }
                callbacks.onPeerConnected(senderPeerId)
            }
            JoinRequestDecision.AwaitApproval -> {
                callbacks.logInfo("Holding join request from $senderPeerId for host approval")
                val previousProof = pendingJoinProofs[senderPeerId]
                val guestName = proof.guestName?.takeIf { it.isNotBlank() }
                    ?: previousProof?.guestName?.takeIf { it.isNotBlank() }
                pendingJoinProofs[senderPeerId] = proof.copy(guestName = guestName)
                val previousRequest = _pendingJoinRequests.value.firstOrNull { it.endpointId == senderPeerId }
                val request = PendingJoinRequest(
                    endpointId = senderPeerId,
                    deviceName = guestName ?: "Guest device",
                    requestedAtMillis = previousRequest?.requestedAtMillis ?: System.currentTimeMillis(),
                )
                _pendingJoinRequests.update { requests ->
                    (requests.filterNot { it.endpointId == senderPeerId } + request)
                        .sortedBy { it.requestedAtMillis }
                }
            }
        }
    }

    fun receiveInviteSecret(secret: InviteSecretPayloadDto, senderPeerId: String) {
        val current = state()
        if (current.localRole == RelayEnvelope.ROLE_HOST) return
        if (secret.eventCode != current.localEventId) return
        if (InviteSecret.decode(secret.inviteSecret) == null) return
        val existingSecret = current.inviteSecretProvider()
        if (existingSecret != null && existingSecret != secret.inviteSecret) return
        authorizedPeers.add(senderPeerId)
        callbacks.onPeerConnected(senderPeerId)
        callbacks.onInviteSecret(secret)
    }

    fun removePeer(peerId: String) {
        _pendingJoinRequests.update { requests -> requests.filterNot { it.endpointId == peerId } }
        pendingJoinProofs.remove(peerId)
    }

    data class State(
        val localEventId: String,
        val localPeerId: String,
        val localRole: String,
        val acceptsNewMembers: Boolean,
        val inviteSecretProvider: () -> String?,
        val userNameProvider: () -> String?,
    )

    data class Callbacks(
        val sendPayload: (String?, Any) -> Unit,
        val onPeerName: (String, String) -> Unit,
        val onPeerConnected: (String) -> Unit,
        val onInviteSecret: (InviteSecretPayloadDto) -> Unit,
        val logInfo: (String) -> Unit,
    )
}
