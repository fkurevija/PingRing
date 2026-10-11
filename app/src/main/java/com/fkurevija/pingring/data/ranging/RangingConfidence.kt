package com.fkurevija.pingring.data.ranging
/**
 * How confident a distance reading is, and why.
 *
 * This is what fusion buys. Before, a distance was either a UWB measurement or
 * an RSSI estimate and the app showed the number without saying which, so a
 * reading that had quietly gone wrong looked exactly like one that had not.
 *
 * The dominant way a UWB reading goes wrong in a crowd is not noise, it is
 * **obstruction**: a body between the two phones blocks the direct first path,
 * and the round trip is measured off a reflection instead, so the distance comes
 * out too long - often by metres, and always in the same direction.
 *
 * BLE signal strength degrades under that same body. The two are physically
 * independent measurements of the same geometry, which is exactly what makes
 * one a usable check on the other: a strong UWB reading accompanied by a
 * collapsed BLE signal is a reading to distrust, and there is no way to tell
 * that from the distance alone.
 *
 * Note what this does *not* do. Obstruction lowers confidence; it does not
 * trigger a downgrade. A UWB reading that is biased long is still a better
 * estimate than a signal-strength one, and silently substituting the worse
 * source would make the radar jump to a different method whenever somebody
 * walked between two phones. The reading is kept, marked, and shown.
 */
enum class RangingConfidence {

    /**
     * Nothing to judge from - one source only, or the peer has just arrived and
     * the corroborating one has not reported yet.
     *
     * Not the same as clear: the absence of evidence is reported as the absence
     * of evidence rather than as reassurance.
     */
    UNKNOWN,

    /** Two independent sources agree and neither is obstructed. */
    CLEAR,

    /**
     * A measurement exists but a body is plausibly in the way.
     *
     * The number is biased long and should be read as an upper bound rather
     * than as the distance.
     */
    OBSTRUCTED,
}