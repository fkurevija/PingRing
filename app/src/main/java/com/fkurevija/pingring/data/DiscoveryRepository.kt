package com.fkurevija.pingring.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.uwb.RangingResult
import com.fkurevija.pingring.MainActivity
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.ble.BleAdvertisingCoordinator
import com.fkurevija.pingring.data.ble.BleIdentityScanner
import com.fkurevija.pingring.data.discovery.ConnectivityStatus
import com.fkurevija.pingring.data.discovery.DiscoveryBeaconCoordinator
import com.fkurevija.pingring.data.discovery.DiscoveryEventCoordinator
import com.fkurevija.pingring.data.radar.PeerGnssAccess
import com.fkurevija.pingring.data.radar.PeerMembershipAccess
import com.fkurevija.pingring.data.radar.PeerRadarCoordinator
import com.fkurevija.pingring.data.radar.PeerRangingAccess
import com.fkurevija.pingring.data.radar.LastKnownPeerDirection
import com.fkurevija.pingring.data.radar.RadarPeerUpdate
import com.fkurevija.pingring.data.settings.AppearanceSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.fkurevija.pingring.data.chat.ChatColourCoordinator
import com.fkurevija.pingring.data.chat.DiscoveryChatCoordinator
import com.fkurevija.pingring.data.radar.FocusPolicy
import com.fkurevija.pingring.data.radar.GnssFixDto
import com.fkurevija.pingring.data.radar.GnssPolicy
import com.fkurevija.pingring.data.radar.PeerRangingPayloadDto
import com.fkurevija.pingring.data.ranging.BleRangeProbe
import com.fkurevija.pingring.data.ranging.DeviceOrientationTracker
import com.fkurevija.pingring.data.ranging.GnssRangingManager
import com.fkurevija.pingring.data.ranging.MockRangingDto
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy
import com.fkurevija.pingring.data.ranging.UwbRangingCoordinator
import com.fkurevija.pingring.data.ranging.UwbRetentionPolicy
import com.fkurevija.pingring.data.ranging.WifiAwareRangingManager
import com.fkurevija.pingring.data.session.BeaconInfo
import com.fkurevija.pingring.data.session.BleIdentityKeyPayloadDto
import com.fkurevija.pingring.data.session.Event
import com.fkurevija.pingring.data.session.EventControlPayloadDto
import com.fkurevija.pingring.data.session.Friend
import com.fkurevija.pingring.data.session.InviteSecret
import com.fkurevija.pingring.data.session.InviteSecretPayloadDto
import com.fkurevija.pingring.data.session.MembershipPolicy
import com.fkurevija.pingring.data.session.MeshPresencePayloadDto
import com.fkurevija.pingring.data.session.SessionManager
import com.fkurevija.pingring.data.session.SessionState
import com.fkurevija.pingring.data.session.MAX_PARTICIPANTS

@SuppressLint("MissingPermission")
class DiscoveryRepository(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val nearbyConnectionsManager: NearbyTransport,
    private val appearanceSettings: AppearanceSettingsRepository = AppearanceSettingsRepository(context)
) {

    private val TAG = "DiscoveryRepository"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // Expose connectivity status
    private val _connectivityStatus = MutableStateFlow(getConnectivityStatus())
    val connectivityStatus: StateFlow<ConnectivityStatus> = _connectivityStatus.asStateFlow()
    private val _hostConnectionLostAtMillis = MutableStateFlow<Long?>(null)
    val hostConnectionLostAtMillis: StateFlow<Long?> = _hostConnectionLostAtMillis.asStateFlow()

    val peerUpdates: Flow<List<RadarPeerUpdate>> get() = peerRadarCoordinator.peerUpdates
    val members: Flow<List<RadarPeerUpdate>> get() = peerRadarCoordinator.members
    val lastKnownPeerDirections: StateFlow<List<LastKnownPeerDirection>>
        get() = peerRadarCoordinator.lastKnownPeerDirections

    // Device orientation (heading, and the world direction of each peer). Kept in
    // its own class because it is the one part of discovery that keeps running
    // whenever the screen is visible, independently of whether a ring is active.
    private val orientationTracker = DeviceOrientationTracker(context).apply {
        onWorldVectorsChanged = { refreshPeerWorldDirections() }
    }
    val deviceHeading: StateFlow<Float> get() = orientationTracker.deviceHeading

    private val beaconCoordinator = DiscoveryBeaconCoordinator(
        context = context,
        sessionManager = sessionManager,
        transport = nearbyConnectionsManager,
        settingsRepository = appearanceSettings,
        onPeerName = ::updatePeerName,
    )
    val activeBeacons: StateFlow<Map<String, BeaconInfo>> get() = beaconCoordinator.activeBeacons
    val endedBeacons: StateFlow<List<BeaconInfo>> get() = beaconCoordinator.endedBeacons
    val localBeacon: StateFlow<BeaconInfo?> get() = beaconCoordinator.localBeacon
    val beaconAlertSettings: StateFlow<BeaconAlertSettings> get() = beaconCoordinator.alertSettings
    val silencedBeaconIds: StateFlow<Set<String>> get() = beaconCoordinator.silencedBeaconIds

    /**
     * Devices waiting on the host to approve them, oldest first.
     *
     * Exposed so the radar can show the host what is waiting without the screen
     * needing the transport itself; the transport is an implementation detail of
     * this repository and the screen has no business reaching past it.
     */
    /** Devices waiting to be let in, oldest first. */
    val pendingJoinRequests get() = nearbyConnectionsManager.pendingJoinRequests

    val pendingAuthentications get() = nearbyConnectionsManager.pendingAuthentications

    fun confirmConnectionAuthentication(endpointId: String) =
        nearbyConnectionsManager.confirmConnectionAuthentication(endpointId)

    fun rejectConnectionAuthentication(endpointId: String) =
        nearbyConnectionsManager.rejectConnectionAuthentication(endpointId)

    fun setNearbyDigitComparisonEnabled(enabled: Boolean) {
        (nearbyConnectionsManager as? NearbyConnectionsManager)?.requireDigitComparison = enabled
    }
    val eventClosed get() = nearbyConnectionsManager.eventClosed

    private val eventCoordinator = DiscoveryEventCoordinator(
        sessionManager = sessionManager,
        transport = nearbyConnectionsManager,
        connectedPeerIds = { peerRadarCoordinator.connectedPeerIds() },
    )

    /** Lets a held join request in. */
    fun approveJoinRequest(endpointId: String) = nearbyConnectionsManager.approveJoinRequest(endpointId)

    /** Turns a held join request away. */
    fun denyJoinRequest(endpointId: String) = nearbyConnectionsManager.denyJoinRequest(endpointId)

    /** Sends a confirmed guest departure before its authenticated host link closes. */
    fun announceMemberDeparture(onComplete: () -> Unit = {}) =
        eventCoordinator.announceMemberDeparture(onComplete)


    // Local peer ID
    val localPeerId: String get() = sessionManager.peerId.value

    private val chatCoordinator = DiscoveryChatCoordinator(
        context = context,
        sessionManager = sessionManager,
        transport = nearbyConnectionsManager,
        appearanceSettings = appearanceSettings,
        updatePeerName = ::updatePeerName,
    )

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
    private var activeDiscoveryInviteSecret: String? = null
    private var activeDiscoveryIsHost: Boolean? = null
    private var activeDiscoveryUseUwb: Boolean? = null
    private var activeDiscoveryUseWifiDirect: Boolean? = null
    private var activeDiscoveryUseHotspot: Boolean? = null
    private var localBleIdentityKeyPair: java.security.KeyPair? = null
    private var localBleIdentityKey: BleIdentityKeyPayloadDto? = null
    private val bleAdvertisingCoordinator = BleAdvertisingCoordinator(
        context = context,
        sessionManager = sessionManager,
        identityKeyPair = { localBleIdentityKeyPair },
        peerCount = { peerRadarCoordinator.peerCount },
        isUwbSupported = ::isUwbSupported,
        onIdentityAdvertisingUnavailable = { _connectivityStatus.value = getConnectivityStatus() },
    )
    private val bleIdentityKeys = java.util.concurrent.ConcurrentHashMap<String, BleIdentityKeyPayloadDto>()
    private val latestBleAdvertisementSequences = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val bleIdentityScanner = BleIdentityScanner(
        context = context,
        sessionManager = sessionManager,
        transport = nearbyConnectionsManager,
        identityKeyForPeer = { peerId -> bleIdentityKeys[peerId] },
        acceptedSequences = latestBleAdvertisementSequences,
        supportsBleIdentity = { getConnectivityStatus().supportsBleIdentity },
        radioPlan = bleAdvertisingCoordinator::currentRadioPlan,
        onVerifiedAdvertisement = ::updatePeerFromBleAdvertisement,
    )
    private val localRangingSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val gnssRanging = GnssRangingManager(context) { fix ->
        publishPeerRanging(gnssFix = fix)
        updateGnssDistances()
    }
    private val wifiRttRanging = WifiAwareRangingManager(
        context,
        focusedPeer = { resolveFocusedPeer() },
        hasFreshUwb = { peerId -> peerRadarCoordinator.hasFreshUwbMeasurement(peerId) },
        onAddress = { mac -> publishPeerRanging(wifiAwareMac = mac) },
        onDistance = { peerId, distance, deviation ->
            updateAdditionalDistance(peerId, distance, deviation, RangingSourcePolicy.RangingSource.WIFI_RTT)
        },
    )

    // Per-peer RSSI Kalman filters smooth signal variation before the nonlinear
    // path-loss conversion. Separate state prevents members' readings from
    // contaminating each other's estimates.
    /**
     * The member the user has chosen to track, or null for "auto" (nearest).
     *
     * This is a *view and ranging* choice only. It deliberately does not
     * filter incoming traffic: every member still receives every message
     * and beacon, so narrowing the radar never silences anyone.
     */
    private val _focusedPeerId = MutableStateFlow<String?>(null)
    val focusedPeerId: StateFlow<String?> = _focusedPeerId.asStateFlow()

    /** Admitted members recently seen over a direct link or signed mesh presence. */
    private val rangingCoordinator: UwbRangingCoordinator = UwbRangingCoordinator(
        context = context,
        sessionManager = sessionManager,
        transport = nearbyConnectionsManager,
        repositoryScope = { repositoryScope },
        refreshFocus = ::onFocusChanged,
        connectedPeerIds = { peerRadarCoordinator.connectedPeerIds() },
        onUwbPosition = ::updateUwbPosition,
    )
    private val peerRangingAccess = object : PeerRangingAccess {
        override val retainedPeerIds get() = rangingCoordinator.retainedPeerIds
        override fun hasUwbMeasurement(peerId: String) = rangingCoordinator.hasUwbMeasurement(peerId)
        override fun recordUwbMeasurement(peerId: String, measuredAtMillis: Long) =
            rangingCoordinator.recordUwbMeasurement(peerId, measuredAtMillis)
        override fun markUwbMeasurement(peerId: String) = rangingCoordinator.markUwbMeasurement(peerId)
        override fun forgetPeer(peerId: String) = rangingCoordinator.forgetPeer(peerId)
    }
    private val peerGnssAccess = object : PeerGnssAccess {
        override fun currentLocalFix() = gnssRanging.currentLocalFix()
        override fun currentFix() = gnssRanging.currentFix()
        override fun setEnabled(enabled: Boolean) = gnssRanging.setEnabled(enabled)
    }
    private val peerMembershipAccess = object : PeerMembershipAccess {
        override val sessionState get() = sessionManager.sessionState
        override val localPeerId get() = sessionManager.peerId.value
        override fun updateFriends(friends: List<Friend>) = sessionManager.updateFriends(friends)
    }
    private val peerRadarCoordinator: PeerRadarCoordinator = PeerRadarCoordinator(
        context = context,
        membership = peerMembershipAccess,
        orientationTracker = orientationTracker,
        gnssRanging = peerGnssAccess,
        rangingCoordinator = peerRangingAccess,
        onPeerNameChanged = { peerId, name ->
            chatCoordinator.renameMemberHistory(peerId, name)
            beaconCoordinator.renamePeer(peerId, name)
        },
        onBeaconEnded = ::endBeacon,
        onHostConnectionRestored = { _hostConnectionLostAtMillis.value = null },
        isLanDebugTransport = { nearbyConnectionsManager is LanDebugConnectionsManager },
    )

    private val chatColourCoordinator = ChatColourCoordinator(
        sessionManager = sessionManager,
        transport = nearbyConnectionsManager,
        chatCoordinator = chatCoordinator,
    )
    val chatColourHolders: StateFlow<Map<Long, String>> get() = chatColourCoordinator.holders

    fun requestChatColour(colorArgb: Long, onResult: (Boolean) -> Unit = {}) =
        chatColourCoordinator.request(colorArgb, onResult)
    fun setFocusedPeer(peerId: String) {
        if (peerId == sessionManager.peerId.value || !peerRadarCoordinator.containsPeer(peerId)) {
            Log.w(TAG, "Cannot track an unavailable or local member")
            return
        }
        _focusedPeerId.value = peerId
        Log.i(TAG, "Radar focus set to $peerId")
        onFocusChanged()
    }

    /**
     * Resolves the focused member, or null when there is nobody to focus on.
     */
    fun resolveFocusedPeer(): String? = resolveFocusedPeer(_focusedPeerId.value)

    private fun resolveFocusedPeer(selectedPeerId: String?): String? {
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return null
        return FocusPolicy.resolveFocus(
            selectedPeerId = selectedPeerId,
            // The map is insertion-ordered, so this is discovery order. It is
            // only the tie-break now: a peer that reported when it joined is
            // ranked ahead of one that did not.
            availablePeerIds = peerRadarCoordinator.peerIds.toList(),
            peerJoinedAt = peerRadarCoordinator.joinedAtByPeer,
            isHost = state.isHost,
            localPeerId = sessionManager.peerId.value,
            hostPeerId = event.hostPeerId
        )
    }

    fun getConnectivityStatus(): ConnectivityStatus {
        val bleEnabled = bluetoothAdapter?.isEnabled ?: false
        val wifiEnabled = wifiManager?.isWifiEnabled ?: false
        return ConnectivityStatus(
            isBluetoothEnabled = bleEnabled,
            isWifiEnabled = wifiEnabled,
            supportsBleIdentity = bleEnabled && BleRangeProbe.supportsSignedBleIdentityAdvertising(context),
        )
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
            event.inviteSecret == activeDiscoveryInviteSecret &&
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
        activeDiscoveryInviteSecret = event?.inviteSecret
        activeDiscoveryIsHost = isHost
        activeDiscoveryUseUwb = event?.useUwb
        activeDiscoveryUseWifiDirect = event?.useWifiDirect
        activeDiscoveryUseHotspot = event?.useHotspot
        _hostConnectionLostAtMillis.value = null
        if (event != null) {
            peerRadarCoordinator.clearLastKnownDirections()
            val keyPair = BleIdentityCrypto.generateKeyPair()
            localBleIdentityKeyPair = keyPair
            localBleIdentityKey = BleIdentityKeyPayloadDto(
                eventCode = event.id,
                peerId = localPeerId,
                publicKey = BleIdentityCrypto.encodePublicKey(keyPair.public),
            ).also { identity ->
                bleIdentityKeys.clear()
                latestBleAdvertisementSequences.clear()
                bleAdvertisingCoordinator.resetAdvertisementSequence()
                bleIdentityKeys[localPeerId] = identity
            }
        }
        repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        refreshConnectivityStatus()

        repositoryScope?.launch {
            peerRadarCoordinator.peerUpdatesById
                .map { peers -> peers.mapValues { (_, peer) -> peer.joinedAtMillis } }
                .distinctUntilChanged()
                .collect { onFocusChanged() }
        }

        // 1. Start Nearby Connections
        if (event != null) {
            // Lets the transport route a guest's UWB ranging offer to the host
            // without this class reaching into the session itself.
            (nearbyConnectionsManager as? NearbyConnectionsManager)?.hostPeerIdProvider = {
                sessionManager.sessionState.value.currentEvent?.hostPeerId
            }
            nearbyConnectionsManager.inviteSecretProvider = {
                sessionManager.sessionState.value.currentEvent?.inviteSecret
            }
            nearbyConnectionsManager.userNameProvider = {
                sessionManager.sessionState.value.userName
            }
            (nearbyConnectionsManager as? NearbyConnectionsManager)?.meshIdentityKeyProvider = { peerId ->
                bleIdentityKeys[peerId]
            }
            (nearbyConnectionsManager as? NearbyConnectionsManager)?.meshPrivateKeyProvider = {
                localBleIdentityKeyPair?.private
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

        if (event != null && nearbyConnectionsManager is NearbyConnectionsManager) {
            wifiRttRanging.start(event.id)
        }
        repositoryScope?.launch {
            sessionManager.sessionState.map { state ->
                val currentEvent = state.currentEvent
                (currentEvent?.useGnss == true) to
                    (currentEvent != null && nearbyConnectionsManager is LanDebugConnectionsManager)
            }.distinctUntilChanged().collect { (useGnss, useLanWifiAnchor) ->
                peerRadarCoordinator.setGnssEnabled(useGnss, useLanWifiAnchor)
            }
        }
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedPeerRanging.collect { payload ->
                val current = sessionManager.sessionState.value.currentEvent ?: return@collect
                if (payload.eventCode != current.id || payload.peerId == localPeerId() ||
                    !peerRadarCoordinator.acceptRangingSequence(payload.peerId, payload.sequence)
                ) return@collect
                payload.wifiAwareMac?.let { wifiRttRanging.registerPeer(payload.peerId, it) }
                val fix = payload.gnssFix
                if (current.useGnss && fix != null && GnssPolicy.isUsable(fix)) {
                    peerRadarCoordinator.recordPeerGnssFix(payload.peerId, fix)
                    updateGnssDistances()
                }
            }
        }

        // 2. Start Periodic Peer Cleanup
        repositoryScope?.launch {
            while (isActive) {
                delay(PEER_CLEANUP_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                val expiredPeerIds = peerRadarCoordinator.sweepExpiredPeers(
                    nowMillis = now,
                    retainedPeerIds = rangingCoordinator.retainedPeerIds,
                )
                expiredPeerIds.forEach(rangingCoordinator::forgetPeerAddress)
                val state = sessionManager.sessionState.value
                if (!state.isHost && state.currentEvent?.hostPeerId in expiredPeerIds &&
                    _hostConnectionLostAtMillis.value == null
                ) {
                    _hostConnectionLostAtMillis.value = now
                }
            }
        }

        // 2b. Start Chat Message Processing Queue Flow Listener Loop
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedInviteSecret.collect { payload ->
                val current = sessionManager.sessionState.value.currentEvent ?: return@collect
                if (sessionManager.sessionState.value.isHost || payload.eventCode != current.id) return@collect
                if (InviteSecret.decode(payload.inviteSecret) == null) return@collect
                if (current.inviteSecret != null && current.inviteSecret != payload.inviteSecret) return@collect
                if (payload.hostPeerId.isBlank()) return@collect
                sessionManager.updateEvent(
                    current.copy(inviteSecret = payload.inviteSecret, hostPeerId = payload.hostPeerId)
                )
                _hostConnectionLostAtMillis.value = null
                addConnectedPeer(payload.hostPeerId)
                activeDiscoveryInviteSecret = payload.inviteSecret
                localBleIdentityKey?.let { identityKey ->
                    nearbyConnectionsManager.sendBleIdentityKey(payload.hostPeerId, identityKey)
                }
                bleAdvertisingCoordinator.stop()
                stopBleScanning()
                bleAdvertisingCoordinator.startSignedAdvertising()
                startBleScanning()
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedBleIdentityKey.collect { identityKey ->
                val currentEvent = sessionManager.sessionState.value.currentEvent ?: return@collect
                if (identityKey.eventCode != currentEvent.id || identityKey.peerId == sessionManager.peerId.value) {
                    return@collect
                }
                if (BleIdentityCrypto.decodePublicKey(identityKey.publicKey) == null) return@collect
                val previous = bleIdentityKeys.put(identityKey.peerId, identityKey)
                if (previous?.publicKey != identityKey.publicKey) {
                    latestBleAdvertisementSequences.remove(identityKey.peerId)
                }
                (nearbyConnectionsManager as? NearbyConnectionsManager)?.connectToPeer(identityKey.peerId)
                if (sessionManager.sessionState.value.isHost) {
                    nearbyConnectionsManager.broadcastBleIdentityKey(identityKey)
                }
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedChatColourClaim.collect { claim ->
                chatColourCoordinator.handleClaim(claim)
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedMemberDeparture.collect { departure ->
                val currentEvent = sessionManager.sessionState.value.currentEvent ?: return@collect
                // The rule that separates losing contact from leaving lives in
                // MembershipPolicy, so it has one place to be asserted rather
                // than being re-derived here every time this path is touched.
                if (!MembershipPolicy.leavesRingOnGracefulDeparture(
                        departureEventCode = departure.eventCode,
                        currentEventCode = currentEvent.id,
                        departurePeerId = departure.peerId,
                        localPeerId = sessionManager.peerId.value,
                    )
                ) {
                    return@collect
                }
                peerRadarCoordinator.markPeerDeparted(departure.peerId)
                rangingCoordinator.forgetPeerAddress(departure.peerId)
                removePeer(departure.peerId, preserveLastKnownDirection = false)
                // removePeer deliberately leaves membership alone, because losing
                // contact is not leaving. This is the case that *is* leaving, and
                // an authenticated graceful departure is the only thing short of
                // the host ending the ring that removes somebody from the list.
                val remaining = sessionManager.sessionState.value.friends
                    .filterNot { it.peerId == departure.peerId }
                if (remaining.size != sessionManager.sessionState.value.friends.size) {
                    sessionManager.updateFriends(remaining)
                }
                peerRadarCoordinator.forgetPeerDisplayName(departure.peerId)
                // Their colour goes back too. Otherwise a departed member holds it
                // for the rest of the event, and with eight members and twelve
                // colours four of them would be gone for good.
                if (sessionManager.sessionState.value.isHost) {
                    chatColourCoordinator.releaseDepartedMembers(
                        remainingPeerIds = peerRadarCoordinator.connectedPeerIds() - departure.peerId + localPeerId(),
                    )
                }
                onFocusChanged()
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedMeshPresence.collect { presence ->
                if (presence.eventCode == sessionManager.sessionState.value.currentEvent?.id) {
                    presence.userName?.let { name ->
                        val sanitized = TextInputPolicy.sanitizeUsername(name).trim()
                        if (sanitized.isNotBlank()) updatePeerName(presence.peerId, sanitized)
                    }
                    updateMeshPresence(presence.peerId)
                }
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedEventControl.collect { control ->
                val current = sessionManager.sessionState.value.currentEvent ?: return@collect
                if (sessionManager.sessionState.value.isHost || control.eventCode != current.id) return@collect
                if (current.hostPeerId != null && control.hostPeerId != current.hostPeerId) return@collect
                if (control.isClosed) {
                    stopDiscovery()
                    sessionManager.endEventFromHost()
                    return@collect
                }
                val updated = current.copy(
                    useUwb = control.useUwb,
                    useGnss = control.useGnss,
                    useWifiDirect = control.useWifiDirect,
                    acceptsNewMembers = control.acceptsNewMembers,
                    hostPeerId = control.hostPeerId,
                )
                if (updated != current) {
                    sessionManager.updateEvent(updated)
                    activeDiscoveryUseUwb = updated.useUwb
                    activeDiscoveryUseWifiDirect = updated.useWifiDirect
                    if (current.useUwb != updated.useUwb) {
                        rangingCoordinator.restart()
                    }
                    onFocusChanged()
                }
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedChatMessage.collect { dto ->
                chatCoordinator.receiveMessage(dto)
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedChatReceipt.collect { receipt ->
                chatCoordinator.receiveReceipt(receipt)
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.peerNames.collect { (peerId, name) ->
                updatePeerName(peerId, name)
            }
        }

        // Register newly connected peers and deliver the host's current chat history.
        repositoryScope?.launch {
            nearbyConnectionsManager.connectionEstablished.collect { peerId ->
                val state = sessionManager.sessionState.value
                if (!state.isHost && state.currentEvent?.hostPeerId == peerId) {
                    _hostConnectionLostAtMillis.value = null
                }
                addConnectedPeer(peerId)
                if (sessionManager.sessionState.value.isHost) {
                    announceMemberJoined(peerId)
                }
                // A peer with a live connection is the only kind that can be
                // UWB-ranged, so this is what makes it eligible as a focus.
                peerRadarCoordinator.addConnectedPeerId(peerId)
                // The peer is back on the transport, so any retention held for
                // it has served its purpose. Clearing it here means the normal
                // connected path takes over and the member is no longer treated
                // as a special out-of-range case.
                if (rangingCoordinator.releaseRetainedPeer(peerId)) {
                    Log.i(TAG, "Peer $peerId is back in transport range; releasing its retained UWB session")
                }
                onFocusChanged()
                if (sessionManager.sessionState.value.isHost) {
                    sessionManager.sessionState.value.currentEvent?.let { event ->
                        event.inviteSecret?.let { secret ->
                            nearbyConnectionsManager.sendInviteSecret(
                                peerId,
                                InviteSecretPayloadDto(event.id, secret, sessionManager.peerId.value),
                            )
                        }
                        localBleIdentityKey?.let { identityKey ->
                            nearbyConnectionsManager.sendBleIdentityKey(peerId, identityKey)
                        }
                        bleIdentityKeys.values
                            .filter { it.peerId != sessionManager.peerId.value && it.peerId != peerId }
                            .forEach { identityKey -> nearbyConnectionsManager.sendBleIdentityKey(peerId, identityKey) }
                        nearbyConnectionsManager.sendEventControl(
                            peerId,
                            EventControlPayloadDto(
                                eventCode = event.id,
                                hostPeerId = sessionManager.peerId.value,
                                useUwb = event.useUwb,
                                useGnss = event.useGnss,
                                useWifiDirect = event.useWifiDirect,
                                acceptsNewMembers = event.acceptsNewMembers,
                                isFull = sessionManager.sessionState.value.friends.size + 1 >= MAX_PARTICIPANTS,
                            ),
                        )
                    }
                    chatCoordinator.sendHistoryToPeer(peerId)
                }
            }
        }

        // A relayed ranging offer makes this device the controlee for a
        // guest-to-guest session. The host consumes offers rather than emitting
        // them (see NearbyConnectionsManager.dispatchEnvelope), so a host
        // simply sees nothing here.
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedUwbRangingOffer.collect { offer ->
                rangingCoordinator.acceptIncomingRangingOffer(offer)
            }
        }

        // A peer whose transport connection dropped may still be reachable over
        // UWB, which reaches further than the transport does. Rather than
        // forgetting it outright, keep the ranging session alive for the peer
        // actually being tracked so that walking back into range is instant
        // instead of renegotiating a channel and STS key.
        repositoryScope?.launch {
            nearbyConnectionsManager.peerDisconnected.collect { peerId ->
                val state = sessionManager.sessionState.value
                val event = state.currentEvent
                val meshPresenceFresh =
                    peerRadarCoordinator.isMeshPresenceFresh(peerId, System.currentTimeMillis())
                if (!state.isHost && event?.hostPeerId == peerId && !meshPresenceFresh) {
                    _hostConnectionLostAtMillis.value = System.currentTimeMillis()
                }
                if (meshPresenceFresh) {
                    peerRadarCoordinator.addConnectedPeerId(peerId)
                    return@collect
                }
                val retained = UwbRetentionPolicy.retainOnDisconnect(
                    peerId = peerId,
                    rangedTargetId = rangingCoordinator.activeFocusTarget,
                    knownUwbAddress = rangingCoordinator.peerAddress(peerId),
                    ringIsActive = event != null,
                    ringIsOver = event == null,
                )
                if (retained != null) {
                    // Keep the address and the pin: only the transport link is
                    // gone, not the member. The retention sweep will drop this
                    // if the window lapses or the ring ends first.
                    rangingCoordinator.retainPeer(peerId, System.currentTimeMillis())
                    Log.i(
                        TAG,
                        "Peer $peerId dropped transport but is UWB-ranged; retaining the " +
                            "session for ${UwbRetentionPolicy.RETENTION_MILLIS}ms so reconnection is instant"
                    )
                    // The focus must not move: moving it would spend the single
                    // UWB session on someone else and abandon the peer we are
                    // deliberately holding open.
                    return@collect
                }

                Log.i(TAG, "Peer $peerId disconnected; removing it from the radar")
                peerRadarCoordinator.removeConnectedPeerId(peerId)
                rangingCoordinator.forgetPeerAddress(peerId)
                removePeer(peerId)
                // If that was the member being tracked, the focus has to be
                // re-resolved or the UWB session would sit on a dead peer.
                onFocusChanged()
            }
        }

        // Drop a retained session once its window lapses. Without this a peer
        // that walked away would stay on the radar indefinitely, holding the one
        // UWB session open so nothing else could ever be ranged.
        repositoryScope?.launch {
            while (isActive) {
                delay(PEER_CLEANUP_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                // The window comes from the power policy rather than the
                // constant, so a phone already running two other radios gives
                // up the UWB session sooner. UwbRetentionPolicy still owns
                // whether retaining is right at all; this only decides how long
                // it is worth paying for.
                val window = bleAdvertisingCoordinator.currentRadioPlan(
                    BleRangeProbe.supportsExtendedAdvertising(context)
                ).uwbRetentionMillis
                val expired = rangingCoordinator.expiredRetainedPeerIds(now, window)
                if (expired.isNotEmpty()) {
                    Log.i(TAG, "Retention window lapsed for $expired; releasing their UWB sessions")
                    expired.forEach { peerId ->
                        rangingCoordinator.releaseRetainedPeer(peerId)
                        peerRadarCoordinator.removeConnectedPeerId(peerId)
                        rangingCoordinator.forgetPeerAddress(peerId)
                        removePeer(peerId)
                    }
                    onFocusChanged()
                }
            }
        }

        // Debug-only: scripted ranges from tools/mock_radar.py or local Wi-Fi
        // RSSI proxies on physical devices. The emulator has no UWB or BLE radio, so without
        // these the radar would have no distance to draw and the only thing
        // testable between two devices would be chat and beacons.
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedMockRanging.collect { dto ->
                updateMockRanging(dto)
            }
        }

        // The LAN debug relay reports event closure here. Nearby peers receive
        // authenticated EventControl on the separate receivedEventControl flow.
        // Either way, leaving clears the session and returns navigation Home.
        repositoryScope?.launch {
            nearbyConnectionsManager.eventClosed.collect {
                Log.i(TAG, "Host ended or removed this member from the PingRing")
                stopDiscovery()
                sessionManager.endEventFromHost()
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedChatHistory.collect { history ->
                chatCoordinator.receiveHistory(history)
            }
        }

        // 2c. Start Beacon Processing Queue Flow Listener Loop
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedBeacon.collect { dto ->
                beaconCoordinator.receive(dto)
            }
        }

        // 3. Start BLE Advertising & Scanning
        bleAdvertisingCoordinator.startSignedAdvertising()
        startBleScanning()

        // 3. Start Sensor Fusion
        orientationTracker.resume()

        // 4. Start foreground-only UWB ranging
        rangingCoordinator.start()
    }

    fun stopDiscovery() {
        gnssRanging.setEnabled(false)
        wifiRttRanging.stop()
        localRangingSequence.set(0L)
        _hostConnectionLostAtMillis.value = null
        activeDiscoveryEventId = null
        activeDiscoveryPeerId = null
        activeDiscoveryInviteSecret = null
        activeDiscoveryIsHost = null
        activeDiscoveryUseUwb = null
        activeDiscoveryUseWifiDirect = null
        activeDiscoveryUseHotspot = null
        beaconCoordinator.resetForNewEvent()
        nearbyConnectionsManager.stopAll()
        bleAdvertisingCoordinator.stop()
        stopBleScanning()
        orientationTracker.pause()
        rangingCoordinator.stop()
        localBleIdentityKeyPair = null
        localBleIdentityKey = null
        bleIdentityKeys.clear()
        latestBleAdvertisementSequences.clear()
        bleAdvertisingCoordinator.resetAdvertisementSequence()
        peerRadarCoordinator.clearForStop()
        // Leaving, or the host ending the ring, must leave nothing rangeable
        // behind: a member that left is not still a member, and a session that
        // outlived the ring would keep ranging someone who no longer has any
        // right to it.
        // Numbering restarts for the next event; no membership is carried over.
        peerRadarCoordinator.clearPeerDisplayNames()
        _focusedPeerId.value = null
        repositoryScope?.cancel()
        repositoryScope = null
        peerRadarCoordinator.clearPeerUpdates()
    }

    fun broadcastOutgoingChatMessage(text: String, senderName: String, colorArgb: Long?) =
        chatCoordinator.broadcastOutgoingMessage(text, senderName, colorArgb)

    fun acknowledgeVisibleChatMessages(messageIds: List<String>) {
        repositoryScope?.launch {
            chatCoordinator.markVisibleMessagesRead(messageIds)
        }
    }

    fun announcePingRingCreated() = chatCoordinator.announceRingCreated()

    private fun announceMemberJoined(peerId: String) =
        chatCoordinator.announceMemberJoined(peerId)

    private fun renameMemberHistory(peerId: String, name: String) =
        chatCoordinator.renameMemberHistory(peerId, name)

    fun updateLocalUsername(rawName: String) {
        val name = TextInputPolicy.sanitizeUsername(rawName).trim()
        val state = sessionManager.sessionState.value
        require(TextInputPolicy.usernameError(name, state.friends.map { it.name }) == null) {
            "Username must be nonblank and distinct from other members"
        }
        sessionManager.setUserName(name)
        renameMemberHistory(sessionManager.peerId.value, name)
        beaconCoordinator.renamePeer(sessionManager.peerId.value, name)
        state.currentEvent?.let { event ->
            nearbyConnectionsManager.broadcastMemberPresence(
                MeshPresencePayloadDto(event.id, sessionManager.peerId.value, name),
            )
        }
    }

    private fun endBeacon(peerId: String) = beaconCoordinator.endBeacon(peerId)

    fun playBeaconAlert(beaconId: String, pattern: LongArray) = beaconCoordinator.playAlert(beaconId, pattern)

    fun setBeaconSilenced(beaconId: String, silenced: Boolean) = beaconCoordinator.setSilenced(beaconId, silenced)

    fun setBeaconAlertSettings(settings: BeaconAlertSettings) = beaconCoordinator.setAlertSettings(settings)

    fun playVibrationPattern(pattern: LongArray) = beaconCoordinator.playTestPattern(pattern)

    fun stopVibration() = beaconCoordinator.stopVibration()

    fun activateBeacon(title: String, colorArgb: Long, vibrationPattern: String, senderName: String) =
        beaconCoordinator.activate(title, colorArgb, vibrationPattern, senderName)

    fun cancelLocalBeacon() = beaconCoordinator.cancelLocal()

    /**
     * Sends a terminal host packet before the host clears its local session. Nearby members
     * receiving this packet clear their session and navigation returns them to Home.
     *
     * The LAN debug transport has no BLE radio to advertise with (the emulator
     * has none at all), so it is told through [NearbyTransport.announceEventClosed]
     * instead, which makes the relay close the event for its connected members.
     */
    fun announceEventClosure(event: Event) = eventCoordinator.announceEventClosure(event)

    fun broadcastEventControl(event: Event, isClosed: Boolean = false) =
        eventCoordinator.broadcastEventControl(event, isClosed)

    private fun updatePeerFromBleAdvertisement(decoded: BleIdentityAdvertisement.Decoded, rssi: Int) =
        peerRadarCoordinator.updatePeerFromBleAdvertisement(decoded, rssi)

    private fun addConnectedPeer(peerId: String) = peerRadarCoordinator.addConnectedPeer(peerId)
    private fun updateMeshPresence(peerId: String) = peerRadarCoordinator.updateMeshPresence(peerId)
    private fun updatePeerName(peerId: String, name: String) = peerRadarCoordinator.updatePeerName(peerId, name)
    private fun removePeer(peerId: String, preserveLastKnownDirection: Boolean = true) =
        peerRadarCoordinator.removePeer(peerId, preserveLastKnownDirection)
    private fun refreshPeerWorldDirections() = peerRadarCoordinator.refreshPeerWorldDirections()
    private fun updateUwbPosition(peerId: String, result: RangingResult) =
        peerRadarCoordinator.updateUwbPosition(peerId, result)
    private fun hasFreshUwbMeasurement(peerId: String): Boolean =
        peerRadarCoordinator.hasFreshUwbMeasurement(peerId)
    private fun updateGnssDistances() = peerRadarCoordinator.updateGnssDistances()
    private fun updateAdditionalDistance(
        peerId: String,
        distance: Float,
        accuracy: Float,
        source: RangingSourcePolicy.RangingSource,
        measurementAgeMillis: Long = 0L,
    ) = peerRadarCoordinator.updateAdditionalDistance(peerId, distance, accuracy, source, measurementAgeMillis)
    private fun clearAdditionalDistances(source: RangingSourcePolicy.RangingSource) =
        peerRadarCoordinator.clearAdditionalDistances(source)
    private fun updateMockRanging(dto: MockRangingDto) = peerRadarCoordinator.updateMockRanging(dto)

    fun removeMember(peerId: String) = eventCoordinator.removeMember(peerId)

    private companion object {
        const val PEER_CLEANUP_INTERVAL_MILLIS = 5_000L
    }

    private fun startBleScanning() = bleIdentityScanner.start()

    private fun stopBleScanning() = bleIdentityScanner.stop()

    fun pauseSensors() = orientationTracker.pause()

    fun resumeSensors() {
        if (sessionManager.sessionState.value.currentEvent != null) {
            orientationTracker.resume()
        }
    }

    /**
     * Rewrites every peer's world direction from the device orientation.
     *
     * The tracker owns the orientation and decides when it moved far enough to be
     * worth redrawing; only the peers are this class's to update.
     */
    private fun onFocusChanged() {
        _focusedPeerId.update { selectedPeerId -> resolveFocusedPeer(selectedPeerId) }
        rangingCoordinator.onFocusChanged(_focusedPeerId.value)
    }

    private fun localPeerId(): String = sessionManager.peerId.value

    private fun publishPeerRanging(wifiAwareMac: String? = null, gnssFix: GnssFixDto? = null) {
        val event = sessionManager.sessionState.value.currentEvent ?: return
        if (gnssFix != null && !event.useGnss) return
        nearbyConnectionsManager.broadcastPeerRanging(
            PeerRangingPayloadDto(event.id, localPeerId(), localRangingSequence.getAndIncrement(), wifiAwareMac, gnssFix)
        )
    }

}
