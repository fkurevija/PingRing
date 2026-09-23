package com.fkurevija.pingring

import com.fkurevija.pingring.data.CoordinationSyncState
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.data.Friend
import com.fkurevija.pingring.data.InMemoryEventCoordinationRepository
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
}
