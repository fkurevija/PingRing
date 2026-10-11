package com.fkurevija.pingring.data.session
import com.fkurevija.pingring.data.session.ChatMessage
import com.fkurevija.pingring.data.session.Event
import com.fkurevija.pingring.data.session.Friend
import com.fkurevija.pingring.data.session.SessionManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    @Test
    fun aCancelledJoinDoesNotFallThroughToASecondFailureNotification() = runTest {
        val sessionManager = SessionManager()
        sessionManager.joinEvent(event("joining"))
        var failures = 0
        var completedWaits = 0
        val join = launch(start = CoroutineStart.UNDISPATCHED) {
            val connected = sessionManager.awaitConnectedPeer(60_000L)
            completedWaits++
            if (!connected) failures++
        }

        join.cancel()
        sessionManager.leaveEvent()
        failures++ // The rejection handler's notification.
        join.join()

        assertEquals(0, completedWaits)
        assertEquals(1, failures)
    }

    @Test
    fun anUnansweredJoinTimesOutAsAFailure() = runTest {
        val sessionManager = SessionManager()
        sessionManager.joinEvent(event("joining"))

        assertFalse(sessionManager.awaitConnectedPeer(10_000L))
    }

    @Test
    fun anAdmittedJoinCompletesSuccessfully() = runTest {
        val sessionManager = SessionManager()
        sessionManager.joinEvent(event("joining"))
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            sessionManager.awaitConnectedPeer(60_000L)
        }

        sessionManager.updateFriends(listOf(Friend("host", "Host", 1L)))

        assertTrue(result.await())
        assertTrue(sessionManager.awaitConnectedPeer(60_000L))
    }

    @Test
    fun joiningAnotherPingRingWhileActiveIsRejected() {
        val sessionManager = SessionManager()

        assertTrue(sessionManager.joinEvent(event("first")))
        assertFalse(sessionManager.joinEvent(event("second")))
        assertTrue(sessionManager.sessionState.value.currentEvent?.id == "first")
    }

    @Test
    fun endingHostedPingRingClearsTheHostSession() {
        val sessionManager = SessionManager()

        sessionManager.hostEvent(event("hosted"))
        sessionManager.endHostedEvent()

        assertNull(sessionManager.sessionState.value.currentEvent)
        assertFalse(sessionManager.sessionState.value.isHost)
    }

    @Test
    fun rejoiningTheSameEventIsAllowed() {
        val sessionManager = SessionManager()
        val event = event("same")

        // The guard is on the *id*, not on "is something active": a re-scan of the
        // same code must not be treated as trying to switch rings.
        assertTrue(sessionManager.joinEvent(event))
        assertTrue(sessionManager.joinEvent(event.copy(name = "renamed")))
    }

    @Test
    fun hostingWhileAlreadyInAPingRingIsRefused() {
        val sessionManager = SessionManager()
        sessionManager.hostEvent(event("first"))

        val failure = runCatching { sessionManager.hostEvent(event("second")) }.exceptionOrNull()

        assertTrue("hosting a second event must be refused", failure is IllegalStateException)
        assertEquals("first", sessionManager.sessionState.value.currentEvent?.id)
    }

    @Test
    fun aGuestCannotEndThePingRing() {
        val sessionManager = SessionManager()
        sessionManager.joinEvent(event("joined"))

        // A guest reaching this is a bug in the caller, not something to absorb:
        // ending the ring is the host's decision alone.
        val failure = runCatching { sessionManager.endHostedEvent() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("joined", sessionManager.sessionState.value.currentEvent?.id)
    }
    @Test
    fun leavingRotatesThePeerIdSoTheNextPingRingIsNotRecognised() {
        val sessionManager = SessionManager()
        val before = sessionManager.peerId.value

        sessionManager.joinEvent(event("first"))
        sessionManager.leaveEvent()

        // Peer ids are the routing identity. Reusing one across events would let a
        // device that was in the previous ring be mistaken for a member of this one.
        assertNotEquals(before, sessionManager.peerId.value)
    }

    @Test
    fun aPeerIdIsEightHexCharacters() {
        val peerId = SessionManager().peerId.value

        assertEquals(8, peerId.length)
        assertTrue("a peer id must be hex, got '$peerId'", peerId.all { it in "0123456789abcdef" })
    }

    @Test
    fun anExpiredEventIsDroppedWhenSessionsAreSwept() {
        val sessionManager = SessionManager()
        sessionManager.joinEvent(event("stale", expiry = 1L))

        sessionManager.cleanupExpiredSessions()

        assertNull(sessionManager.sessionState.value.currentEvent)
    }

    @Test
    fun anEventStillInDateSurvivesTheSweep() {
        val sessionManager = SessionManager()
        sessionManager.joinEvent(event("fresh", expiry = Long.MAX_VALUE))

        sessionManager.cleanupExpiredSessions()

        assertEquals("fresh", sessionManager.sessionState.value.currentEvent?.id)
    }

    @Test
    fun sweepingWithNoEventDoesNothing() {
        val sessionManager = SessionManager()
        val before = sessionManager.peerId.value

        sessionManager.cleanupExpiredSessions()

        // No event means no reason to rotate, so the identity has to survive.
        assertEquals(before, sessionManager.peerId.value)
    }

    @Test
    fun membershipAndChatAreClearedWhenTheEventEnds() {
        val sessionManager = SessionManager()
        sessionManager.hostEvent(event("hosted"))
        sessionManager.updateFriends(listOf(Friend("p1", "Ana", 1L)))
        sessionManager.receiveLocalChatMessage(chat("m1"))

        sessionManager.endHostedEvent()

        assertTrue(sessionManager.sessionState.value.friends.isEmpty())
        assertTrue(sessionManager.sessionState.value.chatMessages.isEmpty())
    }

    @Test
    fun theDisplayNameIsHeldOnTheSession() {
        val sessionManager = SessionManager()

        sessionManager.setUserName("Ana")

        assertEquals("Ana", sessionManager.sessionState.value.userName)
    }

    @Test
    fun refreshingCoordinationIsAcceptedAndChangesNothing() {
        val sessionManager = SessionManager()
        sessionManager.joinEvent(event("first"))
        val before = sessionManager.sessionState.value

        // Coordination moved to the transport; the call is kept so callers still
        // compile, and it has to be a no-op rather than a state reset.
        sessionManager.refreshCoordination()

        assertEquals(before, sessionManager.sessionState.value)
    }

    @Test
    fun anUpdateReplacesTheEventInPlace() {
        val sessionManager = SessionManager()
        sessionManager.joinEvent(event("first"))

        sessionManager.updateEvent(event("first", name = "Renamed"))

        assertEquals("Renamed", sessionManager.sessionState.value.currentEvent?.name)
    }

    @Test
    fun endingFromTheHostSignalsLeaveForAHostSession() {
        val sessionManager = SessionManager()
        sessionManager.hostEvent(event("hosted"))

        sessionManager.endEventFromHost()

        assertNull(sessionManager.sessionState.value.currentEvent)
    }

    private fun chat(id: String) = ChatMessage(
        id = id,
        senderPeerId = "peer-a",
        senderName = "Ana",
        messageText = "hi",
        timestamp = 1L
    )

    private fun event(id: String, expiry: Long = Long.MAX_VALUE, name: String = id) = Event(
        id = id,
        name = name,
        description = "test",
        expiryTimestamp = expiry
    )
}
