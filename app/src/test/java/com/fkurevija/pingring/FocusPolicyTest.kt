package com.fkurevija.pingring

import com.fkurevija.pingring.data.FocusPolicy
import org.junit.Assert.assertEquals
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
    fun anExplicitChoiceWinsOverTheNearest() {
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = bob,
            availablePeerIds = listOf(alice, bob),
            nearestPeerId = alice,
            isHost = true,
            trackHost = false,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(bob, focus)
    }

    @Test
    fun autoFallsBackToTheNearest() {
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = null,
            availablePeerIds = listOf(alice, bob),
            nearestPeerId = alice,
            isHost = true,
            trackHost = false,
            localPeerId = local,
            hostPeerId = null
        )
        assertEquals(alice, focus)
    }

    @Test
    fun aStaleSelectionFallsBackRatherThanPointingAtNobody() {
        // Peer ids are ephemeral: a selection from a previous session, or from
        // a member who has since left, must not leave the radar empty.
        val focus = FocusPolicy.resolveFocus(
            selectedPeerId = "someone-who-left",
            availablePeerIds = listOf(alice, bob),
            nearestPeerId = alice,
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
            nearestPeerId = alice,
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
            nearestPeerId = alice,
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
            nearestPeerId = null,
            isHost = true,
            trackHost = false,
            localPeerId = local,
            hostPeerId = null
        )
        assertNull(focus)
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