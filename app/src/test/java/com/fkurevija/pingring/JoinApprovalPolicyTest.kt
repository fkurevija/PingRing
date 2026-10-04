package com.fkurevija.pingring

import com.fkurevija.pingring.data.JOIN_APPROVAL_GRACE_MILLIS
import com.fkurevija.pingring.data.JOIN_REQUEST_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.MIN_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.JoinRequestDecision
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.decideJoinRequest
import com.fkurevija.pingring.data.expiredJoinRequest
import com.fkurevija.pingring.data.sweepExpiredJoinRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        //
        // Down from two minutes to 45s, which is a real reduction and worth
        // naming rather than quietly relaxing. The trade is deliberate: a host who
        // has the phone in hand answers in seconds, and a host who has walked away
        // is not answering at all - two minutes of the guest staring at a spinner
        // buys nothing there. What is left has to cover noticing a request,
        // reading who it is from, and tapping Allow.
        //
        // If this is ever raised again, raise the guest's wait with it - the
        // invariant below is what stops a slow host being reported as a failure.
        assertTrue(
            "the host needs a usable window to answer in",
            JOIN_REQUEST_TIMEOUT_MILLIS >= 30_000L
        )
    }

    @Test
    fun `a guest waiting outlasts the host giving up on it`() {
        // If the guest's own wait were shorter, a host that times out first
        // would leave the guest reporting an answer as a failed join - telling
        // somebody they could not join a PingRing that was merely slow to answer.
        // The margin is JOIN_APPROVAL_GRACE_MILLIS rather than a literal, because it is
        // now the thing that fixes the slider's floor: MainActivity derives the
        // shortest safe guest wait from these two constants. A literal here would
        // pass while the slider quietly offered a value that raced the host.
        val guestWait = JOIN_REQUEST_TIMEOUT_MILLIS + JOIN_APPROVAL_GRACE_MILLIS

        assertTrue(
            "the guest must wait longer than the host holds a request",
            guestWait > JOIN_REQUEST_TIMEOUT_MILLIS
        )
        assertTrue(
            "the slider's floor must not race the host either",
            MIN_JOIN_WAIT_TIMEOUT_MILLIS >= guestWait
        )
    }

    @Test
    fun `a sweep refuses every request past the window and keeps the rest`() {
        // The behaviour the host-side timeout exists for: one stale request in a
        // queue must not shield the fresh ones behind it, and one fresh request
        // must not protect a stale one beside it.
        val stale = PendingJoinRequest("old", "Ana", requestedAtMillis = 0L)
        val fresh = PendingJoinRequest("new", "Bo", requestedAtMillis = JOIN_REQUEST_TIMEOUT_MILLIS)
        val now = JOIN_REQUEST_TIMEOUT_MILLIS + 5_000L

        val sweep = sweepExpiredJoinRequests(listOf(stale, fresh), now)

        assertEquals("only the stale request should expire", listOf(stale), sweep.expired)
    }

    @Test
    fun `a sweep of an empty queue asks for no further check`() {
        // Null rather than zero: an idle host should stop the timer entirely
        // instead of waking repeatedly to find nothing pending.
        val sweep = sweepExpiredJoinRequests(emptyList(), nowMillis = 1_000L)

        assertNull("nothing pending, nothing to check", sweep.nextCheckInMillis)
        assertTrue("nothing pending, nothing to refuse", sweep.expired.isEmpty())
    }

    @Test
    fun `the sweep wakes exactly when the oldest request is due`() {
        // Timing the next wake to the oldest pending request is what keeps an
        // idle host at zero wakeups. Getting this wrong in the slow direction
        // leaves stale requests on screen past their window; in the fast
        // direction it just burns battery.
        val oldest = PendingJoinRequest("a", "Ana", requestedAtMillis = 0L)
        val freshest = PendingJoinRequest(
            "b", "Bo", requestedAtMillis = JOIN_REQUEST_TIMEOUT_MILLIS / 2
        )
        val now = JOIN_REQUEST_TIMEOUT_MILLIS / 4

        val sweep = sweepExpiredJoinRequests(listOf(freshest, oldest), now)

        assertEquals(
            "should wake when the oldest request expires",
            JOIN_REQUEST_TIMEOUT_MILLIS - now,
            sweep.nextCheckInMillis
        )
    }

    @Test
    fun `a sweep only ever schedules a survivor, never an expired one`() {
        // The clamp on nextCheckInMillis is defensive: by construction anything
        // left in the survivors list has NOT expired, so its remaining time is
        // positive by definition. Asserting that here pins the property the
        // clamp protects - Handler.postDelayed accepts a negative delay and
        // fires immediately, which would turn one sweep into a spin.
        val now = JOIN_REQUEST_TIMEOUT_MILLIS * 2
        val overdue = PendingJoinRequest("a", "Ana", requestedAtMillis = 0L)
        val alsoOverdue = PendingJoinRequest(
            "b", "Bo", requestedAtMillis = -JOIN_REQUEST_TIMEOUT_MILLIS - 1L
        )

        // Both are past the window at this instant: nothing survives, so there
        // is nothing left to wake for.
        val allExpired = sweepExpiredJoinRequests(listOf(overdue, alsoOverdue), nowMillis = now)
        assertEquals("both are overdue", 2, allExpired.expired.size)
        assertNull("nothing left to wake for", allExpired.nextCheckInMillis)

        // A survivor is scheduled with time remaining, never zero or negative.
        val waiting = PendingJoinRequest("c", "Cy", requestedAtMillis = now)
        val sweep = sweepExpiredJoinRequests(listOf(waiting), nowMillis = now)
        assertEquals(
            "a brand new request gets the full window",
            JOIN_REQUEST_TIMEOUT_MILLIS,
            sweep.nextCheckInMillis
        )
    }

    @Test
    fun `a request is expired once the window has passed, whatever its timestamp`() {
        // The clock is a parameter precisely so it can be wrong. A device whose
        // clock jumps forward between the request arriving and the sweep running
        // must expire it rather than compute a wildly negative delay.
        val request = PendingJoinRequest("e1", "Ana", requestedAtMillis = 0L)

        assertNotNull(
            "a request well past the window must expire",
            expiredJoinRequest(request, nowMillis = JOIN_REQUEST_TIMEOUT_MILLIS * 2)
        )
    }
}
