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
     * How far back a reading still counts toward the smoothed RSSI, in
     * milliseconds. A half-second window reduces movement lag while still
     * averaging several fast LAN samples to damp individual reflected packets.
     *
     * A window rather than an exponential moving average, because an EMA
     * weighted per *sample* has a time constant that depends on how often the
     * peer happens to advertise. The same alpha that averages out noise on a
     * phone advertising every 100 ms will lag badly on one advertising every
     * second, and the app cannot see the advertising interval - it only gets
     * the reports that actually arrive.
     */
    const val SMOOTHING_WINDOW_MILLIS = 500L

    /**
     * Single-sample RSSI -> distance estimate, or `null` when the reading is
     * below the noise floor and no honest number can be given.
     */
    fun estimate(rssi: Int): Float? = estimateFromRssi(rssi.toFloat())

    /**
     * The curve on its own, for an already-smoothed RSSI value.
     *
     * Split out from [estimate] so the window can average in dBm - where the
     * mean is meaningful, since power is what falls off linearly with distance
     * - and the curve is applied once at the end. Smoothing the *distances*
     * instead would average the log of the readings, which is not the same
     * thing and flattens the result.
     */
    fun estimateFromRssi(rssi: Float): Float? {
        if (rssi < SENSITIVITY_FLOOR_DBM) return null
        val metres = 10f.pow((RSSI_AT_ONE_METER - rssi) / (10f * PATH_LOSS_EXPONENT))
        return metres.coerceIn(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS)
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

/**
 * A rolling average of one peer's RSSI over the last [windowMillis], and the
 * distance that average implies.
 *
 * One instance per peer, so the readings of eight members in a ring cannot
 * contaminate each other. [DiscoveryRepository] keeps them in a map keyed by
 * peer id and drops the entry when the peer does.
 *
 * The average is taken in dBm rather than in metres. Received power is what
 * falls off with distance and dBm is already a logarithmic scale, so the plain
 * arithmetic mean of a set of readings is the right thing to average. Averaging
 * the resulting *distances* instead would average the logarithms and pull every
 * estimate toward the middle of the range - a worse filter, because it shrinks
 * a genuinely far peer instead of merely steadying them.
 *
 * The timestamp is a parameter rather than read from a clock here, so this is a
 * pure function of what it is given and can be tested without real time passing.
 */
class RssiSmoother(
    private val windowMillis: Long = RssiDistanceEstimator.SMOOTHING_WINDOW_MILLIS,
) {
    /** One reading, and when it was taken. */
    private data class Sample(val rssi: Float, val atMillis: Long)

    private val samples = ArrayDeque<Sample>()

    /** The smoothed RSSI over the window, or null when nothing recent is held. */
    fun smoothedRssi(nowMillis: Long): Float? {
        prune(nowMillis)
        if (samples.isEmpty()) return null
        // Summed as Double: a mean of many Float readings accumulates error in
        // Float, and the whole point of averaging is to be more precise than any
        // one sample.
        return (samples.sumOf { it.rssi.toDouble() } / samples.size).toFloat()
    }

    /** The distance the smoothed RSSI implies, or null when nothing is held. */
    fun distance(nowMillis: Long): Float? =
        smoothedRssi(nowMillis)?.let { RssiDistanceEstimator.estimateFromRssi(it) }

    /**
     * Adds a reading and returns the resulting smoothed distance, or null when
     * the peer is below the noise floor.
     *
     * A below-floor reading is treated as missing rather than as a real signal
     * level. Keep the recent valid samples so a transient fade does not replace
     * a useful distance with "unknown"; the normal time window still expires
     * old samples when the peer has been out of range long enough.
     */
    fun add(rssi: Int, nowMillis: Long): Float? {
        if (rssi < RssiDistanceEstimator.SENSITIVITY_FLOOR_DBM) {
            return distance(nowMillis)
        }
        samples.addLast(Sample(rssi.toFloat(), nowMillis))
        // Pruned before averaging so a stale reading cannot hold a value on
        // screen after the peer has gone quiet.
        prune(nowMillis)
        return distance(nowMillis)
    }

    /** Forgets every reading, for a peer that has left or been dropped. */
    fun clear() = samples.clear()

    /** Drops readings older than the window. */
    private fun prune(nowMillis: Long) {
        while (samples.isNotEmpty() && nowMillis - samples.first().atMillis > windowMillis) {
            samples.removeFirst()
        }
    }
}
