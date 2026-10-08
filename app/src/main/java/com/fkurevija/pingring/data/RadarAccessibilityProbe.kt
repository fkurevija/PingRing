package com.fkurevija.pingring.data

import android.content.res.Resources
import com.fkurevija.pingring.R
import kotlin.math.ceil

object RadarAccessibilityProbe {
    fun describeLocalized(
        resources: Resources,
        peerName: String?,
        distanceMeters: Float?,
        azimuthDegrees: Float?,
        proximityLabel: String?,
        beaconTitle: String?,
        distanceIsEstimate: Boolean = false,
        distanceAccuracyMeters: Float? = null,
        directionUncertaintyDegrees: Float? = null,
    ): String {
        if (peerName.isNullOrBlank()) return resources.getString(R.string.radar_no_tracked_member)

        val distance = if (distanceIsEstimate) {
            val validDistance = distanceMeters?.takeIf { it.isFinite() && it >= 0f }
            val estimate = validDistance?.let { resources.getString(R.string.radar_distance_meters, it) }
                ?: resources.getString(R.string.radar_distance_unavailable)
            "${resources.getString(R.string.gnss_distance_estimate)}. $estimate."
        } else distanceMeters
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.let { resources.getString(R.string.radar_distance_meters, it) }
            ?: proximityLabel?.takeIf { it.isNotBlank() }
                ?.let { resources.getString(R.string.radar_signal_state, it) }
            ?: resources.getString(R.string.radar_distance_unavailable)
        val direction = azimuthDegrees
            ?.takeIf { it.isFinite() }
            ?.let {
                if (directionUncertaintyDegrees != null) resources.getString(
                    R.string.radar_gps_direction, it.toInt(), ceil(directionUncertaintyDegrees).toInt(),
                ) else resources.getString(R.string.radar_direction_degrees, it.toInt())
            }
            ?: resources.getString(R.string.radar_direction_unavailable)
        val beacon = beaconTitle?.takeIf { it.isNotBlank() }
            ?.let { resources.getString(R.string.radar_active_beacon, it) }
            .orEmpty()
        return resources.getString(R.string.radar_description, peerName, distance, direction, beacon)
    }
}
