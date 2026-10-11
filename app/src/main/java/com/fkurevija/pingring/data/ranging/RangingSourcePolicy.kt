package com.fkurevija.pingring.data.ranging
import android.os.Build
import com.fkurevija.pingring.data.radar.GnssPolicy

/**
 * Where a distance reading came from, and which source a device should use for
 * the member it is tracking.
 *
 * Three sources, in descending order of how much they can tell you:
 *
 *  - [RangingSource.UWB] gives sub-metre distance and a bearing. Needs
 *    dedicated hardware, so only a minority of phones have it.
 *  - [RangingSource.WIFI_RTT] is 802.11mc round-trip timing: distance only,
 *    requiring compatible Wi-Fi Aware peers for phone-to-phone ranging.
 *  - [RangingSource.BLE_RSSI] is the fallback that always works - it is the
 *    same radio carrying discovery - but signal strength is a poor distance
 *    proxy. It is smoothed, and no bearing can be derived from it at all.
 *
 * The rule is the same throughout: use the best source this device and this
 * member can actually produce, and fall back only when the better one is
 * genuinely unavailable. Never the other way round, because a coarse reading is
 * worse than none once the user has been told the fine one exists.
 *
 * Pure and Android-free so the rules are directly testable; [RangingSourceProbe.probeWifiRtt] is
 * the one function that touches the platform, kept separate so the policy can
 * be asserted without a device.
 */
object RangingSourcePolicy {

    enum class RangingSource {
        /** Sub-metre distance and bearing from a UWB radio. */
        UWB,

        /** 802.11mc round-trip timing: distance and standard deviation, not bearing. */
        WIFI_RTT,

        /**
         * Coarse position from satellites, differenced between the two phones.
         *
         * Ranked below Wi-Fi RTT because it needs no peer correlation and no
         * permission beyond location, but it is metres rather than centimetres
         * and effectively unavailable indoors - which is most of where this app
         * is used. It earns its place outdoors, at a rally or a field, where
         * nothing better is available.
         */
        GNSS,

        /** BLE signal strength. Distance is an estimate; there is no bearing. */
        BLE_RSSI,
    }

    /** What can bear on whether this device can do Wi-Fi RTT ranging. */
    data class WifiRttCapability(
        /** The platform reports a usable RTT service. */
        val rttAvailable: Boolean,
        /** The app holds the location permission RTT is gated behind. */
        val locationPermissionGranted: Boolean,
        /**
         * Location services are switched on in system settings.
         *
         * Separate from the permission on purpose: a user can grant the
         * permission and still have location off device-wide, and RTT then
         * returns no results. Treating that as available would mean showing
         * ranging that never produces a reading.
         */
        val locationServicesEnabled: Boolean,
        /**
         * The OS level, as `Build.VERSION.SDK_INT`.
         *
         * A fourth condition rather than part of [rttAvailable], because it is
         * the one that is true while looking false: a phone can carry an RTT
         * radio, hold the permission and have location on, and still be too old
         * to connect a ranging result to a peer. Folding it into `rttAvailable`
         * would report that as missing hardware and send the user after
         * something they cannot change by any means at all.
         *
         * Defaults to the minimum supported level so the gate is inert unless a
         * caller supplies a real one; [probeWifiRtt] always passes the device's.
         */
        val apiLevel: Int = MIN_API_LEVEL,
    ) {
        val isUsable: Boolean
            get() = apiLevel >= MIN_API_LEVEL &&
                rttAvailable &&
                locationPermissionGranted &&
                locationServicesEnabled
    }

    /**
     * Why Wi-Fi RTT cannot be used, or null when it can.
     *
     * Ordered so the most actionable blocker is reported first. A user can fix
     * a missing permission by tapping and turn location services on themselves;
     * hardware support is the one they can do nothing about, so telling someone
     * with no RTT radio to grant a permission would send them down a dead end.
     */
    enum class WifiRttUnavailable {
        NO_HARDWARE,
        NO_LOCATION_PERMISSION,
        LOCATION_SERVICES_OFF,

        /**
         * The OS predates the only public way to correlate a ranging result to
         * a peer.
         *
         * Last, and deliberately so: a phone that is too old *and* missing the
         * permission should still be told to grant it, because an upgrade is a
         * bigger ask than a tap and mentioning the tap first costs nothing.
         */
        UNSUPPORTED_OS_VERSION,
    }

    /** What the device settled on, and what it could not use. */
    data class Selection(
        val source: RangingSource,
        val rttRefusedBy: WifiRttUnavailable? = null,
    ) {
        val hasBearing: Boolean get() = source == RangingSource.UWB
    }

    /**
     * Picks the best source available for the tracked member.
     *
     * [uwbActive] is whether a UWB session is open *for this member right now*. A
     * device can have a UWB radio and still have no fix for someone who just
     * arrived, and falling back in that gap is correct - whereas dropping to a
     * coarser source while a UWB fix is available would discard a reading the
     * user is already paying for in battery.
     *
     * [useWifiRtt] is the user's preference, never assumed. Ranging this way
     * costs a Wi-Fi session and needs location services switched on, which is a
     * real trade.
     *
     * The chain is strictly ranked - UWB, then Wi-Fi RTT, then BLE RSSI, which
     * always works because it is the same radio carrying discovery. [gnss] sits
     * below RTT because it needs no peer correlation and no special permission
     * beyond location, but it is unreliable indoors, which is most of where
     * this is used.
     */
    fun select(
        uwbActive: Boolean,
        useWifiRtt: Boolean,
        wifiRtt: WifiRttCapability,
        gnss: GnssCapability = GnssCapability(),
    ): Selection {
        if (uwbActive) return Selection(RangingSource.UWB)

        if (useWifiRtt) {
            val refusal = wifiRttRefusal(wifiRtt)
            if (refusal == null) return Selection(RangingSource.WIFI_RTT)
            // Fall through, but carry the reason so the UI can say why the
            // option it just offered is not running.
            return Selection(if (gnss.isUsable) RangingSource.GNSS else RangingSource.BLE_RSSI, rttRefusedBy = refusal)
        }

        if (gnss.isUsable) return Selection(RangingSource.GNSS)

        return Selection(RangingSource.BLE_RSSI)
    }

    /**
     * Whether a GNSS fallback could be used, if it were wired up.
     *
     * Both conditions are required and neither is implied by the other: a fix
     * needs location services on, and it needs to be *recent*. A fix from ten
     * minutes ago is worse than the RSSI reading it would replace, because at
     * least RSSI describes where the peer is now.
     *
     * The age ceiling is deliberately short. Someone walking at 1.4 m/s moves
     * nearly a screen-width across the radar in that time.
     */
    data class GnssCapability(
        val locationServicesEnabled: Boolean = false,
        val fixAgeMillis: Long? = null,
    ) {
        val isUsable: Boolean
            get() = locationServicesEnabled &&
                fixAgeMillis != null &&
                fixAgeMillis in 0..MAX_FIX_AGE_MILLIS

        companion object {
            /** ~20 m of movement at walking pace; well inside one radar ring. */
            const val MAX_FIX_AGE_MILLIS = GnssPolicy.MAX_FIX_AGE_MILLIS
        }
    }

    /**
     * Why Wi-Fi RTT cannot be used, or null when it can.
     *
     * Extracted so the ordering - which blocker is reported first - lives in one
     * place. Hardware comes before permission because it is not actionable: a
     * user with no RTT radio cannot be helped by granting anything, and telling
     * them to would send them down a dead end. The OS version goes last for the
     * opposite reason: it is the least actionable, so it must not hide an
     * actionable one.
     */
    fun wifiRttRefusal(capability: WifiRttCapability): WifiRttUnavailable? = when {
        !capability.rttAvailable -> WifiRttUnavailable.NO_HARDWARE
        !capability.locationPermissionGranted -> WifiRttUnavailable.NO_LOCATION_PERMISSION
        !capability.locationServicesEnabled -> WifiRttUnavailable.LOCATION_SERVICES_OFF
        capability.apiLevel < MIN_API_LEVEL -> WifiRttUnavailable.UNSUPPORTED_OS_VERSION
        else -> null
    }

    /** A sentence for each refusal, for the settings screen. */
    fun describe(refusal: WifiRttUnavailable): String = when (refusal) {
        WifiRttUnavailable.NO_HARDWARE -> "This device has no Wi-Fi RTT hardware."
        WifiRttUnavailable.NO_LOCATION_PERMISSION ->
            "Wi-Fi RTT ranging needs location permission. Tap to grant it."
        WifiRttUnavailable.LOCATION_SERVICES_OFF ->
            "Turn on location services to use Wi-Fi RTT ranging."
        WifiRttUnavailable.UNSUPPORTED_OS_VERSION ->
            "Wi-Fi RTT ranging needs Android 9 or newer."
    }



    /**
     * Judges a reading against an independent measurement of the same geometry.
     *
     * [rssiDbm] is the smoothed BLE signal strength for the *same peer*, or null
     * when none has arrived. [expectedDistanceMeters] is what that signal alone
     * implies, and it is required: without a second source there is nothing to
     * corroborate, so reporting CLEAR would claim a check that never ran.
     *
     * A low RSSI is only evidence when the peer is *near*. Someone in the next
     * tent can legitimately be faint, so a weak signal at long range is
     * unremarkable and produces no alarm - only [SHORT_RANGE_METERS] and closer
     * makes it suspicious.
     */
    fun confidence(rssiDbm: Int?, expectedDistanceMeters: Float?): RangingConfidence {
        if (rssiDbm == null || expectedDistanceMeters == null) return RangingConfidence.UNKNOWN
        if (expectedDistanceMeters > SHORT_RANGE_METERS) return RangingConfidence.UNKNOWN
        return if (rssiDbm >= OBSTRUCTION_RSSI_DBM) {
            RangingConfidence.CLEAR
        } else {
            RangingConfidence.OBSTRUCTED
        }
    }

    /**
     * RSSI at or above which a near peer is treated as unobstructed; below it,
     * as obstructed.
     *
     * Deliberately conservative. At a festival a phone in a back pocket or
     * behind a body sits below this routinely without anything blocking the
     * UWB first path, and a check that cried wolf on every scan would be
     * ignored within minutes - which is worse than not having it. The bar is
     * inclusive so that the boundary itself counts as the *good* case: it is
     * the last reading an unobstructed link would produce, and only one step
     * past it is evidence of something.
     */
    const val OBSTRUCTION_RSSI_DBM = -80

    /** Within this range a weak signal is evidence of something in the way. */
    const val SHORT_RANGE_METERS = 3.0f

    /** A sentence for each confidence, for the radar. */
    fun describe(confidence: RangingConfidence): String = when (confidence) {
        RangingConfidence.UNKNOWN -> "Distance estimate only."
        RangingConfidence.CLEAR -> "Cross-checked."
        RangingConfidence.OBSTRUCTED -> "Someone may be in the way."
    }

    const val MIN_API_LEVEL = Build.VERSION_CODES.P
}
