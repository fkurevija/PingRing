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
