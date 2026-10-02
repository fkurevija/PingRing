package com.fkurevija.pingring.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.MacAddress
import android.net.wifi.aware.*
import android.net.wifi.rtt.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

internal class WifiAwareRangingManager(
    private val context: Context,
    private val focusedPeer: () -> String?,
    private val hasFreshUwb: (String) -> Boolean,
    private val onAddress: (String) -> Unit,
    private val onDistance: (String, Float, Float) -> Unit,
) {
    private data class PeerAddress(val mac: MacAddress, val receivedAtMillis: Long)
    private val peers = ConcurrentHashMap<String, PeerAddress>()
    private val handler = Handler(Looper.getMainLooper())
    private val aware = context.getSystemService(WifiAwareManager::class.java)
    private val rtt = context.getSystemService(WifiRttManager::class.java)
    private var eventId: String? = null
    private var session: WifiAwareSession? = null
    private var publisher: PublishDiscoverySession? = null
    private var attaching = false
    private var generation = 0
    private var localMac: String? = null
    private var lastAnnouncement = 0L
    private var requestStartedAt = 0L
    private var requestId = 0
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            resetSession()
        }
    }

    fun start(ringId: String) {
        handler.post {
            if (eventId == ringId) return@post
            stopInternal()
            eventId = ringId
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED).apply {
                    addAction(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            handler.post(tick)
        }
    }

    fun stop() { handler.post { stopInternal() } }

    fun registerPeer(peerId: String, mac: String) {
        val address = runCatching { MacAddress.fromString(mac) }.getOrNull() ?: return
        if (address.addressType != MacAddress.TYPE_UNICAST || address.toString() == "00:00:00:00:00:00") return
        peers[peerId] = PeerAddress(address, SystemClock.elapsedRealtime())
    }

    private fun available(): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        ) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { aware?.isAvailable == true && rtt?.isAvailable == true }.getOrDefault(false)
    }

    private fun attach() {
        if (attaching || session != null) return
        attaching = true
        val expectedGeneration = generation
        runCatching {
            aware?.attach(object : AttachCallback() {
                override fun onAttached(attached: WifiAwareSession) {
                    if (eventId == null || expectedGeneration != generation) { attached.close(); return }
                    attaching = false
                    session = attached
                    val config = PublishConfig.Builder()
                        .setServiceName("pingring-rtt-${eventId}")
                        .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                        .setRangingEnabled(true)
                        .build()
                    runCatching {
                        attached.publish(config, object : DiscoverySessionCallback() {
                            override fun onPublishStarted(published: PublishDiscoverySession) {
                                if (expectedGeneration != generation) { published.close(); return }
                                publisher = published
                            }
                            override fun onSessionConfigFailed() {
                                if (expectedGeneration == generation) resetSession()
                            }
                            override fun onSessionTerminated() {
                                if (expectedGeneration == generation) resetSession()
                            }
                        }, handler)
                    }.onFailure { resetSession() }
                }
                override fun onAttachFailed() { if (expectedGeneration == generation) resetSession() }
            }, object : IdentityChangedListener() {
                override fun onIdentityChanged(mac: ByteArray) {
                    if (expectedGeneration == generation) {
                        localMac = MacAddress.fromBytes(mac).toString()
                        lastAnnouncement = 0L
                    }
                }
            }, handler)
        }.onFailure { resetSession() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (eventId == null) return
            if (!available()) resetSession() else {
                attach()
                val nowMillis = SystemClock.elapsedRealtime()
                if (publisher != null && nowMillis - lastAnnouncement >= 10_000L) {
                    localMac?.let(onAddress)
                    lastAnnouncement = nowMillis
                }
                val target = focusedPeer()
                val peer = target?.let(peers::get)
                if (publisher != null && target != null && peer != null && !hasFreshUwb(target) &&
                    nowMillis - peer.receivedAtMillis in 0..20_000L &&
                    (requestStartedAt == 0L || nowMillis - requestStartedAt > 6_000L)
                ) request(target, peer.mac, nowMillis)
            }
            handler.postDelayed(this, 2_000L)
        }
    }

    private fun request(peerId: String, mac: MacAddress, nowMillis: Long) {
        requestStartedAt = nowMillis
        val expectedRequest = ++requestId
        runCatching {
            val request = RangingRequest.Builder().addWifiAwarePeer(mac).build()
            rtt?.startRanging(request, ContextCompat.getMainExecutor(context), object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    if (expectedRequest == requestId) requestStartedAt = 0L
                }
                override fun onRangingResults(results: List<RangingResult>) {
                    if (expectedRequest != requestId || eventId == null || focusedPeer() != peerId) return
                    requestStartedAt = 0L
                    val result = results.singleOrNull { it.macAddress == mac && it.status == RangingResult.STATUS_SUCCESS }
                        ?: return
                    val age = SystemClock.elapsedRealtime() - result.rangingTimestampMillis
                    if (age !in 0..5_000L) return
                    val distance = result.distanceMm / 1_000f
                    val deviation = result.distanceStdDevMm / 1_000f
                    if (WifiRttCorrelation.isUsable(WifiRttCorrelation.Reading(distance, deviation))) {
                        onDistance(peerId, distance, deviation)
                    }
                }
            })
        }.onFailure { requestStartedAt = 0L }
    }

    private fun resetSession() {
        generation++
        requestId++
        requestStartedAt = 0L
        attaching = false
        publisher?.close()
        publisher = null
        session?.close()
        session = null
        localMac = null
    }

    private fun stopInternal() {
        eventId = null
        handler.removeCallbacks(tick)
        resetSession()
        peers.clear()
        if (receiverRegistered) context.unregisterReceiver(receiver)
        receiverRegistered = false
    }
}