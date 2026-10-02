package com.fkurevija.pingring.data

import android.content.res.Resources
import java.util.Locale
import com.fkurevija.pingring.R

/** Produces the spoken equivalent of the radar's visual state. */
object RadarAccessibilityPolicy {
    fun describeLocalized(
        resources: Resources,
        peerName: String?,
        distanceMeters: Float?,
        azimuthDegrees: Float?,
        proximityLabel: String?,
        beaconTitle: String?,
    ): String {
        if (peerName.isNullOrBlank()) return resources.getString(R.string.radar_no_tracked_member)

        val distance = distanceMeters
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.let { resources.getString(R.string.radar_distance_meters, it) }
            ?: proximityLabel?.takeIf { it.isNotBlank() }
                ?.let { resources.getString(R.string.radar_signal_state, it) }
            ?: resources.getString(R.string.radar_distance_unavailable)
        val direction = azimuthDegrees
            ?.takeIf { it.isFinite() }
            ?.let { resources.getString(R.string.radar_direction_degrees, it.toInt()) }
            ?: resources.getString(R.string.radar_direction_unavailable)
        val beacon = beaconTitle?.takeIf { it.isNotBlank() }
            ?.let { resources.getString(R.string.radar_active_beacon, it) }
            .orEmpty()
        return resources.getString(R.string.radar_description, peerName, distance, direction, beacon)
    }

    fun describe(
        peerName: String?,
        distanceMeters: Float?,
        azimuthDegrees: Float?,
        proximity: String?,
        beaconTitle: String?,
    ): String {
        if (peerName.isNullOrBlank()) return "Radar. No tracked member. Direction unavailable."

        val distance = distanceMeters
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.let { String.format(Locale.US, "%.1f meters away.", it) }
            ?: proximity?.takeIf { it.isNotBlank() }?.let { "$it signal." }
            ?: "Distance unavailable."
        val direction = azimuthDegrees
            ?.takeIf { it.isFinite() }
            ?.let { "Direction ${it.toInt()} degrees." }
            ?: "Direction unavailable."
        val beacon = beaconTitle?.takeIf { it.isNotBlank() }?.let { " Active beacon: $it." }.orEmpty()
        return "Radar for $peerName. $distance $direction$beacon"
    }
}