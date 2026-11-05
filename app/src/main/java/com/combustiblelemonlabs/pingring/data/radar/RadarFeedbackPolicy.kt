package com.combustiblelemonlabs.pingring.data.radar
import com.combustiblelemonlabs.pingring.data.ranging.RangingSourcePolicy
internal object RadarFeedbackPolicy {
    fun reduceMotion(appEnabled: Boolean, systemAnimationsEnabled: Boolean): Boolean =
        appEnabled || !systemAnimationsEnabled

    fun proximityHaptic(enabled: Boolean, distanceMeters: Float?, source: RangingSourcePolicy.RangingSource?): Boolean =
        enabled && source != RangingSourcePolicy.RangingSource.GNSS &&
            distanceMeters != null && distanceMeters.isFinite() && distanceMeters >= 0f && distanceMeters < 2f

    fun proximityState(distanceMeters: Float?, source: RangingSourcePolicy.RangingSource?, signalState: String?): String =
        when {
            source == RangingSourcePolicy.RangingSource.GNSS -> "gps-estimate"
            distanceMeters == null || !distanceMeters.isFinite() || distanceMeters < 0f -> signalState ?: "unknown"
            distanceMeters < 2f -> "near"
            distanceMeters < 10f -> "within-ten-metres"
            else -> "farther"
        }
}

internal class RadarAnnouncementPolicy {
    data class State(val peerId: String?, val proximityState: String, val beaconTitle: String?)
    private var lastAnnounced: State? = null
    private var lastAnnouncedAtMillis: Long? = null

    fun delayBeforeAnnouncement(state: State, nowMillis: Long): Long? {
        if (state == lastAnnounced || (lastAnnounced == null && state.peerId == null && state.beaconTitle == null)) return null
        val lastAt = lastAnnouncedAtMillis ?: return 0L
        return (MIN_INTERVAL_MILLIS - (nowMillis - lastAt)).coerceIn(0L, MIN_INTERVAL_MILLIS)
    }

    fun markAnnounced(state: State, nowMillis: Long) {
        lastAnnounced = state
        lastAnnouncedAtMillis = nowMillis
    }

    fun reset() {
        lastAnnounced = null
        lastAnnouncedAtMillis = null
    }

    companion object { const val MIN_INTERVAL_MILLIS = 10_000L }
}