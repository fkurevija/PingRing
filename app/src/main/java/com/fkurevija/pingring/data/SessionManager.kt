package com.fkurevija.pingring.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class SessionManager {
    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _peerId = MutableStateFlow(generatePeerId())
    val peerId: StateFlow<String> = _peerId.asStateFlow()

    fun setVisibility(visible: Boolean) {
        _sessionState.update { it.copy(isVisible = visible) }
    }

    fun joinEvent(event: Event) {
        _sessionState.update { it.copy(currentEvent = event, isVisible = true) }
        // In a real app, this would trigger Bluetooth advertising/scanning
    }

    fun leaveEvent() {
        _sessionState.update { it.copy(currentEvent = null, isVisible = false, friends = emptyList()) }
        _peerId.value = generatePeerId() // Rotate peer ID for privacy
    }

    fun updateFriends(newFriends: List<Friend>) {
        _sessionState.update { it.copy(friends = newFriends) }
    }

    private fun generatePeerId(): String {
        return UUID.randomUUID().toString().substring(0, 8)
    }

    fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        _sessionState.value.currentEvent?.let { event ->
            if (event.expiryTimestamp < now) {
                leaveEvent()
            }
        }
    }
}
