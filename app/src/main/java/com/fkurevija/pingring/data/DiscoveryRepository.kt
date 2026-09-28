package com.fkurevija.pingring.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.util.Log
import android.util.Base64
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.uwb.*
import com.fkurevija.pingring.MainActivity
import com.fkurevija.pingring.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom
import kotlin.math.cos
import kotlin.math.sin

data class RadarPeerUpdate(
    val peerId: String,
    val name: String,
    val rssi: Int?,
    val proximity: String, // "Hot", "Cold", "Unknown"
    val distance: Float?,  // in meters
    val azimuth: Float?,   // in degrees
    val elevation: Float?, // in degrees
    val worldDirectionX: Float?,
    val worldDirectionY: Float?,
    val worldDirectionZ: Float?,
    val lastSeen: Long
)

data class ConnectivityStatus(
    val isBluetoothEnabled: Boolean,
    val isWifiEnabled: Boolean
)

@SuppressLint("MissingPermission")
class DiscoveryRepository(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val nearbyConnectionsManager: NearbyTransport,
    private val appearanceSettings: AppearanceSettingsRepository = AppearanceSettingsRepository(context)
) : SensorEventListener {

    private val TAG = "DiscoveryRepository"
    private val PINGRING_SERVICE_UUID = UUID.fromString("0000fa1b-0000-1000-8000-00805f9b34fb")
    private val hostFlag = 1
    private val trackHostFlag = 1 shl 1
    private val eventClosedFlag = 1 shl 2
    private val useUwbFlag = 1 shl 3
    private val useWifiFlag = 1 shl 4
    private val pingRingFullFlag = 1 shl 5
    private val acceptsNewMembersFlag = 1 shl 6

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // Expose connectivity status
    private val _connectivityStatus = MutableStateFlow(getConnectivityStatus())
    val connectivityStatus: StateFlow<ConnectivityStatus> = _connectivityStatus.asStateFlow()

    // Expose peer updates
    private val _peerUpdates = MutableStateFlow<Map<String, RadarPeerUpdate>>(emptyMap())
    val peerUpdates: Flow<List<RadarPeerUpdate>> = _peerUpdates.map { it.values.toList() }

    // Expose device compass heading
    private val _deviceHeading = MutableStateFlow(0f)
    val deviceHeading: StateFlow<Float> = _deviceHeading.asStateFlow()

    // Active Beacons map (peerId -> BeaconInfo)
    private val _activeBeacons = MutableStateFlow<Map<String, BeaconInfo>>(emptyMap())
    val activeBeacons: StateFlow<Map<String, BeaconInfo>> = _activeBeacons.asStateFlow()

    // Local Beacon state (null if not active)
    private val _localBeacon = MutableStateFlow<BeaconInfo?>(null)
    val localBeacon: StateFlow<BeaconInfo?> = _localBeacon.asStateFlow()

    // Local peer ID
    val localPeerId: String get() = sessionManager.peerId.value

    // Forward nudge events from the transport layer (fire-and-forget, no state needed)
    val receivedNudge = nearbyConnectionsManager.receivedNudge

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var repositoryScope: CoroutineScope? = null
    // Tracks which event/peer/role discovery is currently running for, so a
    // redundant startDiscovery() call for the exact same session (e.g. both
    // HostSetupScreen and RadarScreen call this when navigating between
    // them) doesn't tear down and re-establish transport connections it
    // doesn't need to — that churn was dropping in-flight chat messages.
    //
    // The radio settings are part of the identity on purpose: flipping the UWB
    // or Wi-Fi switch on the host screen really does have to rebuild the
    // subsystems that were started with the old values. What must never happen
    // is a restart when nothing changed, which is the common case: the service,
    // the host screen and the radar screen each call this on their own, and
    // restarting tore the transport down and dropped every live connection.
    private var activeDiscoveryEventId: String? = null
    private var activeDiscoveryPeerId: String? = null
    private var activeDiscoveryIsHost: Boolean? = null
    private var activeDiscoveryUseUwb: Boolean? = null
    private var activeDiscoveryUseWifiDirect: Boolean? = null
    private var activeDiscoveryUseHotspot: Boolean? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertisingActive = false
    private var closureAdvertiser: BluetoothLeAdvertiser? = null
    private var closureAdvertiseCallback: AdvertiseCallback? = null
    private var closureAdvertisingActive = false
    private var bleScanner: BluetoothLeScanner? = null
    private var closureAnnouncementJob: Job? = null
    private val controlScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Sensor Fusion state
    private val rotationMatrix = FloatArray(9).apply { this[0] = 1f; this[4] = 1f; this[8] = 1f }
    private val geomagneticValues = FloatArray(3)
    private val gravityValues = FloatArray(3)
    private var hasRotationVector = false

    // UWB objects
    private var uwbManager: UwbManager? = null
    private var uwbSessionJob: Job? = null
    private val activeRangingJobs = mutableMapOf<String, Job>()

    init {
        rotationMatrix[0] = 1f
        rotationMatrix[4] = 1f
        rotationMatrix[8] = 1f
        if (context.packageManager.hasSystemFeature("android.hardware.uwb")) {
            try {
                uwbManager = UwbManager.createInstance(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UWB Manager", e)
            }
        }
    }

    fun getConnectivityStatus(): ConnectivityStatus {
        val bleEnabled = bluetoothAdapter?.isEnabled ?: false
        val wifiEnabled = wifiManager?.isWifiEnabled ?: false
        return ConnectivityStatus(bleEnabled, wifiEnabled)
    }

    fun refreshConnectivityStatus() {
        _connectivityStatus.value = getConnectivityStatus()
    }

    fun isUwbSupported(): Boolean {
        return context.packageManager.hasSystemFeature("android.hardware.uwb")
    }

    fun startDiscovery() {
        val event = sessionManager.sessionState.value.currentEvent
        val localPeerId = sessionManager.peerId.value
        val isHost = sessionManager.sessionState.value.isHost

        // The host's admission switch has to be applied even when discovery is
        // already running: the host screen calls this again on every toggle, and
        // restarting the transport just to apply a switch would drop the peers
        // that are already connected.
        if (event != null) {
            nearbyConnectionsManager.acceptsNewMembers = event.acceptsNewMembers
        }

        if (event != null &&
            event.id == activeDiscoveryEventId &&
            localPeerId == activeDiscoveryPeerId &&
            isHost == activeDiscoveryIsHost &&
            event.useUwb == activeDiscoveryUseUwb &&
            event.useWifiDirect == activeDiscoveryUseWifiDirect &&
            event.useHotspot == activeDiscoveryUseHotspot &&
            repositoryScope?.isActive == true
        ) {
            // Already actively discovering for this exact session — skip the
            // teardown/restart to avoid unnecessary transport reconnects.
            Log.d(TAG, "Discovery already active for event ${event.id}, ignoring duplicate startDiscovery()")
            return
        }

        // Tear down whatever was running *before* recording the new state.
        // stopDiscovery() clears these markers, so setting them first wiped them
        // again and left the guard above permanently broken: every call became a
        // full stop/start of the transport. That is not cosmetic - stopping
        // clears connectedEndpoints and tears down the Nearby listeners, so a
        // guest's requestConnection would land on a host that was momentarily
        // not listening (ACCEPT_CONNECTION_FAILED / SOCKET_CLOSED), the peer
        // list on the radar comes from BLE so it still looked fine, and no
        // message could ever be delivered.
        stopDiscovery()
        activeDiscoveryEventId = event?.id
        activeDiscoveryPeerId = localPeerId
        activeDiscoveryIsHost = isHost
        activeDiscoveryUseUwb = event?.useUwb
        activeDiscoveryUseWifiDirect = event?.useWifiDirect
        activeDiscoveryUseHotspot = event?.useHotspot
        repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        refreshConnectivityStatus()

        // 1. Start Nearby Connections
        if (event != null) {
            if (isHost) {
                nearbyConnectionsManager.startAdvertising(
                    event.id, 
                    localPeerId, 
                    event.useWifiDirect, 
                    event.useHotspot
                )
            } else {
                nearbyConnectionsManager.startDiscovery(event.id, localPeerId)
            }
        }

        // 2. Start Periodic Peer Cleanup
        repositoryScope?.launch {
            while (isActive) {
                delay(PEER_CLEANUP_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                _peerUpdates.update { current ->
                    current.filterValues { now - it.lastSeen < PEER_EXPIRY_TIMEOUT_MILLIS }
                }
            }
        }

        // 2b. Start Chat Message Processing Queue Flow Listener Loop
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedChatMessage.collect { dto ->
                updatePeerName(dto.senderPeerId, dto.senderName)
                val chatMessage = ChatMessage(
                    id = dto.id,
                    senderPeerId = dto.senderPeerId,
                    senderName = dto.senderName,
                    messageText = dto.text,
                    timestamp = dto.timestamp
                )
                sessionManager.receiveLocalChatMessage(chatMessage)

                // If we are the Host, broker & re-broadcast the message outwards to all other connected peers
                if (sessionManager.sessionState.value.isHost) {
                    nearbyConnectionsManager.broadcastChatMessage(dto)
                }

                if (dto.senderPeerId != sessionManager.peerId.value) {
                    val appWideEnabled = appearanceSettings.chatNotificationsEnabled.first()
                    val eventEnabled = sessionManager.sessionState.value.currentEvent?.chatNotificationsEnabled ?: true
                    if (appWideEnabled && eventEnabled) {
                        postChatNotification(chatMessage)
                    }
                }
            }
        }

        // Register newly connected peers and deliver the host's current chat history.
        repositoryScope?.launch {
            nearbyConnectionsManager.connectionEstablished.collect { peerId ->
                addConnectedPeer(peerId)
                if (sessionManager.sessionState.value.isHost) {
                    val history = sessionManager.sessionState.value.chatMessages.map { message ->
                        ChatPayloadDto(
                            id = message.id,
                            senderPeerId = message.senderPeerId,
                            senderName = message.senderName,
                            text = message.messageText,
                            timestamp = message.timestamp
                        )
                    }
                    nearbyConnectionsManager.sendChatHistoryToPeer(peerId, history)
                }
            }
        }

        // A peer whose transport connection dropped is gone: drop its pin, its
        // beacon and its entry in the member list straight away, instead of
        // leaving a ghost on the radar until the staleness sweep catches up.
        repositoryScope?.launch {
            nearbyConnectionsManager.peerDisconnected.collect { peerId ->
                Log.i(TAG, "Peer $peerId disconnected; removing it from the radar")
                removePeer(peerId)
            }
        }

        // Debug-only: scripted ranges from tools/mock_radar.py, forwarded by the
        // LAN debug relay. The emulator has no UWB or BLE radio, so without
        // these the radar would have no distance to draw and the only thing
        // testable between two devices would be chat and beacons.
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedMockRanging.collect { dto ->
                updateMockRanging(dto)
            }
        }

        // The host ended the PingRing. Over real hardware that arrives as the
        // host's BLE closure advertisement (see handleScanResult); the LAN debug
        // transport reports it here instead, because the emulator has no BLE
        // radio to hear that advertisement with. Either way the result is the
        // same: leave the event, which clears the session and sends navigation
        // back to Home.
        repositoryScope?.launch {
            nearbyConnectionsManager.eventClosed.collect {
                Log.i(TAG, "Host ended the PingRing; leaving the event")
                sessionManager.endEventFromHost()
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedChatHistory.collect { history ->
                history.messages.forEach { dto ->
                    updatePeerName(dto.senderPeerId, dto.senderName)
                    sessionManager.receiveLocalChatMessage(
                        ChatMessage(
                            id = dto.id,
                            senderPeerId = dto.senderPeerId,
                            senderName = dto.senderName,
                            messageText = dto.text,
                            timestamp = dto.timestamp
                        )
                    )
                }
            }
        }

        // 2c. Host nudge re-routing: forward incoming nudges to their intended target(s)
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedNudge.collect { nudge ->
                updatePeerName(nudge.senderPeerId, nudge.senderName)
                if (sessionManager.sessionState.value.isHost) {
                    val target = nudge.targetPeerId
                    if (target == null) {
                        nearbyConnectionsManager.broadcastNudge(nudge)
                    } else {
                        nearbyConnectionsManager.sendNudgeToPeer(target, nudge)
                    }
                }
            }
        }

        // 2d. Start Beacon Processing Queue Flow Listener Loop
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedBeacon.collect { dto ->
                val localPeerId = sessionManager.peerId.value
                // If message is from ourselves, ignore
                if (dto.senderPeerId == localPeerId) return@collect

                val info = BeaconInfo(
                    id = dto.id,
                    senderPeerId = dto.senderPeerId,
                    senderName = dto.senderName,
                    title = dto.title,
                    colorArgb = dto.colorArgb,
                    vibrationPattern = dto.vibrationPattern,
                    isActive = dto.isActive,
                    timestamp = dto.timestamp
                )

                if (dto.isActive) {
                    updatePeerName(dto.senderPeerId, dto.senderName)
                    _activeBeacons.update { it + (dto.senderPeerId to info) }
                    // Trigger haptic vibration for this pattern on recipient device
                    val patternEnum = BeaconVibrationPattern.fromName(dto.vibrationPattern)
                    playVibrationPattern(patternEnum.pattern)
                } else {
                    _activeBeacons.update { it - dto.senderPeerId }
                }

                // If host, broker & re-broadcast outward to all other connected peers
                if (sessionManager.sessionState.value.isHost) {
                    nearbyConnectionsManager.broadcastBeacon(dto)
                }
            }
        }

        // 3. Start BLE Advertising & Scanning
        startBleAdvertising()
        startBleScanning()

        // 3. Start Sensor Fusion
        registerSensors()

        // 4. Start foreground-only UWB ranging
        startUwbRangingSession()
    }

    fun stopDiscovery() {
        activeDiscoveryEventId = null
        activeDiscoveryPeerId = null
        activeDiscoveryIsHost = null
        activeDiscoveryUseUwb = null
        activeDiscoveryUseWifiDirect = null
        activeDiscoveryUseHotspot = null
        stopVibration()
        _activeBeacons.value = emptyMap()
        _localBeacon.value = null
        nearbyConnectionsManager.stopAll()
        stopBleAdvertising()
        stopBleScanning()
        unregisterSensors()
        uwbSessionJob?.cancel()
        uwbSessionJob = null
        activeRangingJobs.values.forEach { it.cancel() }
        activeRangingJobs.clear()
        repositoryScope?.cancel()
        repositoryScope = null
        _peerUpdates.value = emptyMap()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed: $errorCode")
        }
    }

    fun broadcastOutgoingChatMessage(text: String, senderName: String) {
        val sanitizedText = text.take(MAX_CHAT_LENGTH).trim()
        val sanitizedName = senderName.take(MAX_USERNAME_LENGTH).trim()
        if (sanitizedText.isBlank() || sanitizedName.isBlank()) return
        val localPeerId = sessionManager.peerId.value
        val dto = ChatPayloadDto(
            id = UUID.randomUUID().toString(),
            senderPeerId = localPeerId,
            senderName = sanitizedName,
            text = sanitizedText,
            timestamp = System.currentTimeMillis()
        )
        
        // Append locally immediately
        sessionManager.receiveLocalChatMessage(
            ChatMessage(
                id = dto.id,
                senderPeerId = dto.senderPeerId,
                senderName = dto.senderName,
                messageText = dto.text,
                timestamp = dto.timestamp
            )
        )

        // Broadcast to P2P mesh endpoints
        nearbyConnectionsManager.broadcastChatMessage(dto)
    }

    fun sendNudge(targetPeerId: String?, senderName: String) {
        val localPeerId = sessionManager.peerId.value
        val nudge = NudgePayloadDto(
            id = UUID.randomUUID().toString(),
            senderPeerId = localPeerId,
            senderName = senderName,
            targetPeerId = targetPeerId,
            timestamp = System.currentTimeMillis()
        )
        if (targetPeerId == null) {
            nearbyConnectionsManager.broadcastNudge(nudge)
        } else {
            // Send directly if we have the peer connected, otherwise send to host to forward
            val sentDirect = runCatching { nearbyConnectionsManager.sendNudgeToPeer(targetPeerId, nudge) }.isSuccess
            if (!sentDirect || !sessionManager.sessionState.value.isHost) {
                // Guest: send to host and let the host forward it
                val hostPeerId = sessionManager.sessionState.value.currentEvent?.hostPeerId
                if (hostPeerId != null) nearbyConnectionsManager.sendNudgeToPeer(hostPeerId, nudge)
            }
        }
    }

    fun playVibrationPattern(pattern: LongArray) {
        if (pattern.isEmpty() || (pattern.size == 1 && pattern[0] == 0L)) return
        try {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            vibrator?.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "Vibration playback failed", e)
        }
    }

    fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel vibration", e)
        }
    }

    fun activateBeacon(
        title: String,
        colorArgb: Long,
        vibrationPattern: String,
        senderName: String
    ) {
        val localPeerId = sessionManager.peerId.value
        val beaconDto = BeaconPayloadDto(
            id = UUID.randomUUID().toString(),
            senderPeerId = localPeerId,
            senderName = senderName,
            title = title,
            colorArgb = colorArgb,
            vibrationPattern = vibrationPattern,
            isActive = true,
            timestamp = System.currentTimeMillis()
        )
        val info = BeaconInfo(
            id = beaconDto.id,
            senderPeerId = beaconDto.senderPeerId,
            senderName = beaconDto.senderName,
            title = beaconDto.title,
            colorArgb = beaconDto.colorArgb,
            vibrationPattern = beaconDto.vibrationPattern,
            isActive = true,
            timestamp = beaconDto.timestamp
        )
        _localBeacon.value = info
        _activeBeacons.update { it + (localPeerId to info) }

        nearbyConnectionsManager.broadcastBeacon(beaconDto)
    }

    fun cancelLocalBeacon() {
        val current = _localBeacon.value ?: return
        val beaconDto = BeaconPayloadDto(
            id = current.id,
            senderPeerId = current.senderPeerId,
            senderName = current.senderName,
            title = current.title,
            colorArgb = current.colorArgb,
            vibrationPattern = current.vibrationPattern,
            isActive = false,
            timestamp = System.currentTimeMillis()
        )
        _localBeacon.value = null
        _activeBeacons.update { it - current.senderPeerId }
        stopVibration()

        nearbyConnectionsManager.broadcastBeacon(beaconDto)
    }

    @Synchronized
    private fun startBleAdvertising() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val currentPeerId = sessionManager.peerId.value
        val currentEvent = sessionManager.sessionState.value.currentEvent ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val peerIdBytes = currentPeerId.toByteArray(StandardCharsets.UTF_8).copyOf(8)
        val currentMemberCount = sessionManager.sessionState.value.friends.size + 1
        val isFull = currentMemberCount >= MAX_PARTICIPANTS
        val flags = (if (sessionManager.sessionState.value.isHost) hostFlag else 0) or
            (if (currentEvent.trackHost) trackHostFlag else 0) or
            (if (currentEvent.useUwb) useUwbFlag else 0) or
            (if (currentEvent.useWifiDirect) useWifiFlag else 0) or
            (if (isFull) pingRingFullFlag else 0) or
            (if (currentEvent.acceptsNewMembers) acceptsNewMembersFlag else 0)
        val servicePayload = buildServicePayload(currentEvent.id.hashCode(), flags, peerIdBytes)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(PINGRING_SERVICE_UUID))
            .addServiceData(ParcelUuid(PINGRING_SERVICE_UUID), servicePayload)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                synchronized(this@DiscoveryRepository) {
                    if (advertiseCallback === this) {
                        advertisingActive = true
                        Log.d(TAG, "BLE Advertising started successfully")
                    } else {
                        advertiser.stopAdvertising(this)
                    }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                synchronized(this@DiscoveryRepository) {
                    if (advertiseCallback === this) {
                        advertiseCallback = null
                        bleAdvertiser = null
                        advertisingActive = false
                    }
                }
                Log.e(TAG, "BLE Advertising failed to start: $errorCode")
            }
        }
        bleAdvertiser = advertiser
        advertiseCallback = callback
        advertisingActive = false
        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (e: Exception) {
            if (advertiseCallback === callback) {
                advertiseCallback = null
                bleAdvertiser = null
                advertisingActive = false
            }
            Log.e(TAG, "Exception starting BLE advertising", e)
        }
    }

    @Synchronized
    private fun stopBleAdvertising() {
        val advertiser = bleAdvertiser
        val callback = advertiseCallback
        bleAdvertiser = null
        advertiseCallback = null
        val shouldStop = advertisingActive
        advertisingActive = false
        if (advertiser != null && callback != null && shouldStop) {
            try {
                advertiser.stopAdvertising(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping BLE advertising", e)
            }
        }
    }

    /**
     * Sends a terminal host packet before the host clears its local session. Nearby members
     * receiving this packet clear their session and navigation returns them to Home.
     *
     * The LAN debug transport has no BLE radio to advertise with (the emulator
     * has none at all), so it is told through [NearbyTransport.announceEventClosed]
     * instead, which makes the relay close the event for its connected members.
     */
    fun announceEventClosure(event: Event) {
        nearbyConnectionsManager.announceEventClosed()
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(PINGRING_SERVICE_UUID))
            .addServiceData(
                ParcelUuid(PINGRING_SERVICE_UUID),
                buildServicePayload(
                    event.id.hashCode(),
                    hostFlag or eventClosedFlag or (if (event.useUwb) useUwbFlag else 0) or (if (event.useWifiDirect) useWifiFlag else 0),
                    sessionManager.peerId.value.toByteArray(StandardCharsets.UTF_8).copyOf(8)
                )
            )
            .build()
        try {
            closureAnnouncementJob?.cancel()
            stopClosureAdvertising()
            stopBleAdvertising()
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    synchronized(this@DiscoveryRepository) {
                        if (closureAdvertiseCallback === this) {
                            closureAdvertisingActive = true
                        } else {
                            advertiser.stopAdvertising(this)
                        }
                    }
                }

                override fun onStartFailure(errorCode: Int) {
                    synchronized(this@DiscoveryRepository) {
                        if (closureAdvertiseCallback === this) {
                            closureAdvertiseCallback = null
                            closureAdvertiser = null
                            closureAdvertisingActive = false
                        }
                    }
                    Log.e(TAG, "PingRing closure announcement failed to start: $errorCode")
                }
            }
            closureAdvertiser = advertiser
            closureAdvertiseCallback = callback
            closureAdvertisingActive = false
            advertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build(),
                data,
                callback
            )
            closureAnnouncementJob = controlScope.launch {
                delay(CLOSURE_ANNOUNCEMENT_DURATION_MILLIS)
                stopClosureAdvertising()
            }
        } catch (exception: Exception) {
            stopClosureAdvertising()
            Log.e(TAG, "Unable to announce PingRing closure", exception)
        }
    }

    @Synchronized
    private fun stopClosureAdvertising() {
        val advertiser = closureAdvertiser
        val callback = closureAdvertiseCallback
        closureAdvertiser = null
        closureAdvertiseCallback = null
        val shouldStop = closureAdvertisingActive
        closureAdvertisingActive = false
        if (advertiser != null && callback != null && shouldStop) {
            try {
                advertiser.stopAdvertising(callback)
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to stop PingRing closure announcement", exception)
            }
        }
    }

    private fun buildServicePayload(eventHash: Int, flags: Int, peerIdBytes: ByteArray): ByteArray =
        ByteBuffer.allocate(13)
            .putInt(eventHash)
            .put(flags.toByte())
            .put(peerIdBytes.copyOf(8))
            .array()

    private companion object {
        const val CLOSURE_ANNOUNCEMENT_DURATION_MILLIS = 5_000L
        const val PEER_CLEANUP_INTERVAL_MILLIS = 5_000L
        const val PEER_EXPIRY_TIMEOUT_MILLIS = 15_000L
        const val CHAT_CHANNEL_ID = "pingring_chat_channel"
        const val CHAT_NOTIFICATION_GROUP = "com.fkurevija.pingring.CHAT_NOTIFICATION"
    }

    private fun startBleScanning() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        bleScanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PINGRING_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scanning", e)
        }
    }

    private fun stopBleScanning() {
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping BLE scanning", e)
        }
        bleScanner = null
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(ParcelUuid(PINGRING_SERVICE_UUID)) ?: return
        if (serviceData.size < 13) return

        val payload = ByteBuffer.wrap(serviceData)
        val eventHash = payload.int
        val flags = payload.get().toInt()
        val isHostPeer = flags and hostFlag != 0
        val hostTracksMembers = flags and trackHostFlag != 0
        val hostUsesUwb = flags and useUwbFlag != 0
        val hostUsesWifi = flags and useWifiFlag != 0
        val peerId = String(ByteArray(payload.remaining()).also(payload::get), StandardCharsets.UTF_8).trim()
        var currentEvent = sessionManager.sessionState.value.currentEvent ?: return
        if (eventHash != currentEvent.id.hashCode()) return
        if (isHostPeer && flags and eventClosedFlag != 0) {
            sessionManager.endEventFromHost()
            return
        }
        if (isHostPeer && !sessionManager.sessionState.value.isHost) {
            currentEvent = currentEvent.copy(
                hostPeerId = peerId,
                trackHost = hostTracksMembers,
                useUwb = hostUsesUwb,
                useWifiDirect = hostUsesWifi
            )
            sessionManager.updateEvent(currentEvent)
        }
        if (currentEvent.trackHost && !sessionManager.sessionState.value.isHost && !isHostPeer) return
        if (peerId.isEmpty() || peerId == sessionManager.peerId.value) return

        val rssi = result.rssi
        val proximity = if (rssi >= -70) "Hot" else "Cold"

        // Find or create existing update
        val existing = _peerUpdates.value[peerId]
        val peerName = existing?.name ?: "Peer $peerId"
        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = peerName,
            rssi = rssi,
            proximity = proximity,
            distance = existing?.distance ?: (2f + (rssi + 100) / 10f), // Initial distance estimate from RSSI
            azimuth = existing?.azimuth,
            elevation = existing?.elevation,
            worldDirectionX = existing?.worldDirectionX,
            worldDirectionY = existing?.worldDirectionY,
            worldDirectionZ = existing?.worldDirectionZ,
            lastSeen = System.currentTimeMillis()
        )

        _peerUpdates.update { current ->
            current + (peerId to updated)
        }

        syncFriend(updated)
    }

    /**
     * Mirrors the latest radar reading of [peer] into the SessionManager's
     * friend list: added on first sight, then only its last-seen time and
     * distance are refreshed.
     */
    private fun syncFriend(peer: RadarPeerUpdate) {
        val friends = sessionManager.sessionState.value.friends
        val distance = peer.distance?.toDouble()
        val friendExists = friends.any { it.peerId == peer.peerId }
        if (!friendExists) {
            val updatedFriends = friends + Friend(
                peerId = peer.peerId,
                name = peer.name,
                lastSeen = peer.lastSeen,
                distance = distance
            )
            sessionManager.updateFriends(updatedFriends)
        } else {
            val updatedFriends = friends.map {
                if (it.peerId == peer.peerId) it.copy(lastSeen = peer.lastSeen, distance = distance) else it
            }
            sessionManager.updateFriends(updatedFriends)
        }
    }

    /**
     * Forgets everything about a peer that is no longer connected: its radar
     * pin, any beacon it was broadcasting, and its place in the member list.
     */
    private fun removePeer(peerId: String) {
        _peerUpdates.update { current -> current - peerId }
        _activeBeacons.update { current -> current - peerId }
        val friends = sessionManager.sessionState.value.friends
        if (friends.any { it.peerId == peerId }) {
            sessionManager.updateFriends(friends.filterNot { it.peerId == peerId })
        }
    }

    private fun addConnectedPeer(peerId: String) {
        if (peerId.isBlank() || peerId == sessionManager.peerId.value) return
        val existing = _peerUpdates.value[peerId]
        val peerName = existing?.name ?: "Peer $peerId"
        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = peerName,
            rssi = existing?.rssi ?: -60,
            proximity = existing?.proximity ?: "Hot",
            distance = existing?.distance,
            azimuth = existing?.azimuth,
            elevation = existing?.elevation,
            worldDirectionX = existing?.worldDirectionX,
            worldDirectionY = existing?.worldDirectionY,
            worldDirectionZ = existing?.worldDirectionZ,
            lastSeen = System.currentTimeMillis()
        )
        _peerUpdates.update { current -> current + (peerId to updated) }
        syncFriend(updated)
    }

    private fun updatePeerName(peerId: String, name: String) {
        if (name.isBlank() || peerId == sessionManager.peerId.value) return
        val existing = _peerUpdates.value[peerId]
        val updated = if (existing != null) {
            existing.copy(name = name)
        } else {
            RadarPeerUpdate(
                peerId = peerId,
                name = name,
                rssi = -60,
                proximity = "Hot",
                distance = null,
                azimuth = null,
                elevation = null,
                worldDirectionX = null,
                worldDirectionY = null,
                worldDirectionZ = null,
                lastSeen = System.currentTimeMillis()
            )
        }
        _peerUpdates.update { current -> current + (peerId to updated) }
        syncFriend(updated)
    }

    private var sensorsRegistered = false

    private fun registerSensors() {
        if (sensorsRegistered) return
        val sm = sensorManager ?: return
        val rotVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVector != null) {
            sm.registerListener(this, rotVector, SensorManager.SENSOR_DELAY_UI)
        } else {
            // Fallback to mag field and accelerometer
            val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (mag != null) sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
            if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
        }
        sensorsRegistered = true
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        sensorManager?.unregisterListener(this)
        hasRotationVector = false
        sensorsRegistered = false
    }

    fun pauseSensors() {
        if (sensorsRegistered) {
            sensorManager?.unregisterListener(this)
            hasRotationVector = false
            sensorsRegistered = false
        }
    }

    fun resumeSensors() {
        if (!sensorsRegistered && sessionManager.sessionState.value.currentEvent != null) {
            registerSensors()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val ev = event ?: return
        when (ev.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, ev.values)
                hasRotationVector = true
                updateWorldVectors()
                updateDeviceHeading()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(ev.values, 0, geomagneticValues, 0, 3)
                if (!hasRotationVector) {
                    computeRotationMatrixFallback()
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(ev.values, 0, gravityValues, 0, 3)
                if (!hasRotationVector) {
                    computeRotationMatrixFallback()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun computeRotationMatrixFallback() {
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, geomagneticValues)
        if (success) {
            updateWorldVectors()
            updateDeviceHeading()
        }
    }

    private fun updateDeviceHeading() {
        val orientationValues = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationValues)
        val headingDegrees = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
        _deviceHeading.value = headingDegrees
    }

    private val lastWorldVectorUpdate = AtomicLong(0L)
    private val WORLD_VECTOR_UPDATE_INTERVAL = 33L // ~30 FPS

    private fun updateWorldVectors() {
        val now = System.currentTimeMillis()
        val lastUpdate = lastWorldVectorUpdate.get()
        if (now - lastUpdate < WORLD_VECTOR_UPDATE_INTERVAL) return
        if (!lastWorldVectorUpdate.compareAndSet(lastUpdate, now)) return

        // Recalculate world directional vectors for all peers that have UWB/RSSI direction info
        _peerUpdates.update { current ->
            current.mapValues { (_, peer) ->
                val azimuthRad = Math.toRadians((peer.azimuth ?: 0f).toDouble()).toFloat()
                val elevationRad = Math.toRadians((peer.elevation ?: 0f).toDouble()).toFloat()

                // Vector in device horizontal plane: x = sin(azimuth), y = cos(azimuth), z = sin(elevation)
                val devX = sin(azimuthRad) * cos(elevationRad)
                val devY = cos(azimuthRad) * cos(elevationRad)
                val devZ = sin(elevationRad)

                // Multiply by rotation matrix to get world space vector
                // R is 3x3 row-major matrix:
                // [0] [1] [2]
                // [3] [4] [5]
                // [6] [7] [8]
                val wX = rotationMatrix[0] * devX + rotationMatrix[1] * devY + rotationMatrix[2] * devZ
                val wY = rotationMatrix[3] * devX + rotationMatrix[4] * devY + rotationMatrix[5] * devZ
                val wZ = rotationMatrix[6] * devX + rotationMatrix[7] * devY + rotationMatrix[8] * devZ

                peer.copy(
                    worldDirectionX = wX,
                    worldDirectionY = wY,
                    worldDirectionZ = wZ
                )
            }
        }
    }

    private fun startUwbRangingSession() {
        val manager = uwbManager
        val scope = repositoryScope ?: return
        val event = sessionManager.sessionState.value.currentEvent ?: return
        val localPeerId = sessionManager.peerId.value

        uwbSessionJob = scope.launch {
            if (manager == null || !event.useUwb) {
                Log.d(TAG, "UWB ranging disabled or unavailable")
                return@launch
            }
            try {
                if (sessionManager.sessionState.value.isHost) {
                    val controller = manager.controllerSessionScope()
                    val sessionKey = ByteArray(8).also(SecureRandom()::nextBytes)
                    val parameters = UwbControllerParametersDto(
                        hostPeerId = localPeerId,
                        controllerAddress = controller.localAddress.toString(),
                        channel = controller.uwbComplexChannel.channel,
                        preambleIndex = controller.uwbComplexChannel.preambleIndex,
                        sessionId = event.id.hashCode(),
                        staticStsKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                    )

                    // Launch a separate coroutine to handle each peer connection
                    nearbyConnectionsManager.receivedPeerAddress.collect { peer ->
                        // Don't start a new session if one is already active for this peer
                        if (activeRangingJobs[peer.peerId]?.isActive == true) return@collect

                        activeRangingJobs[peer.peerId] = launch {
                            try {
                                // Send our controller parameters back to the member
                                nearbyConnectionsManager.sendUwbParameters(peer.peerId, parameters)

                                // Start ranging with the member in a non-blocking flow
                                controller.prepareSession(
                                    RangingParameters(
                                        RangingParameters.CONFIG_UNICAST_DS_TWR,
                                        parameters.sessionId,
                                        0,
                                        sessionKey,
                                        null,
                                        null,
                                        listOf(UwbDevice.createForAddress(peer.address)),
                                        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                                    )
                                ).collect { result -> updateUwbPosition(peer.peerId, result) }
                            } catch (e: Exception) {
                                Log.e(TAG, "UWB session failed for peer ${peer.peerId}", e)
                            } finally {
                                activeRangingJobs.remove(peer.peerId)
                            }
                        }
                    }
                } else {
                    val controlee = manager.controleeSessionScope()

                    // Periodically send our peer address to the host until ranging starts
                    launch {
                        while (isActive) {
                            nearbyConnectionsManager.broadcastUwbParameters(
                                UwbPeerAddressDto(localPeerId, controlee.localAddress.toString())
                            )
                            delay(5000)
                        }
                    }

                    // Collect controller parameters from the host
                    nearbyConnectionsManager.receivedControllerParams.collect { controllerParams ->
                        // Don't start a new session if one is already active for the host
                        if (activeRangingJobs[controllerParams.hostPeerId]?.isActive == true) return@collect

                        activeRangingJobs[controllerParams.hostPeerId] = launch {
                            try {
                                controlee.prepareSession(
                                    RangingParameters(
                                        RangingParameters.CONFIG_UNICAST_DS_TWR,
                                        controllerParams.sessionId,
                                        0,
                                        Base64.decode(controllerParams.staticStsKey, Base64.NO_WRAP),
                                        null,
                                        UwbComplexChannel(controllerParams.channel, controllerParams.preambleIndex),
                                        listOf(UwbDevice.createForAddress(controllerParams.controllerAddress)),
                                        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                                    )
                                ).collect { result -> updateUwbPosition(controllerParams.hostPeerId, result) }
                            } catch (e: Exception) {
                                Log.e(TAG, "UWB session failed with host", e)
                            } finally {
                                activeRangingJobs.remove(controllerParams.hostPeerId)
                            }
                        }
                    }
                }
            } catch (exception: Exception) {
                Log.w(TAG, "UWB ranging unavailable; continuing with BLE proximity", exception)
            }
        }
    }

    private fun updateUwbPosition(peerId: String, result: RangingResult) {
        val position = (result as? RangingResult.RangingResultPosition)?.position ?: return
        _peerUpdates.update { peers ->
            val current = peers[peerId] ?: return@update peers
            peers + (
                peerId to current.copy(
                    distance = position.distance?.value,
                    azimuth = position.azimuth?.value,
                    elevation = position.elevation?.value,
                    lastSeen = System.currentTimeMillis()
                )
            )
        }
        updateWorldVectors()
    }

    /**
     * Applies a scripted range report received over the LAN debug transport
     * (see tools/mock_radar.py).
     *
     * This performs exactly the update [updateUwbPosition] would for a real
     * report, so the code under test is the production one: the rings, the
     * compass-corrected arrow, the distance readout, the "Direction locked" /
     * "BLE proximity mode" switch, the sub-2 m haptic and the staleness sweep
     * all react to a scripted walk just as they would to a real one. Only the
     * measurement is fake, never the rendering.
     *
     * Unlike a UWB update this also *creates* the peer entry when it is
     * missing, because a device on the LAN debug transport never sees a BLE
     * scan result for the other device: without that, the radar would stay
     * empty on the emulator and the injected distances would be dropped.
     */
    private fun updateMockRanging(dto: MockRangingDto) {
        val peerId = dto.peerId
        if (peerId.isEmpty() || peerId == sessionManager.peerId.value) return
        if (sessionManager.sessionState.value.currentEvent == null) return

        // A scripted report has a distance but no real signal strength, so an
        // RSSI is derived from the same curve the BLE scan path uses to turn
        // RSSI into a distance in the first place (distance = 2 + (rssi+100)/10).
        // Without it the hot/cold ring and the dBm readout would sit at their
        // "no signal" values and stop reacting to the scripted movement, which
        // is half of what the radar has to be tested for.
        val rssi = dto.distance?.let { ((it - 2f) * 10f - 100f).coerceIn(-100f, -30f) }?.toInt()
        val existing = _peerUpdates.value[peerId]
        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = dto.name?.takeIf { it.isNotBlank() } ?: existing?.name ?: "Peer $peerId",
            rssi = rssi,
            proximity = when {
                rssi == null -> "Unknown"
                rssi >= -70 -> "Hot"
                else -> "Cold"
            },
            distance = dto.distance ?: existing?.distance,
            azimuth = dto.azimuth ?: existing?.azimuth,
            elevation = dto.elevation ?: existing?.elevation,
            worldDirectionX = existing?.worldDirectionX,
            worldDirectionY = existing?.worldDirectionY,
            worldDirectionZ = existing?.worldDirectionZ,
            lastSeen = System.currentTimeMillis()
        )

        _peerUpdates.update { current ->
            current + (peerId to updated)
        }
        syncFriend(updated)
        updateWorldVectors()
    }

    private fun createChatNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHAT_CHANNEL_ID,
                "PingRing Chat Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming chat messages in PingRing"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun postChatNotification(chatMessage: ChatMessage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        createChatNotificationChannel()

        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            chatMessage.id.hashCode(),
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentEvent = sessionManager.sessionState.value.currentEvent
        val eventName = currentEvent?.name ?: "PingRing"

        val notification = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
            .setContentTitle("$eventName • ${chatMessage.senderName}")
            .setContentText(chatMessage.messageText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup(CHAT_NOTIFICATION_GROUP)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(chatMessage.id.hashCode(), notification)
    }
}
