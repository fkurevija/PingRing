package com.fkurevija.pingring

import com.fkurevija.pingring.data.MembershipPolicy
import com.fkurevija.pingring.data.MembershipPolicy.ContactLoss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Losing contact is not leaving.
 *
 * Grouped by the failure each case prevents, because the destructive one is a
 * later "tidy up" that treats the member list as a cache of who is currently
 * reachable. That is what caused this: changing the tracked member re-points the
 * single UWB session, the tracked peer loses its transport first, and removing
 * it from `friends` left two phones in one ring each showing a single member.
 */
class MembershipPolicyTest {

    @Test
    fun `no kind of contact loss removes a member from the ring`() {
        // The assertion is "never", deliberately not "except when". A later edit
        // that wanted to drop a member nobody has heard from in a long time would
        // have to change this test on purpose rather than by adding a branch.
        ContactLoss.entries.forEach { loss ->
            assertFalse(
                "$loss must not remove a member from the ring",
                MembershipPolicy.leavesRingOnContactLoss(loss),
            )
        }
    }

    @Test
    fun `an authenticated graceful departure does remove a member`() {
        // The counterpart, so the rule is a distinction rather than a blanket
        // refusal: somebody who says they are leaving has left.
        assertTrue(
            MembershipPolicy.leavesRingOnGracefulDeparture(
                departureEventCode = "4C1A9B",
                currentEventCode = "4C1A9B",
                departurePeerId = "peer-a",
                localPeerId = "peer-me",
            )
        )
    }

    @Test
    fun `a departure for another ring does not touch this one`() {
        // Two rings can be running on two devices, and a departure is only a
        // statement about the ring it names. Honouring one from elsewhere would
        // remove a member who is very much present.
        assertFalse(
            MembershipPolicy.leavesRingOnGracefulDeparture(
                departureEventCode = "AAAAAA",
                currentEventCode = "4C1A9B",
                departurePeerId = "peer-a",
                localPeerId = "peer-me",
            )
        )
    }

    @Test
    fun `our own departure does not remove us from our own member list`() {
        // The local device sends one of these when it leaves. Honouring it would
        // clear the list that is about to be torn down anyway, but worse, it
        // would look like the last member being removed, which is not what
        // happened and is the kind of thing that shows up as a phantom event.
        assertFalse(
            MembershipPolicy.leavesRingOnGracefulDeparture(
                departureEventCode = "4C1A9B",
                currentEventCode = "4C1A9B",
                departurePeerId = "peer-me",
                localPeerId = "peer-me",
            )
        )
    }

    @Test
    fun `a departure while no ring is active removes nobody`() {
        assertEquals(
            0,
            ContactLoss.entries.count { MembershipPolicy.leavesRingOnContactLoss(it) },
        )
        assertFalse(
            MembershipPolicy.leavesRingOnGracefulDeparture(
                departureEventCode = "4C1A9B",
                currentEventCode = null,
                departurePeerId = "peer-a",
                localPeerId = "peer-me",
            )
        )
    }
}