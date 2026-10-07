package com.fkurevija.pingring

import com.fkurevija.pingring.data.NearbyDiscoveryIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyDiscoveryIdentityTest {
    private val host = NearbyDiscoveryIdentity("1234abcd", true)
    private val guest = NearbyDiscoveryIdentity("abcd1234", false)

    @Test
    fun hostRoleAndPeerIdRoundTripWithoutChangingRoutingIdentity() {
        for (identity in listOf(host, guest)) {
            assertEquals(
                identity,
                NearbyDiscoveryIdentity.decode(
                    NearbyDiscoveryIdentity.encode(identity.peerId, identity.isHost),
                ),
            )
        }
    }

    @Test
    fun legacyNamesRemainPeerIdsWithoutInventingAHostRole() {
        assertEquals(guest, NearbyDiscoveryIdentity.decode("abcd1234"))
    }

    @Test
    fun malformedNamesAreNotHostCandidates() {
        for (name in listOf("", "pingring-host:", "pingring-host:garbage", "1234abcd:host")) {
            assertNull(NearbyDiscoveryIdentity.decode(name))
        }
    }

    @Test
    fun nearbyCanBootstrapAGuestWithoutAnyBleAdvertisement() {
        assertEquals(host.peerId, candidate(null, null, host))
    }

    @Test
    fun anUnknownGuestCannotBecomeTheAdmissionHost() {
        assertNull(candidate(null, null, guest))
    }

    @Test
    fun knownHostAndExistingCandidateCannotBeReplacedByAnotherHint() {
        assertEquals("8765abcd", candidate("8765abcd", null, host))
        assertEquals("8765abcd", candidate(null, "8765abcd", host))
    }

    @Test
    fun aHostNeverBootstrapsThroughAnotherHostAndAGuestCannotChooseItself() {
        assertNull(candidate(null, null, host, isLocalHost = true))
        assertNull(candidate(null, null, host, localPeerId = host.peerId))
    }

    private fun candidate(
        known: String?,
        discovered: String?,
        identity: NearbyDiscoveryIdentity,
        localPeerId: String = "aabbccdd",
        isLocalHost: Boolean = false,
    ) = NearbyDiscoveryIdentity.hostCandidate(known, discovered, identity, localPeerId, isLocalHost)
}
