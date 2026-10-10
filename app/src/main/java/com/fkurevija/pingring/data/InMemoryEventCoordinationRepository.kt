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
        // Stamped here rather than by the caller so every path into an event
        // records one, including the ones that arrive from the debug transport.
        // Re-joining the event already in progress keeps the original time:
        // a reconnect is not a new arrival, and moving it would let a peer that
        // dropped out win "first to join" by coming back.
        _sessionState.update {
            it.copy(
                currentEvent = event,
                isHost = false,
                joinedAtMillis = it.joinedAtMillis ?: System.currentTimeMillis(),
            )
        }
        markSynced()
    }

    override fun hostEvent(event: Event, peerId: String) {
        _sessionState.update {
            it.copy(
                currentEvent = event,
                isHost = true,
                joinedAtMillis = it.joinedAtMillis ?: System.currentTimeMillis(),
            )
        }
        markSynced()
    }

    override fun updateEvent(event: Event) {
        _sessionState.update { it.copy(currentEvent = event) }
        markSynced()
    }

    override fun leaveEvent(peerId: String) {
        _sessionState.update {
            // joinedAtMillis goes with the event: the next one is a new arrival
            // and must not inherit this one's place in the order.
            it.copy(
                currentEvent = null,
                isHost = false,
                friends = emptyList(),
                chatMessages = emptyList(),
                joinedAtMillis = null,
            )
        }
        _syncStatus.value = CoordinationSyncStatus()
    }

    override fun setUserName(name: String) {
        _sessionState.update { it.copy(userName = name) }
    }

    override fun renameMemberHistory(peerId: String, name: String) {
        if (peerId.isBlank() || name.isBlank()) return
        _sessionState.update { current ->
            current.copy(
                friends = current.friends.map { friend ->
                    if (friend.peerId == peerId) friend.copy(name = name) else friend
                },
                chatMessages = current.chatMessages.map { message ->
                    message.copy(
                        senderName = if (message.senderPeerId == peerId) name else message.senderName,
                        subjectName = if (message.subjectPeerId == peerId) name else message.subjectName,
                    )
                },
                activeBeacons = current.activeBeacons.mapValues { (senderPeerId, beacon) ->
                    if (senderPeerId == peerId) beacon.copy(senderName = name) else beacon
                },
            )
        }
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
            val index = current.chatMessages.indexOfFirst { it.id == message.id }
            when {
                index < 0 -> current.copy(chatMessages = current.chatMessages + message)
                current.chatMessages[index] == ChatReceiptPolicy.merge(current.chatMessages[index], message) -> current
                else -> current.copy(
                    chatMessages = current.chatMessages.mapIndexed { messageIndex, existing ->
                        if (messageIndex == index) ChatReceiptPolicy.merge(existing, message) else existing
                    },
                )
            }
        }
    }

    override fun receiveChatHistory(messages: List<ChatMessage>) {
        _sessionState.update { current ->
            val currentById = current.chatMessages.associateBy { it.id }
            val merged = messages.map { incoming ->
                currentById[incoming.id]?.let { ChatReceiptPolicy.merge(it, incoming) } ?: incoming
            }
            if (current.chatMessages == merged) current else current.copy(chatMessages = merged)
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
