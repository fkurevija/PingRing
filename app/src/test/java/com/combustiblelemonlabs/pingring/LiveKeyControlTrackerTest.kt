package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.LiveKeyControlTracker
import com.combustiblelemonlabs.pingring.data.BleIdentityCrypto
import com.combustiblelemonlabs.pingring.data.BleIdentityAdvertisement
import com.combustiblelemonlabs.pingring.data.MeshPeerAuthentication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKeyControlTrackerTest {
    private val challenge = ByteArray(32) { it.toByte() }

    @Test
    fun aCapturedValidV5AdvertisementNeverCreatesLiveKeyProof() {
        val tracker = LiveKeyControlTracker()
        val keys = BleIdentityCrypto.generateKeyPair()
        val key = BleIdentityCrypto.encodePublicKey(keys.public)
        val packet = BleIdentityAdvertisement.encode(
            flags = 0,
            peerId = "deadbeef",
            eventCode = "4C1A9B",
            joinedAtMillis = 1L,
            sequence = 42L,
            privateKey = keys.private,
        )
        repeat(10) { attempt ->
            val decoded = BleIdentityAdvertisement.decode(packet, keys.public) ?: error("invalid signed packet")
            assertTrue(BleIdentityAdvertisement.acceptsSequence(null, decoded.sequence))
            assertFalse(tracker.isFresh(decoded.peerId, key, attempt.toLong()))
        }
    }

    @Test
    fun advertisementsCannotCreateOrExtendAProof() {
        val tracker = LiveKeyControlTracker()
        assertFalse(tracker.isFresh("peer", "key", 0))
        tracker.begin("endpoint", "peer", "key", challenge, 0)
        assertFalse(tracker.isFresh("peer", "key", 1))
        assertTrue(tracker.complete("endpoint", "peer", "key", challenge, 2))
        assertTrue(tracker.isFresh("peer", "key", 10_001))
        assertFalse(tracker.isFresh("peer", "key", 10_002))
    }

    @Test
    fun proofMustMatchFreshNonceEndpointMemberAndRegisteredKey() {
        val tracker = LiveKeyControlTracker()
        tracker.begin("endpoint", "peer", "key", challenge, 0)
        assertFalse(tracker.complete("other", "peer", "key", challenge, 1))
        assertFalse(tracker.complete("endpoint", "other", "key", challenge, 1))
        assertFalse(tracker.complete("endpoint", "peer", "new-key", challenge, 1))
        assertFalse(tracker.complete("endpoint", "peer", "key", ByteArray(32), 1))
        assertTrue(tracker.complete("endpoint", "peer", "key", challenge, 1))
        assertFalse(tracker.complete("endpoint", "peer", "key", challenge, 2))
        assertFalse(tracker.isFresh("peer", "new-key", 2))
    }

    @Test
    fun expiredResponsesAndOldChallengesCannotRefreshPresence() {
        val tracker = LiveKeyControlTracker()
        tracker.begin("endpoint", "peer", "key", challenge, 0)
        assertFalse(tracker.complete("endpoint", "peer", "key", challenge, 15_000))
        val nextChallenge = challenge.copyOf().also { it[0] = 42 }
        tracker.begin("endpoint", "peer", "key", nextChallenge, 15_001)
        assertFalse(tracker.complete("endpoint", "peer", "key", challenge, 15_002))
        assertTrue(tracker.complete("endpoint", "peer", "key", nextChallenge, 15_002))
    }

    @Test
    fun disconnectAndRingEndInvalidateProofs() {
        val tracker = LiveKeyControlTracker()
        tracker.begin("endpoint", "peer", "key", challenge, 0)
        assertTrue(tracker.complete("endpoint", "peer", "key", challenge, 1))
        tracker.invalidate("endpoint", "peer")
        assertFalse(tracker.isFresh("peer", "key", 2))
        tracker.begin("endpoint", "peer", "key", challenge, 3)
        assertTrue(tracker.complete("endpoint", "peer", "key", challenge, 4))
        tracker.clear()
        assertFalse(tracker.isFresh("peer", "key", 5))
        assertFalse(tracker.hasPending("endpoint", 5))
    }

    @Test
    fun signedResponseUnlocksOnlyThePendingChallengeAndCannotBeReplayedToRenewIt() {
        val tracker = LiveKeyControlTracker()
        val keys = BleIdentityCrypto.generateKeyPair()
        val key = BleIdentityCrypto.encodePublicKey(keys.public)
        val signedBytes = MeshPeerAuthentication.signingBytes("4C1A9B", "a1b2c3d4", "deadbeef", challenge)
        val signature = BleIdentityCrypto.sign(keys.private, signedBytes)

        tracker.begin("endpoint", "deadbeef", key, challenge, 0)
        assertFalse(tracker.isFresh("deadbeef", key, 1))
        assertTrue(BleIdentityCrypto.verify(keys.public, signedBytes, signature))
        assertTrue(tracker.complete("endpoint", "deadbeef", key, challenge, 1))
        assertTrue(tracker.isFresh("deadbeef", key, 2))
        assertFalse(tracker.isFresh("deadbeef", key, 10_001))

        val nextChallenge = challenge.copyOf().also { it[0] = 42 }
        tracker.begin("endpoint", "deadbeef", key, nextChallenge, 10_002)
        val nextBytes = MeshPeerAuthentication.signingBytes("4C1A9B", "a1b2c3d4", "deadbeef", nextChallenge)
        assertFalse(BleIdentityCrypto.verify(keys.public, nextBytes, signature))
        assertFalse(tracker.complete("endpoint", "deadbeef", key, challenge, 10_003))
        assertFalse(tracker.isFresh("deadbeef", key, 10_003))
    }

    @Test
    fun clockRegressionFailsClosed() {
        val tracker = LiveKeyControlTracker()
        tracker.begin("endpoint", "peer", "key", challenge, 100)
        assertFalse(tracker.hasPending("endpoint", 99))
        assertTrue(tracker.complete("endpoint", "peer", "key", challenge, 101))
        assertFalse(tracker.isFresh("peer", "key", 100))
    }
}