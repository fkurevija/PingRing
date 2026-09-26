package com.fkurevija.pingring

import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.shouldRefuseConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host's admission policy is applied to devices asking to join, and only
 * to those. A guest answering the host's half of the handshake must always be
 * let in: refusing there used to leave guests with a connection that never
 * established, so they received no chat at all.
 */
class NearbyAdmissionPolicyTest {

    @Test
    fun aHostTurnsAwayDevicesWhenItIsClosed() {
        assertTrue(shouldRefuseConnection(isIncoming = true, acceptsNewMembers = false, connectedPeerCount = 0))
    }

    @Test
    fun aHostTurnsAwayDevicesOnceItIsFull() {
        // +1 for the device asking, and for the host itself.
        assertFalse(shouldRefuseConnection(isIncoming = true, acceptsNewMembers = true, connectedPeerCount = MAX_PARTICIPANTS - 2))
        assertTrue(shouldRefuseConnection(isIncoming = true, acceptsNewMembers = true, connectedPeerCount = MAX_PARTICIPANTS - 1))
    }

    @Test
    fun aHostLetsDevicesInWhileThereIsRoom() {
        assertFalse(shouldRefuseConnection(isIncoming = true, acceptsNewMembers = true, connectedPeerCount = 0))
    }

    @Test
    fun answeringOurOwnRequestIsNeverGatedByTheHostPolicy() {
        // The regression: a guest whose own event copy has the switch off (or
        // is full) must still connect to the host it asked for.
        assertFalse(shouldRefuseConnection(isIncoming = false, acceptsNewMembers = false, connectedPeerCount = 0))
        assertFalse(shouldRefuseConnection(isIncoming = false, acceptsNewMembers = false, connectedPeerCount = MAX_PARTICIPANTS))
    }
}