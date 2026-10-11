package com.fkurevija.pingring.data.ranging
import com.fkurevija.pingring.data.ranging.RssiDistanceEstimator
import com.fkurevija.pingring.data.ranging.RssiKalmanFilter
import com.fkurevija.pingring.data.BleSignalWarningTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fkurevija.pingring.data.DiscoveryRepository

/**
 * The BLE RSSI -> distance model. The bug these guard against is the one that
 * motivated the estimator: a linear formula evaluated only on first sight, so
 * the radar froze on the distance a peer had when it was first seen.
 */
class RssiDistanceEstimatorTest {

    @Test
    fun weakSignalWarningRequiresSustainedSmoothedRssi() {
        val tracker = BleSignalWarningTracker()

        assertFalse(tracker.update("peer", -86f, 0L))
        assertFalse(tracker.update("peer", -86f, BleSignalWarningTracker.WARNING_DURATION_MILLIS - 1L))
        assertTrue(tracker.update("peer", -86f, BleSignalWarningTracker.WARNING_DURATION_MILLIS))
    }

    @Test
    fun weakSignalWarningHasHysteresisAndPersistsThroughAnImmediateFade() {
        val tracker = BleSignalWarningTracker()

        assertFalse(tracker.update("peer", -86f, 0L))
        assertFalse(tracker.update("peer", -82f, 500L))
        assertFalse(tracker.update("peer", -86f, 1_000L))
        assertFalse(tracker.update("peer", -86f, 2_499L))
        assertTrue(tracker.update("peer", -86f, 2_500L))
        assertTrue(tracker.update("peer", -83f, 2_600L))
        assertTrue(tracker.update("peer", null, 2_700L))
        assertFalse(tracker.update("peer", -79f, 2_800L))
    }

    /**
     * Reference receiver sensitivity for LE 1M, the PHY every un-coded
     * advertisement and scan on this device runs on. TI's SimpleLink guide
     * quotes -93 dBm, and the README's reach figures are derived from it.
     */
    private val LE_1M_REFERENCE_SENSITIVITY = -93

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
        val filter = RssiKalmanFilter()

        assertEquals(raw, filter.add(-70, 0L)!!, 0.0001f)
    }

    @Test
    fun staleFilterStateExpiresAndTheNextSampleSeedsItExactly() {
        assertEquals(500L, RssiDistanceEstimator.FILTER_RESET_AFTER_MILLIS)
        val filter = RssiKalmanFilter()
        filter.add(-60, 0L)
        filter.add(-80, 500L)

        assertNotNull(filter.filteredRssi(1_000L))
        assertNull(filter.filteredRssi(1_001L))
        assertEquals(
            RssiDistanceEstimator.estimate(-80)!!,
            filter.add(-80, 1_001L)!!,
            0.0001f
        )
    }

    @Test
    fun filterRespondsToMovementWithoutJumpingToOneMeasurement() {
        val filter = RssiKalmanFilter()
        val near = RssiDistanceEstimator.estimate(-50)!!
        val far = RssiDistanceEstimator.estimate(-90)!!
        var at = 0L
        repeat(6) {
            filter.add(-50, at)
            at += 100
        }
        val before = filter.distance(at)!!
        val after = filter.add(-90, at)!!

        assertEquals("a steady peer should have settled near the near value", near, before, 0.5f)
        assertTrue(
            "expected to move toward the far reading without taking one sample literally",
            after > before && after < far
        )
    }

    @Test
    fun kalmanFilterDampsJitterAroundAStationaryPeer() {
        val filter = RssiKalmanFilter()
        val readings = listOf(-68, -72, -69, -71, -67, -73, -68, -70, -69, -72)
        var at = 0L
        val rawSteps = mutableListOf<Float>()
        val smoothedSteps = mutableListOf<Float>()
        var previousRaw: Float? = null
        var previousSmoothed: Float? = null

        readings.forEach { noisy ->
            val raw = RssiDistanceEstimator.estimate(noisy)!!
            val smoothed = filter.add(noisy, at)!!
            previousRaw?.let { rawSteps += kotlin.math.abs(raw - it) }
            previousSmoothed?.let { smoothedSteps += kotlin.math.abs(smoothed - it) }
            previousRaw = raw
            previousSmoothed = smoothed
            at += 100
        }

        val rawTotal = rawSteps.sum()
        val smoothedTotal = smoothedSteps.sum()
        assertTrue(
            "a steady peer should not move the readout: raw drifted $rawTotal m, " +
                "filtered drifted $smoothedTotal m",
            smoothedTotal < rawTotal
        )
    }

    @Test
    fun elapsedTimeChangesHowMuchTheFilterTrustsANewSample() {
        val rapid = RssiKalmanFilter()
        val sparse = RssiKalmanFilter()
        rapid.add(-60, 0L)
        sparse.add(-60, 0L)

        val rapidUpdate = rapid.add(-80, 100L)!!
        val sparseUpdate = sparse.add(-80, 400L)!!

        assertTrue(
            "a longer interval should increase process uncertainty and measurement gain",
            sparseUpdate > rapidUpdate
        )
    }

    @Test
    fun belowFloorReadingPreservesTheEstimateAndLaterValidReportsStillUpdateIt() {
        val filter = RssiKalmanFilter()
        var at = 0L
        repeat(4) {
            filter.add(-70, at)
            at += 100
        }

        val beforeFade = filter.distance(at)!!
        assertEquals(beforeFade, filter.add(-120, at)!!, 0.0001f)
        assertTrue("a later valid report should update the estimate", filter.add(-80, at + 100)!! > beforeFade)
    }

    @Test
    fun anOutlierBurstFromAMultiPathReflectionDoesNotMoveTheReadout() {
        // What tools/mock_radar.py now generates under --outliers: a peer
        // holding a steady distance whose adverts occasionally arrive far
        // stronger than the path deserves, because a packet reflected off a
        // wall or a body. Every one of those readings is a real, decodable
        // advert, so nothing upstream rejects them. This is the case the
        // filter exists for, and it is the one a smooth trajectory misses.
        val filter = RssiKalmanFilter()
        val steady = -70
        val steadyDistance = RssiDistanceEstimator.estimate(steady)!!
        // One reading in twenty is a reflection: a repeatable synthetic stress
        // trace, not a claim about how often reflections occur in a venue.
        val readings = mutableListOf(steady)
        repeat(40) { index ->
            readings += if (index % 20 == 0) steady + 20 else steady
        }

        // Let the filter settle on the stationary peer before injecting noise.
        var at = 0L
        var worstDrift = 0f
        repeat(20) {
            filter.add(steady, at)
            at += 100
        }
        readings.forEach { rssi ->
            val shown = filter.add(rssi, at)!!
            at += 100
            worstDrift = maxOf(worstDrift, kotlin.math.abs(shown - steadyDistance))
        }

        assertTrue(
            "the reflection must be big enough for this test to mean anything",
            RssiDistanceEstimator.estimate(steady + 20)!! < 0.6f
        )
        assertTrue(
            "a peer holding station at ${steadyDistance}m drifted $worstDrift m under reflections",
            worstDrift < 1.0f
        )
    }

    @Test
    fun aBelowFloorReadingPreservesTheEstimateForTheNextDistanceEstimate() {
        // A transient fade is not a useful range reading, so keep the current
        // estimate rather than flashing "unknown".
        val filter = RssiKalmanFilter()
        var at = 0L
        repeat(10) {
            filter.add(-70, at)
            at += 100
        }
        val previous = filter.distance(at)
        assertNotNull("the peer should be reported while it is audible", previous)
        assertEquals(
            "a below-floor sample should leave the last usable estimate available",
            previous,
            filter.add(RssiDistanceEstimator.SENSITIVITY_FLOOR_DBM.toInt() - 1, at),
        )
        assertEquals(
            "a matching valid sample should preserve the steady estimate",
            previous,
            filter.add(-70, at + 100),
        )
    }

    @Test
    fun aPeerDropsOutAroundTheDocumentedDistance() {
        // The README states peers stop appearing at roughly 35 m at the 2.2
        // exponent. That is the -59 dBm/1 m reference reading falling to the
        // -93 dBm LE 1M reference sensitivity, and it is a prediction from the
        // constants rather than a measurement. Asserting it keeps the documented
        // reach honest: change either calibration constant and this fails, which
        // is the point, because the README quotes the number back.
        val atReference = RssiDistanceEstimator.estimate(LE_1M_REFERENCE_SENSITIVITY)!!
        assertEquals(35f, atReference, 5f)
    }

    @Test
    fun theClampDoesNotMaskWherePeersActuallyDisappear() {
        // MAX_DISTANCE_METERS exists to stop the curve running away, but if it
        // engaged before the radio's own noise floor it would be the thing
        // deciding reach instead. It sits above the predicted drop-out, so the
        // radio decides - which is the honest ordering.
        val atReference = RssiDistanceEstimator.estimate(LE_1M_REFERENCE_SENSITIVITY)!!
        assertTrue(
            "the clamp engages at ${RssiDistanceEstimator.MAX_DISTANCE_METERS} m, before the " +
                "predicted drop-out at $atReference m",
            atReference < RssiDistanceEstimator.MAX_DISTANCE_METERS
        )
    }

    @Test
    fun theNoiseFloorSitsAtOrBelowRealReceiverSensitivity() {
        // SENSITIVITY_FLOOR_DBM is a self-imposed cutoff, not the radio's
        // sensitivity. A typical LE 1M receiver gives out around -93 dBm, so a
        // floor at or below that never gets to be the binding constraint: a
        // peer vanishes because the radio stopped hearing it, not because the
        // app gave up. If the floor were raised above the reference sensitivity
        // the app would start dropping peers it could still decode.
        assertTrue(
            "noise floor ${RssiDistanceEstimator.SENSITIVITY_FLOOR_DBM} dBm is above the " +
                "$LE_1M_REFERENCE_SENSITIVITY dBm reference sensitivity",
            RssiDistanceEstimator.SENSITIVITY_FLOOR_DBM <= LE_1M_REFERENCE_SENSITIVITY
        )
    }

    @Test
    fun thePathLossExponentStaysInsideTheDocumentedBand() {
        // 2.0 is free space and 2.0-3.0 is the commonly cited indoor range. A
        // value outside it would invalidate the reach figures in the README,
        // which are all quoted against this exponent.
        assertTrue(
            "exponent ${RssiDistanceEstimator.PATH_LOSS_EXPONENT} is outside the documented 2.0-3.0 band",
            RssiDistanceEstimator.PATH_LOSS_EXPONENT >= 2.0f &&
                RssiDistanceEstimator.PATH_LOSS_EXPONENT <= 3.0f
        )
    }

    @Test
    fun theOneMeterReferenceSitsMidBandForAHandset() {
        // -55 to -65 dBm at 1 m is the commonly reported range for a phone.
        // -59 is inside it, which is the whole claim the README makes about
        // this constant being a reasonable default rather than a guess.
        assertTrue(
            "reference ${RssiDistanceEstimator.RSSI_AT_ONE_METER} dBm is outside the -55 to -65 dBm handset band",
            RssiDistanceEstimator.RSSI_AT_ONE_METER >= -65f &&
                RssiDistanceEstimator.RSSI_AT_ONE_METER <= -55f
        )
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
