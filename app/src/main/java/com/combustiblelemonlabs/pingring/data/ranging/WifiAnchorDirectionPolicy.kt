package com.combustiblelemonlabs.pingring.data.ranging
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import com.combustiblelemonlabs.pingring.data.radar.GnssFixDto
import com.combustiblelemonlabs.pingring.data.radar.GnssPolicy

/**
 * Local-only approximation of a fixed Wi-Fi access-point location for LAN
 * RSSI-proxied peers. The first reliable host fix, compass heading, and RSSI
 * range establish the anchor; later host fixes yield a changing relative
 * bearing without sending coordinates through the relay.
 */
internal object WifiAnchorDirectionPolicy {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MAX_FIX_AGE_MILLIS = 10_000L
    private const val HEADING_UNCERTAINTY_DEGREES = 10f

    data class Anchor(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
    )

    fun createAnchor(
        fix: GnssFixDto,
        magneticHeadingDegrees: Float?,
        declinationDegrees: Float,
        distanceMeters: Float?,
    ): Anchor? {
        if (!isFreshUsableFix(fix) || magneticHeadingDegrees?.isFinite() != true ||
            !declinationDegrees.isFinite() || distanceMeters?.isFinite() != true ||
            distanceMeters !in 0.1f..100f
        ) return null

        val bearing = Math.toRadians((magneticHeadingDegrees + declinationDegrees).toDouble())
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val latitude = Math.toRadians(fix.latitude)
        val longitude = Math.toRadians(fix.longitude)
        val targetLatitude = asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing)
        )
        val targetLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(targetLatitude),
        )
        return Anchor(
            latitude = Math.toDegrees(targetLatitude),
            longitude = ((Math.toDegrees(targetLongitude) + 540.0) % 360.0) - 180.0,
            accuracyMeters = fix.accuracyMeters,
        )
    }

    fun estimate(
        localFix: GnssFixDto,
        anchor: Anchor,
        magneticHeadingDegrees: Float?,
        declinationDegrees: Float,
    ): EstimatedRadarDirection? {
        if (!isFreshUsableFix(localFix) || magneticHeadingDegrees?.isFinite() != true ||
            !declinationDegrees.isFinite() || !anchor.latitude.isFinite() ||
            !anchor.longitude.isFinite() || !anchor.accuracyMeters.isFinite() ||
            anchor.accuracyMeters < 0f
        ) return null

        val latitude = Math.toRadians(localFix.latitude)
        val targetLatitude = Math.toRadians(anchor.latitude)
        val longitudeDelta = Math.toRadians(anchor.longitude - localFix.longitude)
        val east = sin(longitudeDelta) * cos(targetLatitude)
        val north = cos(latitude) * sin(targetLatitude) -
            sin(latitude) * cos(targetLatitude) * cos(longitudeDelta)
        if (east == 0.0 && north == 0.0) return null

        val trueBearing = Math.toDegrees(atan2(east, north)).toFloat()
        val relativeBearing = trueBearing - (magneticHeadingDegrees + declinationDegrees)
        val distance = GnssPolicy.separationMeters(
            localFix,
            GnssFixDto(anchor.latitude, anchor.longitude, anchor.accuracyMeters, 0L),
        )
        if (!distance.isFinite() || distance <= 0f) return null

        val errorRatio = ((localFix.accuracyMeters + anchor.accuracyMeters) / distance)
            .coerceIn(0f, 1f)
        val uncertainty = Math.toDegrees(asin(errorRatio.toDouble())).toFloat() +
            HEADING_UNCERTAINTY_DEGREES
        return EstimatedRadarDirection(
            azimuthDegrees = (relativeBearing % 360f + 360f) % 360f,
            uncertaintyDegrees = uncertainty,
        )
    }

    private fun isFreshUsableFix(fix: GnssFixDto): Boolean =
        GnssPolicy.isUsable(fix) && fix.accuracyMeters <= GnssPolicy.MAX_ACCURACY_METERS &&
            fix.ageMillis <= MAX_FIX_AGE_MILLIS
}
