package com.fkurevija.pingring.data

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class NearbyConnectionsManager(private val context: Context) : NearbyTransport {
    private val TAG = "NearbyConnections"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    
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

    private val connectedEndpoints = mutableMapOf<String, String>()
    private val endpointToPeerId = mutableMapOf<String, String>()
    private var localPeerId: String = ""

    override fun startAdvertising(eventId: String, localPeerId: String, useWifiDirect: Boolean, useHotspot: Boolean) {
        this.localPeerId = localPeerId
        val optionsBuilder = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
        
        // Nearby Connections automatically handles these, but we can potentially force or suggest
        // However, standard API doesn't have simple toggles for "WiFi Direct only".
        // It uses "Strategy" to decide. P2P_STAR is the most robust for this use case.
        
        connectionsClient.startAdvertising(
            localPeerId,
            eventId,
            connectionLifecycleCallback,
            optionsBuilder.build()
        ).addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed", e)
        }
    }

    override fun startDiscovery(eventId: String, localPeerId: String) {
        this.localPeerId = localPeerId
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        connectionsClient.startDiscovery(
            eventId,
            endpointDiscoveryCallback,
            options
        ).addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed", e)
        }
    }

    override fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        endpointToPeerId.clear()
    }

    override fun sendUwbParameters(peerId: String, params: Any) {
        val endpointId = connectedEndpoints[peerId] ?: return
        sendPayloadToEndpoint(endpointId, params)
    }

    /**
     * Sends parameters to all connected endpoints. 
     * Useful for members to send their address to the host before they know the host's peerId.
     */
    override fun broadcastUwbParameters(params: Any) {
        connectedEndpoints.values.forEach { endpointId ->
            sendPayloadToEndpoint(endpointId, params)
        }
    }

    override fun broadcastChatMessage(chatMsg: ChatPayloadDto) {
        val endpoints = connectedEndpoints.values.toList()
        Log.d(TAG, "Broadcasting chat message ${chatMsg.id} to ${endpoints.size} endpoint(s)")
        endpoints.forEach { endpointId ->
            sendPayloadToEndpoint(endpointId, chatMsg)
        }
    }

    override fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>) {
        val endpointId = connectedEndpoints[peerId]
        if (endpointId == null) {
            Log.w(TAG, "Cannot send chat history: peer $peerId is not connected")
            return
        }
        sendPayloadToEndpoint(endpointId, ChatHistoryPayloadDto(messages))
    }

    override fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto) {
        val endpointId = connectedEndpoints[peerId] ?: return
        sendPayloadToEndpoint(endpointId, chatMsg)
    }

    override fun sendNudgeToPeer(peerId: String, nudge: NudgePayloadDto) {
        val endpointId = connectedEndpoints[peerId] ?: return
        sendPayloadToEndpoint(endpointId, nudge)
    }

    override fun broadcastNudge(nudge: NudgePayloadDto) {
        connectedEndpoints.values.forEach { endpointId ->
            sendPayloadToEndpoint(endpointId, nudge)
        }
    }

    override fun sendBeaconToPeer(peerId: String, beacon: BeaconPayloadDto) {
        val endpointId = connectedEndpoints[peerId] ?: return
        sendPayloadToEndpoint(endpointId, beacon)
    }

    override fun broadcastBeacon(beacon: BeaconPayloadDto) {
        connectedEndpoints.values.forEach { endpointId ->
            sendPayloadToEndpoint(endpointId, beacon)
        }
    }

    private fun sendPayloadToEndpoint(endpointId: String, params: Any) {
        val json = when (params) {
            is UwbControllerParametersDto -> moshi.adapter(UwbControllerParametersDto::class.java).toJson(params)
            is UwbPeerAddressDto -> moshi.adapter(UwbPeerAddressDto::class.java).toJson(params)
            is ChatPayloadDto -> moshi.adapter(ChatPayloadDto::class.java).toJson(params)
            is ChatHistoryPayloadDto -> moshi.adapter(ChatHistoryPayloadDto::class.java).toJson(params)
            is NudgePayloadDto -> moshi.adapter(NudgePayloadDto::class.java).toJson(params)
            is BeaconPayloadDto -> moshi.adapter(BeaconPayloadDto::class.java).toJson(params)
            else -> return
        }
        val payload = Payload.fromBytes(json.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                Log.d(TAG, "Payload sent to endpoint $endpointId")
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Payload failed for endpoint $endpointId", error)
            }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Store mapping immediately if we have it from info.endpointName (which is peerId)
            endpointToPeerId[endpointId] = info.endpointName
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val peerId = endpointToPeerId[endpointId]
                if (peerId != null) {
                    connectedEndpoints[peerId] = endpointId
                    _connectionEstablished.tryEmit(peerId)
                }
                Log.d(TAG, "Connected to $endpointId (Peer: $peerId)")
            } else {
                endpointToPeerId.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val peerId = endpointToPeerId.remove(endpointId)
            if (peerId != null) {
                connectedEndpoints.remove(peerId)
            }
            Log.d(TAG, "Disconnected from $endpointId")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(
                localPeerId,
                endpointId,
                connectionLifecycleCallback
            )
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val json = String(bytes)
                // Try parsing as Controller Params first
                try {
                    val controllerParams = moshi.adapter(UwbControllerParametersDto::class.java).fromJson(json)
                    if (controllerParams != null) {
                        _receivedControllerParams.tryEmit(controllerParams)
                        return
                    }
                } catch (e: Exception) {}

                // Then try as Peer Address
                try {
                    val peerAddress = moshi.adapter(UwbPeerAddressDto::class.java).fromJson(json)
                    if (peerAddress != null) {
                        _receivedPeerAddress.tryEmit(peerAddress)
                        return
                    }
                } catch (e: Exception) {}

                // Then try as Chat History
                try {
                    val chatHistory = moshi.adapter(ChatHistoryPayloadDto::class.java).fromJson(json)
                    if (chatHistory != null) {
                        _receivedChatHistory.tryEmit(chatHistory)
                        return
                    }
                } catch (e: Exception) {}

                // Then try as Chat Message
                try {
                    val chatMsg = moshi.adapter(ChatPayloadDto::class.java).fromJson(json)
                    if (chatMsg != null) {
                        _receivedChatMessage.tryEmit(chatMsg)
                        return
                    }
                } catch (e: Exception) {}

                // Then try as Nudge
                try {
                    val nudge = moshi.adapter(NudgePayloadDto::class.java).fromJson(json)
                    if (nudge != null) {
                        _receivedNudge.tryEmit(nudge)
                        return
                    }
                } catch (e: Exception) {}

                // Then try as Beacon
                try {
                    val beacon = moshi.adapter(BeaconPayloadDto::class.java).fromJson(json)
                    if (beacon != null) {
                        _receivedBeacon.tryEmit(beacon)
                        return
                    }
                } catch (e: Exception) {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
