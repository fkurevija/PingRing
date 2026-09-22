package com.fkurevija.pingring.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class SessionManager(
    context: android.content.Context? = null,
    private val coordinationRepository: EventCoordinationRepository = InMemoryEventCoordinationRepository()
) {
    val sessionState: StateFlow<SessionState> = coordinationRepository.sessionState
    val syncStatus: StateFlow<CoordinationSyncStatus> = coordinationRepository.syncStatus

    private val _peerId = MutableStateFlow(generatePeerId())
    val peerId: StateFlow<String> = _peerId.asStateFlow()

    fun setVisibility(visible: Boolean) {
        coordinationRepository.setVisibility(visible)
    }

    fun joinEvent(event: Event): Boolean {
        val activeEvent = sessionState.value.currentEvent
        if (activeEvent != null && activeEvent.id != event.id) {
            return false
        }
        coordinationRepository.markSyncing()
        coordinationRepository.joinEvent(event, peerId.value)
        // In a real app, this would trigger Bluetooth advertising/scanning
        return true
    }

    fun hostEvent(event: Event) {
        check(sessionState.value.currentEvent == null) {
            "Leave the active Ping-Ring before hosting another one."
        }
        coordinationRepository.markSyncing()
        coordinationRepository.hostEvent(event, peerId.value)
        // Hosting currently uses the same local BLE mesh as joining.
    }

    fun updateEvent(event: Event) {
        coordinationRepository.updateEvent(event)
    }

    fun receiveLocalChatMessage(message: ChatMessage) {
        coordinationRepository.receiveChatMessage(message)
    }

    fun leaveEvent() {
        coordinationRepository.leaveEvent(peerId.value)
        _peerId.value = generatePeerId() // Rotate peer ID for privacy
    }

    fun endHostedEvent() {
        check(sessionState.value.isHost) { "Only the host can end a Ping-Ring." }
        leaveEvent()
    }

    fun endEventFromHost() {
        leaveEvent()
    }

    fun updateFriends(newFriends: List<Friend>) {
        coordinationRepository.updateMembers(newFriends)
    }

    private fun generatePeerId(): String {
        return UUID.randomUUID().toString().substring(0, 8)
    }

    fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        sessionState.value.currentEvent?.let { event ->
            if (event.expiryTimestamp < now) {
                leaveEvent()
            }
        }
    }

    fun refreshCoordination() {
        // Coordination is now handled P2P via Nearby Connections in DiscoveryRepository
    }
}
