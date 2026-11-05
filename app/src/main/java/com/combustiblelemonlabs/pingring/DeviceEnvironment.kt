package com.combustiblelemonlabs.pingring

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings

/**
 * What this device is, for the parts of the app that adapt to it.
 *
 * Both the settings screen and the appearance settings needed to know whether the
 * app is running on an emulator, and had grown the same list of signals
 * separately. They drift the way duplicated probes do - one gets a new check and
 * the other keeps guessing - and the two disagreeing is not something a test
 * catches, because there is only one device to run either on.
 */
object DeviceEnvironment {

    /**
     * Emulator images report sdk_gphone hardware and usually run userdebug/eng
     * builds.
     */
    fun isEmulator(): Boolean =
        Build.TYPE == "userdebug" ||
            Build.TYPE == "eng" ||
            Build.MODEL.startsWith("sdk_gphone") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.FINGERPRINT.startsWith("generic")

    // `Settings.Global.ADB_WIFI_ENABLED` is @hide in the SDK, so the wireless
    // debugging flag is read through its platform string value instead.
    private const val ADB_WIFI_ENABLED_KEY = "adb_wifi_enabled"

    /**
     * Whether this device is set up for development: ADB over Wi-Fi, ADB over USB,
     * the Developer options master switch, a USB cable to a development machine, or
     * an emulator.
     *
     * Android 15 and later filter `adb_enabled` and `development_settings_enabled`
     * out of `Settings.Global` for apps on some devices - the reads silently return
     * the default value instead of throwing - so those two only report the truth
     * where the platform still exposes them; `adb_wifi_enabled` is readable on the
     * devices checked so far. A debug build on an emulator or with a USB cable
     * attached therefore covers the remaining cases. Every read is defensive: any
     * failure simply means "not detected".
     */
    fun isDebuggingActive(context: Context): Boolean =
        isGlobalFlagEnabled(context, ADB_WIFI_ENABLED_KEY) ||
            isGlobalFlagEnabled(context, Settings.Global.ADB_ENABLED) ||
            isGlobalFlagEnabled(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) ||
            isUsbPowered(context) ||
            isEmulator()

    private fun isGlobalFlagEnabled(context: Context, key: String): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, key, 0) == 1
    }.getOrDefault(false)

    /** True while the device is powered over USB, i.e. by a cable to a dev machine. */
    private fun isUsbPowered(context: Context): Boolean = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_USB
    }.getOrDefault(false)
}
