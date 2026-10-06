package com.fkurevija.pingring.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only [NearbyTransport] implementation for testing PingRing between
 * the Android Emulator and a physical device, neither of which can reach the
 * other over real Bluetooth/Wi-Fi Direct radios.
 *
 * Instead of device-to-device discovery, every participant opens a single
 * persistent TCP connection to a small relay server run on the developer's
 * machine (see tools/lan_debug_relay.py).
 *
 * Mimics NearbyConnectionsManager join and host approval rules:
 * - Code joins require host approval via [pendingJoinRequests].
 * - QR code joins carrying the valid [InviteSecret] are auto-admitted.
 * - Samples physical device Wi-Fi RSSI to mock realistic radar ranging.
 */
class LanDebugConnectionsManager(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val context: Context? = null,
) : NearbyTransport {
    private val TAG = "LanDebugTransport"
    private val events = TransportEvents(TAG)

    override val receivedPeerAddress: SharedFlow<UwbPeerAddressDto> get() = events.receivedPeerAddress
    override val receivedControllerParams: SharedFlow<UwbControllerParametersDto> get() = events.receivedControllerParams
    override val receivedUwbRangingOffer: SharedFlow<UwbRangingOfferDto> get() = events.receivedUwbRangingOffer
    override val receivedChatMessage: SharedFlow<ChatPayloadDto> get() = events.receivedChatMessage
    override val receivedChatHistory: SharedFlow<ChatHistoryPayloadDto> get() = events.receivedChatHistory
    override val connectionEstablished: SharedFlow<String> get() = events.connectionEstablished
    override val peerDisconnected: SharedFlow<String> get() = events.peerDisconnected
    override val eventClosed: SharedFlow<Unit> get() = events.eventClosed
    override val receivedBeacon: SharedFlow<BeaconPayloadDto> get() = events.receivedBeacon
    override val receivedInviteSecret: SharedFlow<InviteSecretPayloadDto> get() = events.receivedInviteSecret
    override val receivedBleIdentityKey: SharedFlow<BleIdentityKeyPayloadDto> get() = events.receivedBleIdentityKey
    override val receivedMemberDeparture: SharedFlow<MemberDeparturePayloadDto> get() = events.receivedMemberDeparture
    override val receivedMeshPresence: SharedFlow<MeshPresencePayloadDto> get() = events.receivedMeshPresence
    override val receivedPeerRanging: SharedFlow<PeerRangingPayloadDto> get() = events.receivedPeerRanging
    override val receivedChatColourClaim: SharedFlow<ChatColourClaimPayloadDto> get() = events.receivedChatColourClaim
    override val receivedEventControl: SharedFlow<EventControlPayloadDto> get() = events.receivedEventControl
    override val receivedMockRanging: SharedFlow<MockRangingDto> get() = events.receivedMockRanging

    override var acceptsNewMembers: Boolean = true
    override var inviteSecretProvider: (() -> String?)? = null

    private val _pendingJoinRequests = MutableStateFlow<List<PendingJoinRequest>>(emptyList())
    override val pendingJoinRequests: StateFlow<List<PendingJoinRequest>> = _pendingJoinRequests.asStateFlow()
    override val pendingAuthentications: StateFlow<List<ConnectionAuthenticationRequest>> = MutableStateFlow(emptyList())

    private val authorizedPeers = mutableSetOf<String>()
    private val pendingJoinProofs = mutableMapOf<String, JoinProofPayloadDto>()

    override fun approveJoinRequest(endpointId: String) {
        Log.i(TAG, "Host approved join request for $endpointId")
        _pendingJoinRequests.update { current -> current.filterNot { it.endpointId == endpointId } }
        pendingJoinProofs.remove(endpointId)
        authorizedPeers.add(endpointId)
        val secret = inviteSecretProvider?.invoke()
        if (secret != null) {
            sendPayload(endpointId, InviteSecretPayloadDto(localEventId, localPeerId, secret))
        }
        events.onPeerConnected(endpointId)
    }

    override fun denyJoinRequest(endpointId: String) {
        Log.i(TAG, "Host denied join request for $endpointId")
        _pendingJoinRequests.update { current -> current.filterNot { it.endpointId == endpointId } }
        pendingJoinProofs.remove(endpointId)
        sendPayload(
            endpointId,
            EventControlPayloadDto(
                eventCode = localEventId,
                hostPeerId = localPeerId,
                useUwb = true,
                useWifiDirect = true,
                acceptsNewMembers = acceptsNewMembers,
                isFull = false,
                isClosed = true
            )
        )
    }

    override fun confirmConnectionAuthentication(endpointId: String) = Unit
    override fun rejectConnectionAuthentication(endpointId: String) = Unit

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var rssiJob: Job? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()

    private val connectionGeneration = AtomicInteger(0)
    private val pendingEnvelopes = ArrayDeque<RelayEnvelope>()

    private var localPeerId: String = ""
    private var localEventId: String = ""
    private var localRole: String = RelayEnvelope.ROLE_MEMBER

    @Volatile
    private var eventClosedByRelay = false

    override fun startAdvertising(eventId: String, localPeerId: String, useWifiDirect: Boolean, useHotspot: Boolean) {
        connect(eventId, localPeerId, RelayEnvelope.ROLE_HOST)
    }

    override fun startDiscovery(eventId: String, localPeerId: String) {
        connect(eventId, localPeerId, RelayEnvelope.ROLE_MEMBER)
    }

    override fun connectToPeer(peerId: String) = Unit

    override fun announceEventClosed() {
        eventClosedByRelay = true
        val envelope = RelayEnvelope(
            type = RelayEnvelope.END,
            eventId = localEventId,
            senderPeerId = localPeerId
        )
        val activeWriter = writer
        val sent = if (activeWriter == null) {
            false
        } else {
            runBlocking(Dispatchers.IO) { writeEnvelopeNow(activeWriter, envelope) }
        }
        Log.i(TAG, "Announced event closure for $localEventId (sent=$sent)")
    }

    private fun connect(eventId: String, localPeerId: String, role: String) {
        if (eventId == this.localEventId && localPeerId == this.localPeerId && role == this.localRole && connectionJob?.isActive == true) {
            Log.d(TAG, "Already connecting/connected for event $eventId as $localPeerId, ignoring duplicate request")
            return
        }
        Log.i(TAG, "connect() called for event $eventId as $localPeerId/$role")
        this.localEventId = eventId
        this.localPeerId = localPeerId
        this.localRole = role
        eventClosedByRelay = false
        stopAll()
        val myGeneration = connectionGeneration.incrementAndGet()
        Log.i(TAG, "Starting connection attempt generation $myGeneration")
        connectionJob = scope.launch {
            while (isActive) {
                try {
                    Log.d(TAG, "Connecting to relay at $host:$port for event $eventId as $localPeerId (gen $myGeneration)")
                    val newSocket = Socket(host, port)
                    if (connectionGeneration.get() != myGeneration) {
                        Log.w(TAG, "Generation $myGeneration superseded, discarding")
                        runCatching { newSocket.close() }
                        break
                    }
                    socket = newSocket
                    val newWriter = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream()))
                    writer = newWriter
                    Log.i(TAG, "Connected to relay, joining event $eventId as $localPeerId/$localRole (gen $myGeneration)")
                    writeEnvelopeNow(
                        newWriter,
                        RelayEnvelope(
                            type = RelayEnvelope.JOIN,
                            eventId = eventId,
                            senderPeerId = localPeerId,
                            role = localRole
                        )
                    )
                    flushPendingEnvelopes()

                    if (localRole == RelayEnvelope.ROLE_MEMBER) {
                        val proof = JoinProofPayloadDto(
                            eventCode = localEventId,
                            inviteSecret = inviteSecretProvider?.invoke()
                        )
                        sendPayload(null, proof)
                    }
                    startRssiSampling(myGeneration)

                    val heartbeatJob = scope.launch {
                        while (isActive) {
                            delay(HEARTBEAT_INTERVAL_MILLIS)
                            if (connectionGeneration.get() != myGeneration) break
                            val currentWriter = writer ?: break
                            if (!writeEnvelopeNow(currentWriter, RelayEnvelope(type = RelayEnvelope.PING, eventId = eventId, senderPeerId = localPeerId))) {
                                break
                            }
                        }
                    }
                    try {
                        val reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            Log.d(TAG, "Received ${line.length} characters from relay")
                            handleLine(line)
                            if (eventClosedByRelay) {
                                Log.i(TAG, "Relay closed event $eventId; not retrying (gen $myGeneration)")
                                break
                            }
                        }
                        Log.w(TAG, "Relay connection closed, retrying… (gen $myGeneration)")
                    } finally {
                        heartbeatJob.cancel()
                    }
                } catch (e: SocketException) {
                    Log.w(TAG, "Relay socket error: ${e.message} (gen $myGeneration)")
                } catch (e: Exception) {
                    Log.e(TAG, "Relay connection failed (gen $myGeneration)", e)
                }
                if (eventClosedByRelay) {
                    Log.i(TAG, "Event was closed by host, stopping retry loop (gen $myGeneration)")
                    break
                }
                if (connectionGeneration.get() != myGeneration) {
                    Log.w(TAG, "Generation $myGeneration superseded after disconnect, stopping retry loop")
                    break
                }
                socket = null
                writer = null
                delay(RECONNECT_DELAY_MILLIS)
            }
        }
    }

    override fun stopAll() {
        val newGen = connectionGeneration.incrementAndGet()
        Log.i(TAG, "stopAll() called, bumping generation to $newGen")
        connectionJob?.cancel()
        connectionJob = null
        rssiJob?.cancel()
        rssiJob = null
        runCatching { socket?.close() }
        socket = null
        writer = null
    }

    private fun handleLine(line: String) {
        val envelope = RelayCodec.decodeEnvelope(line).getOrNull() ?: return
        when (envelope.type) {
            RelayEnvelope.PEER_JOINED -> {
                val senderId = envelope.senderPeerId ?: return
                Log.i(TAG, "Peer joined relay: $senderId")
                if (localRole == RelayEnvelope.ROLE_MEMBER) {
                    val proof = JoinProofPayloadDto(
                        eventCode = localEventId,
                        inviteSecret = inviteSecretProvider?.invoke()
                    )
                    sendPayload(senderId, proof)
                } else if (localRole == RelayEnvelope.ROLE_HOST && senderId in authorizedPeers) {
                    events.onPeerConnected(senderId)
                }
            }
            RelayEnvelope.PEER_LEFT -> {
                val senderId = envelope.senderPeerId ?: return
                Log.i(TAG, "Peer left relay: $senderId")
                authorizedPeers.remove(senderId)
                _pendingJoinRequests.update { current -> current.filterNot { it.endpointId == senderId } }
                pendingJoinProofs.remove(senderId)
                events.onPeerDisconnected(senderId)
            }
            RelayEnvelope.EVENT_CLOSED -> {
                Log.i(TAG, "Event ${envelope.eventId} was closed by host ${envelope.senderPeerId}")
                eventClosedByRelay = true
                events.onEventClosed()
            }
            RelayEnvelope.DATA -> {
                val json = envelope.payload ?: return
                val wrapped = RelayCodec.decodeEnvelope(json).getOrNull()?.takeIf { it.payload != null }
                if (wrapped != null) {
                    deliver(wrapped, envelope.senderPeerId.orEmpty())
                } else {
                    RelayCodec.decodeUnwrapped(json)?.let { deliver(it, envelope.senderPeerId.orEmpty()) }
                }
            }
        }
    }

    private fun deliver(envelope: RelayEnvelope, fallbackSenderId: String): Boolean {
        val body = envelope.payload ?: return false
        val sender = envelope.senderPeerId?.ifBlank { fallbackSenderId } ?: fallbackSenderId
        val payload = RelayCodec.decode(envelope.type, body)?.getOrNull() ?: return false
        return deliver(payload, sender)
    }

    private fun deliver(payload: DecodedPayload, senderPeerId: String): Boolean = when (payload) {
        is DecodedPayload.JoinProof -> {
            receiveJoinProof(payload.proof, senderPeerId)
            true
        }
        is DecodedPayload.InviteSecret -> {
            receiveInviteSecret(payload.secret, senderPeerId)
            true
        }
        is DecodedPayload.EventControl -> {
            if (payload.control.isClosed) {
                Log.w(TAG, "Event control closed or refused our connection")
                eventClosedByRelay = true
                events.onEventClosed()
            } else {
                RelayCodec.route(payload, events)
            }
            true
        }
        is DecodedPayload.UwbOffer -> {
            events.onUwbRangingOfferReceived(payload.offer)
            true
        }
        is DecodedPayload.MockRanging -> {
            events.onMockRanging(payload.report)
            true
        }
        else -> RelayCodec.route(payload, events)
    }

    private fun receiveJoinProof(proof: JoinProofPayloadDto, senderPeerId: String) {
        if (localRole != RelayEnvelope.ROLE_HOST || proof.eventCode != localEventId || senderPeerId.isBlank()) return
        if (senderPeerId in authorizedPeers) {
            val secret = inviteSecretProvider?.invoke()
            if (secret != null) {
                sendPayload(senderPeerId, InviteSecretPayloadDto(localEventId, localPeerId, secret))
            }
            events.onPeerConnected(senderPeerId)
            return
        }

        val expectedSecret = inviteSecretProvider?.invoke()
        val hasValidInvite = expectedSecret != null && proof.inviteSecret == expectedSecret &&
            InviteSecret.decode(proof.inviteSecret.orEmpty()) != null

        val decision = decideJoinRequest(
            isIncoming = true,
            requiresApproval = joinRequiresHostApproval(hasValidInvite),
            acceptsNewMembers = acceptsNewMembers,
            connectedPeerCount = authorizedPeers.size + _pendingJoinRequests.value.size
        )

        when (decision) {
            JoinRequestDecision.Refuse -> {
                Log.i(TAG, "Refusing join request from $senderPeerId")
                sendPayload(
                    senderPeerId,
                    EventControlPayloadDto(
                        eventCode = localEventId,
                        hostPeerId = localPeerId,
                        useUwb = true,
                        useWifiDirect = true,
                        acceptsNewMembers = acceptsNewMembers,
                        isFull = true,
                        isClosed = true
                    )
                )
            }
            JoinRequestDecision.Admit -> {
                Log.i(TAG, "Auto-admitting $senderPeerId (valid QR invite secret)")
                authorizedPeers.add(senderPeerId)
                val secret = inviteSecretProvider?.invoke()
                if (secret != null) {
                    sendPayload(senderPeerId, InviteSecretPayloadDto(localEventId, localPeerId, secret))
                }
                events.onPeerConnected(senderPeerId)
            }
            JoinRequestDecision.AwaitApproval -> {
                Log.i(TAG, "Holding join request from $senderPeerId for host approval")
                pendingJoinProofs[senderPeerId] = proof
                val request = PendingJoinRequest(
                    endpointId = senderPeerId,
                    deviceName = senderPeerId.ifBlank { "Guest device" },
                    requestedAtMillis = System.currentTimeMillis()
                )
                _pendingJoinRequests.update { current ->
                    (current.filterNot { it.endpointId == senderPeerId } + request)
                        .sortedBy { it.requestedAtMillis }
                }
            }
        }
    }

    private fun receiveInviteSecret(secret: InviteSecretPayloadDto, senderPeerId: String) {
        if (localRole == RelayEnvelope.ROLE_HOST) return
        if (secret.eventCode != localEventId) return
        if (InviteSecret.decode(secret.inviteSecret) == null) return
        val existingSecret = inviteSecretProvider?.invoke()
        if (existingSecret != null && existingSecret != secret.inviteSecret) return
        authorizedPeers.add(senderPeerId)
        events.onPeerConnected(senderPeerId)
        events.onInviteSecret(secret)
    }

    private fun startRssiSampling(myGeneration: Int) {
        rssiJob?.cancel()
        rssiJob = scope.launch {
            while (isActive && connectionGeneration.get() == myGeneration) {
                delay(2_500L)
                if (socket?.isConnected == true && localPeerId.isNotBlank() && localEventId.isNotBlank()) {
                    val wifiRssi = measureWifiRssi()
                    sendPayload(null, MockRangingDto(peerId = localPeerId, rssi = wifiRssi))
                }
            }
        }
    }

    private fun measureWifiRssi(): Int {
        val ctx = context ?: return -65
        val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val infoRssi = wifiManager?.connectionInfo?.rssi
        return if (infoRssi != null && infoRssi in -100..-20) {
            infoRssi
        } else {
            -65
        }
    }

    private fun sendEnvelope(envelope: RelayEnvelope, onComplete: (() -> Unit)? = null) {
        scope.launch {
            val activeWriter = writer
            if (activeWriter == null) {
                if (onComplete != null) {
                    Log.w(TAG, "Not connected to relay; cannot send ${envelope.type} before shutdown")
                    onComplete()
                    return@launch
                }
                Log.w(TAG, "Not connected to relay, queuing ${envelope.type} envelope")
                synchronized(writeLock) { pendingEnvelopes.addLast(envelope) }
                return@launch
            }
            if (!writeEnvelopeNow(activeWriter, envelope)) {
                synchronized(writeLock) { pendingEnvelopes.addLast(envelope) }
            }
            onComplete?.invoke()
        }
    }

    private fun writeEnvelopeNow(activeWriter: BufferedWriter, envelope: RelayEnvelope): Boolean {
        val json = RelayCodec.encodeEnvelope(envelope)
        synchronized(writeLock) {
            return try {
                activeWriter.write(json)
                activeWriter.newLine()
                activeWriter.flush()
                Log.d(TAG, "Sent ${json.length} characters to relay")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send to relay, queuing for retry", e)
                false
            }
        }
    }

    private fun flushPendingEnvelopes() {
        val toFlush = synchronized(writeLock) {
            val copy = pendingEnvelopes.toList()
            pendingEnvelopes.clear()
            copy
        }
        if (toFlush.isNotEmpty()) {
            Log.i(TAG, "Flushing ${toFlush.size} queued envelope(s) after reconnect")
        }
        toFlush.forEach { sendEnvelope(it) }
    }

    private fun sendPayload(targetPeerId: String?, params: Any, onComplete: (() -> Unit)? = null) {
        val encoded = RelayCodec.encode(params) ?: run { onComplete?.invoke(); return }
        val innerEnvelope = RelayEnvelope(type = encoded.wireType, payload = encoded.json)
        val innerJson = RelayCodec.encodeEnvelope(innerEnvelope)
        sendEnvelope(
            RelayEnvelope(
                type = RelayEnvelope.DATA,
                eventId = localEventId,
                senderPeerId = localPeerId,
                targetPeerId = targetPeerId,
                payload = innerJson
            ),
            onComplete,
        )
    }

    override fun sendUwbParameters(peerId: String, params: Any) {
        sendPayload(peerId, params)
    }

    override fun sendUwbRangingOfferToHost(params: Any) {
        val target = (params as? UwbRangingOfferDto)?.targetPeerId
        if (target == null) {
            Log.w(TAG, "Cannot send a UWB ranging offer without a target peer")
            return
        }
        sendPayload(target, params)
    }

    override fun broadcastUwbParameters(params: Any) {
        sendPayload(null, params)
    }

    override fun broadcastChatColourClaim(claim: ChatColourClaimPayloadDto) {
        sendPayload(null, claim)
    }

    override fun broadcastChatMessage(chatMsg: ChatPayloadDto) {
        sendPayload(null, chatMsg)
    }

    override fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>) {
        sendPayload(peerId, ChatHistoryPayloadDto(messages))
    }

    override fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto) {
        sendPayload(peerId, chatMsg)
    }

    override fun sendBeaconToPeer(peerId: String, beacon: BeaconPayloadDto) {
        sendPayload(peerId, beacon)
    }

    override fun sendInviteSecret(peerId: String, secret: InviteSecretPayloadDto) {
        sendPayload(peerId, secret)
    }

    override fun sendBleIdentityKey(peerId: String, identityKey: BleIdentityKeyPayloadDto) {
        sendPayload(peerId, identityKey)
    }

    override fun broadcastBleIdentityKey(identityKey: BleIdentityKeyPayloadDto) {
        sendPayload(null, identityKey)
    }

    override fun announceMemberDeparture(departure: MemberDeparturePayloadDto, onComplete: () -> Unit) {
        sendPayload(null, departure, onComplete)
    }

    override fun broadcastMemberDeparture(departure: MemberDeparturePayloadDto) {
        sendPayload(null, departure)
    }

    override fun sendEventControl(peerId: String, control: EventControlPayloadDto) {
        sendPayload(peerId, control)
    }

    override fun sendJoinProofToHost(proof: JoinProofPayloadDto) {
        sendPayload(null, proof)
    }

    override fun broadcastBeacon(beacon: BeaconPayloadDto) {
        sendPayload(null, beacon)
    }

    override fun broadcastPeerRanging(ranging: PeerRangingPayloadDto) {
        sendPayload(null, ranging)
    }

    companion object {
        const val DEFAULT_PORT = 8942
        private const val RECONNECT_DELAY_MILLIS = 2000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 10000L
    }
}
