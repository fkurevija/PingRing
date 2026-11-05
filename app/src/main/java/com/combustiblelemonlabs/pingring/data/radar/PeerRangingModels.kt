package com.combustiblelemonlabs.pingring.data.radar
import kotlin.math.*

data class GnssFixDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val ageMillis: Long,
)

data class PeerRangingPayloadDto(
    val eventCode: String,
    val peerId: String,
    val sequence: Long,
    val wifiAwareMac: String? = null,
    val gnssFix: GnssFixDto? = null,
)

internal object GnssPolicy {
    const val UPDATE_INTERVAL_MILLIS = 3_000L
    const val MAX_FIX_AGE_MILLIS = 30_000L
    const val QUANTIZATION_METERS = 10.0
    const val MAX_ACCURACY_METERS = 50f

    fun isUsable(fix: GnssFixDto): Boolean = fix.latitude.isFinite() && fix.longitude.isFinite() &&
        fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
        fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0f..(MAX_ACCURACY_METERS + 8f) &&
        fix.ageMillis in 0..MAX_FIX_AGE_MILLIS

    fun quantize(fix: GnssFixDto): GnssFixDto? {
        if (!isUsable(fix) || fix.accuracyMeters > MAX_ACCURACY_METERS) return null
        val latitudeStep = QUANTIZATION_METERS / 111_320.0
        val longitudeStep = latitudeStep / cos(Math.toRadians(fix.latitude)).coerceAtLeast(0.01)
        return fix.copy(
            latitude = (round(fix.latitude / latitudeStep) * latitudeStep).coerceIn(-90.0, 90.0),
            longitude = (round(fix.longitude / longitudeStep) * longitudeStep).coerceIn(-180.0, 180.0),
            accuracyMeters = fix.accuracyMeters + 8f,
        )
    }

    fun separationMeters(local: GnssFixDto, remote: GnssFixDto): Float {
        val latitudeDelta = Math.toRadians(remote.latitude - local.latitude)
        val longitudeDelta = Math.toRadians(remote.longitude - local.longitude)
        val haversine = sin(latitudeDelta / 2).pow(2) +
            cos(Math.toRadians(local.latitude)) * cos(Math.toRadians(remote.latitude)) *
            sin(longitudeDelta / 2).pow(2)
        return (6_371_000.0 * 2 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))).toFloat()
    }
}