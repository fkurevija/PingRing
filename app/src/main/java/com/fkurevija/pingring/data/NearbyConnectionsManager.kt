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

class NearbyConnectionsManager(private val context: Context) {
    private val TAG = "NearbyConnections"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    
    private val _receivedPeerAddress = MutableSharedFlow<UwbPeerAddressDto>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedPeerAddress = _receivedPeerAddress.asSharedFlow()

    private val _receivedControllerParams = MutableSharedFlow<UwbControllerParametersDto>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedControllerParams = _receivedControllerParams.asSharedFlow()

    private var activeEndpointId: String? = null

    fun startAdvertising(eventId: String, localPeerId: String) {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        connectionsClient.startAdvertising(
            localPeerId,
            eventId,
            connectionLifecycleCallback,
            options
        ).addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed", e)
        }
    }

    fun startDiscovery(eventId: String) {
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

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        activeEndpointId = null
    }

    fun sendUwbParameters(params: Any) {
        val endpointId = activeEndpointId ?: return
        val json = when (params) {
            is UwbControllerParametersDto -> moshi.adapter(UwbControllerParametersDto::class.java).toJson(params)
            is UwbPeerAddressDto -> moshi.adapter(UwbPeerAddressDto::class.java).toJson(params)
            else -> return
        }
        val payload = Payload.fromBytes(json.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                activeEndpointId = endpointId
                Log.d(TAG, "Connected to $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            activeEndpointId = null
            Log.d(TAG, "Disconnected from $endpointId")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(
                context.packageName,
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
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
