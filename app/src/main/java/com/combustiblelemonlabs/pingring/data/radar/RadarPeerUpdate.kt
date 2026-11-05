package com.combustiblelemonlabs.pingring.data.radar

import com.combustiblelemonlabs.pingring.data.radar.FocusPolicy
import com.combustiblelemonlabs.pingring.data.ranging.RangingConfidence
import com.combustiblelemonlabs.pingring.data.ranging.RangingSourcePolicy
import com.combustiblelemonlabs.pingring.data.ranging.EstimatedRadarDirection
import com.combustiblelemonlabs.pingring.data.session.Event

data class RadarPeerUpdate(
    val peerId: String,
    val name: String,
    val rssi: Int?,
    val proximity: String,
    val distance: Float?,
    val azimuth: Float?,
    val elevation: Float?,
    val worldDirectionX: Float?,
    val worldDirectionY: Float?,
    val worldDirectionZ: Float?,
    val lastSeen: Long,
    val signalWeak: Boolean = false,
    val meshOnlyPresence: Boolean = false,
    val meshPresenceLastSeenAtMillis: Long? = null,
    val lastUwbMeasurementAtMillis: Long? = null,
    val distanceSource: RangingSourcePolicy.RangingSource? = null,
    val distanceAccuracyMeters: Float? = null,
    val lastAdditionalRangingAtMillis: Long? = null,
    val directionIsLastKnown: Boolean = false,
    val estimatedDirection: EstimatedRadarDirection? = null,
    val estimatedDirectionUpdatedAtMillis: Long? = null,
    val estimatedDirectionIsLastKnown: Boolean = false,
    /**
     * When this peer reported that it joined the event, in epoch millis.
     *
     * Null when the peer has not said, which is the case for one whose advert
     * predates the field and for one seen only over the debug transport. The
     * distinction matters because [FocusPolicy] breaks ties by this value: a
     * peer with no timestamp has to sort as *unknown* rather than as
     * arbitrarily early, or a member on an older build would win the host's
     * default for the wrong reason.
     */
    val joinedAtMillis: Long? = null,
    /**
     * Whether this distance can be corroborated, and whether something is
     * plausibly in the way.
     *
     * Null until either source has produced a reading. [RangingConfidence] rather
     * than a boolean, because "no second opinion yet" and "a second opinion
     * that agrees" are different states and the radar shows them differently.
     */
    val rangingConfidence: RangingConfidence? = null,
    val syntheticRanging: Boolean = false
) {
    val displayedEstimatedDirection: EstimatedRadarDirection?
        get() = estimatedDirection?.takeIf { azimuth?.isFinite() != true || directionIsLastKnown }
    val radarAzimuth: Float?
        get() = displayedEstimatedDirection?.azimuthDegrees ?: azimuth
    val radarDirectionIsLastKnown: Boolean
        get() = if (displayedEstimatedDirection != null) estimatedDirectionIsLastKnown else directionIsLastKnown
    val radarDirectionUpdatedAtMillis: Long?
        get() = if (displayedEstimatedDirection != null) estimatedDirectionUpdatedAtMillis else lastUwbMeasurementAtMillis
}

data class LastKnownPeerDirection(
    val peerId: String,
    val name: String,
    val worldDirectionX: Float,
    val worldDirectionY: Float,
    val distance: Float?,
    val lastDetectedAtMillis: Long,
    val recordedAtMillis: Long,
)
