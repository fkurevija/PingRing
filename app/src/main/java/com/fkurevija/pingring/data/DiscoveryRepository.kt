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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.provider.Settings
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

    // Peers that have a real UWB measurement, whose distance must not be
    // overwritten by an RSSI estimate. Tracked explicitly because a UWB
    // report can legitimately carry a null azimuth or elevation, so the
    // presence of those fields is not a reliable "has been ranged" signal.
    private val uwbRangedPeers = mutableSetOf<String>()

    // Running RSSI distance estimate per peer, fed through an exponential
    // moving average so the readout does not jitter with every advertisement.
    private val estimatedDistances = mutableMapOf<String, Float?>()

    /**
     * The member the user has chosen to track, or null for "auto" (nearest).
     *
     * This is a *view and ranging* choice only. It deliberately does not
     * filter incoming traffic: every member still receives every message,
     * beacon and nudge, so narrowing the radar never silences anyone.
     */
    private val _focusedPeerId = MutableStateFlow<String?>(null)
    val focusedPeerId: StateFlow<String?> = _focusedPeerId.asStateFlow()

    /** Peers with a live transport connection, which is who can be ranged. */
    private val connectedPeerIds = mutableSetOf<String>()

    fun setFocusedPeer(peerId: String?) {
        _focusedPeerId.value = peerId
        Log.i(TAG, "Radar focus set to ${peerId ?: "auto (nearest)"}")
        onFocusChanged()
    }

    /**
     * Resolves the focused member, or null when there is nobody to focus on.
     */
    fun resolveFocusedPeer(): String? {
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return null
        val nearest = _peerUpdates.value.values
            .minByOrNull { it.distance ?: Float.MAX_VALUE }
            ?.peerId
        return FocusPolicy.resolveFocus(
            selectedPeerId = _focusedPeerId.value,
            availablePeerIds = _peerUpdates.value.keys.toList(),
            nearestPeerId = nearest,
            isHost = state.isHost,
            trackHost = event.trackHost,
            localPeerId = sessionManager.peerId.value,
            hostPeerId = event.hostPeerId
        )
    }

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
            // Lets the transport route a guest's UWB ranging offer to the host
            // without this class reaching into the session itself.
            (nearbyConnectionsManager as? NearbyConnectionsManager)?.hostPeerIdProvider = {
                sessionManager.sessionState.value.currentEvent?.hostPeerId
            }
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
                        handleIncomingChatMessage(chatMessage)
                    }
                }
            }
        }

        // Register newly connected peers and deliver the host's current chat history.
        repositoryScope?.launch {
            nearbyConnectionsManager.connectionEstablished.collect { peerId ->
                addConnectedPeer(peerId)
                // A peer with a live connection is the only kind that can be
                // UWB-ranged, so this is what makes it eligible as a focus.
                connectedPeerIds.add(peerId)
                onFocusChanged()
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

        // A relayed ranging offer makes this device the controlee for a
        // guest-to-guest session. The host consumes offers rather than emitting
        // them (see NearbyConnectionsManager.dispatchEnvelope), so a host
        // simply sees nothing here.
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedUwbRangingOffer.collect { offer ->
                acceptIncomingRangingOffer(offer)
            }
        }

        // A peer whose transport connection dropped is gone: drop its pin, its
        // beacon and its entry in the member list straight away, instead of
        // leaving a ghost on the radar until the staleness sweep catches up.
        repositoryScope?.launch {
            nearbyConnectionsManager.peerDisconnected.collect { peerId ->
                Log.i(TAG, "Peer $peerId disconnected; removing it from the radar")
                connectedPeerIds.remove(peerId)
                peerUwbAddresses.remove(peerId)
                removePeer(peerId)
                // If that was the member being tracked, the focus has to be
                // re-resolved or the UWB session would sit on a dead peer.
                onFocusChanged()
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
        // The focus is per-session state: peer ids are ephemeral, so carrying a
        // selection into the next event would point the radar at nobody.
        activeFocusRangingJob?.cancel()
        activeFocusRangingJob = null
        activeFocusRangingTarget = null
        connectedPeerIds.clear()
        peerUwbAddresses.clear()
        _focusedPeerId.value = null
        repositoryScope?.cancel()
        repositoryScope = null
        _peerUpdates.value = emptyMap()
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

        val settings = BleRangePolicy.advertiseSettings()

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
                BleRangePolicy.advertiseSettings(),
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

        /** Two short pulses: distinguishable from a chat message, not a nuisance. */
        val CHAT_VIBRATION_PATTERN = longArrayOf(0, 120, 90, 120)

        /**
         * Debounce before moving the UWB session to a newly focused member.
         * Switching costs a handshake, so flicking through the member list must
         * not open and abandon a session per tap.
         */
        const val UWB_FOCUS_SETTLE_MILLIS = 750L
    }

    /**
     * Scan callbacks, one per pass. Android delivers every matching advert to
     * each registered callback, so the callbacks deliberately share one
     * [scanCallback] implementation and differ only in the settings they were
     * registered with.
     */
    private val scanCallbacks = mutableListOf<ScanCallback>()

    private fun startBleScanning() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        bleScanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PINGRING_SERVICE_UUID))
            .build()

        val extended = BleRangePolicy.supportsExtendedAdvertising(context)
        val passes = BleRangePolicy.scanPassCount(extended)
        BleRangePolicy.logSummary(context)

        // Each pass gets its own ScanCallback instance: Android treats the
        // callback identity as the scan request, so reusing one would replace
        // the previous pass rather than add to it.
        repeat(passes) { pass ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let { handleScanResult(it) }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach { handleScanResult(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan pass $pass failed: $errorCode")
                }
            }
            val settings = BleRangePolicy.scanSettingsForPass(pass, extended)
            try {
                bleScanner?.startScan(listOf(filter), settings, callback)
                scanCallbacks.add(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting BLE scan pass $pass", e)
            }
        }
    }

    private fun stopBleScanning() {
        scanCallbacks.forEach { callback ->
            try {
                bleScanner?.stopScan(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping a BLE scan pass", e)
            }
        }
        scanCallbacks.clear()
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

        // A UWB measurement is real ranging, so it always wins over an RSSI
        // guess. Only peers that have never been ranged fall back to the
        // estimate, and it is recomputed on every scan so it tracks a peer who
        // is walking rather than freezing on first contact.
        val rssiEstimate = RssiDistanceEstimator.smooth(
            previous = estimatedDistances[peerId],
            rawEstimate = RssiDistanceEstimator.estimate(rssi)
        )
        estimatedDistances[peerId] = rssiEstimate
        val hasUwbFix = uwbRangedPeers.contains(peerId)

        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = peerName,
            rssi = rssi,
            proximity = proximity,
            distance = if (hasUwbFix) existing?.distance else rssiEstimate ?: existing?.distance,
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
        // A peer that reconnects must be re-ranged from scratch: keeping the
        // old flags would suppress its RSSI estimate, and keeping the old
        // average would seed it with a distance from a session that is over.
        uwbRangedPeers.remove(peerId)
        estimatedDistances.remove(peerId)
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

    /**
     * The UWB session currently open, so a change of focus can close it before
     * opening the next. Only ever one at a time: the platform is built for a
     * single ranging session, and holding several is what made ranging degrade
     * once a PingRing filled up.
     */
    private var activeFocusRangingJob: Job? = null
    private val peerUwbAddresses = mutableMapOf<String, String>()

    /**
     * Re-points the UWB radio at whoever is now focused.
     *
     * This is the payoff of the focus filter: instead of trying to range seven
     * people at once, the radio holds exactly one session and moves it. The
     * switch costs a fresh handshake, so it is debounced - a user flicking
     * through the member list should not open and abandon a session per tap.
     */
    private fun onFocusChanged() {
        val manager = uwbManager ?: return
        val scope = repositoryScope ?: return
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return
        if (!event.useUwb) return

        val target = FocusPolicy.rangingTarget(
            focusPeerId = resolveFocusedPeer(),
            isHost = state.isHost,
            trackHost = event.trackHost,
            hostPeerId = event.hostPeerId,
            connectedPeerIds = connectedPeerIds.toSet()
        )
        val targetId = (target as? FocusPolicy.RangingTarget.Peer)?.peerId
        if (activeFocusRangingJob?.isActive == true && targetId == activeFocusRangingTarget) return
        activeFocusRangingJob?.cancel()
        activeFocusRangingTarget = targetId
        if (targetId == null) {
            Log.i(TAG, "UWB ranging idle: no focused member to range")
            return
        }

        val address = peerUwbAddresses[targetId]
        if (address == null) {
            Log.i(TAG, "UWB ranging idle: $targetId has not shared a UWB address yet")
            return
        }

        val isHost = (target as FocusPolicy.RangingTarget.Peer).isHost
        activeFocusRangingJob = scope.launch {
            // Let a rapid change of selection settle before spending a
            // handshake on it.
            delay(UWB_FOCUS_SETTLE_MILLIS)
            try {
                Log.i(TAG, "UWB: ranging $targetId${if (isHost) " (host)" else ""} at $address")
                val eventId = sessionManager.sessionState.value.currentEvent?.id ?: return@launch
                val sessionId = eventId.hashCode()
                if (isHost) {
                    // The host holds the controller scope and therefore has to
                    // drive. It publishes the channel and STS key the guest
                    // needs, which is why this is the only direction that can
                    // invent them.
                    val controller = manager.controllerSessionScope()
                    val sessionKey = ByteArray(8).also(SecureRandom()::nextBytes)
                    nearbyConnectionsManager.sendUwbParameters(
                        targetId,
                        UwbControllerParametersDto(
                            hostPeerId = localPeerId(),
                            controllerAddress = controller.localAddress.toString(),
                            channel = controller.uwbComplexChannel.channel,
                            preambleIndex = controller.uwbComplexChannel.preambleIndex,
                            sessionId = sessionId,
                            staticStsKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                        )
                    )
                    controller.prepareSession(
                        RangingParameters(
                            RangingParameters.CONFIG_UNICAST_DS_TWR,
                            sessionId,
                            0,
                            sessionKey,
                            null,
                            null,
                            listOf(UwbDevice.createForAddress(address)),
                            RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                        )
                    ).collect { result -> updateUwbPosition(targetId, result) }
                } else {
                    // A guest ranging another guest. Neither side is the host,
                    // so the parameters one of them invents have to reach the
                    // other over a link that does not exist: P2P_STAR leaves
                    // guests connected only to the host. This device therefore
                    // offers itself as controller and sends the offer up to the
                    // host, which forwards it to the peer it names. The peer
                    // that receives it becomes the controlee (see
                    // [acceptIncomingRangingOffer]) using the exact channel and
                    // STS key sent here, which is what makes the two ends
                    // agree.
                    val controller = manager.controllerSessionScope()
                    val sessionKey = ByteArray(8).also(SecureRandom()::nextBytes)
                    val eventId = sessionManager.sessionState.value.currentEvent?.id
                    if (eventId == null) {
                        Log.w(TAG, "UWB: no event to derive a session id from; skipping $targetId")
                        return@launch
                    }
                    // Both ends must end up on the same session id. The
                    // controlee takes this value verbatim from the offer, so
                    // agreement is guaranteed in that direction; deriving it
                    // from the unordered pair anyway means two guests that
                    // offer each other simultaneously still land on one id
                    // rather than two sessions that interleave on a channel.
                    val sessionId = (31L * eventId.hashCode() +
                        17L * (targetId.hashCode().toLong() + localPeerId().hashCode())).toInt()
                    nearbyConnectionsManager.sendUwbRangingOfferToHost(
                        UwbRangingOfferDto(
                            fromPeerId = localPeerId(),
                            targetPeerId = targetId,
                            controllerAddress = controller.localAddress.toString(),
                            channel = controller.uwbComplexChannel.channel,
                            preambleIndex = controller.uwbComplexChannel.preambleIndex,
                            sessionId = sessionId,
                            staticStsKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                        )
                    )
                    controller.prepareSession(
                        RangingParameters(
                            RangingParameters.CONFIG_UNICAST_DS_TWR,
                            sessionId,
                            0,
                            sessionKey,
                            null,
                            null,
                            listOf(UwbDevice.createForAddress(address)),
                            RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                        )
                    ).collect { result -> updateUwbPosition(targetId, result) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UWB focus session with $targetId failed; peer falls back to RSSI", e)
            }
        }
    }

    private var activeFocusRangingTarget: String? = null

    private fun localPeerId(): String = sessionManager.peerId.value

    /**
     * Takes the controlee side of a ranging session another guest has offered.
     *
     * This is the other half of guest-to-guest ranging: [onFocusChanged] offers
     * itself as controller and sends the offer up to the host, and whoever is
     * named in it ends up here. Using the channel and STS key from the offer
     * verbatim is the whole point - they were generated by the other end, and
     * inventing different ones is exactly what produced silently wrong ranges
     * before this path existed.
     *
     * The session runs in its own coroutine rather than replacing
     * [activeFocusRangingJob], because the two roles are held by different
     * devices: this one is the controlee here even though it may
     * simultaneously be the controller for a different peer it is tracking.
     */
    private fun acceptIncomingRangingOffer(offer: UwbRangingOfferDto) {
        val manager = uwbManager ?: return
        val scope = repositoryScope ?: return
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return
        if (!event.useUwb) return
        // Only act on offers actually meant for this device, and never on one
        // this device sent.
        if (offer.targetPeerId != localPeerId() || offer.fromPeerId == localPeerId()) {
            return
        }
        // The offerer is only worth ranging if it is a member of this event.
        if (offer.fromPeerId != event.hostPeerId && offer.fromPeerId !in connectedPeerIds) {
            Log.w(
                TAG,
                "Ignoring a UWB ranging offer from ${offer.fromPeerId}: not a member of this event"
            )
            return
        }

        Log.i(
            TAG,
            "Accepting a UWB ranging offer from ${offer.fromPeerId} " +
                "(session ${offer.sessionId}, channel ${offer.channel})"
        )
        scope.launch {
            try {
                val controlee = manager.controleeSessionScope()
                controlee.prepareSession(
                    RangingParameters(
                        RangingParameters.CONFIG_UNICAST_DS_TWR,
                        offer.sessionId,
                        0,
                        Base64.decode(offer.staticStsKey, Base64.NO_WRAP),
                        null,
                        UwbComplexChannel(offer.channel, offer.preambleIndex),
                        listOf(UwbDevice.createForAddress(offer.controllerAddress)),
                        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                    )
                ).collect { result -> updateUwbPosition(offer.fromPeerId, result) }
            } catch (e: Exception) {
                Log.e(TAG, "UWB ranging as controlee for ${offer.fromPeerId} failed", e)
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
                        // Record the address regardless of whether a session is
                        // started: the focus-driven session above needs it too,
                        // and a peer that shared one while it was not the
                        // focused member is exactly the peer the user will want
                        // to focus next.
                        peerUwbAddresses[peer.peerId] = peer.address
                        onFocusChanged()

                        // Don't start a new session if one is already active for this peer
                        if (activeRangingJobs[peer.peerId]?.isActive == true) return@collect

                        activeRangingJobs[peer.peerId] = launch {
                            // Declared outside the try so the catch below can
                            // report how long the session survived.
                            val startedAt = System.currentTimeMillis()
                            try {
                                // Send our controller parameters back to the member
                                nearbyConnectionsManager.sendUwbParameters(peer.peerId, parameters)

                                // Instrumented because a full PingRing opens one
                                // session per guest on this single controller
                                // scope. Whether the platform tolerates several
                                // concurrent prepareSession calls is hardware
                                // dependent and undocumented, and a refusal shows
                                // up only as a peer that silently stops ranging.
                                // The count and the first-failure reason are what
                                // tell the two behaviours apart in a logcat
                                // capture, so log both.
                                Log.i(
                                    TAG,
                                    "Starting UWB session for peer ${peer.peerId} " +
                                        "(active sessions: ${activeRangingJobs.size}/${MAX_PARTICIPANTS - 1})"
                                )

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
                                Log.e(
                                    TAG,
                                    "UWB session failed for peer ${peer.peerId} " +
                                        "after ${System.currentTimeMillis() - startedAt} ms; " +
                                        "this peer will fall back to RSSI-only distance",
                                    e
                                )
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
        // From here on this peer has a real measurement, so later BLE
        // advertisements must not replace it with an estimate.
        uwbRangedPeers.add(peerId)
        estimatedDistances.remove(peerId)
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
        // RSSI is derived by inverting the curve the BLE scan path uses, via
        // RssiDistanceEstimator. Without it the hot/cold ring and the dBm
        // readout would sit at their "no signal" values and stop reacting to
        // the scripted movement, which is half of what the radar has to be
        // tested for. A scripted report is a real measurement, so it also
        // pins the peer the way a UWB fix would.
        val rssi = dto.distance?.let { RssiDistanceEstimator.rssiForDistance(it) }?.toInt()
        val existing = _peerUpdates.value[peerId]
        if (dto.distance != null) {
            uwbRangedPeers.add(peerId)
            estimatedDistances.remove(peerId)
        }
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

    /**
     * Creates (or re-creates) the chat notification channel to match the
     * user's sound/vibration preferences.
     *
     * A [NotificationChannel]'s sound and vibration are fixed at creation time
     * and cannot be changed afterwards, so honouring a settings toggle means
     * comparing the desired configuration against the live channel and
     * deleting it when they disagree. Deleting also drops the user's own
     * system-level customisation of that channel, which is unavoidable here
     * and is preferable to silently ignoring the in-app setting.
     */
    private fun createChatNotificationChannel(soundEnabled: Boolean, vibrationEnabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val desiredSound: Uri? = if (soundEnabled) Settings.System.DEFAULT_NOTIFICATION_URI else null
        val desiredVibration: LongArray? = if (vibrationEnabled) CHAT_VIBRATION_PATTERN else null

        // LongArray is compared by content, not identity: vibrationPattern
        // hands back a fresh array on every read, so == would never match and
        // the channel would be needlessly recreated on every message.
        manager.getNotificationChannel(CHAT_CHANNEL_ID)?.let { existing ->
            val soundMatches = existing.sound == desiredSound
            val vibrationMatches = existing.vibrationPattern.contentEqualsOrNull(desiredVibration)
            if (!soundMatches || !vibrationMatches) {
                manager.deleteNotificationChannel(CHAT_CHANNEL_ID)
            }
        }

        val channel = NotificationChannel(
            CHAT_CHANNEL_ID,
            "PingRing Chat Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for incoming chat messages in PingRing"
            enableVibration(vibrationEnabled)
            if (desiredVibration != null) {
                vibrationPattern = desiredVibration
            }
            setSound(
                desiredSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Entry point for an incoming chat message from another peer.
     *
     * When the user is already watching the radar/chat window the shade
     * notification is redundant noise, so it is dropped — but the device is
     * still allowed to vibrate and/or make a sound, because a message can
     * still be worth a haptic while the user's eyes are on the radar. Those
     * two channels are controlled independently from app settings.
     */
    private suspend fun handleIncomingChatMessage(chatMessage: ChatMessage) {
        val soundEnabled = appearanceSettings.notificationSoundEnabled.first()
        val vibrationEnabled = appearanceSettings.notificationVibrationEnabled.first()

        if (ForegroundPresenceTracker.shouldSuppressChatNotification(context)) {
            if (vibrationEnabled) playChatVibration()
            if (soundEnabled) playChatSound()
            return
        }

        postChatNotification(chatMessage, soundEnabled, vibrationEnabled)
    }

    private fun playChatVibration() {
        val deviceVibrator = vibrator ?: return
        try {
            val effect = VibrationEffect.createWaveform(CHAT_VIBRATION_PATTERN, -1)
            deviceVibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "Chat vibration playback failed", e)
        }
    }

    /**
     * Plays the same tone the notification would have used, straight through
     * the media stack. Respects the device's notification volume and mute
     * state because it is the notification stream that is used.
     */
    private fun playChatSound() {
        val uri: Uri = Settings.System.DEFAULT_NOTIFICATION_URI
        try {
            RingtoneManager.getRingtone(context, uri)?.apply {
                if (!isPlaying) play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chat notification sound playback failed", e)
        }
    }

    private fun postChatNotification(
        chatMessage: ChatMessage,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        createChatNotificationChannel(soundEnabled, vibrationEnabled)

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

    /**
     * Content comparison that treats "no pattern" and "no pattern" as equal,
     * so a channel created with vibration off is not recreated forever.
     */
    private fun LongArray?.contentEqualsOrNull(other: LongArray?): Boolean = when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
}
