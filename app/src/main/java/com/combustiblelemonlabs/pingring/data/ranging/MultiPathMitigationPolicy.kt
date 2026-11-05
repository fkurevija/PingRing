package com.combustiblelemonlabs.pingring.data.ranging
import kotlin.math.abs

/**
 * Predictive model for Wi-Fi RTT and BLE RSSI using time/angle observations
 * to reject reflected multi-path signals in complex indoor venues.
 */
object MultiPathMitigationPolicy {

    /** Maximum acceptable distance variance before flagging multi-path reflection. */
    const val MAX_REASONABLE_RTT_VARIANCE_METRES = 2.5f

    /**
     * Evaluates whether a raw Wi-Fi RTT or BLE RSSI distance reading is corrupted
     * by multi-path fading/reflection based on sudden variance or angle jump.
     */
    fun isMultiPathCorrupted(
        currentDistanceMeters: Float,
        historicalAverageMeters: Float,
        rssiDbm: Int?,
        angleDegreesDelta: Float?,
    ): Boolean {
        val distanceJump = abs(currentDistanceMeters - historicalAverageMeters)
        if (distanceJump > MAX_REASONABLE_RTT_VARIANCE_METRES) {
            if (rssiDbm != null && rssiDbm > -70 && distanceJump > 4.0f) {
                return true
            }
            if (angleDegreesDelta != null && angleDegreesDelta > 45f && distanceJump > 3.0f) {
                return true
            }
        }
        return false
    }
}
