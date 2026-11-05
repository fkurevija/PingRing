package com.combustiblelemonlabs.pingring.data

/**
 * How long a member waits between repeats of a beacon alert.
 *
 * The bounds are a judgement about the difference between an alert and a
 * nuisance. One second is as tight as a repeat can be without the gap being
 * shorter than the buzz itself, which just reads as one long buzz. Thirty
 * seconds is as loose as it can be while still being recognisably "the same
 * alert repeating" rather than something that happened to be raised again.
 */
const val DEFAULT_BEACON_REPEAT_GAP_MILLIS = 5_000L
const val MIN_BEACON_REPEAT_GAP_MILLIS = 1_000L
const val MAX_BEACON_REPEAT_GAP_MILLIS = 30_000L

const val DEFAULT_BEACON_REPEAT_DURATION_MILLIS = 60_000L
const val MIN_BEACON_REPEAT_DURATION_MILLIS = 30_000L
const val MAX_BEACON_REPEAT_DURATION_MILLIS = 300_000L
const val BEACON_REPEAT_DURATION_UNTIL_STOPPED = Long.MAX_VALUE

internal fun normalizeBeaconRepeatGapMillis(gapMillis: Long): Long =
    gapMillis.coerceIn(MIN_BEACON_REPEAT_GAP_MILLIS, MAX_BEACON_REPEAT_GAP_MILLIS)

internal fun normalizeBeaconRepeatDurationMillis(durationMillis: Long): Long {
    if (durationMillis == BEACON_REPEAT_DURATION_UNTIL_STOPPED) return BEACON_REPEAT_DURATION_UNTIL_STOPPED
    return durationMillis.coerceIn(MIN_BEACON_REPEAT_DURATION_MILLIS, MAX_BEACON_REPEAT_DURATION_MILLIS)
}

/**
 * Whether a beacon alert plays once or keeps going.
 *
 * This is a *local* setting and is deliberately never sent in the beacon
 * payload. Nuisance is personal: the member raising the beacon has no business
 * deciding how loudly or how often somebody else's phone buzzes on their desk,
 * and a payload-carried setting would let one person dictate alerting for the
 * other seven. The pattern itself is the sender's choice and stays in the
 * payload; whether to repeat it is the receiver's.
 */
enum class BeaconAlertRepeat {
    /** Play the pattern a single time. The original behaviour. */
    ONCE,

    /**
     * Keep playing until the beacon stops or the member silences it.
     *
     * There is no middle option - "repeat exactly three times" - on purpose. A
     * fixed count needs a timer and a cancel path to be reliable, and the case
     * it covers is already served by the gap below: somebody who wants the alert
     * to persist sets the largest gap they can tolerate, and somebody who has
     * had enough silences that specific beacon.
     */
    REPEAT,
}

/**
 * How this device alerts for a beacon.
 *
 * Per-device and persisted, for the reason given on [BeaconAlertRepeat].
 */
data class BeaconAlertSettings(
    val repeat: BeaconAlertRepeat = BeaconAlertRepeat.ONCE,
    val repeatGapMillis: Long = DEFAULT_BEACON_REPEAT_GAP_MILLIS,
    val repeatDurationMillis: Long = DEFAULT_BEACON_REPEAT_DURATION_MILLIS,
) {
    /** This with the gap and duration forced into allowed ranges. */
    fun normalized(): BeaconAlertSettings =
        copy(
            repeatGapMillis = normalizeBeaconRepeatGapMillis(repeatGapMillis),
            repeatDurationMillis = normalizeBeaconRepeatDurationMillis(repeatDurationMillis),
        )

    companion object {
        /** No repeating, at the default gap and duration. What every install starts with. */
        val DEFAULT = BeaconAlertSettings()
    }
}

/**
 * A vibration waveform and where to loop it from, in the shape Android's
 * `VibrationEffect.createWaveform` wants it.
 *
 * Deliberately not a `VibrationEffect`: that is an Android class, and keeping
 * this a plain data holder is what lets the rules below be tested without a
 * device or a vibrator.
 */
data class BeaconWaveform(
    val timings: LongArray,
    /** -1 plays once; otherwise the index the loop restarts from. */
    val repeatFromIndex: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is BeaconWaveform &&
                timings.contentEquals(other.timings) &&
                repeatFromIndex == other.repeatFromIndex)

    override fun hashCode(): Int =
        31 * timings.contentHashCode() + repeatFromIndex
}

/**
 * How a beacon's pattern and this device's alert settings become a waveform.
 *
 * Pure: takes data, returns data. The Android call lives in DiscoveryRepository.
 */
object BeaconAlertPolicy {

    /** Passed through to `createWaveform` to mean "play once, do not loop". */
    const val NO_REPEAT = -1

    /**
     * The waveform to play for [pattern] under [settings], or null to play
     * nothing at all.
     *
     * Null is returned for exactly one case - the "No Vibration" pattern - and
     * that holds even when the member has asked for repeats. Repeating silence
     * on a timer would keep the alert path busy and wake the device for a
     * member who has already said they do not want to be buzzed, which is the
     * opposite of what the setting is for.
     */
    fun waveformFor(pattern: LongArray, settings: BeaconAlertSettings): BeaconWaveform? {
        if (isSilent(pattern)) return null

        val effective = settings.normalized()
        if (effective.repeat == BeaconAlertRepeat.ONCE) {
            return BeaconWaveform(pattern.copyOf(), NO_REPEAT)
        }

        // The gap is appended as the last entry so the loop is pattern-then-pause.
        // It has to live inside the waveform rather than being scheduled around
        // it: a separate timer would keep running after the member silenced the
        // beacon, and would drift against the real playback clock.
        return BeaconWaveform(
            timings = pattern.copyOf(pattern.size + 1).also { it[pattern.size] = effective.repeatGapMillis },
            repeatFromIndex = 0,
        )
    }

    /**
     * True when [pattern] asks for no vibration.
     *
     * Matches the shape the enum uses for "No Vibration" and also tolerates an
     * empty or all-zero array, since those are what a malformed or older
     * payload can carry. Being wrong in this direction means staying silent
     * rather than buzzing someone who asked not to be buzzed.
     */
    fun isSilent(pattern: LongArray): Boolean =
        pattern.isEmpty() || pattern.all { it == 0L }

    /**
     * Whether this beacon should alert on this device at all.
     *
     * Two independent reasons to say no: the member silenced this specific
     * beacon, or the beacon asks for no vibration. Both are local decisions and
     * neither is communicated to anybody.
     *
     * Silencing covers alerts ONLY. A silenced beacon is still drawn in the
     * transcript, still tints the radar, still counts as active and still ends
     * when the sender stops it. That boundary is the whole safety argument for
     * the feature: a member who is tired of a beacon must not be able to make an
     * emergency invisible to themselves, because the one case where muting is
     * dangerous is exactly the case where the beacon is real. Muting changes
     * what your phone *does*, never what it *shows you*.
     */
    fun shouldAlert(
        beaconId: String,
        silencedBeaconIds: Set<String>,
        pattern: LongArray,
    ): Boolean = beaconId !in silencedBeaconIds && !isSilent(pattern)
}