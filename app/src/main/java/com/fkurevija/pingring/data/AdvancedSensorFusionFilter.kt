package com.fkurevija.pingring.data

import kotlin.math.max

/**
 * 1D Kalman Filter for sensor fusion across heterogeneous ranging sources
 * (UWB, Wi-Fi RTT, BLE RSSI, and GNSS).
 *
 * State vector: x = [distance (m), rate of change (m/s)]^T
 *
 * Process model:
 *   x_k = x_{k-1} + v_{k-1} * dt
 *   v_k = v_{k-1}
 *
 * Measurement model:
 *   z_k = [1, 0] * [x_k, v_k]^T = x_k
 *
 * Dynamic measurement noise variance R (m^2) depends on the ranging source:
 *   UWB:       0.05 m^2 (High precision)
 *   WIFI_RTT:  0.50 m^2 (Sub-metre precision)
 *   BLE_RSSI:  4.00 m^2 (Noisy RSSI path-loss estimate)
 *   GNSS:     10.00 m^2 (Satellite position difference estimate)
 */
class AdvancedSensorFusionFilter(
    initialDistanceMeters: Float = 0f,
    initialVelocityMps: Float = 0f,
    private val processNoiseVar: Float = 0.5f,
) {
    // State estimate: [x, v]
    var distanceMeters: Float = initialDistanceMeters
        private set
    var velocityMps: Float = initialVelocityMps
        private set

    // Estimate covariance matrix P = [[p00, p01], [p10, p11]]
    private var p00: Float = 1.0f
    private var p01: Float = 0.0f
    private var p10: Float = 0.0f
    private var p11: Float = 1.0f

    private var lastTimestampMillis: Long = -1L

    /**
     * Determines the measurement noise variance R (m^2) based on ranging source.
     */
    fun measurementNoiseVariance(source: RangingSourcePolicy.RangingSource?): Float {
        return when (source) {
            RangingSourcePolicy.RangingSource.UWB -> 0.05f
            RangingSourcePolicy.RangingSource.WIFI_RTT -> 0.50f
            RangingSourcePolicy.RangingSource.BLE_RSSI -> 4.00f
            RangingSourcePolicy.RangingSource.GNSS -> 10.00f
            null -> 4.00f
        }
    }

    /**
     * Updates the state estimate with a new ranging measurement.
     *
     * @param rawDistanceMeters The observed distance measurement.
     * @param timestampMillis The time at which the measurement occurred.
     * @param source The ranging source providing this measurement.
     * @param customMeasurementNoise Variance override, or null to use source default.
     * @return The filtered distance estimate.
     */
    fun update(
        rawDistanceMeters: Float,
        timestampMillis: Long,
        source: RangingSourcePolicy.RangingSource? = RangingSourcePolicy.RangingSource.BLE_RSSI,
        customMeasurementNoise: Float? = null,
    ): Float {
        if (!rawDistanceMeters.isFinite() || rawDistanceMeters < 0f) {
            return distanceMeters
        }

        if (lastTimestampMillis < 0L) {
            lastTimestampMillis = timestampMillis
            distanceMeters = rawDistanceMeters
            return distanceMeters
        }

        val dt = max(0.001f, (timestampMillis - lastTimestampMillis) / 1000f)
        lastTimestampMillis = timestampMillis

        // --- 1. PREDICT ---
        // x_pred = x + v * dt
        val xPred = distanceMeters + velocityMps * dt
        val vPred = velocityMps

        // Process noise covariance Q (continuous white noise acceleration model)
        val dt2 = dt * dt
        val dt3 = dt2 * dt / 2f
        val dt4 = dt2 * dt2 / 4f

        val q00 = dt4 * processNoiseVar
        val q01 = dt3 * processNoiseVar
        val q10 = dt3 * processNoiseVar
        val q11 = dt2 * processNoiseVar

        // P_pred = F * P * F^T + Q
        // F = [[1, dt], [0, 1]]
        val p00Pred = p00 + dt * (p10 + p01 + dt * p11) + q00
        val p01Pred = p01 + dt * p11 + q01
        val p10Pred = p10 + dt * p11 + q10
        val p11Pred = p11 + q11

        // --- 2. UPDATE ---
        val r = customMeasurementNoise ?: measurementNoiseVariance(source)

        // Innovation covariance S = H * P_pred * H^T + R = p00Pred + r
        val s = p00Pred + r
        if (s <= 0f || !s.isFinite()) {
            distanceMeters = max(0f, xPred)
            velocityMps = vPred
            return distanceMeters
        }

        // Kalman gain K = P_pred * H^T / S = [p00Pred / s, p10Pred / s]
        val k0 = p00Pred / s
        val k1 = p10Pred / s

        // Innovation y = z - H * x_pred = z - xPred
        val y = rawDistanceMeters - xPred

        // Corrected state x_new = x_pred + K * y
        distanceMeters = max(0f, xPred + k0 * y)
        velocityMps = vPred + k1 * y

        // Corrected covariance P_new = (I - K * H) * P_pred
        p00 = p00Pred * (1f - k0)
        p01 = p01Pred * (1f - k0)
        p10 = p10Pred - k1 * p00Pred
        p11 = p11Pred - k1 * p01Pred

        return distanceMeters
    }

    /**
     * Resets the filter state.
     */
    fun reset(initialDistanceMeters: Float = 0f) {
        distanceMeters = initialDistanceMeters
        velocityMps = 0f
        p00 = 1.0f
        p01 = 0.0f
        p10 = 0.0f
        p11 = 1.0f
        lastTimestampMillis = -1L
    }
}
