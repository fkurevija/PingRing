package com.fkurevija.pingring.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.fkurevija.pingring.DeviceEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import com.fkurevija.pingring.data.ranging.MockRangingDto
import com.fkurevija.pingring.data.ranging.RssiDistanceEstimator

/**
 * Supplies the LAN debug transport with local Wi-Fi RSSI proxies for admitted
 * peers, while keeping scripted ranging reports authoritative.
 */
internal class LanDebugWifiRssiProxy(private val context: Context?) {

    private val scriptedPeers = ConcurrentHashMap.newKeySet<String>()
    private val baselines = ConcurrentHashMap<String, Int>()
    private var rssiUnavailable = false

    fun startSampling(
        scope: CoroutineScope,
        shouldContinue: () -> Boolean,
        shouldSample: () -> Boolean,
        onSample: (Int?) -> Unit,
    ): Job? {
        if (context == null || DeviceEnvironment.isEmulator()) return null
        return scope.launch {
            while (isActive && shouldContinue()) {
                delay(SAMPLE_INTERVAL_MILLIS)
                if (shouldSample()) onSample(measureWifiRssi())
            }
        }
    }

    fun markScriptedPeer(peerId: String) {
        scriptedPeers.add(peerId)
    }

    fun removePeer(peerId: String) {
        scriptedPeers.remove(peerId)
        baselines.remove(peerId)
    }

    fun reset() {
        scriptedPeers.clear()
        baselines.clear()
        rssiUnavailable = false
    }

    fun reportsFor(
        rssi: Int?,
        admittedPeers: Set<String>,
        localPeerId: String,
    ): List<MockRangingDto> {
        if (rssi == null || rssi !in -100..-20) {
            if (!rssiUnavailable) {
                Log.w(TAG, "Wi-Fi RSSI unavailable; local peer mock sampling paused (no random fallback)")
                rssiUnavailable = true
            }
            return emptyList()
        }
        if (rssiUnavailable) {
            Log.i(TAG, "Wi-Fi RSSI available again; resuming local peer mock sampling")
            rssiUnavailable = false
        }

        return admittedPeers
            .filterNot { it == localPeerId || it in scriptedPeers }
            .map { peerId ->
                val baseline = baselines.getOrPut(peerId) {
                    Log.i(TAG, "Calibrating Wi-Fi proxy for $peerId at 1 m with $rssi dBm")
                    rssi
                }
                // Normalize Wi-Fi's stronger transmit power to the BLE curve's 1 m reference.
                val mockRssi = RssiDistanceEstimator.RSSI_AT_ONE_METER.toInt() + rssi - baseline
                MockRangingDto(peerId = peerId, rssi = mockRssi)
            }
    }

    @Suppress("DEPRECATION")
    private fun measureWifiRssi(): Int? {
        val ctx = context?.applicationContext ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val connectivity = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = connectivity?.activeNetwork ?: return null
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
                (capabilities.transportInfo as? WifiInfo)?.rssi
            } else {
                val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifi?.connectionInfo?.rssi
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi-Fi RSSI access denied; check Wi-Fi and location permissions", e)
            null
        }
    }

    companion object {
        const val SAMPLE_INTERVAL_MILLIS = 250L
        private const val TAG = "LanDebugTransport"
    }
}
