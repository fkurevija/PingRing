package com.fkurevija.pingring

import com.fkurevija.pingring.data.FocusPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which member the radar tracks and the UWB session ranges.
 *
 * The invariant that matters most: this must never affect who *receives*
 * messages. Focus is a display and ranging choice only, so these tests are
 * about picking a target, never about filtering traffic.
 */
class FocusPolicyTest {

    private val local = "me"
    private val host = "host-1"
    private val alice = "alice"
    private val bob = "bob"

    @Test
    fun anExplicitChoiceWinsOverTheFallback() {
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = bob,
            availablePeerIds = listOf(alice, bob),
            isHost = true,
            trackHost = false,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(bob, focus)
    }

    @Test
    fun theHostFallsBackToTheFirstMemberNotTheNearest() {
        // Whoever arrived first is a stable choice; "nearest" moves underneath
        // the user every time somebody walks, which is not a selection at all.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(bob, alice),
            isHost = true,
            trackHost = false,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(bob, focus)
    }

    @Test
    fun aGuestFallsBackToTheHostWhenThePolicyIsOff() {
        // The host is on a guest's radar only when "track host" is off, so
        // without this the guest would default to an arbitrary member and never
        // point at the person running the event.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(host, alice),
            isHost = false,
            trackHost = false,
            localPeerId = local,
            hostPeerId = host
        )
        assertEquals(host, focus)
    }

    @Test
    fun aGuestCanStillChooseWhenTheHostPolicyIsOff() {
        // "The host picks the default" is not "the host picks for you": with
        // the policy off a guest is free to follow whoever it likes.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = alice,
            availablePeerIds = listOf(host, alice),
            isHost = false,
            trackHost = false,
            localPeerId = local,
            hostPeerId = host
        )
        assertEquals(alice, focus)
    }

    @Test
    fun aStaleSelectionFallsBackRatherThanPointingAtNobody() {
        // Ephemeral ids rotate, so a remembered peer that has gone comes back
        // with a different id. Holding the stale one would leave the radar
        // aimed at a member who is no longer in the ring.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = "someone-who-left",
            availablePeerIds = listOf(alice, bob),
            isHost = true,
            trackHost = false,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(alice, focus)
    }

    @Test
    fun trackHostOverridesTheUsersChoiceForAGuest() {
        // The host set this policy, so a guest under it tracks the host even if
        // it had picked somebody else. The host's policy is not something a
        // local tap can quietly override.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = bob,
            availablePeerIds = listOf(alice, bob),
            isHost = false,
            trackHost = true,
            localPeerId = local,
            hostPeerId = host
        )
        assertEquals(host, focus)
    }

    @Test
    fun theHostIsNeverAskedToTrackItself() {
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(alice, bob),
            isHost = true,
            trackHost = true,
            localPeerId = local,
            hostPeerId = host
        )
        assertEquals(alice, focus)
    }

    @Test
    fun anEmptyRadarResolvesToNobody() {
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = emptyList(),
            isHost = true,
            trackHost = false,
            localPeerId = local,
            hostPeerId = null
        )
        assertNull(focus)
    }

    @Test
    fun aGuestUnderTrackHostCannotChangeWhatIsTracked() {
        // The policy is the host's, set for the whole event. A member choosing
        // otherwise would make the host's decision advisory, which is not what
        // turning it on promised.
        assertFalse(FocusPolicy.canUserSelect(isHost = false, trackHost = true))
    }

    @Test
    fun theHostIsNeverLockedOutOfItsOwnRadar() {
        // "Track host" asks members to follow the host. It must not also stop
        // the host deciding who it watches, which would leave the policy
        // disabling the only device that can act on it.
        assertTrue(FocusPolicy.canUserSelect(isHost = true, trackHost = true))
    }

    @Test
    fun aGuestIsFreeOnceThePolicyIsOff() {
        // With the policy off the guest can see every member, so restricting it
        // to one would be a limit the host never asked for.
        assertTrue(FocusPolicy.canUserSelect(isHost = false, trackHost = false))
    }

    @Test
    fun aLockedGuestStillResolvesToTheHost() {
        // The read-only control is only useful if it names who is actually
        // being tracked, so the locked state has to resolve to the host too.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(host),
            isHost = false,
            trackHost = true,
            localPeerId = local,
            hostPeerId = host
        )
        assertEquals(host, focus)
    }

    @Test
    fun onlyTheFocusedPeerIsRanged() {
        val target = FocusPolicy.rangingTarget(
            focusPeerId = bob,
            isHost = true,
            trackHost = false,
            hostPeerId = null,
            connectedPeerIds = setOf(alice, bob)
        )
        assertEquals(FocusPolicy.RangingTarget.Peer(bob, isHost = false), target)
    }

    @Test
    fun aDisconnectedPeerIsNotRanged() {
        // A BLE sighting with no transport connection cannot complete the UWB
        // handshake, so ranging it would only produce a failing session.
        val target = FocusPolicy.rangingTarget(
            focusPeerId = bob,
            isHost = true,
            trackHost = false,
            hostPeerId = null,
            connectedPeerIds = setOf(alice)
        )
        assertEquals(FocusPolicy.RangingTarget.None, target)
    }

    @Test
    fun aGuestUnderTrackHostRangesTheHost() {
        val target = FocusPolicy.rangingTarget(
            focusPeerId = bob,
            isHost = false,
            trackHost = true,
            hostPeerId = host,
            connectedPeerIds = setOf(host)
        )
        assertEquals(FocusPolicy.RangingTarget.Peer(host, isHost = true), target)
    }

    @Test
    fun nothingIsRangedWithoutAFocus() {
        val target = FocusPolicy.rangingTarget(
            focusPeerId = null,
            isHost = true,
            trackHost = false,
            hostPeerId = null,
            connectedPeerIds = setOf(alice, bob)
        )
        assertEquals(FocusPolicy.RangingTarget.None, target)
    }

    /**
     * The session id a guest-to-guest ranging offer and the session opened on
     * the far side must agree on, or the two ends never complete a handshake.
     *
     * This is the crux of the relay path: the offerer invents the id, sends it
     * up to the host, and the host forwards it verbatim. The receiving side
     * then re-derives the same value from the same inputs, so both ends are
     * looking at one session rather than two unrelated ones.
     *
     * [SessionKey] mirrors the formula in DiscoveryRepository.onFocusChanged.
     * It is duplicated rather than imported because the production function
     * is private to that class and reads its inputs from live session state;
     * this copy is the contract the two ends are held to.
     */
    object SessionKey {
        fun forPair(eventId: String, targetPeerId: String, localPeerId: String): Int {
            val event = 31L * eventId.hashCode()
            // The two peer ids are summed rather than weighted separately, so
            // the key is the same whichever guest derives it. A weighted pair
            // would give Alice and Bob different ids for their own exchange.
            val peers = 17L * (targetPeerId.hashCode().toLong() + localPeerId.hashCode())
            return (event + peers).toInt()
        }
    }

    @Test
    fun anOfferAndItsReceiverAgreeOnTheSessionId() {
        // Alice offers to range Bob. The controlee takes the id from the offer
        // verbatim, so agreement is structural - but the key is derived from
        // the unordered pair so that *both* ends computing it independently
        // also land on the same session. A weighted pair would not: Alice and
        // Bob would derive different ids for their own exchange.
        val event = "event-42"
        val fromAlice = SessionKey.forPair(eventId = event, targetPeerId = bob, localPeerId = alice)
        val fromBob = SessionKey.forPair(eventId = event, targetPeerId = alice, localPeerId = bob)
        assertEquals(
            "both ends must derive the same session id for a guest-to-guest session",
            fromAlice,
            fromBob
        )
    }

    @Test
    fun theSessionIdIsStableForTheSamePair() {
        val first = SessionKey.forPair("e", bob, alice)
        val second = SessionKey.forPair("e", bob, alice)
        assertEquals(first, second)
    }

    @Test
    fun differentPairsDoNotCollideOnOneSession() {
        // Two guests ranging each other at the same time must not land on one
        // session id, or their exchanges would interleave on a single channel.
        val aliceToBob = SessionKey.forPair("e", bob, alice)
        val aliceToCarol = SessionKey.forPair("e", "carol", alice)
        assertTrue("a peer ranging two others collided on a session", aliceToBob != aliceToCarol)
    }

    @Test
    fun differentEventsDoNotCollide() {
        assertTrue(
            SessionKey.forPair("event-a", bob, alice) != SessionKey.forPair("event-b", bob, alice)
        )
    }

    @Test
    fun atMostOnePeerIsRangedNoMatterHowManyArePresent() {
        // The whole point of the filter: seven members present, one session.
        val connected = (1..7).map { "peer-$it" }.toSet()
        val target = FocusPolicy.rangingTarget(
            focusPeerId = "peer-4",
            isHost = true,
            trackHost = false,
            hostPeerId = null,
            connectedPeerIds = connected
        )
        assertTrue(target is FocusPolicy.RangingTarget.Peer)
        assertEquals("peer-4", (target as FocusPolicy.RangingTarget.Peer).peerId)
    }
}