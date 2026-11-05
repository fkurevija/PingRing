package com.combustiblelemonlabs.pingring.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.combustiblelemonlabs.pingring.data.BleIdentityAdvertisement
import com.combustiblelemonlabs.pingring.data.ranging.BleRangePolicy
import com.combustiblelemonlabs.pingring.data.ranging.BleRangeProbe
import com.combustiblelemonlabs.pingring.data.ConnectionMethodPolicy
import com.combustiblelemonlabs.pingring.data.RadioPowerPolicy
import com.combustiblelemonlabs.pingring.data.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository
import com.combustiblelemonlabs.pingring.data.session.Event
import com.combustiblelemonlabs.pingring.data.session.InviteSecret
import com.combustiblelemonlabs.pingring.data.session.SessionState

/** Owns BLE advertisement encoding, callbacks, refresh, and stop/restart cleanup. */
@SuppressLint("MissingPermission")
internal class BleAdvertisingCoordinator(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val identityKeyPair: () -> KeyPair?,
    private val peerCount: () -> Int,
    private val isUwbSupported: () -> Boolean,
    private val onIdentityAdvertisingUnavailable: () -> Unit,
) {
    private val tag = "DiscoveryRepository"
    private val serviceUuid = UUID.fromString("0000fa1b-0000-1000-8000-00805f9b34fb")
    private val bluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val nextAdvertisementSequence = AtomicLong()
    private val controlScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertisingActive = false
    private var signedBleAdvertiser: BluetoothLeAdvertiser? = null
    private var signedAdvertisingSet: AdvertisingSet? = null
    private var signedAdvertisingCallback: AdvertisingSetCallback? = null
    private var signedAdvertisementRefreshJob: Job? = null

    @Synchronized
    fun resetAdvertisementSequence() {
        nextAdvertisementSequence.set(0L)
    }

    @Synchronized
    fun startSignedAdvertising() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        if (!BleRangeProbe.supportsSignedBleIdentityAdvertising(context)) {
            onIdentityAdvertisingUnavailable()
            Log.w(tag, "BLE identity proximity unavailable: extended advertising is unsupported")
            return
        }
        val event = sessionManager.sessionState.value.currentEvent ?: return
        val keyPair = identityKeyPair() ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val peerId = sessionManager.peerId.value
        val isHost = sessionManager.sessionState.value.isHost
        // Only the host bit survives here. The advert identifies a member to peers
        // already in the ring; it does not advertise the ring for strangers to join.
        val flags = if (isHost) BleIdentityAdvertisement.FLAG_HOST else 0
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        fun advertisingData(sequence: Long): AdvertiseData? = runCatching {
            val payload = BleIdentityAdvertisement.encode(
                flags = flags,
                peerId = peerId,
                eventCode = event.id,
                joinedAtMillis = sessionManager.sessionState.value.joinedAtMillis ?: 0L,
                sequence = sequence,
                privateKey = keyPair.private,
            )
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(serviceUuid))
                .addServiceData(ParcelUuid(serviceUuid), payload)
                .build()
        }.onFailure { Log.e(tag, "Could not build signed BLE identity advertisement", it) }.getOrNull()

        val initialData = advertisingData(nextAdvertisementSequence.getAndIncrement()) ?: return
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                synchronized(this@BleAdvertisingCoordinator) {
                    if (signedAdvertisingCallback !== this ||
                        status != AdvertisingSetCallback.ADVERTISE_SUCCESS || set == null
                    ) {
                        advertiser.stopAdvertisingSet(this)
                        Log.e(tag, "Signed extended BLE advertising failed to start: status=$status")
                        return
                    }
                    signedAdvertisingSet = set
                    signedAdvertisementRefreshJob?.cancel()
                    signedAdvertisementRefreshJob = controlScope.launch {
                        while (isActive && signedAdvertisingSet === set) {
                            delay(1_000L)
                            val data = advertisingData(nextAdvertisementSequence.getAndIncrement()) ?: break
                            runCatching { set.setAdvertisingData(data) }
                                .onFailure { Log.w(tag, "Could not refresh signed BLE advertisement", it) }
                        }
                    }
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                synchronized(this@BleAdvertisingCoordinator) {
                    if (signedAdvertisingCallback === this) {
                        signedAdvertisementRefreshJob?.cancel()
                        signedAdvertisementRefreshJob = null
                        signedAdvertisingSet = null
                        signedAdvertisingCallback = null
                        signedBleAdvertiser = null
                    }
                }
            }
        }
        signedBleAdvertiser = advertiser
        signedAdvertisingCallback = callback
        try {
            advertiser.startAdvertisingSet(parameters, initialData, null, null, null, callback)
        } catch (error: Exception) {
            if (signedAdvertisingCallback === callback) {
                signedAdvertisingCallback = null
                signedBleAdvertiser = null
            }
            Log.e(tag, "Exception starting signed extended BLE advertising", error)
        }
    }

    @Synchronized
    fun startAdvertising() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val currentPeerId = sessionManager.peerId.value
        val currentEvent = sessionManager.sessionState.value.currentEvent ?: return
        if (currentEvent.inviteSecret == null) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val peerIdBytes = currentPeerId.toByteArray(StandardCharsets.UTF_8).copyOf(8)
        // BLE flags are untrusted discovery hints, not an admission-control channel.
        val servicePayload = buildServicePayload(flags = 0, peerIdBytes = peerIdBytes)
        val settings = BleRangePolicy.advertiseSettings()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addServiceData(ParcelUuid(serviceUuid), servicePayload)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                synchronized(this@BleAdvertisingCoordinator) {
                    if (advertiseCallback === this) {
                        advertisingActive = true
                        Log.d(tag, "BLE Advertising started successfully")
                    } else {
                        advertiser.stopAdvertising(this)
                    }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                synchronized(this@BleAdvertisingCoordinator) {
                    if (advertiseCallback === this) {
                        advertiseCallback = null
                        bleAdvertiser = null
                        advertisingActive = false
                    }
                }
                Log.e(tag, "BLE Advertising failed to start: $errorCode")
            }
        }
        bleAdvertiser = advertiser
        advertiseCallback = callback
        advertisingActive = false
        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (error: Exception) {
            if (advertiseCallback === callback) {
                advertiseCallback = null
                bleAdvertiser = null
                advertisingActive = false
            }
            Log.e(tag, "Exception starting BLE advertising", error)
        }
    }

    @Synchronized
    fun stop() {
        stopSignedAdvertising()
        val advertiser = bleAdvertiser
        val callback = advertiseCallback
        bleAdvertiser = null
        advertiseCallback = null
        val shouldStop = advertisingActive
        advertisingActive = false
        if (advertiser != null && callback != null && shouldStop) {
            try {
                advertiser.stopAdvertising(callback)
            } catch (error: Exception) {
                Log.e(tag, "Exception stopping BLE advertising", error)
            }
        }
    }

    @Synchronized
    fun stopSignedAdvertising() {
        val advertiser = signedBleAdvertiser
        val callback = signedAdvertisingCallback
        signedBleAdvertiser = null
        signedAdvertisingSet = null
        signedAdvertisingCallback = null
        signedAdvertisementRefreshJob?.cancel()
        signedAdvertisementRefreshJob = null
        if (advertiser != null && callback != null) {
            runCatching { advertiser.stopAdvertisingSet(callback) }
                .onFailure { Log.e(tag, "Exception stopping signed extended BLE advertising", it) }
        }
    }

    /**
     * Recomputes the plan because peer load, ranging, and charging can change
     * while discovery is active. Host-run hotspots are not supported, so that
     * input remains false.
     */
    fun currentRadioPlan(supportsExtended: Boolean): RadioPowerPolicy.Plan {
        val state = sessionManager.sessionState.value
        val event = state.currentEvent
        val selection = ConnectionMethodPolicy.chooseMethod(
            capabilities = ConnectionMethodPolicy.Capabilities(
                apiLevel = Build.VERSION.SDK_INT,
                uwbHardwareSupported = isUwbSupported(),
                uwbPermissionGranted = event?.useUwb ?: false,
            ),
            preferences = ConnectionMethodPolicy.Preferences(
                uwbRangingEnabled = event?.useUwb ?: false,
            ),
        )
        return RadioPowerPolicy.plan(
            load = RadioPowerPolicy.Load(
                isHost = state.isHost,
                memberCount = peerCount(),
                uwbActive = selection.usesUwb,
                hotspotActive = false,
                onBattery = isOnBattery(),
            ),
            supportsExtendedAdvertising = supportsExtended,
        )
    }

    private fun buildServicePayload(flags: Int, peerIdBytes: ByteArray): ByteArray {
        val event = sessionManager.sessionState.value.currentEvent ?: return ByteArray(0)
        val secret = event.inviteSecret ?: return ByteArray(0)
        return BleAdvertisementPayload.encode(
            flags = flags,
            peerIdBytes = peerIdBytes,
            eventCode = event.id,
            // Carry the session's arrival time so a host can determine join
            // order from the payload rather than packet reception timing.
            joinedAtMillis = sessionManager.sessionState.value.joinedAtMillis ?: 0L,
            inviteSecret = secret,
        )
    }

    /**
     * `BATTERY_PLUGGED` being none means either unplugged or on battery. Unknown
     * state is treated as battery-powered because these settings trade radio use
     * against the runtime of a phone that may not be charged for a while.
     */
    private fun isOnBattery(): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged == 0
    }
}
