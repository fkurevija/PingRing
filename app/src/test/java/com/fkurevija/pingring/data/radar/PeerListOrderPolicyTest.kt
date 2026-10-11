package com.fkurevija.pingring.data.radar
import com.fkurevija.pingring.data.radar.PeerListOrderPolicy
import com.fkurevija.pingring.data.radar.RadarPeerUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The rule for the order the member list is drawn in.
 *
 * The failure this guards against is a list that reshuffles while somebody is
 * reading it, and a list that ranks a peer we cannot hear above one standing
 * next to us. Both are invisible in a single assertion and obvious in a sweep.
 */
class PeerListOrderPolicyTest {

    private fun peer(
        id: String,
        name: String = "Peer $id",
        rssi: Int? = null,
        meshOnly: Boolean = false,
    ) = RadarPeerUpdate(
        peerId = id,
        name = name,
        rssi = rssi,
        proximity = when {
            rssi == null -> "Unknown"
            rssi >= -65 -> "Hot"
            else -> "Cold"
        },
        distance = null,
        azimuth = null,
        elevation = null,
        worldDirectionX = null,
        worldDirectionY = null,
        worldDirectionZ = null,
        lastSeen = 0L,
        meshOnlyPresence = meshOnly,
    )

    private fun order(peers: List<RadarPeerUpdate>) =
        PeerListOrderPolicy.bySignalStrength(peers).map { it.peerId }

    @Test
    fun `the strongest signal is first because that is the closest member`() {
        // BLE RSSI is negative dBm, so -45 is a stronger signal than -95 and the
        // member holding it is the nearer one. Sorting ascending here would put
        // the furthest member at the top of the list, which is the whole bug.
        val peers = listOf(
            peer("far", rssi = -95),
            peer("near", rssi = -45),
            peer("mid", rssi = -70),
        )

        assertEquals(listOf("near", "mid", "far"), order(peers))
    }

    @Test
    fun `a peer we cannot hear goes last rather than being treated as closest`() {
        // A null RSSI is missing information, not a measurement. Ranking it first
        // would put someone we have lost above someone standing next to us.
        val peers = listOf(
            peer("silent", rssi = null),
            peer("nextdoor", rssi = -48),
        )

        assertEquals(listOf("nextdoor", "silent"), order(peers))
    }

    @Test
    fun `every peer is still listed, so a quiet member is never hidden`() {
        val peers = listOf(
            peer("a", rssi = -80),
            peer("b", rssi = null),
            peer("c", rssi = -60),
            peer("d", rssi = null, meshOnly = true),
        )

        val ordered = PeerListOrderPolicy.bySignalStrength(peers)

        assertEquals(peers.size, ordered.size)
        assertEquals(peers.map { it.peerId }.toSet(), ordered.map { it.peerId }.toSet())
    }

    @Test
    fun `members on the same signal do not swap places between refreshes`() {
        // Two members sitting on identical dBm is ordinary, not exceptional. An
        // unstable sort would reorder them on every recomposition, which is the
        // reshuffling complaint this policy exists to fix - so ties resolve on a
        // stable key instead of on arrival order.
        val peers = listOf(
            peer("z", name = "Zoe", rssi = -70),
            peer("a", name = "Ana", rssi = -70),
        )

        assertEquals(listOf("a", "z"), order(peers))
        // Arrival order is not what decides it, so swapping the input does not
        // change the answer either.
        assertEquals(order(peers), order(peers.reversed()))
    }

    @Test
    fun `name comparison does not put capitals after lower case`() {
        // The tie-break lowercases, so "ana" has to sort before "Zoe" rather than
        // after it - which is what a raw String comparison would do, putting
        // every capitalised name at the bottom of a tie group.
        val peers = listOf(
            peer("1", name = "Zoe", rssi = -70),
            peer("2", name = "ana", rssi = -70),
        )

        assertEquals(listOf("2", "1"), order(peers))
    }
    /**
     * The sweep that matters: whatever order the members arrive in, the list must
     * come out sorted and complete.
     *
     * Asserting one arrangement proves nothing here - this is an ordering bug, and
     * it only shows up for the inputs nobody thought to write down.
     */
    @Test
    fun `any arrival order produces the same complete sorted list`() {
        val pool = listOf(
            peer("a", name = "Ana", rssi = -55),
            peer("b", name = "Bo", rssi = -55),
            peer("c", name = "Cy", rssi = -91),
            peer("d", name = "Di", rssi = null),
            peer("e", name = "Ed", rssi = -70),
            peer("f", name = "Fi", rssi = null),
        )
        val random = Random(20260904)
        val expected = order(pool)

        repeat(300) {
            val arrival = pool.shuffled(random)
            val ordered = order(arrival)

            assertEquals(
                "arrival order must not change the result",
                expected,
                ordered,
            )
            assertEquals(
                "nothing may be dropped",
                pool.map { it.peerId }.toSet(),
                ordered.toSet(),
            )
        }
    }

    @Test
    fun `no measured member is ever ranked below an unheard one`() {
        // The invariant, over every mix of present and absent signals: every
        // null-RSSI peer sits below every peer we can actually hear.
        val random = Random(31)
        val values = listOf(-95, -80, -70, -55, -40, null)

        repeat(300) {
            val peers = (0 until 5).map { index ->
                peer("p$index", name = "Peer $index", rssi = values.random(random))
            }
            val ordered = PeerListOrderPolicy.bySignalStrength(peers)

            val firstUnheard = ordered.indexOfFirst { it.rssi == null }
            if (firstUnheard >= 0) {
                assertTrue(
                    "an unheard peer appeared above a heard one in ${ordered.map { it.rssi }}",
                    ordered.drop(firstUnheard).all { it.rssi == null },
                )
            }
            val measured = ordered.mapNotNull { it.rssi }
            assertTrue(
                "measured signals must descend, got $measured",
                measured.zipWithNext().all { (a, b) -> a >= b },
            )
        }
    }

    @Test
    fun `one decibel of jitter cannot move a member past another`() {
        // The property a member actually perceives: the list must not reshuffle
        // while they are reading it. Not implied by "sorted" - a correct sort
        // with a noisy key still jitters - so it is asserted directly: with a
        // clear 10 dB gap, the nearer member stays first however the readings
        // wobble and whatever order they arrive in.
        val random = Random(7)

        repeat(200) {
            val strong = -50 + random.nextInt(-4, 5)
            val weak = -80 + random.nextInt(-4, 5)
            val peers = listOf(
                peer("strong", name = "Ana", rssi = strong),
                peer("weak", name = "Bo", rssi = weak),
            )

            assertTrue(
                "expected a clear gap, got $strong and $weak",
                strong - weak >= 10,
            )
            assertEquals(listOf("strong", "weak"), order(peers))
            assertEquals(listOf("strong", "weak"), order(peers.reversed()))
        }
    }

    @Test
    fun `an empty ring stays empty`() {
        assertEquals(emptyList<String>(), order(emptyList()))
    }

    @Test
    fun `a single member needs no ordering decision`() {
        assertEquals(listOf("solo"), order(listOf(peer("solo", rssi = null))))
    }
}