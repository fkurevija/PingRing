package com.fkurevija.pingring.data

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log

object BleRangeProbe {

    private const val TAG = "BleRangeProbe"

    /**
     * True when this device's radio and platform can act on long-range hints.
     */
    fun isCodedPhyUsable(context: Context): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { adapter.isLeCodedPhySupported }.getOrDefault(false)
        } else {
            false
        }
    }

    /**
     * True when extended (BLE 5.0) advertising is available.
     */
    fun supportsExtendedAdvertising(context: Context): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)
        } else {
            false
        }
    }

    fun supportsSignedBleIdentityAdvertising(context: Context): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled || !supportsExtendedAdvertising(context)) return false
        return runCatching {
            adapter.leMaximumAdvertisingDataLength >= BleRangePolicy.MIN_EXTENDED_AD_DATA_BYTES
        }.getOrDefault(false)
    }

    /** Human-readable summary of the active configuration, for logcat and settings. */
    fun describe(context: Context): String {
        val extended = supportsExtendedAdvertising(context)
        return "BLE range: passes=${BleRangePolicy.scanPassCount(extended)}, " +
            "extendedAdvertising=$extended, codedPhyHardware=${isCodedPhyUsable(context)} (not selectable via public API)"
    }

    internal fun logSummary(context: Context) {
        Log.i(TAG, describe(context))
    }
}
