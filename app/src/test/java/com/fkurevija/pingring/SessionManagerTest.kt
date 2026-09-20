package com.fkurevija.pingring

import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.data.SessionManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
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

    private fun event(id: String) = Event(
        id = id,
        name = id,
        description = "test",
        expiryTimestamp = Long.MAX_VALUE
    )
}
