package com.fkurevija.pingring.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import android.util.Base64
import androidx.core.uwb.*
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
    val worldDirectionZ: Float?
)

data class ConnectivityStatus(
    val isBluetoothEnabled: Boolean,
    val isWifiEnabled: Boolean
)

@SuppressLint("MissingPermission")
class DiscoveryRepository(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val nearbyConnectionsManager: NearbyConnectionsManager
) : SensorEventListener {

    private val TAG = "DiscoveryRepository"
    private val PINGRING_SERVICE_UUID = UUID.fromString("0000fa1b-0000-1000-8000-00805f9b34fb")
    private val hostFlag = 1
    private val trackHostFlag = 1 shl 1
    private val eventClosedFlag = 1 shl 2

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

    private var repositoryScope: CoroutineScope? = null
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
    private val rotationMatrix = FloatArray(9) { 0f }.apply { this[0] = 1f; this[4] = 1f; this[8] = 1f }
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

    fun startDiscovery() {
        stopDiscovery()
        repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        refreshConnectivityStatus()

        // 1. Start Nearby Connections
        val event = sessionManager.sessionState.value.currentEvent
        if (event != null) {
            if (sessionManager.sessionState.value.isHost) {
                nearbyConnectionsManager.startAdvertising(event.id, sessionManager.peerId.value)
            } else {
                nearbyConnectionsManager.startDiscovery(event.id)
            }
        }

        // 2. Start BLE Advertising & Scanning
        startBleAdvertising()
        startBleScanning()

        // 3. Start Sensor Fusion
        registerSensors()

        // 4. Start foreground-only UWB ranging
        startUwbRangingSession()
    }

    fun stopDiscovery() {
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

    @Synchronized
    private fun startBleAdvertising() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val currentPeerId = sessionManager.peerId.value
        val currentEvent = sessionManager.sessionState.value.currentEvent ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val peerIdBytes = currentPeerId.toByteArray(StandardCharsets.UTF_8).copyOf(8)
        val flags = (if (sessionManager.sessionState.value.isHost) hostFlag else 0) or
            (if (currentEvent.trackHost) trackHostFlag else 0)
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
     */
    fun announceEventClosure(event: Event) {
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
                    hostFlag or eventClosedFlag,
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
                    Log.e(TAG, "Ping-Ring closure announcement failed to start: $errorCode")
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
            Log.e(TAG, "Unable to announce Ping-Ring closure", exception)
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
                Log.e(TAG, "Unable to stop Ping-Ring closure announcement", exception)
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
                trackHost = hostTracksMembers
            )
            sessionManager.updateEvent(currentEvent)
        }
        if (currentEvent.trackHost && !sessionManager.sessionState.value.isHost && !isHostPeer) return
        if (peerId.isEmpty()) return

        val rssi = result.rssi
        val proximity = if (rssi >= -70) "Hot" else "Cold"

        // Find or create existing update
        val existing = _peerUpdates.value[peerId]
        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = "Peer $peerId",
            rssi = rssi,
            proximity = proximity,
            distance = existing?.distance ?: (2f + (rssi + 100) / 10f), // Initial distance estimate from RSSI
            azimuth = existing?.azimuth,
            elevation = existing?.elevation,
            worldDirectionX = existing?.worldDirectionX,
            worldDirectionY = existing?.worldDirectionY,
            worldDirectionZ = existing?.worldDirectionZ
        )

        _peerUpdates.update { current ->
            current + (peerId to updated)
        }

        // Maintain friend list in SessionManager
        val friends = sessionManager.sessionState.value.friends
        val friendExists = friends.any { it.peerId == peerId }
        if (!friendExists) {
            val updatedFriends = friends + Friend(
                peerId = peerId,
                name = "Peer $peerId",
                lastSeen = System.currentTimeMillis(),
                distance = updated.distance?.toDouble()
            )
            sessionManager.updateFriends(updatedFriends)
        } else {
            val updatedFriends = friends.map {
                if (it.peerId == peerId) it.copy(lastSeen = System.currentTimeMillis(), distance = updated.distance?.toDouble()) else it
            }
            sessionManager.updateFriends(updatedFriends)
        }
    }

    private fun registerSensors() {
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
    }

    private fun unregisterSensors() {
        sensorManager?.unregisterListener(this)
        hasRotationVector = false
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
            if (manager == null) return@launch
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
                    elevation = position.elevation?.value
                )
            )
        }
        updateWorldVectors()
    }

}
