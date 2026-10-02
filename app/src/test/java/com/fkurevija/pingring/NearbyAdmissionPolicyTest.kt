package com.fkurevija.pingring

import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.ConnectionAuthenticationRequest
import com.fkurevija.pingring.data.JOIN_REQUEST_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.shouldRefuseConnection
import com.fkurevija.pingring.data.isPayloadSenderAuthorized
import com.fkurevija.pingring.data.isBleIdentityKeySenderAuthorized
import com.fkurevija.pingring.data.isMemberDepartureSenderAuthorized
import com.fkurevija.pingring.data.isWithinSasPromptRateLimit
import com.fkurevija.pingring.data.expiredAuthenticationRequests
import org.junit.Assert.assertEquals
import com.fkurevija.pingring.data.joinRequiresHostApproval
import com.fkurevija.pingring.data.SAS_PROMPT_WINDOW_MILLIS
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

    @Test
    fun hostAcceptsOnlyTheIdentityBoundToTheAuthenticatedEndpoint() {
        assertTrue(isPayloadSenderAuthorized(true, "peer-a", null, "peer-a"))
        assertFalse(isPayloadSenderAuthorized(true, "peer-a", null, "peer-b"))
    }

    @Test
    fun guestAcceptsSenderAttributedMessagesOnlyFromTheAuthenticatedHost() {
        assertTrue(isPayloadSenderAuthorized(false, "host", "host", "peer-b"))
        assertFalse(isPayloadSenderAuthorized(false, "guest", "host", "peer-b"))
        assertFalse(isPayloadSenderAuthorized(false, "host", null, "peer-b"))
    }

    @Test
    fun hostAcceptsBleKeysOnlyWhenTheyMatchAnAuthorizedEndpoint() {
        assertTrue(isBleIdentityKeySenderAuthorized(true, "endpoint", "a1b2c3d4", true, null, "a1b2c3d4"))
        assertFalse(isBleIdentityKeySenderAuthorized(true, "endpoint", "a1b2c3d4", true, null, "deadbeef"))
        assertFalse(isBleIdentityKeySenderAuthorized(true, "endpoint", "a1b2c3d4", false, null, "a1b2c3d4"))
    }

    @Test
    fun guestsAcceptBleKeyRostersOnlyFromTheirAuthenticatedHostEndpoint() {
        assertTrue(isBleIdentityKeySenderAuthorized(false, "host-endpoint", null, false, "host-endpoint", "a1b2c3d4"))
        assertFalse(isBleIdentityKeySenderAuthorized(false, "guest-endpoint", null, false, "host-endpoint", "a1b2c3d4"))
    }

    @Test
    fun hostAcceptsGracefulDepartureOnlyFromTheMatchingAuthorizedMember() {
        assertTrue(isMemberDepartureSenderAuthorized(true, "endpoint", "a1b2c3d4", true, null, "a1b2c3d4"))
        assertFalse(isMemberDepartureSenderAuthorized(true, "endpoint", "a1b2c3d4", true, null, "deadbeef"))
        assertFalse(isMemberDepartureSenderAuthorized(true, "endpoint", "a1b2c3d4", false, null, "a1b2c3d4"))
    }

    @Test
    fun guestsAcceptRelayedDepartureOnlyFromTheAuthenticatedHost() {
        assertTrue(isMemberDepartureSenderAuthorized(false, "host-endpoint", null, false, "host-endpoint", "a1b2c3d4"))
        assertFalse(isMemberDepartureSenderAuthorized(false, "other-endpoint", null, false, "host-endpoint", "a1b2c3d4"))
    }

    @Test
    fun gracefulDepartureIsNotInferredFromAnUnmatchedEndpoint() {
        assertFalse(isMemberDepartureSenderAuthorized(true, "endpoint-a", "a1b2c3d4", true, null, "b1b2c3d4"))
        assertFalse(isMemberDepartureSenderAuthorized(false, "guest-endpoint", null, false, "host-endpoint", "a1b2c3d4"))
    }

    @Test
    fun sasPromptRateLimitBoundsRepeatedNearbyRequests() {
        val now = 120_000L
        assertTrue(isWithinSasPromptRateLimit(emptyList(), now))
        assertTrue(isWithinSasPromptRateLimit((0 until 11).map { now - it }, now))
        assertFalse(isWithinSasPromptRateLimit((0 until 12).map { now - it }, now))
        assertTrue(isWithinSasPromptRateLimit(listOf(now - SAS_PROMPT_WINDOW_MILLIS), now))
    }

    @Test
    fun typedCodeJoinsAlwaysRequireHostApproval() {
        assertTrue(joinRequiresHostApproval(hasValidInviteSecret = false, hostRequiresApproval = false))
        assertFalse(joinRequiresHostApproval(hasValidInviteSecret = true, hostRequiresApproval = false))
        assertTrue(joinRequiresHostApproval(hasValidInviteSecret = true, hostRequiresApproval = true))
    }

    @Test
    fun unansweredSasPromptsExpireAfterTheJoinWindow() {
        val now = 500_000L
        val expired = ConnectionAuthenticationRequest("old", "Old", "000 000", true, now - JOIN_REQUEST_TIMEOUT_MILLIS - 1)
        val current = ConnectionAuthenticationRequest("new", "New", "111 111", true, now)

        assertEquals(listOf(expired), expiredAuthenticationRequests(listOf(expired, current), now))
    }
}