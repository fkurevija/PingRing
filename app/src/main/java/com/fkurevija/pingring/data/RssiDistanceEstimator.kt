package com.fkurevija.pingring.data

import kotlin.math.pow

/**
 * Turns a BLE RSSI reading into a distance estimate, and keeps the estimate
 * steady while a peer walks around.
 *
 * This replaces a linear formula (`distance = 2 + (rssi + 100) / 10`) that was
 * only ever evaluated the *first* time a peer was seen, so the number never
 * moved for the rest of the session. Two things were wrong with it:
 *
 * 1. It ran once, behind `existing?.distance ?: ...`, so a peer that walked
 *    from 2 m to 15 m kept reading the value it had on first contact. The
 *    radar showed a live hot/cold colour next to a frozen distance.
 * 2. It was linear, but received power falls off logarithmically, so it badly
 *    under-read at range. It also implied a peer at -70 dBm (the hot/cold
 *    threshold) was only 5 m away, which is why non-UWB peers clustered near
 *    the middle of the screen regardless of how far away they really were.
 *
 * The model here is the usual log-distance path-loss fit. Kept free of Android
 * APIs so the curve can be unit tested; [DiscoveryRepository] supplies the
 * readings and applies the smoothing.
 */
object RssiDistanceEstimator {

    /**
     * RSSI expected from this kind of advert at 1 m. A real calibration
     * constant: it depends on the phone's antenna and the peer's tx power, and
     * the "distance" below is only ever as good as this figure. Real measured
     * values for a Pixel-class handset sit around -55 to -65 dBm at 1 m, so
     * -59 is mid-band rather than arbitrary. It is still a literature default
     * and not a measurement taken from PingRing hardware.
     *
     * A limit worth naming: a received advert carries no transmit-power field,
     * so this single constant is necessarily an average across every peer model
     * and antenna in the ring, and per-peer bias cannot be corrected from the
     * packet. Fitting it properly means regressing against a known distance —
     * see the UWB link, which already provides one for peers it can range.
     */
    const val RSSI_AT_ONE_METER = -59f

    /**
     * Path-loss exponent. 2.0 is free space; 2.0-3.0 covers typical indoor and
     * crowded-venue conditions, where bodies and walls absorb signal. Higher
     * values make the curve fall away faster, i.e. read *nearer* for a given
     * RSSI. The exponent does double duty: it sets the accuracy of the estimate
     * *and* how far a peer can be before its signal hits the radio's noise
     * floor, so it decides reach as much as it decides the number on screen.
     */
    const val PATH_LOSS_EXPONENT = 2.2f

    /** Below this, the peer is too far to read anything trustworthy. */
    const val SENSITIVITY_FLOOR_DBM = -96f

    /** Closest reading worth reporting; guards against a bogus 0.0 m. */
    const val MIN_DISTANCE_METERS = 0.3f

    /**
     * Beyond this the estimate is dominated by noise and multi-path effects,
     * so it is clamped rather than allowed to run away to absurd values.
     */
    const val MAX_DISTANCE_METERS = 40f

    /**
     * Weight of the newest sample in the exponential moving average. 0.2 keeps
     * the readout steady enough to read while still tracking a peer who is
     * genuinely moving.
     */
    const val SMOOTHING_ALPHA = 0.2f

    /**
     * Single-sample RSSI -> distance estimate, or `null` when the reading is
     * below the noise floor and no honest number can be given.
     */
    fun estimate(rssi: Int): Float? {
        if (rssi < SENSITIVITY_FLOOR_DBM) return null
        val metres = 10f.pow((RSSI_AT_ONE_METER - rssi) / (10f * PATH_LOSS_EXPONENT))
        return metres.coerceIn(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS)
    }

    /**
     * Folds a new reading into a running estimate.
     *
     * A weak signal is noisy in both directions, so the raw value alone makes
     * the number jump around. Averaging exponentially keeps it legible. The
     * first sample seeds the estimate directly rather than being blended into
     * an arbitrary starting point, and a `null` reading (peer out of range)
     * drops back to the unfiltered sample so a peer that returns is not shown
     * a long-stale average.
     */
    fun smooth(previous: Float?, rawEstimate: Float?): Float? {
        if (rawEstimate == null) return null
        if (previous == null) return rawEstimate
        return previous + SMOOTHING_ALPHA * (rawEstimate - previous)
    }

    /**
     * The inverse of [estimate]: what RSSI a peer at [metres] would have.
     *
     * Only used to keep the scripted radar (tools/mock_radar.py) driving the
     * real rendering path — a scripted report has a distance and no signal
     * strength, so the dBm readout and the hot/cold ring have to be derived
     * from it. Sharing this curve is what keeps the mock honest: a change to
     * the model moves both together instead of silently desynchronising them.
     */
    fun rssiForDistance(metres: Float): Float {
        val clamped = metres.coerceIn(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS)
        val rssi = RSSI_AT_ONE_METER - 10f * PATH_LOSS_EXPONENT * kotlin.math.log10(clamped)
        return rssi.coerceIn(SENSITIVITY_FLOOR_DBM, 0f)
    }
}
