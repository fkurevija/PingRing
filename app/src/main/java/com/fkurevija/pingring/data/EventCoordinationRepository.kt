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

    fun joinEvent(event: Event, peerId: String)
    fun hostEvent(event: Event, peerId: String)
    fun updateEvent(event: Event)
    fun leaveEvent(peerId: String)
    fun setUserName(name: String)
    fun renameMemberHistory(peerId: String, name: String)
    fun updateMembers(newMembers: List<Friend>)
    fun upsertMember(member: Friend)
    fun receiveChatMessage(message: ChatMessage)
    fun receiveChatHistory(messages: List<ChatMessage>)
    fun markSyncing()
    fun markSynced()
    fun markSyncError(message: String)
}
