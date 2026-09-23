package com.fkurevija.pingring.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryEventCoordinationRepository : EventCoordinationRepository {
    private val _sessionState = MutableStateFlow(SessionState())
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _syncStatus = MutableStateFlow(CoordinationSyncStatus())
    override val syncStatus: StateFlow<CoordinationSyncStatus> = _syncStatus.asStateFlow()

    override fun joinEvent(event: Event, peerId: String) {
        _sessionState.update { it.copy(currentEvent = event, isHost = false, isVisible = true) }
        markSynced()
    }

    override fun hostEvent(event: Event, peerId: String) {
        _sessionState.update { it.copy(currentEvent = event, isHost = true, isVisible = true) }
        markSynced()
    }

    override fun updateEvent(event: Event) {
        _sessionState.update { it.copy(currentEvent = event) }
        markSynced()
    }

    override fun leaveEvent(peerId: String) {
        _sessionState.update {
            it.copy(currentEvent = null, isHost = false, isVisible = false, friends = emptyList(), chatMessages = emptyList())
        }
        _syncStatus.value = CoordinationSyncStatus()
    }

    override fun setVisibility(visible: Boolean) {
        _sessionState.update { it.copy(isVisible = visible) }
    }

    override fun setUserName(name: String) {
        _sessionState.update { it.copy(userName = name) }
    }

    override fun updateMembers(newMembers: List<Friend>) {
        _sessionState.update { it.copy(friends = newMembers) }
        if (_sessionState.value.currentEvent != null) {
            markSynced()
        }
    }

    override fun upsertMember(member: Friend) {
        val updatedFriends = _sessionState.value.friends
            .filterNot { it.peerId == member.peerId } + member
        updateMembers(updatedFriends)
    }

    override fun receiveChatMessage(message: ChatMessage) {
        _sessionState.update { current ->
            if (current.chatMessages.any { it.id == message.id }) {
                current
            } else {
                current.copy(chatMessages = current.chatMessages + message)
            }
        }
    }

    override fun markSyncing() {
        _syncStatus.value = CoordinationSyncStatus(state = CoordinationSyncState.SYNCING)
    }

    override fun markSynced() {
        _syncStatus.value = CoordinationSyncStatus(state = CoordinationSyncState.SYNCED)
    }

    override fun markSyncError(message: String) {
        _syncStatus.value = CoordinationSyncStatus(
            state = CoordinationSyncState.ERROR,
            errorMessage = message
        )
    }
}
