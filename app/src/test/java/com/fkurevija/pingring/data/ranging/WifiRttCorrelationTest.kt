package com.fkurevija.pingring.data.ranging
import com.fkurevija.pingring.data.ranging.WifiRttCorrelation
import com.fkurevija.pingring.data.ranging.WifiRttCorrelation.Attribution
import com.fkurevija.pingring.data.ranging.WifiRttCorrelation.RangeablePeer
import com.fkurevija.pingring.data.ranging.WifiRttCorrelation.Reading
import com.fkurevija.pingring.data.ranging.WifiRttCorrelation.RejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fkurevija.pingring.data.session.Event

/**
 * Attributing a Wi-Fi RTT result to a member.
 *
 * The property that matters is negative: almost every case here asserts that a
 * result was *refused*. An RTT session reports whatever peers the platform
 * found, so results belonging to nobody in this event are the expected case,
 * not an edge case - and the one thing this must never do is hand such a result
 * to the nearest or focused member. That failure is invisible afterwards,
 * because the number it produces looks exactly like a correct one.
 */
class WifiRttCorrelationTest {

    private fun peer(id: String, usd: String? = null) = RangeablePeer(peerId = id, usdPeerId = usd)

    private val alice = peer("alice", "usd-1")
    private val bob = peer("bob", "usd-2")
    private val carol = peer("carol") // never published a USD record

    private val members = listOf(alice, bob, carol)

    private fun rejected(usd: String?, peers: List<RangeablePeer> = members) =
        WifiRttCorrelation.attribute(usd, peers) as Attribution.Rejected

    @Test
    fun `a result is matched to the member that published the id`() {
        assertEquals(
            Attribution.Matched("bob"),
            WifiRttCorrelation.attribute("usd-2", members),
        )
    }

    @Test
    fun `a null peer id is rejected rather than matching nobody`() {
        // The platform documents getUsdPeerId() as nullable, and this is the
        // shape it takes for a peer the platform discovered but could not tie to
        // a USD record - a router, typically. It gets its own reason so the
        // caller can tell it apart from a stranger, not so it can be guessed at.
        assertEquals(RejectionReason.NO_USD_PEER_ID, rejected(null).reason)
    }

    @Test
    fun `a stranger ranging on the same channel is discarded`() {
        // The dangerous case. Someone nearby who is not a member produces a
        // perfectly good reading, and attributing it to whoever happens to be
        // closest would put a stranger's distance under a member's name.
        assertEquals(RejectionReason.UNKNOWN_PEER, rejected("usd-someone-else").reason)
    }

    @Test
    fun `no result is ever attributed to a member who published nothing`() {
        // carol is in the event and on the radar, and must still be unreachable
        // by this source. Ranging her because someone else's id was closer would
        // be the guessing this exists to prevent.
        for (usd in listOf(null, "usd-1", "usd-2", "usd-stranger")) {
            val result = WifiRttCorrelation.attribute(usd, members)
            assertTrue(
                "carol was attributed from $usd",
                (result as? Attribution.Matched)?.peerId != "carol",
            )
        }
    }

    @Test
    fun `a duplicated id is refused rather than resolved by picking one`() {
        // Should be impossible while peer ids are unique per session. If it
        // happens something upstream is broken, and choosing either member would
        // hide that rather than surface it.
        val clash = listOf(peer("alice", "usd-1"), peer("mallory", "usd-1"))

        assertEquals(RejectionReason.AMBIGUOUS_BINDING, rejected("usd-1", clash).reason)
    }

    @Test
    fun `a duplicate among other peers does not spoil a valid match`() {
        // Ambiguity is checked after membership on purpose: a clash between two
        // members we are not being told about says nothing about this result.
        val messy = listOf(peer("alice", "usd-1"), peer("mallory", "usd-1"), bob)

        assertEquals(
            Attribution.Matched("bob"),
            WifiRttCorrelation.attribute("usd-2", messy),
        )
    }

    @Test
    fun `an empty event matches nothing`() {
        assertEquals(RejectionReason.UNKNOWN_PEER, rejected("usd-1", emptyList()).reason)
    }

    @Test
    fun `rangeability is about the peer's own usd record`() {
        assertTrue(WifiRttCorrelation.isRangeable(alice))
        assertFalse(
            "a member with no usd record cannot be ranged, only skipped",
            WifiRttCorrelation.isRangeable(carol),
        )
    }
    @Test
    fun `a reading with no distance is refused`() {
        // The peer replied, so a result object exists, while the measurement
        // inside it does not. Passing that on would put NaN on the radar.
        assertFalse(WifiRttCorrelation.isUsable(Reading(distanceMeters = null)))
    }

    @Test
    fun `nonsensical distances are refused`() {
        assertFalse(WifiRttCorrelation.isUsable(Reading(distanceMeters = -1f)))
        assertFalse(WifiRttCorrelation.isUsable(Reading(distanceMeters = 0f)))
        assertFalse(WifiRttCorrelation.isUsable(Reading(distanceMeters = Float.NaN)))
        assertFalse(WifiRttCorrelation.isUsable(Reading(distanceMeters = Float.POSITIVE_INFINITY)))
    }

    @Test
    fun `an implausibly large distance is refused`() {
        // Finite, positive and wrong. A field is bounded by its own size, so a
        // number past the bound came from a failed decode rather than from a peer
        // standing very far away.
        assertFalse(
            WifiRttCorrelation.isUsable(
                Reading(distanceMeters = WifiRttCorrelation.MAX_PLAUSIBLE_DISTANCE_METERS + 1f),
            ),
        )
    }

    @Test
    fun `an ordinary reading passes with or without a spread`() {
        // The spread is optional: a result carrying only a distance is still a
        // reading worth showing, and refusing it would throw away the common case
        // over a field that is not always populated.
        assertTrue(WifiRttCorrelation.isUsable(Reading(distanceMeters = 1.4f)))
        assertTrue(
            WifiRttCorrelation.isUsable(
                Reading(distanceMeters = 1.4f, standardDeviationMeters = 0.3f),
            ),
        )
    }

    @Test
    fun `a failed exchange is caught by its spread`() {
        // A distance came out of it, but the spread says the round trip did not
        // decode properly. Believing the distance anyway is how a wrong number
        // becomes an ordinary-looking one.
        assertFalse(
            WifiRttCorrelation.isUsable(
                Reading(distanceMeters = 1.4f, standardDeviationMeters = -1f),
            ),
        )
        assertFalse(
            WifiRttCorrelation.isUsable(
                Reading(
                    distanceMeters = 1.4f,
                    standardDeviationMeters =
                        WifiRttCorrelation.MAX_PLAUSIBLE_DEVIATION_METERS + 1f,
                ),
            ),
        )
    }

    @Test
    fun `every rejection has a sentence`() {
        for (reason in RejectionReason.entries) {
            val sentence = WifiRttCorrelation.describe(reason)
            assertTrue(
                "rejection $reason has no explanation",
                sentence.isNotBlank() && sentence.endsWith("."),
            )
        }
    }
}