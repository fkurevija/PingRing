package com.fkurevija.pingring

import com.fkurevija.pingring.data.RssiDistanceEstimator
import com.fkurevija.pingring.data.RssiSmoother
import com.fkurevija.pingring.data.BleSignalWarningTracker
import org.junit.Assert.assertFalse
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
        val smoother = RssiSmoother()

        assertEquals(raw, smoother.add(-70, 0L)!!, 0.0001f)
    }

    @Test
    fun defaultWindowIncludesOneSecondButDropsOlderReadings() {
        assertEquals(1_000L, RssiDistanceEstimator.SMOOTHING_WINDOW_MILLIS)
        val smoother = RssiSmoother()
        smoother.add(-60, 0L)
        smoother.add(-80, 1_000L)
        assertEquals(-70f, smoother.smoothedRssi(1_000L)!!, 0.001f)
        assertEquals(-80f, smoother.smoothedRssi(1_001L)!!, 0.001f)
        assertEquals(RssiDistanceEstimator.estimate(-80)!!, smoother.distance(1_001L)!!, 0.001f)
        assertNull(smoother.distance(2_001L))
    }

    @Test
    fun smoothingMovesTowardTheNewReadingWithoutJumpingToIt() {
        // A reading that differs sharply from the window is outvoted by the rest
        // rather than replacing the estimate outright: one reflected packet
        // should not move the readout the length of the room.
        val smoother = RssiSmoother()
        val near = RssiDistanceEstimator.estimate(-50)!!
        val far = RssiDistanceEstimator.estimate(-90)!!
        var at = 0L
        repeat(6) {
            smoother.add(-50, at)
            at += 100
        }
        val before = smoother.distance(at)!!
        val after = smoother.add(-90, at)!!

        assertEquals("a steady peer should have settled near the near value", near, before, 0.5f)
        assertTrue(
            "expected to move toward the far reading but not reach it",
            after > before && after < far
        )
    }

    @Test
    fun smoothingDampsJitterAroundASteadyPeer() {
        // A peer standing still still produces noisy RSSI. This is the property
        // the whole window exists for: the estimate moves much less for the same
        // sequence of readings than the raw samples do.
        val smoother = RssiSmoother()
        val readings = listOf(-68, -72, -69, -71, -67, -73, -68, -70, -69, -72)
        var at = 0L
        val rawSteps = mutableListOf<Float>()
        val smoothedSteps = mutableListOf<Float>()
        var previousRaw: Float? = null
        var previousSmoothed: Float? = null

        readings.forEach { noisy ->
            val raw = RssiDistanceEstimator.estimate(noisy)!!
            val smoothed = smoother.add(noisy, at)!!
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
                "smoothed drifted $smoothedTotal m",
            smoothedTotal < rawTotal
        )
    }

    @Test
    fun outOfRangeReadingIsIgnoredAndRecentSamplesStillSmoothTheNextReading() {
        val smoother = RssiSmoother()
        var at = 0L
        repeat(4) {
            smoother.add(-70, at)
            at += 100
        }

        assertEquals(RssiDistanceEstimator.estimate(-70)!!, smoother.add(-120, at)!!, 0.0001f)
        assertEquals(
            RssiDistanceEstimator.estimateFromRssi((-70f * 4f - 80f) / 5f)!!,
            smoother.add(-80, at + 100)!!,
            0.0001f
        )
    }

    @Test
    fun anOutlierBurstFromAMultiPathReflectionDoesNotMoveTheReadout() {
        // What tools/mock_radar.py now generates under --outliers: a peer
        // holding a steady distance whose adverts occasionally arrive far
        // stronger than the path deserves, because a packet reflected off a
        // wall or a body. Every one of those readings is a real, decodable
        // advert, so nothing upstream rejects them - the window is the only
        // thing standing between one reflection and a peer appearing in your
        // face. This is the case the filter exists for, and it is the one a
        // smooth scripted trajectory could never produce.
        val smoother = RssiSmoother()
        val steady = -70
        val steadyDistance = RssiDistanceEstimator.estimate(steady)!!
        // One reading in twenty is a reflection. That is a busy indoor rate, not
        // a pathological one: measured against the window, 2% holds the readout
        // within ~0.3 m and 5% within ~0.6 m, while the reflection itself claims
        // a peer under 0.6 m. At a rate like this the window is doing real work
        // rather than meeting a caricature of one.
        val readings = mutableListOf(steady)
        repeat(40) { index ->
            readings += if (index % 20 == 0) steady + 20 else steady
        }

        // The window has to be full before a reflection means anything. An
        // outlier arriving against two samples averages to 1.1 m, which says
        // nothing about the filter - it says the filter had barely started. A
        // peer that has been in range for a moment is the case being tested.
        var at = 0L
        var worstDrift = 0f
        repeat(20) {
            smoother.add(steady, at)
            at += 100
        }
        readings.forEach { rssi ->
            val shown = smoother.add(rssi, at)!!
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
    fun aBelowFloorReadingPreservesRecentSamplesForTheNextDistanceEstimate() {
        // A transient fade is not a useful range reading. Keep the valid recent
        // signal samples so the next advert averages with them rather than
        // flashing "unknown" or jumping to the new sample alone.
        val smoother = RssiSmoother()
        var at = 0L
        repeat(10) {
            smoother.add(-70, at)
            at += 100
        }
        val previous = smoother.distance(at)
        assertNotNull("the peer should be reported while it is audible", previous)
        assertEquals(
            "a below-floor sample should leave the last usable estimate available",
            previous,
            smoother.add(RssiDistanceEstimator.SENSITIVITY_FLOOR_DBM.toInt() - 1, at),
        )
        assertEquals(
            "the next valid sample should still be averaged with recent readings",
            previous,
            smoother.add(-70, at + 100),
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
