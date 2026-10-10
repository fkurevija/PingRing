package com.fkurevija.pingring

import com.fkurevija.pingring.data.ChatMessageOwnership
import com.fkurevija.pingring.data.CoordinationSyncState
import com.fkurevija.pingring.data.CoordinationSyncStatus
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.RelayEnvelope
import com.fkurevija.pingring.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAndTransportRulesTest {
    @Test
    fun aMessageIsOursOnlyWhenThePeerIdsMatch() {
        assertTrue(ChatMessageOwnership.isOwnMessage("peer-a", "peer-a"))
    }

    @Test
    fun aMessageFromAnotherMemberIsNotOurs() {
        assertFalse(ChatMessageOwnership.isOwnMessage("peer-b", "peer-a"))
    }

    @Test
    fun aMessageWithNoKnownOriginIsNeverOurs() {
        assertFalse(ChatMessageOwnership.isOwnMessage(null, "peer-a"))
        assertFalse(ChatMessageOwnership.isOwnMessage("", "peer-a"))
        assertFalse(ChatMessageOwnership.isOwnMessage("   ", "peer-a"))
    }

    @Test
    fun withNoLocalIdentityNothingCanBeOurs() {
        assertFalse(ChatMessageOwnership.isOwnMessage("peer-a", null))
        assertFalse(ChatMessageOwnership.isOwnMessage("peer-a", ""))
    }

    @Test
    fun theRelayEnvelopeTypesAreDistinct() {
        val all = listOf(
            RelayEnvelope.JOIN, RelayEnvelope.DATA, RelayEnvelope.LEAVE,
            RelayEnvelope.END, RelayEnvelope.PING, RelayEnvelope.PEER_JOINED,
            RelayEnvelope.PEER_LEFT, RelayEnvelope.EVENT_CLOSED,
        )

        assertEquals(all.size, all.distinct().size)
    }

    @Test
    fun theRelayRolesAreDistinct() {
        assertNotEquals(RelayEnvelope.ROLE_HOST, RelayEnvelope.ROLE_MEMBER)
    }

    @Test
    fun aDefaultSessionHasNoEventAndIsNotHost() {
        val state = SessionState()

        assertEquals(null, state.currentEvent)
        assertFalse(state.isHost)
        assertTrue(state.friends.isEmpty())
        assertTrue(state.chatMessages.isEmpty())
    }

    @Test
    fun syncStatusStartsIdleAndCarriesNoError() {
        val status = CoordinationSyncStatus()

        assertEquals(CoordinationSyncState.IDLE, status.state)
        assertEquals(null, status.errorMessage)
    }

    @Test
    fun theSyncStatesAreDistinct() {
        val states = CoordinationSyncState.entries

        assertEquals(4, states.size)
        assertEquals(states.size, states.distinct().size)
    }

    @Test
    fun aCreatedEventIsOfferedForTheDocumentedLifetime() {
        val before = System.currentTimeMillis()
        val event = Event.create(id = "ABC", hostPeerId = "peer-a")
        val after = System.currentTimeMillis()

        assertTrue("the event must not be born already expired", event.expiryTimestamp > before)
        assertTrue(
            "expiry ${event.expiryTimestamp} outlives the documented lifetime",
            event.expiryTimestamp <= after + Event.DURATION_MILLIS,
        )
        assertEquals("peer-a", event.hostPeerId)
        assertTrue(event.useUwb)
    }

    @Test
    fun aCreatedEventCanBeCreatedWithoutUwbOrAHost() {
        val event = Event.create(id = "ABC", useUwb = false)

        assertFalse(event.useUwb)
        assertEquals(null, event.hostPeerId)
    }

    @Test
    fun twoCopiesOfTheSameEventAreEqual() {
        val event = Event.create(id = "ABC")

        assertEquals(event, event.copy())
        assertEquals(event.hashCode(), event.copy().hashCode())
    }

    @Test
    fun theParticipantLimitIsTheOneTheAppAdvertises() {
        assertTrue("a ring has to hold more than one member", MAX_PARTICIPANTS > 1)
    }
}
