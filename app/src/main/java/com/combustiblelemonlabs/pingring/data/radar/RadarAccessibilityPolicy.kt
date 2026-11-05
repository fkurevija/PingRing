package com.combustiblelemonlabs.pingring.data.radar
import java.util.Locale
import kotlin.math.ceil

/** Produces the spoken equivalent of the radar's visual state. */
object RadarAccessibilityPolicy {
    fun describe(
        peerName: String?,
        distanceMeters: Float?,
        azimuthDegrees: Float?,
        proximity: String?,
        beaconTitle: String?,
        distanceIsEstimate: Boolean = false,
        distanceAccuracyMeters: Float? = null,
        directionUncertaintyDegrees: Float? = null,
    ): String {
        if (peerName.isNullOrBlank()) return "Radar. No tracked member. Direction unavailable."

        val distance = if (distanceIsEstimate) {
            val validDistance = distanceMeters?.takeIf { it.isFinite() && it >= 0f }
            if (validDistance != null) {
                String.format(Locale.US, "GPS distance estimate. %.1f meters away.", validDistance)
            } else "GPS distance estimate. Distance unavailable."
        } else distanceMeters
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.let { String.format(Locale.US, "%.1f meters away.", it) }
            ?: proximity?.takeIf { it.isNotBlank() }?.let { "$it signal." }
            ?: "Distance unavailable."
        val direction = azimuthDegrees
            ?.takeIf { it.isFinite() }
            ?.let {
                if (directionUncertaintyDegrees != null) {
                    "Estimated GPS direction ${it.toInt()} degrees, uncertainty ${ceil(directionUncertaintyDegrees).toInt()} degrees."
                } else "Direction ${it.toInt()} degrees."
            }
            ?: "Direction unavailable."
        val beacon = beaconTitle?.takeIf { it.isNotBlank() }?.let { " Active beacon: $it." }.orEmpty()
        return "Radar for $peerName. $distance $direction$beacon"
    }
}
