package com.fkurevija.pingring.data

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class NearbyConnectionsManager(private val context: Context) : NearbyTransport {
    private val TAG = "NearbyConnections"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val envelopeAdapter = moshi.adapter(RelayEnvelope::class.java)

    private val _receivedPeerAddress = MutableSharedFlow<UwbPeerAddressDto>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val receivedPeerAddress = _receivedPeerAddress.asSharedFlow()

    private val _receivedControllerParams = MutableSharedFlow<UwbControllerParametersDto>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val receivedControllerParams = _receivedControllerParams.asSharedFlow()

    private val _receivedChatMessage = MutableSharedFlow<ChatPayloadDto>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val receivedChatMessage = _receivedChatMessage.asSharedFlow()

    private val _receivedChatHistory = MutableSharedFlow<ChatHistoryPayloadDto>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val receivedChatHistory = _receivedChatHistory.asSharedFlow()

    private val _connectionEstablished = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val connectionEstablished = _connectionEstablished.asSharedFlow()

    private val _peerDisconnected = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val peerDisconnected = _peerDisconnected.asSharedFlow()

    /**
     * Nearby Connections has no server-side equivalent of "the event is over":
     * peers learn it from the host's BLE closure advertisement, which
     * [DiscoveryRepository] already watches for, so nothing is ever emitted
     * here.
     */
    override val eventClosed: SharedFlow<Unit> = MutableSharedFlow()

    private val _receivedNudge = MutableSharedFlow<NudgePayloadDto>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val receivedNudge = _receivedNudge.asSharedFlow()

    private val _receivedBeacon = MutableSharedFlow<BeaconPayloadDto>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val receivedBeacon = _receivedBeacon.asSharedFlow()

    /**
     * Nearby Connections has no way of being told a distance that was not
     * measured by this device's own UWB/BLE radios, so nothing is ever emitted
     * here. Scripted ranges only exist for the LAN debug transport, which gets
     * them from tools/mock_radar.py.
     */
    override val receivedMockRanging: SharedFlow<MockRangingDto> = MutableSharedFlow()

    private val connectedEndpoints = mutableMapOf<String, String>()
    private val endpointToPeerId = mutableMapOf<String, String>()
    private var localPeerId: String = ""
    override var acceptsNewMembers: Boolean = true

    private fun getServiceId(eventId: String): String = "com.fkurevija.pingring.$eventId"

    override fun startAdvertising(eventId: String, localPeerId: String, useWifiDirect: Boolean, useHotspot: Boolean) {
        this.localPeerId = localPeerId
        val serviceId = getServiceId(eventId)
        Log.i(TAG, "startAdvertising: serviceId=$serviceId, localPeerId=$localPeerId")
        val optionsBuilder = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)

        connectionsClient.startAdvertising(
            localPeerId,
            serviceId,
            connectionLifecycleCallback,
            optionsBuilder.build()
        ).addOnSuccessListener {
            Log.i(TAG, "Advertising started successfully for $serviceId as $localPeerId")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed for $serviceId", e)
        }
    }

    override fun startDiscovery(eventId: String, localPeerId: String) {
        this.localPeerId = localPeerId
        val serviceId = getServiceId(eventId)
        Log.i(TAG, "startDiscovery: serviceId=$serviceId, localPeerId=$localPeerId")
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.i(TAG, "Discovery started successfully for $serviceId as $localPeerId")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed for $serviceId", e)
        }
    }

    override fun stopAll() {
        Log.i(TAG, "stopAll called; stopping advertising/discovery and clearing connected endpoints")
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        endpointToPeerId.clear()
    }

    /**
     * Nothing to do: peers of a real host find out the PingRing is over from the
     * BLE closure advertisement that [DiscoveryRepository.announceEventClosure]
     * broadcasts. The LAN debug transport is the one that needs to tell the
     * relay instead.
     */
    override fun announceEventClosed() = Unit

    override fun sendUwbParameters(peerId: String, params: Any) {
        val endpointId = connectedEndpoints[peerId] ?: return
        sendPayloadToEndpoint(endpointId, params)
    }

    override fun broadcastUwbParameters(params: Any) {
        val endpoints = connectedEndpoints.values.toSet()
        endpoints.forEach { endpointId ->
            sendPayloadToEndpoint(endpointId, params)
        }
    }

    override fun broadcastChatMessage(chatMsg: ChatPayloadDto) {
        val endpoints = connectedEndpoints.values.toSet()
        Log.i(TAG, "Broadcasting chat message '${chatMsg.text}' (id=${chatMsg.id}) to ${endpoints.size} endpoint(s): $endpoints")
        endpoints.forEach { endpointId ->
            sendPayloadToEndpoint(endpointId, chatMsg)
        }
    }

    override fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>) {
        val endpointId = connectedEndpoints[peerId]
        if (endpointId == null) {
            Log.w(TAG, "Cannot send chat history: peer $peerId is not connected. Connected endpoints: $connectedEndpoints")
            return
        }
        Log.i(TAG, "Sending chat history (${messages.size} msgs) to peer $peerId at endpoint $endpointId")
        sendPayloadToEndpoint(endpointId, ChatHistoryPayloadDto(messages))
    }

    override fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto) {
        val endpointId = connectedEndpoints[peerId]
        if (endpointId == null) {
            Log.w(TAG, "Cannot send chat message to peer $peerId: not in connectedEndpoints")
            return
        }
        sendPayloadToEndpoint(endpointId, chatMsg)
    }

    override fun sendNudgeToPeer(peerId: String, nudge: NudgePayloadDto) {
        val endpointId = connectedEndpoints[peerId] ?: return
        sendPayloadToEndpoint(endpointId, nudge)
    }

    override fun broadcastNudge(nudge: NudgePayloadDto) {
        val endpoints = connectedEndpoints.values.toSet()
        Log.i(TAG, "Broadcasting nudge from ${nudge.senderName} to ${endpoints.size} endpoint(s)")
        endpoints.forEach { endpointId ->
            sendPayloadToEndpoint(endpointId, nudge)
        }
    }

    override fun sendBeaconToPeer(peerId: String, beacon: BeaconPayloadDto) {
        val endpointId = connectedEndpoints[peerId] ?: return
        sendPayloadToEndpoint(endpointId, beacon)
    }

    override fun broadcastBeacon(beacon: BeaconPayloadDto) {
        val endpoints = connectedEndpoints.values.toSet()
        Log.i(TAG, "Broadcasting beacon '${beacon.title}' to ${endpoints.size} endpoint(s)")
        endpoints.forEach { endpointId ->
            sendPayloadToEndpoint(endpointId, beacon)
        }
    }

    private fun sendPayloadToEndpoint(endpointId: String, params: Any) {
        val (type, dtoJson) = when (params) {
            is ChatPayloadDto -> "chat" to moshi.adapter(ChatPayloadDto::class.java).toJson(params)
            is ChatHistoryPayloadDto -> "chat-history" to moshi.adapter(ChatHistoryPayloadDto::class.java).toJson(params)
            is NudgePayloadDto -> "nudge" to moshi.adapter(NudgePayloadDto::class.java).toJson(params)
            is BeaconPayloadDto -> "beacon" to moshi.adapter(BeaconPayloadDto::class.java).toJson(params)
            is UwbPeerAddressDto -> "uwb-address" to moshi.adapter(UwbPeerAddressDto::class.java).toJson(params)
            is UwbControllerParametersDto -> "uwb-params" to moshi.adapter(UwbControllerParametersDto::class.java).toJson(params)
            else -> return
        }
        val envelope = RelayEnvelope(type = type, payload = dtoJson)
        val envelopeJson = envelopeAdapter.toJson(envelope)
        val payload = Payload.fromBytes(envelopeJson.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                Log.d(TAG, "Payload ($type) sent to endpoint $endpointId")
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Payload ($type) failed for endpoint $endpointId", error)
            }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "Connection initiated from endpoint $endpointId, name: '${info.endpointName}'")
            if (!acceptsNewMembers) {
                Log.w(TAG, "Host is currently not accepting new members. Rejecting connection from $endpointId")
                connectionsClient.rejectConnection(endpointId)
                return
            }
            val currentConnectedCount = connectedEndpoints.values.toSet().size
            if ((currentConnectedCount + 1) >= MAX_PARTICIPANTS) {
                Log.w(TAG, "PingRing is full ($currentConnectedCount connected peers + host >= $MAX_PARTICIPANTS). Rejecting endpoint $endpointId")
                connectionsClient.rejectConnection(endpointId)
                return
            }
            endpointToPeerId[endpointId] = info.endpointName
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val peerId = endpointToPeerId[endpointId]?.ifBlank { null } ?: endpointId
                connectedEndpoints[peerId] = endpointId
                connectedEndpoints[endpointId] = endpointId
                _connectionEstablished.tryEmit(peerId)
                Log.i(TAG, "Connection SUCCESS to $endpointId (Peer ID: $peerId). Active connected endpoints: $connectedEndpoints")
            } else {
                endpointToPeerId.remove(endpointId)
                Log.w(TAG, "Connection FAILED to $endpointId: status code ${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            val peerId = endpointToPeerId.remove(endpointId)
            if (peerId != null) {
                connectedEndpoints.remove(peerId)
            }
            connectedEndpoints.remove(endpointId)
            Log.i(TAG, "Disconnected from endpoint $endpointId (Peer: $peerId)")
            // Let the radar drop this peer immediately rather than waiting for
            // the staleness timeout to notice it went quiet.
            if (!peerId.isNullOrBlank()) {
                _peerDisconnected.tryEmit(peerId)
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "Endpoint found: $endpointId, name: '${info.endpointName}', serviceId: '${info.serviceId}'")
            connectionsClient.requestConnection(
                localPeerId,
                endpointId,
                connectionLifecycleCallback
            ).addOnSuccessListener {
                Log.i(TAG, "Requested connection to $endpointId")
            }.addOnFailureListener { e ->
                Log.e(TAG, "Request connection failed to $endpointId", e)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "Endpoint lost: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val json = String(bytes)
                Log.d(TAG, "Received payload from $endpointId: $json")

                // First try parsing as structured RelayEnvelope
                val envelope = runCatching { envelopeAdapter.fromJson(json) }.getOrNull()
                if (envelope != null && envelope.payload != null) {
                    when (envelope.type) {
                        "chat" -> {
                            runCatching { moshi.adapter(ChatPayloadDto::class.java).fromJson(envelope.payload) }
                                .getOrNull()?.let {
                                    Log.i(TAG, "Received chat message '${it.text}' from ${it.senderName}")
                                    _receivedChatMessage.tryEmit(it)
                                }
                        }
                        "chat-history" -> {
                            runCatching { moshi.adapter(ChatHistoryPayloadDto::class.java).fromJson(envelope.payload) }
                                .getOrNull()?.let {
                                    Log.i(TAG, "Received chat history with ${it.messages.size} msgs")
                                    _receivedChatHistory.tryEmit(it)
                                }
                        }
                        "nudge" -> {
                            runCatching { moshi.adapter(NudgePayloadDto::class.java).fromJson(envelope.payload) }
                                .getOrNull()?.let {
                                    Log.i(TAG, "Received nudge from ${it.senderName}")
                                    _receivedNudge.tryEmit(it)
                                }
                        }
                        "beacon" -> {
                            runCatching { moshi.adapter(BeaconPayloadDto::class.java).fromJson(envelope.payload) }
                                .getOrNull()?.let {
                                    Log.i(TAG, "Received beacon '${it.title}' from ${it.senderName}")
                                    _receivedBeacon.tryEmit(it)
                                }
                        }
                        "uwb-address" -> {
                            runCatching { moshi.adapter(UwbPeerAddressDto::class.java).fromJson(envelope.payload) }
                                .getOrNull()?.let { _receivedPeerAddress.tryEmit(it) }
                        }
                        "uwb-params" -> {
                            runCatching { moshi.adapter(UwbControllerParametersDto::class.java).fromJson(envelope.payload) }
                                .getOrNull()?.let { _receivedControllerParams.tryEmit(it) }
                        }
                    }
                    return
                }

                // Fallback for raw legacy JSON
                parseRawLegacyJson(json)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun parseRawLegacyJson(json: String) {
        try {
            val chatMsg = moshi.adapter(ChatPayloadDto::class.java).fromJson(json)
            if (chatMsg != null && chatMsg.id.isNotEmpty() && chatMsg.text.isNotEmpty()) {
                _receivedChatMessage.tryEmit(chatMsg)
                return
            }
        } catch (e: Exception) {}

        try {
            val chatHistory = moshi.adapter(ChatHistoryPayloadDto::class.java).fromJson(json)
            if (chatHistory != null) {
                _receivedChatHistory.tryEmit(chatHistory)
                return
            }
        } catch (e: Exception) {}

        try {
            val nudge = moshi.adapter(NudgePayloadDto::class.java).fromJson(json)
            if (nudge != null && nudge.id.isNotEmpty()) {
                _receivedNudge.tryEmit(nudge)
                return
            }
        } catch (e: Exception) {}

        try {
            val beacon = moshi.adapter(BeaconPayloadDto::class.java).fromJson(json)
            if (beacon != null && beacon.id.isNotEmpty()) {
                _receivedBeacon.tryEmit(beacon)
                return
            }
        } catch (e: Exception) {}

        try {
            val controllerParams = moshi.adapter(UwbControllerParametersDto::class.java).fromJson(json)
            if (controllerParams != null && controllerParams.hostPeerId.isNotEmpty()) {
                _receivedControllerParams.tryEmit(controllerParams)
                return
            }
        } catch (e: Exception) {}

        try {
            val peerAddress = moshi.adapter(UwbPeerAddressDto::class.java).fromJson(json)
            if (peerAddress != null && peerAddress.peerId.isNotEmpty()) {
                _receivedPeerAddress.tryEmit(peerAddress)
                return
            }
        } catch (e: Exception) {}
    }
}
