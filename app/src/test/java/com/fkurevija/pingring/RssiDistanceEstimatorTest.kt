package com.fkurevija.pingring

import com.fkurevija.pingring.data.RssiDistanceEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BLE RSSI -> distance model. The bug these guard against is the one that
 * motivated the estimator: a linear formula evaluated only on first sight, so
 * the radar froze on the distance a peer had when it was first seen.
 */
class RssiDistanceEstimatorTest {

    @Test
    fun weakerSignalAlwaysReadsFarther() {
        // Monotonic decrease in RSSI must never produce a nearer peer.
        var previous = 0f
        var rssi = -30
        while (rssi >= -95) {
            val distance = RssiDistanceEstimator.estimate(rssi)
            assertNotNull("no estimate at $rssi dBm", distance)
            distance!!
            assertTrue(
                "distance increased as signal weakened: $rssi dBm gave $distance, previous was $previous",
                distance >= previous
            )
            previous = distance
            rssi -= 1
        }
    }

    @Test
    fun referenceReadingMapsToOneMeter() {
        val distance = RssiDistanceEstimator.estimate(RssiDistanceEstimator.RSSI_AT_ONE_METER.toInt())!!
        assertEquals(1f, distance, 0.05f)
    }

    @Test
    fun hotColdThresholdSitsAtASingleDigitDistance() {
        // -70 dBm is the hot/cold threshold in DiscoveryRepository. The old
        // linear model put it at exactly 5 m and made every weaker reading
        // climb in flat 0.1 m steps, so the ring only ever changed state over
        // a narrow band. The log-distance curve puts the boundary near 3 m
        // and, more importantly, keeps spreading readings out well past it.
        val distance = RssiDistanceEstimator.estimate(-70)!!
        assertTrue("expected a single-digit distance at -70 dBm, was $distance", distance < 10f)
        // A peer clearly below the threshold must read materially farther.
        val colder = RssiDistanceEstimator.estimate(-85)!!
        assertTrue("expected -85 dBm to be several times farther, was $colder", colder > distance * 3f)
    }

    @Test
    fun reportsUsefulRangeOutToFortyMeters() {
        // The linear model topped out around 9 m. The new curve must keep
        // producing a number across the whole plausible venue radius.
        assertNotNull(RssiDistanceEstimator.estimate(-85))
        val far = RssiDistanceEstimator.estimate(-95)!!
        assertTrue("expected 20 m or more at -95 dBm, was $far", far >= 20f)
    }

    @Test
    fun returnsNullBelowTheNoiseFloor() {
        assertNull(RssiDistanceEstimator.estimate(RssiDistanceEstimator.SENSITIVITY_FLOOR_DBM.toInt() - 1))
    }

    @Test
    fun clampsAbsurdlyCloseReadings() {
        val distance = RssiDistanceEstimator.estimate(0)!!
        assertEquals(RssiDistanceEstimator.MIN_DISTANCE_METERS, distance, 0.001f)
    }

    @Test
    fun firstSampleSeedsTheEstimateExactly() {
        val raw = RssiDistanceEstimator.estimate(-70)!!
        assertEquals(raw, RssiDistanceEstimator.smooth(null, raw)!!, 0.0001f)
    }

    @Test
    fun smoothingMovesTowardTheNewReadingWithoutJumpingToIt() {
        val near = RssiDistanceEstimator.estimate(-50)!!
        val far = RssiDistanceEstimator.estimate(-90)!!
        val smoothed = RssiDistanceEstimator.smooth(near, far)!!
        assertTrue("expected a value between the two samples", smoothed > near && smoothed < far)
    }

    @Test
    fun smoothingDampsJitterAroundASteadyPeer() {
        val steady = RssiDistanceEstimator.estimate(-70)!!
        var running = steady
        // A peer standing still still produces noisy RSSI; the smoothed value
        // should stay much tighter than the raw samples.
        var spread = 0f
        listOf(-68, -72, -69, -71, -67, -73).forEach { noisy ->
            val raw = RssiDistanceEstimator.estimate(noisy)!!
            spread = maxOf(spread, kotlin.math.abs(raw - steady))
            running = RssiDistanceEstimator.smooth(running, raw)!!
        }
        assertTrue("smoothed value drifted too far: $running", kotlin.math.abs(running - steady) < spread)
    }

    @Test
    fun outOfRangeReadingDropsTheEstimateRatherThanFreezingIt() {
        val previous = 4f
        assertNull(RssiDistanceEstimator.smooth(previous, null))
    }

    @Test
    fun inverseCurveReproducesTheForwardEstimate() {
        // The mock derives RSSI from a scripted distance, so the two
        // directions have to agree or the scripted radar stops matching the
        // real rendering path. Tolerance is loose because the mock stores the
        // derived reading as an Int, and one dBm is worth several metres of
        // error out at 20 m - the curve itself is exact.
        listOf(0.5f, 1f, 2.5f, 5f, 10f, 20f).forEach { metres ->
            val rssi = RssiDistanceEstimator.rssiForDistance(metres)
            val recovered = RssiDistanceEstimator.estimate(rssi.toInt())!!
            val relativeError = kotlin.math.abs(recovered - metres) / metres
            assertTrue(
                "round trip failed at $metres m (rssi $rssi -> $recovered, ${relativeError * 100}% off)",
                relativeError < 0.12f
            )
        }
    }
}
