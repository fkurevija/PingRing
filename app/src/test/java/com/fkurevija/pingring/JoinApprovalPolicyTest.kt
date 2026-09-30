package com.fkurevija.pingring

import com.fkurevija.pingring.data.JOIN_REQUEST_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.JoinRequestDecision
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.decideJoinRequest
import com.fkurevija.pingring.data.expiredJoinRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a device asking to join is refused, admitted, or held for the host.
 *
 * Two things must not be broken by this: a guest must never be gated by the
 * host's admission policy, and a request the host could not have admitted anyway
 * must not become a question for them.
 */
class JoinApprovalPolicyTest {

    private fun decide(
        isIncoming: Boolean = true,
        requiresApproval: Boolean = false,
        acceptsNewMembers: Boolean = true,
        connectedPeerCount: Int = 0,
    ) = decideJoinRequest(
        isIncoming = isIncoming,
        requiresApproval = requiresApproval,
        acceptsNewMembers = acceptsNewMembers,
        connectedPeerCount = connectedPeerCount,
    )

    @Test
    fun `a guest is admitted when the host does not ask`() {
        assertEquals(JoinRequestDecision.Admit, decide())
    }

    @Test
    fun `an incoming request is held when approval is required`() {
        assertEquals(JoinRequestDecision.AwaitApproval, decide(requiresApproval = true))
    }

    @Test
    fun `a guest is never asked to approve anything`() {
        // isIncoming = false is the guest answering the host's half of a
        // handshake it started. Gating it would leave the guest on a connection
        // that never establishes and receiving nothing, which is
        // indistinguishable from broken chat.
        assertEquals(JoinRequestDecision.Admit, decide(isIncoming = false, requiresApproval = true))
    }

    @Test
    fun `a closed host refuses rather than asking`() {
        // The standing "no" is not a question for a human to answer, and
        // refusing straight away means a host with the switch off is never
        // prompted for joins it has already said it does not want.
        assertEquals(
            JoinRequestDecision.Refuse,
            decide(requiresApproval = true, acceptsNewMembers = false)
        )
    }

    @Test
    fun `a full host refuses rather than asking`() {
        // +1 for the device asking, and for the host itself.
        assertEquals(
            JoinRequestDecision.Refuse,
            decide(requiresApproval = true, connectedPeerCount = MAX_PARTICIPANTS - 1)
        )
    }

    @Test
    fun `one slot left is still admitable`() {
        // The boundary: refusing here would make the ring one short of the limit
        // it advertises.
        assertEquals(
            JoinRequestDecision.AwaitApproval,
            decide(requiresApproval = true, connectedPeerCount = MAX_PARTICIPANTS - 2)
        )
    }

    @Test
    fun `an unanswered request expires rather than being pinned forever`() {
        val request = PendingJoinRequest("e1", "Ana", requestedAtMillis = 1_000L)

        assertNull(
            "a request inside the window must survive",
            expiredJoinRequest(request, nowMillis = 1_000L + JOIN_REQUEST_TIMEOUT_MILLIS)
        )
        assertSame(
            request,
            expiredJoinRequest(request, nowMillis = 1_000L + JOIN_REQUEST_TIMEOUT_MILLIS + 1)
        )
    }

    @Test
    fun `the request timeout is long enough to be usable`() {
        // Not a policy so much as a guard: a window a host cannot come back
        // within turns approval into a coin flip rather than a decision.
        assertTrue(
            "the host needs a usable window to answer in",
            JOIN_REQUEST_TIMEOUT_MILLIS >= 60_000L
        )
    }

    @Test
    fun `a guest waiting outlasts the host giving up on it`() {
        // If the guest's own wait were shorter, a host that times out first
        // would leave the guest reporting an answer as a failed join - telling
        // somebody they could not join a PingRing that was merely slow to answer.
        // The margin is the same 15s MainActivity adds to the request window.
        val guestWait = JOIN_REQUEST_TIMEOUT_MILLIS + 15_000L

        assertTrue(
            "the guest must wait longer than the host holds a request",
            guestWait > JOIN_REQUEST_TIMEOUT_MILLIS
        )
    }
}
