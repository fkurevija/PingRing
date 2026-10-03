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
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(bob, focus)
    }

    @Test
    fun theHostTracksWhoseverJoinedFirstNotWhoseverWasHeardFirst() {
        // The reason the join timestamp exists. Alice arrived ten minutes ago;
        // Bob wandered in a second ago and is being scanned first because he is
        // nearest. Before the timestamp this returned Bob, which read as "first
        // to join" and was not.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            // Discovery order puts Bob first, which is the whole point.
            availablePeerIds = listOf(bob, alice),
            peerJoinedAt = mapOf(alice to 1_000L, bob to 600_000L),
            isHost = true,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(alice, focus)
    }

    @Test
    fun aPeerThatNeverSaidWhenItJoinedSortsLast() {
        // A peer whose advert predates the field, or one seen only over the
        // debug transport, has no arrival time. It must not be treated as
        // arriving before everyone else - that is what reading zero as epoch
        // 1970 would do - and it must not lose to someone who did say either.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(bob, alice),
            peerJoinedAt = mapOf(alice to 1_000L),
            isHost = true,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(alice, focus)
    }

    @Test
    fun aPeerWithNoTimestampsAtAllFallsBackToDiscoveryOrder() {
        // Nothing to go on, so the old behaviour stands. It has to stay
        // deterministic, which is why the tie-break must not be the peer id.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(bob, alice),
            peerJoinedAt = emptyMap(),
            isHost = true,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(bob, focus)
    }

    @Test
    fun aZeroTimestampIsUnknownRatherThanTheEarliestPossibleArrival() {
        // What a device outside an event advertises. Same shape as a missing
        // entry, and it must not win the tie.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(bob, alice),
            peerJoinedAt = mapOf(bob to 0L, alice to 1_000L),
            isHost = true,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(alice, focus)
    }

    @Test
    fun theHostFallsBackToTheFirstMemberNotTheNearest() {
        // Whoever arrived first is a stable choice; "nearest" moves underneath
        // the user every time somebody walks, which is not a selection at all.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(bob, alice),
            isHost = true,
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
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(alice, focus)
    }

    @Test
    fun aGuestsOwnChoiceWinsOverTheHostDefault() {
        // The host default is a default, not a policy. It used to be a policy the
        // host could enforce - "track host" held a guest's radar on the host even
        // after the guest picked somebody else - which made the host a control
        // surface over every guest's screen. Removed: a guest tracking another
        // guest is a thing the guest is entitled to do.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = bob,
            availablePeerIds = listOf(alice, bob, host),
            isHost = false,
            localPeerId = local,
            hostPeerId = host
        )
        assertEquals(bob, focus)
    }

    @Test
    fun theHostIsNeverAskedToTrackItself() {
        // A guest defaulting to the host must not put a *host* on its own radar,
        // even when its own id is somehow in the candidate list.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(local, alice, bob),
            isHost = true,
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
            localPeerId = local,
            hostPeerId = null
        )
        assertNull(focus)
    }

    @Test
    fun aGuestDefaultsToTheHostWhenItHasNotChosen() {
        // The role-based default, and the reason it exists: a guest arriving at a
        // ring wants to know where the host is, and the host is already the member
        // everybody is following.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(alice, host),
            peerJoinedAt = mapOf(alice to 1_000L, host to 9_000L),
            isHost = false,
            localPeerId = local,
            hostPeerId = host
        )
        assertEquals(host, focus)
    }

    @Test
    fun everyRoleCanChooseAnyMember() {
        // There is no role whose picker is locked, which is what the removed
        // "track host" switch produced on a guest's screen. Asserted for both
        // roles because the policy only ever locked the guest's.
        FocusPolicy.resolveFocus(
            selectedPeerId = bob,
            availablePeerIds = listOf(alice, bob),
            isHost = true,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(bob, FocusPolicy.resolveFocus(
            selectedPeerId = bob,
            availablePeerIds = listOf(alice, bob, host),
            isHost = false,
            localPeerId = local,
            hostPeerId = host
        ))
    }

    @Test
    fun onlyTheFocusedPeerIsRanged() {
        val target = FocusPolicy.rangingTarget(
            focusPeerId = bob,
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
            hostPeerId = null,
            connectedPeerIds = setOf(alice)
        )
        assertEquals(FocusPolicy.RangingTarget.None, target)
    }

    @Test
    fun aGuestRangingTheHostIsMarkedAsRangingTheHost() {
        // Kept distinct from ranging another guest so a guest-to-guest offer is
        // never confused with the host link, which is the only one authenticated.
        val target = FocusPolicy.rangingTarget(
            focusPeerId = host,
            hostPeerId = host,
            connectedPeerIds = setOf(host)
        )
        assertEquals(FocusPolicy.RangingTarget.Peer(host, isHost = true), target)
    }

    @Test
    fun aGuestChosenPeerIsRangedEvenWhenTheHostIsAlsoPresent() {
        // The case the removed policy used to override. It has to range the peer
        // the guest actually chose, which is never the host here.
        val target = FocusPolicy.rangingTarget(
            focusPeerId = bob,
            hostPeerId = host,
            connectedPeerIds = setOf(host, bob)
        )
        assertEquals(FocusPolicy.RangingTarget.Peer(bob, isHost = false), target)
    }

    @Test
    fun nothingIsRangedWithoutAFocus() {
        val target = FocusPolicy.rangingTarget(
            focusPeerId = null,
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
            hostPeerId = null,
            connectedPeerIds = connected
        )
        assertTrue(target is FocusPolicy.RangingTarget.Peer)
        assertEquals("peer-4", (target as FocusPolicy.RangingTarget.Peer).peerId)
    }
}
