package com.fkurevija.pingring.data.ranging
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.fkurevija.pingring.data.radar.GnssFixDto
import com.fkurevija.pingring.data.radar.GnssPolicy

internal class GnssRangingManager(
    private val context: Context,
    private val onFix: (GnssFixDto) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(LocationManager::class.java)
    @Volatile private var latestLocation: Location? = null
    @Volatile private var enabled = false
    private var listening = false
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = Location(location)
        }
        override fun onProviderDisabled(provider: String) { latestLocation = null }
        override fun onProviderEnabled(provider: String) = Unit
        @Deprecated("Legacy Android callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!enabled) return
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val available = permission && runCatching {
                manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            }.getOrDefault(false)
            if (!available) {
                runCatching { manager?.removeUpdates(listener) }
                listening = false
                latestLocation = null
            } else if (!listening) {
                listening = runCatching {
                    manager?.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        GnssPolicy.UPDATE_INTERVAL_MILLIS,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                    true
                }.getOrDefault(false)
            }
            currentFix()?.let(onFix)
            handler.postDelayed(this, GnssPolicy.UPDATE_INTERVAL_MILLIS)
        }
    }

    fun setEnabled(value: Boolean) {
        handler.post {
            if (enabled == value) return@post
            enabled = value
            handler.removeCallbacks(tick)
            if (value) handler.post(tick) else {
                runCatching { manager?.removeUpdates(listener) }
                listening = false
                latestLocation = null
            }
        }
    }

    /**
     * Fine-grained fix for calculations that stay on this device. In contrast
     * to [currentFix], this is never quantized or transmitted.
     */
    fun currentLocalFix(): GnssFixDto? {
        if (!enabled || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val location = latestLocation ?: return null
        if (!location.hasAccuracy()) return null
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        return GnssFixDto(location.latitude, location.longitude, location.accuracy, ageMillis)
    }

    fun currentFix(): GnssFixDto? =
        currentLocalFix()?.let(GnssPolicy::quantize)
}