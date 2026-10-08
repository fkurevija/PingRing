package com.fkurevija.pingring

import com.fkurevija.pingring.data.CoordinationSyncState
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.data.Friend
import com.fkurevija.pingring.data.InMemoryEventCoordinationRepository
import com.fkurevija.pingring.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryEventCoordinationRepositoryTest {

    @Test
    fun joinAndPresenceUpdatesRemainLocalAndQueryable() {
        val repository = InMemoryEventCoordinationRepository()
        val event = Event("E1", "Event E1", "desc", System.currentTimeMillis() + 1_000L)

        repository.markSyncing()
        repository.joinEvent(event, "peer-1")
        repository.upsertMember(
            Friend(
                peerId = "peer-1",
                name = "Peer 1",
                lastSeen = 100L,
                distance = 1.2
            )
        )

        val state = repository.sessionState.value
        assertEquals("E1", state.currentEvent?.id)
        assertEquals(1, state.friends.size)
        assertEquals(CoordinationSyncState.SYNCED, repository.syncStatus.value.state)
    }

    @Test
    fun leaveClearsMembershipAndResetsSyncStatus() {
        val repository = InMemoryEventCoordinationRepository()
        repository.joinEvent(
            Event("E2", "Event E2", "desc", System.currentTimeMillis() + 1_000L),
            "peer-2"
        )
        repository.updateMembers(
            listOf(Friend("peer-2", "Peer 2", 200L, null))
        )

        repository.leaveEvent("peer-2")

        val state = repository.sessionState.value
        assertNull(state.currentEvent)
        assertEquals(emptyList<Friend>(), state.friends)
        assertEquals(CoordinationSyncState.IDLE, repository.syncStatus.value.state)
    }

    @Test
    fun syncErrorIsExposedForFutureBackendAdapters() {
        val repository = InMemoryEventCoordinationRepository()

        repository.markSyncError("network timeout")

        assertEquals(CoordinationSyncState.ERROR, repository.syncStatus.value.state)
        assertEquals("network timeout", repository.syncStatus.value.errorMessage)
    }

    @Test
    fun renamingMemberUpdatesTheirMembershipAndEarlierChatMessages() {
        val repository = InMemoryEventCoordinationRepository()
        repository.updateMembers(listOf(Friend("peer-1", "Old name", 100L, 2.0)))
        repository.receiveChatMessage(
            ChatMessage("message-1", "peer-1", "Old name", "Hello", 200L),
        )

        repository.renameMemberHistory("peer-1", "New name")

        assertEquals("New name", repository.sessionState.value.friends.single().name)
        assertEquals("New name", repository.sessionState.value.chatMessages.single().senderName)
    }

    @Test
    fun receivingChatHistoryReplacesStaleEntriesAndUpdatesExistingMessages() {
        val repository = InMemoryEventCoordinationRepository()
        repository.receiveChatMessage(ChatMessage("message-1", "peer-1", "Old name", "Hello", 200L))
        repository.receiveChatMessage(ChatMessage("stale-message", "peer-2", "Peer 2", "Stale", 300L))

        val updatedMessage = ChatMessage("message-1", "peer-1", "New name", "Hello", 200L)
        repository.receiveChatHistory(listOf(updatedMessage))

        assertEquals(listOf(updatedMessage), repository.sessionState.value.chatMessages)
    }
}
