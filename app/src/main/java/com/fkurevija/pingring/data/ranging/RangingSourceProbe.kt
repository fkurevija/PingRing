package com.fkurevija.pingring.data.ranging
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.core.content.ContextCompat

object RangingSourceProbe {
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
    fun probeWifiRtt(context: Context): RangingSourcePolicy.WifiRttCapability {
        val rttAvailable = runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
            val aware = context.applicationContext.getSystemService(WifiAwareManager::class.java)
            manager?.isAvailable == true && aware?.isAvailable == true
        }.getOrDefault(defaultValue = false)

        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        val locationServicesEnabled = runCatching {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager?.isLocationEnabled == true
        }.getOrDefault(defaultValue = false)

        return RangingSourcePolicy.WifiRttCapability(
            rttAvailable = rttAvailable,
            locationPermissionGranted = permissionGranted,
            locationServicesEnabled = locationServicesEnabled,
            apiLevel = Build.VERSION.SDK_INT,
        )
    }
}
