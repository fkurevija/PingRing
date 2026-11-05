package com.combustiblelemonlabs.pingring.data

/**
 * What this device can actually do, and which of those ways of joining a ring
 * it should use.
 *
 * The settings screen used to offer two switches and let them imply a
 * decision: Ultra-wideband, on or off, defaulted from whether the hardware
 * existed, and a "High Bandwidth (Wi-Fi)" switch that was later deleted because
 * Nearby Connections carries the payload over Wi-Fi Direct whether or not the
 * app asks. That left one switch standing in for two questions - *can* this
 * device range, and *should* it - with no way to answer the second.
 *
 * So the two are separated here. [Capabilities] is what the hardware and OS
 * permit and is not a choice; [chooseMethod] is a preference narrowed to what
 * is actually on offer. Pure and Android-free so the rules can be asserted
 * directly.
 *
 * **Why there is no transport choice here.** The roadmap asked for one, and the
 * answer is that the platform does not offer it. Nearby Connections'
 * `requestConnection` takes no transport parameter: the service picks BLE or
 * Wi-Fi Direct itself, and the only transport constants that exist
 * (`WIFI_DIRECT`, `WIFI_HOTSPOT`) live in obfuscated internal classes, not the
 * public API. A host-run hotspot is out for the same reason - see
 * [Method.NEARBY]. So the item reduces to what genuinely remains: whether to
 * range, and whether this device is capable of ranging at all.
 */
object ConnectionMethodPolicy {

    /** How this device joins a ring. Each entry is a thing that can be absent. */
    enum class Method {
        /**
         * Nearby Connections over BLE and Wi-Fi Direct.
         *
         * Always present, and never optional: it is how every device is
         * discovered, and the transport beneath it is not the app's to pick.
         *
         * **A host-run hotspot is not an alternative, and that was verified
         * rather than assumed.** `TetheringManager.startTethering` is gated on
         * `TETHER_PRIVILEGED`, a signature|privileged permission only the system
         * holds. The one route a third-party app has is
         * `WifiManager.startLocalOnlyHotspot`, but against the API 37
         * `android.jar` the three pieces an app needs are all hidden:
         * `isLocalOnlyHotspotSupported`, `LocalOnlyHotspotReservation.getWifiInfo`
         * and `SoftApConfiguration.Builder`'s setters. So the app could start an
         * access point and then be unable to read its SSID - meaning it could
         * never tell a member which network to join. That is not a degraded
         * version of the feature, it is the feature with the one part that
         * matters removed.
         */
        NEARBY,

        /** UWB ranging for sub-metre distance and bearing. Needs hardware and permission. */
        UWB_RANGING,
    }

    /**
     * What the hardware and OS permit.
     *
     * [apiLevel] matters and was previously ignored: UWB ranging needs Android
     * 12 (API 31) or newer, so a device reporting the feature on an older
     * build can still be refused the permission. Gating on the feature alone
     * would offer a switch that cannot be granted.
     */
    data class Capabilities(
        val apiLevel: Int,
        val uwbHardwareSupported: Boolean,
        val uwbPermissionGranted: Boolean,
    ) {
        /** Whether UWB ranging can actually be turned on right now. */
        val canRange: Boolean
            get() = uwbHardwareSupported &&
                uwbPermissionGranted &&
                apiLevel >= MIN_UWB_API_LEVEL

        fun supports(method: Method): Boolean = when (method) {
            Method.NEARBY -> true
            Method.UWB_RANGING -> canRange
        }

        /** The methods actually on offer, in the order they should be offered. */
        fun available(): List<Method> = Method.entries.filter { supports(it) }
    }

    /** What the user asked for. Every field is a preference, not a promise. */
    data class Preferences(val uwbRangingEnabled: Boolean = true)

    /**
     * Why a method that was asked for is not in the result.
     *
     * Reported rather than silently dropped, so the settings screen can say
     * "UWB is off on this device" instead of showing a switch that refuses to
     * stay on.
     */
    enum class Unavailable {
        NO_HARDWARE,
        NO_PERMISSION,
        UNSUPPORTED_OS_VERSION,
    }

    /** The narrowed result: what runs, and what was asked for and refused. */
    data class Selection(
        val methods: List<Method>,
        val unranged: Unavailable?,
    ) {
        val usesUwb: Boolean get() = Method.UWB_RANGING in methods
    }

    /**
     * Android 12. Below this the UWB_RANGING permission does not exist, so a
     * device reporting the feature cannot be granted it, and offering the
     * switch would be offering something that cannot be turned on.
     */
    const val MIN_UWB_API_LEVEL = 31

    /**
     * Narrows [preferences] to what [capabilities] permits.
     *
     * NEARBY is always present and never refused: it is what makes discovery
     * work at all, and there is no configuration in which a PingRing runs
     * without it.
     */
    fun chooseMethod(
        capabilities: Capabilities,
        preferences: Preferences,
    ): Selection {
        val methods = mutableListOf(Method.NEARBY)

        // Order matters, and the version check deliberately comes before the
        // permission one. Below API 31 the UWB_RANGING permission does not
        // exist, so `rememberUwbAvailability` never requests it and
        // permissionGranted is only ever false - reporting that as "tap to
        // grant it" would send the user to a prompt that cannot appear. The
        // version is the blocker they cannot act on, so it is the one to name.
        val unranged = if (!preferences.uwbRangingEnabled) {
            null
        } else if (!capabilities.uwbHardwareSupported) {
            Unavailable.NO_HARDWARE
        } else if (capabilities.apiLevel < MIN_UWB_API_LEVEL) {
            Unavailable.UNSUPPORTED_OS_VERSION
        } else if (!capabilities.uwbPermissionGranted) {
            Unavailable.NO_PERMISSION
        } else {
            methods += Method.UWB_RANGING
            null
        }

        return Selection(methods = methods, unranged = unranged)
    }

    /** A sentence for each refusal, for the settings screen. */
    fun describe(refusal: Unavailable): String = when (refusal) {
        Unavailable.NO_HARDWARE -> "This device has no UWB radio."
        Unavailable.NO_PERMISSION -> "UWB ranging needs permission. Tap to grant it."
        Unavailable.UNSUPPORTED_OS_VERSION -> "UWB ranging needs Android 12 or newer."
    }
}
