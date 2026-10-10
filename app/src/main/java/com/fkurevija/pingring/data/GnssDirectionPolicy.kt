package com.fkurevija.pingring.data

import com.fkurevija.pingring.data.radar.RadarPeerUpdate
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A screen-relative estimate, separate from a radio's measured azimuth. */
data class EstimatedRadarDirection(val azimuthDegrees: Float, val uncertaintyDegrees: Float)

internal object GnssDirectionPolicy {
    const val MAX_DIRECTION_AGE_MILLIS = 10_000L
    const val LAST_KNOWN_DIRECTION_MILLIS = 30_000L
    const val MAX_DIRECTION_DISTANCE_METERS = 100f
    const val MAX_UNCERTAINTY_DEGREES = 30f
    private const val HEADING_UNCERTAINTY_DEGREES = 10f

    fun supplement(
        peer: RadarPeerUpdate,
        local: GnssFixDto?,
        remote: GnssFixDto?,
        magneticHeadingDegrees: Float?,
        declinationDegrees: Float,
        nowMillis: Long,
    ): RadarPeerUpdate {
        val hasFreshMeasuredBearing = measuredBearingAvailable(peer, nowMillis)
        val freshEstimate = if (local != null && remote != null && !hasFreshMeasuredBearing) {
            estimate(local, remote, magneticHeadingDegrees, declinationDegrees)
        } else null
        val keepLastEstimate = freshEstimate == null && !hasFreshMeasuredBearing &&
            peer.estimatedDirection != null &&
            peer.estimatedDirectionUpdatedAtMillis?.let {
                nowMillis - it in 0..LAST_KNOWN_DIRECTION_MILLIS
            } == true

        return peer.copy(
            estimatedDirection = freshEstimate ?: peer.estimatedDirection.takeIf { keepLastEstimate },
            estimatedDirectionUpdatedAtMillis = when {
                freshEstimate != null -> nowMillis
                keepLastEstimate -> peer.estimatedDirectionUpdatedAtMillis
                else -> null
            },
            estimatedDirectionIsLastKnown = keepLastEstimate,
        )
    }

    fun estimate(
        local: GnssFixDto,
        remote: GnssFixDto,
        magneticHeadingDegrees: Float?,
        declinationDegrees: Float,
    ): EstimatedRadarDirection? {
        if (!GnssPolicy.isUsable(local) || !GnssPolicy.isUsable(remote) ||
            local.ageMillis > MAX_DIRECTION_AGE_MILLIS || remote.ageMillis > MAX_DIRECTION_AGE_MILLIS ||
            magneticHeadingDegrees == null || !magneticHeadingDegrees.isFinite() ||
            !declinationDegrees.isFinite()
        ) return null
        val separation = GnssPolicy.separationMeters(local, remote)
        val error = local.accuracyMeters + remote.accuracyMeters
        if (separation <= error || separation > MAX_DIRECTION_DISTANCE_METERS) return null
        // Sum the position-error radii; RSSI cannot justify shrinking this uncertainty.
        val uncertainty = Math.toDegrees(asin((error / separation).toDouble())).toFloat() +
            HEADING_UNCERTAINTY_DEGREES
        if (uncertainty > MAX_UNCERTAINTY_DEGREES) return null

        val lat1 = Math.toRadians(local.latitude)
        val lat2 = Math.toRadians(remote.latitude)
        val deltaLongitude = Math.toRadians(remote.longitude - local.longitude)
        val east = sin(deltaLongitude) * cos(lat2)
        val north = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLongitude)
        val trueBearing = Math.toDegrees(atan2(east, north)).toFloat()
        val relative = trueBearing - (magneticHeadingDegrees + declinationDegrees)
        return EstimatedRadarDirection((relative % 360f + 360f) % 360f, uncertainty)
    }

    fun measuredBearingAvailable(peer: RadarPeerUpdate, nowMillis: Long): Boolean =
        peer.azimuth?.isFinite() == true && !peer.directionIsLastKnown &&
            (peer.lastUwbMeasurementAtMillis == null ||
                nowMillis - peer.lastUwbMeasurementAtMillis in 0..2_000L)
}
