package com.fkurevija.pingring.data.ranging
import com.fkurevija.pingring.data.*
import com.fkurevija.pingring.data.radar.RadarPeerUpdate
import org.junit.Assert.*
import org.junit.Test
import com.fkurevija.pingring.data.radar.GnssFixDto
import com.fkurevija.pingring.data.radar.GnssPolicy
import com.fkurevija.pingring.data.radar.RadarAccessibilityPolicy

class GnssDirectionPolicyTest {
    private val local = GnssFixDto(0.0, 0.0, 5f, 0L)
    private val east = local.copy(longitude = 0.00072) // Approximately 80 m.
    private val peer = RadarPeerUpdate(
        "peer", "Ana", -70, "Hot", 2f, null, null, null, null, null, 1_000L,
        distanceSource = RangingSourcePolicy.RangingSource.UWB,
        lastUwbMeasurementAtMillis = 1_000L,
    )

    private fun estimate(remote: GnssFixDto = east, heading: Float? = 0f, declination: Float = 0f) =
        GnssDirectionPolicy.estimate(local, remote, heading, declination)

    @Test
    fun cardinalBearingsAndDatelineAreCorrect() {
        assertEquals(90f, estimate()!!.azimuthDegrees, 0.01f)
        assertEquals(0f, estimate(local.copy(latitude = 0.00072))!!.azimuthDegrees, 0.01f)
        assertEquals(180f, estimate(local.copy(latitude = -0.00072))!!.azimuthDegrees, 0.01f)
        assertEquals(270f, estimate(local.copy(longitude = -0.00072))!!.azimuthDegrees, 0.01f)
        val nearDateline = local.copy(longitude = 179.9998)
        val acrossDateline = local.copy(longitude = -179.9998)
        assertEquals(90f, GnssDirectionPolicy.estimate(nearDateline, acrossDateline, 0f, 0f)!!.azimuthDegrees, 0.01f)
    }

    @Test
    fun headingAndMagneticDeclinationRotateTheScreenBearing() {
        assertEquals(0f, estimate(heading = 80f, declination = 10f)!!.azimuthDegrees, 0.01f)
        assertEquals(350f, estimate(heading = 100f)!!.azimuthDegrees, 0.01f)
        assertEquals(90f, estimate(heading = 360f)!!.azimuthDegrees, 0.01f)
    }

    @Test
    fun uncertainCloseStaleAndOutOfRangePositionsHaveNoBearing() {
        assertNull(estimate(local))
        assertNull(estimate(local.copy(longitude = 0.0001)))
        assertNull(estimate(east.copy(accuracyMeters = 50f)))
        assertNull(estimate(east.copy(ageMillis = 10_001L)))
        assertNull(GnssDirectionPolicy.estimate(local.copy(ageMillis = 10_001L), east, 0f, 0f))
        assertNull(estimate(east.copy(longitude = 0.002)))
        assertNull(estimate(east.copy(latitude = Double.NaN)))
        assertNull(estimate(heading = null))
        assertNull(estimate(heading = Float.NaN))
        assertNull(estimate(declination = Float.NaN))
        assertNotNull(estimate(east.copy(ageMillis = 10_000L)))
    }

    @Test
    fun quantizationErrorIsIncludedAndUncertaintyNeverExceedsThirtyDegrees() {
        val quantizedLocal = GnssPolicy.quantize(local)!!
        val quantizedRemote = GnssPolicy.quantize(east)!!
        val result = GnssDirectionPolicy.estimate(quantizedLocal, quantizedRemote, 0f, 0f)!!
        assertTrue(result.uncertaintyDegrees in 28f..30f)
        assertTrue(result.uncertaintyDegrees > estimate()!!.uncertaintyDegrees)
        assertNull(GnssDirectionPolicy.estimate(
            quantizedLocal, GnssPolicy.quantize(local.copy(longitude = 0.0005))!!, 0f, 0f,
        ))
    }

    @Test
    fun angularUncertaintyIsGatedAtThirtyDegreesIncludingHeadingAllowance() {
        val separation = GnssPolicy.separationMeters(local, east)
        fun remoteWithError(angle: Double) = east.copy(
            accuracyMeters = separation * kotlin.math.sin(Math.toRadians(angle)).toFloat() - local.accuracyMeters,
        )
        assertEquals(29.9f, estimate(remoteWithError(19.9))!!.uncertaintyDegrees, 0.01f)
        assertNull(estimate(remoteWithError(20.1)))
    }

    @Test
    fun spokenGpsDirectionIsExplicitlyAnEstimateAlongsideARadioDistance() {
        val summary = RadarAccessibilityPolicy.describe(
            "Ana", 2f, 90f, "Hot", null, directionUncertaintyDegrees = 25.1f,
        )
        assertEquals(
            "Radar for Ana. 2.0 meters away. Estimated GPS direction 90 degrees, uncertainty 26 degrees.",
            summary,
        )
    }

    @Test
    fun aDistanceOnlyUwbReadingIsPreservedWhenGpsAddsDirection() {
        val supplemented = GnssDirectionPolicy.supplement(peer, local, east, 0f, 0f, 1_001L)
        assertEquals(
            peer,
            supplemented.copy(
                estimatedDirection = null,
                estimatedDirectionUpdatedAtMillis = null,
                estimatedDirectionIsLastKnown = false,
            ),
        )
        assertEquals(90f, supplemented.radarAzimuth!!, 0.01f)
        assertEquals(2f, supplemented.distance)
        assertEquals(RangingSourcePolicy.RangingSource.UWB, supplemented.distanceSource)
    }

    @Test
    fun measuredBearingsWinAndInvalidGpsClearsOnlyTheSupplement() {
        val measured = peer.copy(azimuth = 45f)
        val updated = GnssDirectionPolicy.supplement(measured, local, east, 0f, 0f, 1_001L)
        assertEquals(measured, updated)
        assertEquals(45f, updated.radarAzimuth)
        val supplemented = GnssDirectionPolicy.supplement(peer, local, east, 0f, 0f, 1_001L)
        assertEquals(peer, GnssDirectionPolicy.supplement(supplemented, null, east, 0f, 0f, 31_002L))
    }

    @Test
    fun aGnssBearingIsKeptAsLastKnownUntilItsThirtySecondFadeExpires() {
        val initial = GnssDirectionPolicy.supplement(peer, local, east, 0f, 0f, 1_001L)
        val retained = GnssDirectionPolicy.supplement(initial, null, null, null, 0f, 10_000L)
        assertEquals(initial.estimatedDirection, retained.estimatedDirection)
        assertEquals(1_001L, retained.estimatedDirectionUpdatedAtMillis)
        assertTrue(retained.estimatedDirectionIsLastKnown)

        val expired = GnssDirectionPolicy.supplement(retained, null, null, null, 0f, 31_002L)
        assertNull(expired.estimatedDirection)
        assertNull(expired.estimatedDirectionUpdatedAtMillis)
        assertFalse(expired.estimatedDirectionIsLastKnown)
    }

    @Test
    fun freshMeasuredBearingReplacesAndClearsTheLastKnownGnssEstimate() {
        val initial = GnssDirectionPolicy.supplement(peer, local, east, 0f, 0f, 1_001L)
        val measured = initial.copy(
            azimuth = 45f,
            directionIsLastKnown = false,
            lastUwbMeasurementAtMillis = 5_000L,
        )
        val updated = GnssDirectionPolicy.supplement(measured, null, null, null, 0f, 5_001L)
        assertEquals(45f, updated.radarAzimuth)
        assertNull(updated.estimatedDirection)
        assertNull(updated.estimatedDirectionUpdatedAtMillis)
        assertFalse(updated.estimatedDirectionIsLastKnown)
    }

    @Test
    fun supplementalDirectionDoesNotChangeRssiOrWifiRttDistances() {
        for (source in listOf(RangingSourcePolicy.RangingSource.BLE_RSSI, RangingSourcePolicy.RangingSource.WIFI_RTT)) {
            val original = peer.copy(distanceSource = source, lastUwbMeasurementAtMillis = null)
            val supplemented = GnssDirectionPolicy.supplement(original, local, east, 0f, 0f, 1_001L)
            assertNotNull(supplemented.estimatedDirection)
            assertEquals(
                original,
                supplemented.copy(
                    estimatedDirection = null,
                    estimatedDirectionUpdatedAtMillis = null,
                    estimatedDirectionIsLastKnown = false,
                ),
            )
        }
    }

    @Test
    fun staleUwbBearingCanUseGpsAndAFreshBearingTakesOverAgain() {
        val original = peer.copy(azimuth = 45f, directionIsLastKnown = true)
        val supplemented = GnssDirectionPolicy.supplement(original, local, east, 0f, 0f, 3_001L)
        assertEquals(90f, supplemented.radarAzimuth!!, 0.01f)
        val fresh = supplemented.copy(directionIsLastKnown = false, lastUwbMeasurementAtMillis = 3_001L)
        assertEquals(45f, fresh.radarAzimuth)
        assertNull(GnssDirectionPolicy.supplement(fresh, local, east, 0f, 0f, 3_002L).estimatedDirection)
    }
}
