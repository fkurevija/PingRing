package com.combustiblelemonlabs.pingring.data.chat

import com.combustiblelemonlabs.pingring.data.chat.ChatBubbleColorPolicy
import com.combustiblelemonlabs.pingring.data.session.ChatColourClaimPayloadDto
import com.combustiblelemonlabs.pingring.data.session.ChatColourPolicy
import com.combustiblelemonlabs.pingring.data.NearbyTransport
import com.combustiblelemonlabs.pingring.data.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import com.combustiblelemonlabs.pingring.data.session.Event
import com.combustiblelemonlabs.pingring.data.session.SessionState

/** Resolves host-authoritative chat bubble colour claims for an event. */
internal class ChatColourCoordinator(
    private val sessionManager: SessionManager,
    private val transport: NearbyTransport,
    private val chatCoordinator: DiscoveryChatCoordinator,
) {
    private val _holders = MutableStateFlow<Map<Long, String>>(emptyMap())
    val holders: StateFlow<Map<Long, String>> = _holders.asStateFlow()
    private val requests = ConcurrentHashMap<Long, (Boolean) -> Unit>()

    fun request(colorArgb: Long, onResult: (Boolean) -> Unit) {
        val eventId = sessionManager.sessionState.value.currentEvent?.id
        if (eventId == null) {
            onResult(false)
            return
        }
        val peerId = sessionManager.peerId.value
        if (sessionManager.sessionState.value.isHost) {
            applyGranted(colorArgb, peerId)
            transport.broadcastChatColourClaim(
                ChatColourClaimPayloadDto(
                    eventCode = eventId,
                    peerId = peerId,
                    colorArgb = colorArgb,
                    granted = true,
                    requestedAtMillis = System.currentTimeMillis(),
                )
            )
            onResult(true)
            return
        }

        transport.broadcastChatColourClaim(
            ChatColourClaimPayloadDto(
                eventCode = eventId,
                peerId = peerId,
                colorArgb = colorArgb,
                granted = null,
                requestedAtMillis = System.currentTimeMillis(),
            )
        )
        requests[colorArgb] = onResult
    }

    fun handleClaim(claim: ChatColourClaimPayloadDto) {
        val state = sessionManager.sessionState.value
        val eventId = state.currentEvent?.id ?: return
        if (claim.eventCode != eventId) return
        val localPeerId = sessionManager.peerId.value
        val colorArgb = claim.colorArgb

        if (state.isHost) {
            if (colorArgb == null) return
            val decision = ChatColourPolicy.evaluateClaim(
                claimColorArgb = colorArgb,
                claimPeerId = claim.peerId,
                currentHolders = _holders.value,
                localPeerId = localPeerId,
            )
            if (decision == ChatColourPolicy.Decision.GRANTED) {
                applyGranted(colorArgb, claim.peerId)
            }
            transport.broadcastChatColourClaim(
                ChatColourClaimPayloadDto(
                    eventCode = eventId,
                    peerId = claim.peerId,
                    colorArgb = colorArgb,
                    granted = decision == ChatColourPolicy.Decision.GRANTED,
                    requestedAtMillis = claim.requestedAtMillis,
                )
            )
            return
        }

        if (claim.granted == true && colorArgb != null) {
            applyGranted(colorArgb, claim.peerId)
        }
        if (claim.peerId != localPeerId || claim.granted == null || colorArgb == null) return
        requests.remove(colorArgb)?.invoke(claim.granted)
    }

    fun releaseDepartedMembers(remainingPeerIds: Set<String>) {
        _holders.update { current ->
            ChatColourPolicy.releaseDepartedMembers(current, remainingPeerIds)
        }
    }

    private fun applyGranted(colorArgb: Long, peerId: String) {
        _holders.update { current ->
            ChatColourPolicy.applyGrantedClaim(current, colorArgb, peerId)
        }
        val currentHistory = sessionManager.sessionState.value.chatMessages
        val updatedHistory = ChatBubbleColorPolicy.updateSenderColor(
            messages = currentHistory,
            senderPeerId = peerId,
            colorArgb = colorArgb,
        )
        if (updatedHistory != currentHistory) {
            sessionManager.receiveChatHistory(updatedHistory)
            chatCoordinator.broadcastHistory()
        }
    }
}
