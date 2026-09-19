package com.fkurevija.pingring.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class SessionManager(
    private val coordinationRepository: EventCoordinationRepository = InMemoryEventCoordinationRepository()
) {
    val sessionState: StateFlow<SessionState> = coordinationRepository.sessionState
    val syncStatus: StateFlow<CoordinationSyncStatus> = coordinationRepository.syncStatus

    private val _peerId = MutableStateFlow(generatePeerId())
    val peerId: StateFlow<String> = _peerId.asStateFlow()

    fun setVisibility(visible: Boolean) {
        coordinationRepository.setVisibility(visible)
    }

    fun joinEvent(event: Event) {
        coordinationRepository.markSyncing()
        coordinationRepository.joinEvent(event)
        // In a real app, this would trigger Bluetooth advertising/scanning
    }

    fun leaveEvent() {
        coordinationRepository.leaveEvent()
        _peerId.value = generatePeerId() // Rotate peer ID for privacy
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
}
