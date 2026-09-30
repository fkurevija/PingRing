package com.fkurevija.pingring.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Where a distance reading came from, and which source a device should use for
 * the member it is tracking.
 *
 * Three sources, in descending order of how much they can tell you:
 *
 *  - [RangingSource.UWB] gives sub-metre distance and a bearing. Needs
 *    dedicated hardware, so only a minority of phones have it.
 *  - [RangingSource.WIFI_RTT] is 802.11mc round-trip timing: roughly
 *    metre-scale with a bearing, on hardware far more common than UWB.
 *  - [RangingSource.BLE_RSSI] is the fallback that always works - it is the
 *    same radio carrying discovery - but signal strength is a poor distance
 *    proxy. It is smoothed, and no bearing can be derived from it at all.
 *
 * The rule is the same throughout: use the best source this device and this
 * member can actually produce, and fall back only when the better one is
 * genuinely unavailable. Never the other way round, because a coarse reading is
 * worse than none once the user has been told the fine one exists.
 *
 * Pure and Android-free so the rules are directly testable; [probeWifiRtt] is
 * the one function that touches the platform, kept separate so the policy can
 * be asserted without a device.
 */
object RangingSourcePolicy {

    enum class RangingSource {
        /** Sub-metre distance and bearing from a UWB radio. */
        UWB,

        /** 802.11mc round-trip timing: distance, standard deviation and bearing. */
        WIFI_RTT,

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
    ) {
        val isUsable: Boolean
            get() = rttAvailable && locationPermissionGranted && locationServicesEnabled
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
    }

    /** What the device settled on, and what it could not use. */
    data class Selection(
        val source: RangingSource,
        val rttRefusedBy: WifiRttUnavailable? = null,
    ) {
        /** RSSI alone gives no bearing; the other two do. */
        val hasBearing: Boolean get() = source != RangingSource.BLE_RSSI
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
     */
    fun select(
        uwbActive: Boolean,
        useWifiRtt: Boolean,
        wifiRtt: WifiRttCapability,
    ): Selection {
        if (uwbActive) return Selection(RangingSource.UWB)

        if (useWifiRtt) {
            val refusal = when {
                !wifiRtt.rttAvailable -> WifiRttUnavailable.NO_HARDWARE
                !wifiRtt.locationPermissionGranted ->
                    WifiRttUnavailable.NO_LOCATION_PERMISSION
                !wifiRtt.locationServicesEnabled ->
                    WifiRttUnavailable.LOCATION_SERVICES_OFF
                else -> null
            }
            if (refusal == null) return Selection(RangingSource.WIFI_RTT)
            // Fall through to RSSI, but carry the reason so the UI can say why
            // the option it just offered is not running.
            return Selection(RangingSource.BLE_RSSI, rttRefusedBy = refusal)
        }

        return Selection(RangingSource.BLE_RSSI)
    }

    /** A sentence for each refusal, for the settings screen. */
    fun describe(refusal: WifiRttUnavailable): String = when (refusal) {
        WifiRttUnavailable.NO_HARDWARE -> "This device has no Wi-Fi RTT hardware."
        WifiRttUnavailable.NO_LOCATION_PERMISSION ->
            "Wi-Fi RTT ranging needs location permission. Tap to grant it."
        WifiRttUnavailable.LOCATION_SERVICES_OFF ->
            "Turn on location services to use Wi-Fi RTT ranging."
    }

    /**
     * Reads what this device can currently do.
     *
     * Every check is wrapped: all three can throw on an OEM build that exposes
     * the class without supporting it, and a capability probe that crashes the
     * settings screen is worse than one that reports nothing.
     *
     * `WifiRttManager.isAvailable` is a documented way to ask, unlike the
     * hotspot equivalents which are hidden.
     */
    fun probeWifiRtt(context: Context): WifiRttCapability {
        val rttAvailable = runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
            manager?.isAvailable() == true
        }.getOrDefault(false)

        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        val locationServicesEnabled = runCatching {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager?.isLocationEnabled == true
        }.getOrDefault(false)

        return WifiRttCapability(
            rttAvailable = rttAvailable,
            locationPermissionGranted = permissionGranted,
            locationServicesEnabled = locationServicesEnabled,
        )
    }

    /**
     * Whether the OS is new enough for the usable path at all.
     *
     * 802.11mc ranging itself arrived in Android 9, but joining a ranging result
     * back to a *specific* peer needs Wi-Fi Direct Service Discovery, whose peer
     * ids come back on `RangingResult.getUsdPeerId()`. Below API 34 those two
     * cannot be connected, so the feature stays off whatever the radio reports.
     */
    const val MIN_API_LEVEL = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
