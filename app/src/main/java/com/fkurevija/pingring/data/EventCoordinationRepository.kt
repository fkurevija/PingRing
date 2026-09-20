package com.fkurevija.pingring.data

import kotlinx.coroutines.flow.StateFlow

enum class CoordinationSyncState {
    IDLE,
    SYNCING,
    SYNCED,
    ERROR
}

data class CoordinationSyncStatus(
    val state: CoordinationSyncState = CoordinationSyncState.IDLE,
    val errorMessage: String? = null
)

interface EventCoordinationRepository {
    val sessionState: StateFlow<SessionState>
    val syncStatus: StateFlow<CoordinationSyncStatus>

    fun joinEvent(event: Event)
    fun hostEvent(event: Event)
    fun updateEvent(event: Event)
    fun leaveEvent()
    fun setVisibility(visible: Boolean)
    fun updateMembers(newMembers: List<Friend>)
    fun upsertMember(member: Friend)
    fun markSyncing()
    fun markSynced()
    fun markSyncError(message: String)
}
