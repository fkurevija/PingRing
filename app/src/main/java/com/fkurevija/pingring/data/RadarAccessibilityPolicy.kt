package com.fkurevija.pingring.data

import java.util.Locale

/** Produces the spoken equivalent of the radar's visual state. */
object RadarAccessibilityPolicy {
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