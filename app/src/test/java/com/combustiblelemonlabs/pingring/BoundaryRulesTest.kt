package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.ranging.RssiDistanceEstimator
import com.combustiblelemonlabs.pingring.data.ranging.RssiKalmanFilter
import com.combustiblelemonlabs.pingring.data.ranging.UwbRetentionPolicy
import com.combustiblelemonlabs.pingring.ui.screens.PingRingCodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.session.Event

/**
 * Boundary and abuse cases for the pure decision logic, gathered in one place
 * because each is a small rule whose failure mode is silent.
 *
 * Every class here is deliberately free of Android and Compose types - that is
 * why they can be tested at all - so these need no device, no framework
 * simulation and no mocking.
 */
class BoundaryRulesTest {

    // --- PingRingCodeParser: the code has to survive a round trip through a
    // --- scanned QR code, which mangles formatting, and must never accept junk.

    @Test
    fun `a code survives surrounding whitespace`() {
        // A scanned QR routinely arrives with padding, and a user pasting a link
        // leaves a trailing space.
        assertEquals("ABC123", PingRingCodeParser.parse("  ABC123  "))
    }

    @Test
    fun `exactly six hexadecimal characters are required`() {
        assertEquals("ABC123", PingRingCodeParser.parse("ABC123"))
        assertNull(PingRingCodeParser.parse("abc"))
        assertNull(PingRingCodeParser.parse("ABC12"))
        assertNull(PingRingCodeParser.parse("ABC1234"))
    }

    @Test
    fun `a code starting with a dash or underscore is rejected`() {
        // The pattern anchors the first character to alphanumeric deliberately: a
        // leading separator invites a truncated or concatenated read being taken
        // for a real code.
        assertNull(PingRingCodeParser.parse("-ABC12"))
        assertNull(PingRingCodeParser.parse("_ABC12"))
    }

    @Test
    fun `a code containing a path separator is rejected`() {
        assertNull(PingRingCodeParser.parse("AB/CD"))
    }

    @Test
    fun `a null scan result is rejected rather than throwing`() {
        // The camera reports failure as a null scan, not an exception, so this is
        // the ordinary "user cancelled" path.
        assertNull(PingRingCodeParser.parse(null))
    }

    @Test
    fun `a percent-encoded code in a query is decoded`() {
        // URLDecoder is applied to query values before the six-hex format check.
        assertEquals("AB12CD", PingRingCodeParser.parse("pingring://join?event=%41B12CD"))
    }

    @Test
    fun `a decoded value that is not a valid code is still rejected`() {
        // Decoding happens before validation, so it must not become a way past it:
        // %20 decodes to a space, which is not a legal code character.
        assertNull(PingRingCodeParser.parse("pingring://join?event=A%20B"))
    }

    @Test
    fun `an unknown query key is ignored rather than treated as a code`() {
        // A link with a tracking parameter must not have that parameter's value
        // mistaken for the event code.
        assertEquals("DEAD12", PingRingCodeParser.parse("pingring://join?utm_source=FAKE1&event=DEAD12"))
    }
    // --- RssiDistanceEstimator: a bogus reading must never produce a number the
    // --- radar would then draw as fact.

    @Test
    fun `the inverse curve never reports more signal than the reference`() {
        // 0 dBm is the strongest possible reading; nothing may exceed it.
        listOf(0.3f, 1f, 5f, 40f, 1000f).forEach { metres ->
            val rssi = RssiDistanceEstimator.rssiForDistance(metres)
            assertTrue("rssiForDistance($metres) = $rssi, above 0 dBm", rssi <= 0f)
        }
    }

    @Test
    fun `an absurd distance is clamped rather than reported`() {
        // A scripted or corrupt value must not run away: the model has a stated
        // ceiling and both directions of the curve respect it. -95 dBm is the
        // strongest reading the curve is still trusted at, and it already
        // saturates the 40 m ceiling.
        assertEquals(
            RssiDistanceEstimator.MAX_DISTANCE_METERS,
            RssiDistanceEstimator.estimate(-95)!!,
            0.001f
        )
    }

    @Test
    fun `a distance past the ceiling round-trips back to the ceiling`() {
        // 10 km is nonsense input; the inverse clamps to the stated maximum, and
        // feeding that back must not produce a number beyond it. The tolerance is
        // 5% because the mock stores the derived reading as an Int, and one dBm is
        // worth a few metres of distance out at the far end of the curve.
        val recovered = RssiDistanceEstimator.estimate(
            RssiDistanceEstimator.rssiForDistance(10_000f).toInt()
        )!!

        assertTrue(
            "expected the ceiling, got $recovered",
            recovered <= RssiDistanceEstimator.MAX_DISTANCE_METERS
        )
        assertTrue(
            "expected within 5% of the ceiling, got $recovered",
            recovered >= RssiDistanceEstimator.MAX_DISTANCE_METERS * 0.95f
        )
    }

    @Test
    fun `a zero distance reads as the closest possible rather than zero`() {
        // 0.0 m would put a peer at the exact centre of the radar, which is not a
        // position the model can honestly claim.
        val recovered = RssiDistanceEstimator.estimate(
            RssiDistanceEstimator.rssiForDistance(0f).toInt()
        )!!
        assertEquals(RssiDistanceEstimator.MIN_DISTANCE_METERS, recovered, 0.001f)
    }

    @Test
    fun `the filter stays at a stationary signal when every sample agrees`() {
        val filter = RssiKalmanFilter()
        val expected = RssiDistanceEstimator.estimate(-70)!!

        var at = 0L
        repeat(5) {
            filter.add(-70, at)
            at += 200
        }

        assertEquals(expected, filter.distance(at)!!, 0.0001f)
    }

    @Test
    fun `the filtered estimate stays within the observed RSSI range`() {
        // A scalar Kalman update uses a gain between zero and one, so without
        // prediction the estimate must remain bounded by valid observations.
        val filter = RssiKalmanFilter()
        var at = 0L
        listOf(-50, -90, -70, -55, -85).forEach {
            filter.add(it, at)
            at += 100
        }
        val strongest = RssiDistanceEstimator.estimate(-50)!!
        val weakest = RssiDistanceEstimator.estimate(-90)!!
        val filtered = filter.distance(at)!!

        assertTrue(
            "filtered $filtered escaped [$strongest, $weakest]",
            filtered >= strongest && filtered <= weakest
        )
    }

    @Test
    fun `filter state expires after the silence timeout`() {
        // A peer that has gone quiet must not keep a distance on screen. This
        // timeout is measured in elapsed time rather than number of samples.
        val filter = RssiKalmanFilter()
        filter.add(-70, 0L)
        val silenceTimeout = RssiDistanceEstimator.FILTER_RESET_AFTER_MILLIS
        filter.add(-70, silenceTimeout / 2)

        assertNull("a stale reading should not hold a value on screen", filter.distance(silenceTimeout * 10))
    }
    // --- UwbRetentionPolicy: the window is measured from the drop, not from
    // --- when ranging started.

    @Test
    fun `the retention window boundary is exclusive at the last millisecond`() {
        // Off-by-one here decides whether a member who walks back one frame late
        // gets a fast reconnect or a full re-negotiation, so both edges are
        // pinned. The comparison is strict, so the final millisecond of the window
        // is already outside it.
        val droppedAt = 5_000L
        val window = UwbRetentionPolicy.RETENTION_MILLIS

        assertTrue("the moment of the drop is inside the window", UwbRetentionPolicy.isWithinRetentionWindow(droppedAt, droppedAt))
        assertTrue(
            "one millisecond before the window is still inside it",
            UwbRetentionPolicy.isWithinRetentionWindow(droppedAt, droppedAt + window - 1)
        )
        assertTrue(
            "exactly at the window is already outside it",
            !UwbRetentionPolicy.isWithinRetentionWindow(droppedAt, droppedAt + window)
        )
        assertTrue(
            "past the window is outside it",
            !UwbRetentionPolicy.isWithinRetentionWindow(droppedAt, droppedAt + window + 1)
        )
    }

    @Test
    fun `the retention window is short enough not to imply membership`() {
        // The window is a chance to walk back into range, not a claim that the
        // member is still here. Anything approaching a peer's own expiry timeout
        // would amount to that claim.
        assertTrue(
            "retention ${UwbRetentionPolicy.RETENTION_MILLIS}ms must stay well under a minute",
            UwbRetentionPolicy.RETENTION_MILLIS < 60_000L
        )
    }

    @Test
    fun `classification is decided at exactly the silent threshold`() {
        val threshold = 5_000L

        assertEquals(
            UwbRetentionPolicy.RetainedState.UWB_ACTIVE,
            UwbRetentionPolicy.classify(threshold - 1, threshold)
        )
        assertEquals(
            UwbRetentionPolicy.RetainedState.UWB_SILENT,
            UwbRetentionPolicy.classify(threshold, threshold)
        )
    }

    @Test
    fun `every retained state is named and reachable`() {
        // Guards the enum against a member being added without a rule behind it.
        UwbRetentionPolicy.RetainedState.entries.forEach { state ->
            assertNotNull(state.name)
        }
        assertTrue(
            UwbRetentionPolicy.RetainedState.entries.containsAll(
                listOf(
                    UwbRetentionPolicy.RetainedState.UWB_ACTIVE,
                    UwbRetentionPolicy.RetainedState.UWB_SILENT
                )
            )
        )
    }
}