package com.fkurevija.pingring.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.fkurevija.pingring.data.transport.LanDebugJoinAdmission
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.fkurevija.pingring.data.chat.BeaconPayloadDto
import com.fkurevija.pingring.data.chat.ChatHistoryPayloadDto
import com.fkurevija.pingring.data.chat.ChatPayloadDto
import com.fkurevija.pingring.data.chat.ChatReceiptPayloadDto
import com.fkurevija.pingring.data.chat.MessageStatus
import com.fkurevija.pingring.data.radar.PeerRangingPayloadDto
import com.fkurevija.pingring.data.ranging.MockRangingDto
import com.fkurevija.pingring.data.ranging.UwbControllerParametersDto
import com.fkurevija.pingring.data.ranging.UwbPeerAddressDto
import com.fkurevija.pingring.data.ranging.UwbRangingOfferDto
import com.fkurevija.pingring.data.session.BleIdentityKeyPayloadDto
import com.fkurevija.pingring.data.session.ChatColourClaimPayloadDto
import com.fkurevija.pingring.data.session.Event
import com.fkurevija.pingring.data.session.EventControlPayloadDto
import com.fkurevija.pingring.data.session.InviteSecret
import com.fkurevija.pingring.data.session.InviteSecretPayloadDto
import com.fkurevija.pingring.data.session.JoinProofPayloadDto
import com.fkurevija.pingring.data.session.MemberDeparturePayloadDto
import com.fkurevija.pingring.data.session.MeshPresencePayloadDto

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
 * - Uses physical device Wi-Fi RSSI as a local proxy for admitted peers.
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
    override val receivedChatReceipt: SharedFlow<ChatReceiptPayloadDto> get() = events.receivedChatReceipt
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
    override val peerNames: SharedFlow<Pair<String, String>> get() = events.peerNames

    override var acceptsNewMembers: Boolean = true
    override var inviteSecretProvider: (() -> String?)? = null
    override var userNameProvider: (() -> String?)? = null

    private val authorizedPeers = ConcurrentHashMap.newKeySet<String>()
    private val joinAdmission = LanDebugJoinAdmission(
        state = {
            LanDebugJoinAdmission.State(
                localEventId = localEventId,
                localPeerId = localPeerId,
                localRole = localRole,
                acceptsNewMembers = acceptsNewMembers,
                inviteSecretProvider = { inviteSecretProvider?.invoke() },
                userNameProvider = { userNameProvider?.invoke() },
            )
        },
        authorizedPeers = authorizedPeers,
        callbacks = LanDebugJoinAdmission.Callbacks(
            sendPayload = { targetPeerId, payload -> sendPayload(targetPeerId, payload) },
            onPeerName = events::onPeerName,
            onPeerConnected = events::onPeerConnected,
            onInviteSecret = events::onInviteSecret,
            logInfo = { Log.i(TAG, it) },
        ),
    )
    override val pendingJoinRequests: StateFlow<List<PendingJoinRequest>> get() = joinAdmission.pendingJoinRequests
    override val pendingAuthentications: StateFlow<List<ConnectionAuthenticationRequest>> =
        events.pendingAuthentications

    private val wifiRssiProxy = LanDebugWifiRssiProxy(context)

    override fun approveJoinRequest(endpointId: String) {
        joinAdmission.approveJoinRequest(endpointId)
    }

    override fun denyJoinRequest(endpointId: String) {
        joinAdmission.denyJoinRequest(endpointId)
    }

    override fun confirmConnectionAuthentication(endpointId: String) = Unit

    override fun rejectConnectionAuthentication(endpointId: String) = Unit

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var rssiJob: Job? = null
    private val socketLifecycle = LanDebugSocketLifecycle(host, port, scope, TAG)
    private val connectionGeneration = AtomicInteger(0)

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
        val sent = runBlocking(Dispatchers.IO) { socketLifecycle.announce(envelope) }
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
            socketLifecycle.run(
                generation = myGeneration,
                isCurrentGeneration = { connectionGeneration.get() == it },
                eventWasClosed = { eventClosedByRelay },
                joinEnvelope = RelayEnvelope(
                    type = RelayEnvelope.JOIN,
                    eventId = eventId,
                    senderPeerId = localPeerId,
                    role = localRole
                ),
                heartbeatEnvelope = {
                    RelayEnvelope(type = RelayEnvelope.PING, eventId = eventId, senderPeerId = localPeerId)
                },
                onConnected = {
                    if (localRole == RelayEnvelope.ROLE_MEMBER) {
                        delay(300L)
                        sendLocalJoinProof(null)
                    }
                    startRssiSampling(myGeneration)
                },
                onLine = ::handleLine,
                onHeartbeat = ::publishMemberPresence,
                reconnectDelayMillis = RECONNECT_DELAY_MILLIS,
                heartbeatIntervalMillis = HEARTBEAT_INTERVAL_MILLIS,
            )
        }
    }

    override fun stopAll() {
        val newGen = connectionGeneration.incrementAndGet()
        Log.i(TAG, "stopAll() called, bumping generation to $newGen")
        connectionJob?.cancel()
        connectionJob = null
        rssiJob?.cancel()
        rssiJob = null
        socketLifecycle.close()
        authorizedPeers.clear()
        wifiRssiProxy.reset()
    }

    private fun handleLine(line: String) {
        val envelope = RelayCodec.decodeEnvelope(line).getOrNull() ?: return
        when (envelope.type) {
            RelayEnvelope.PEER_JOINED -> {
                val senderId = envelope.senderPeerId ?: return
                Log.i(TAG, "Peer joined relay: $senderId")
                if (localRole == RelayEnvelope.ROLE_MEMBER) {
                    sendLocalJoinProof(senderId)
                } else if (localRole == RelayEnvelope.ROLE_HOST && senderId in authorizedPeers) {
                    events.onPeerConnected(senderId)
                }
            }
            RelayEnvelope.PEER_LEFT -> {
                val senderId = envelope.senderPeerId ?: return
                Log.i(TAG, "Peer left relay: $senderId")
                authorizedPeers.remove(senderId)
                wifiRssiProxy.removePeer(senderId)
                joinAdmission.removePeer(senderId)
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

    private fun sendLocalJoinProof(targetPeerId: String?) {
        joinAdmission.sendLocalJoinProof(targetPeerId)
    }

    private fun deliver(envelope: RelayEnvelope, fallbackSenderId: String): Boolean {
        val body = envelope.payload ?: return false
        val sender = envelope.senderPeerId?.ifBlank { fallbackSenderId } ?: fallbackSenderId
        val payload = RelayCodec.decode(envelope.type, body)?.getOrNull() ?: return false
        return deliver(payload, sender)
    }

    private fun deliver(payload: DecodedPayload, senderPeerId: String): Boolean = when (payload) {
        is DecodedPayload.JoinProof -> {
            joinAdmission.receiveJoinProof(payload.proof, senderPeerId)
            true
        }
        is DecodedPayload.InviteSecret -> {
            joinAdmission.receiveInviteSecret(payload.secret, senderPeerId)
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
            wifiRssiProxy.markScriptedPeer(payload.report.peerId)
            events.onMockRanging(payload.report)
            true
        }
        is DecodedPayload.MeshPresence -> {
            if (senderPeerId in authorizedPeers &&
                payload.presence.peerId == senderPeerId &&
                payload.presence.eventCode == localEventId
            ) {
                events.onMeshPresence(payload.presence)
                true
            } else {
                Log.w(TAG, "Ignoring member presence from an unadmitted or mismatched peer")
                false
            }
        }
        is DecodedPayload.ChatReceipt -> {
            if (senderPeerId in authorizedPeers &&
                payload.receipt.recipientPeerId == senderPeerId &&
                payload.receipt.eventCode == localEventId &&
                payload.receipt.status != MessageStatus.SENT
            ) {
                events.onChatReceipt(payload.receipt)
                true
            } else {
                Log.w(TAG, "Ignoring chat receipt from an unadmitted or mismatched peer")
                false
            }
        }
        else -> RelayCodec.route(payload, events)
    }


    private fun startRssiSampling(myGeneration: Int) {
        rssiJob?.cancel()
        rssiJob = wifiRssiProxy.startSampling(
            scope = scope,
            shouldContinue = { connectionGeneration.get() == myGeneration },
            shouldSample = {
                socketLifecycle.currentWriter() != null && localPeerId.isNotBlank() && localEventId.isNotBlank()
            },
            onSample = ::publishWifiRssi,
        )
    }

    internal fun publishWifiRssi(rssi: Int?) {
        wifiRssiProxy.reportsFor(rssi, authorizedPeers, localPeerId).forEach(events::onMockRanging)
    }

    private fun sendEnvelope(envelope: RelayEnvelope, onComplete: (() -> Unit)? = null) {
        socketLifecycle.send(envelope, onComplete)
    }

    private fun sendPayload(targetPeerId: String?, params: Any, onComplete: (() -> Unit)? = null) {
        val envelope = payloadEnvelope(targetPeerId, params) ?: run { onComplete?.invoke(); return }
        sendEnvelope(envelope, onComplete)
    }

    private fun payloadEnvelope(targetPeerId: String?, params: Any): RelayEnvelope? {
        val encoded = RelayCodec.encode(params) ?: return null
        val innerEnvelope = RelayEnvelope(type = encoded.wireType, payload = encoded.json)
        val innerJson = RelayCodec.encodeEnvelope(innerEnvelope)
        return RelayEnvelope(
            type = RelayEnvelope.DATA,
            eventId = localEventId,
            senderPeerId = localPeerId,
            targetPeerId = targetPeerId,
            payload = innerJson,
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

    override fun broadcastChatHistory(messages: List<ChatPayloadDto>) {
        sendPayload(null, ChatHistoryPayloadDto(messages))
    }

    override fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>) {
        sendPayload(peerId, ChatHistoryPayloadDto(messages))
    }

    override fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto) {
        sendPayload(peerId, chatMsg)
    }

    override fun sendChatReceiptToPeer(peerId: String, receipt: ChatReceiptPayloadDto) {
        sendPayload(peerId, receipt)
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

    override fun removeMember(
        peerId: String,
        departure: MemberDeparturePayloadDto,
        removalControl: EventControlPayloadDto,
    ) {
        if (localRole != RelayEnvelope.ROLE_HOST || peerId == localPeerId ||
            departure.peerId != peerId || departure.eventCode != localEventId ||
            removalControl.eventCode != localEventId || !removalControl.isClosed
        ) {
            Log.w(TAG, "Ignoring invalid LAN member removal request")
            return
        }
        events.onMemberDeparture(departure)
        authorizedPeers.remove(peerId)
        wifiRssiProxy.removePeer(peerId)
        // Keep relay notifications best-effort: local membership is removed
        // above and is never contingent on a live relay or guest connection.
        socketLifecycle.sendInOrder(
            listOfNotNull(
                payloadEnvelope(null, departure),
                payloadEnvelope(peerId, removalControl),
            ),
        )
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

    override fun broadcastMemberPresence(presence: MeshPresencePayloadDto) {
        authorizedPeers.forEach { peerId -> sendPayload(peerId, presence) }
    }

    private fun publishMemberPresence() {
        broadcastMemberPresence(MeshPresencePayloadDto(localEventId, localPeerId, userNameProvider?.invoke()))
    }

    companion object {
        const val DEFAULT_PORT = 8942
        internal const val RSSI_SAMPLE_INTERVAL_MILLIS = LanDebugWifiRssiProxy.SAMPLE_INTERVAL_MILLIS
        private const val RECONNECT_DELAY_MILLIS = 2000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 10000L
    }
}
