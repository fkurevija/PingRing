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

    override fun joinEvent(event: Event) {
        _sessionState.update { it.copy(currentEvent = event, isVisible = true) }
        markSynced()
    }

    override fun leaveEvent() {
        _sessionState.update { it.copy(currentEvent = null, isVisible = false, friends = emptyList()) }
        _syncStatus.value = CoordinationSyncStatus()
    }

    override fun setVisibility(visible: Boolean) {
        _sessionState.update { it.copy(isVisible = visible) }
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
