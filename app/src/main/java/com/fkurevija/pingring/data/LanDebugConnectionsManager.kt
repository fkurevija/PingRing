package com.fkurevija.pingring.data

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException

/**
 * Debug-only [NearbyTransport] implementation for testing PingRing between
 * the Android Emulator and a physical device, neither of which can reach the
 * other over real Bluetooth/Wi-Fi Direct radios (the emulator has none, and
 * even two real devices may sit in different network segments).
 *
 * Instead of device-to-device discovery, every participant opens a single
 * persistent TCP connection to a small relay server run on the developer's
 * machine (see tools/lan_debug_relay.py). The relay groups connections by
 * event ID and fans messages out, so this class never needs to know the
 * other device's IP address:
 *  - On the Emulator, `10.0.2.2` always resolves to the host machine, so it
 *    works out of the box with zero setup.
 *  - On a physical device, run `adb reverse tcp:<port> tcp:<port>` once so
 *    `127.0.0.1:<port>` on the phone reaches the same relay on the host
 *    machine, then set the host to `127.0.0.1` in Settings.
 *
 * This is a testing aid only; it is never used unless explicitly enabled in
 * Settings on a debug build.
 */
class LanDebugConnectionsManager(
    private val host: String,
    private val port: Int = DEFAULT_PORT
) : NearbyTransport {
    private val TAG = "LanDebugTransport"
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val envelopeAdapter = moshi.adapter(RelayEnvelope::class.java)

    private val _receivedPeerAddress = MutableSharedFlow<UwbPeerAddressDto>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val receivedPeerAddress = _receivedPeerAddress.asSharedFlow()

    private val _receivedControllerParams = MutableSharedFlow<UwbControllerParametersDto>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val receivedControllerParams = _receivedControllerParams.asSharedFlow()

    private val _receivedChatMessage = MutableSharedFlow<ChatPayloadDto>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val receivedChatMessage = _receivedChatMessage.asSharedFlow()

    private val _receivedChatHistory = MutableSharedFlow<ChatHistoryPayloadDto>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val receivedChatHistory = _receivedChatHistory.asSharedFlow()

    private val _connectionEstablished = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val connectionEstablished = _connectionEstablished.asSharedFlow()

    private val _receivedNudge = MutableSharedFlow<NudgePayloadDto>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val receivedNudge = _receivedNudge.asSharedFlow()

    private val _receivedBeacon = MutableSharedFlow<BeaconPayloadDto>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val receivedBeacon = _receivedBeacon.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()

    private var localPeerId: String = ""
    private var localEventId: String = ""

    override fun startAdvertising(eventId: String, localPeerId: String, useWifiDirect: Boolean, useHotspot: Boolean) {
        connect(eventId, localPeerId)
    }

    override fun startDiscovery(eventId: String, localPeerId: String) {
        connect(eventId, localPeerId)
    }

    private fun connect(eventId: String, localPeerId: String) {
        this.localEventId = eventId
        this.localPeerId = localPeerId
        stopAll()
        connectionJob = scope.launch {
            while (isActive) {
                try {
                    Log.d(TAG, "Connecting to relay at $host:$port for event $eventId as $localPeerId")
                    val newSocket = Socket(host, port)
                    socket = newSocket
                    val newWriter = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream()))
                    writer = newWriter
                    sendEnvelope(RelayEnvelope(type = "join", eventId = eventId, senderPeerId = localPeerId))

                    val reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        handleLine(line)
                    }
                    Log.w(TAG, "Relay connection closed, retrying…")
                } catch (e: SocketException) {
                    Log.w(TAG, "Relay socket error: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Relay connection failed", e)
                }
                socket = null
                writer = null
                kotlinx.coroutines.delay(RECONNECT_DELAY_MILLIS)
            }
        }
    }

    override fun stopAll() {
        connectionJob?.cancel()
        connectionJob = null
        runCatching { socket?.close() }
        socket = null
        writer = null
    }

    private fun handleLine(line: String) {
        val envelope = runCatching { envelopeAdapter.fromJson(line) }.getOrNull() ?: return
        when (envelope.type) {
            "peer-joined" -> {
                envelope.senderPeerId?.let { _connectionEstablished.tryEmit(it) }
            }
            "data" -> {
                val json = envelope.payload ?: return
                parseAndEmitPayload(json)
            }
        }
    }

    private fun parseAndEmitPayload(json: String) {
        try {
            val controllerParams = moshi.adapter(UwbControllerParametersDto::class.java).fromJson(json)
            if (controllerParams != null) {
                _receivedControllerParams.tryEmit(controllerParams)
                return
            }
        } catch (e: Exception) {}

        try {
            val peerAddress = moshi.adapter(UwbPeerAddressDto::class.java).fromJson(json)
            if (peerAddress != null) {
                _receivedPeerAddress.tryEmit(peerAddress)
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
            val chatMsg = moshi.adapter(ChatPayloadDto::class.java).fromJson(json)
            if (chatMsg != null) {
                _receivedChatMessage.tryEmit(chatMsg)
                return
            }
        } catch (e: Exception) {}

        try {
            val nudge = moshi.adapter(NudgePayloadDto::class.java).fromJson(json)
            if (nudge != null) {
                _receivedNudge.tryEmit(nudge)
                return
            }
        } catch (e: Exception) {}

        try {
            val beacon = moshi.adapter(BeaconPayloadDto::class.java).fromJson(json)
            if (beacon != null) {
                _receivedBeacon.tryEmit(beacon)
                return
            }
        } catch (e: Exception) {}
    }

    private fun sendEnvelope(envelope: RelayEnvelope) {
        val activeWriter = writer ?: return
        val json = envelopeAdapter.toJson(envelope)
        synchronized(writeLock) {
            try {
                activeWriter.write(json)
                activeWriter.newLine()
                activeWriter.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send to relay", e)
            }
        }
    }

    private fun sendPayload(targetPeerId: String?, dtoJson: String) {
        sendEnvelope(
            RelayEnvelope(
                type = "data",
                eventId = localEventId,
                senderPeerId = localPeerId,
                targetPeerId = targetPeerId,
                payload = dtoJson
            )
        )
    }

    private fun toJson(params: Any): String? = when (params) {
        is UwbControllerParametersDto -> moshi.adapter(UwbControllerParametersDto::class.java).toJson(params)
        is UwbPeerAddressDto -> moshi.adapter(UwbPeerAddressDto::class.java).toJson(params)
        is ChatPayloadDto -> moshi.adapter(ChatPayloadDto::class.java).toJson(params)
        is ChatHistoryPayloadDto -> moshi.adapter(ChatHistoryPayloadDto::class.java).toJson(params)
        is NudgePayloadDto -> moshi.adapter(NudgePayloadDto::class.java).toJson(params)
        is BeaconPayloadDto -> moshi.adapter(BeaconPayloadDto::class.java).toJson(params)
        else -> null
    }

    override fun sendUwbParameters(peerId: String, params: Any) {
        toJson(params)?.let { sendPayload(peerId, it) }
    }

    override fun broadcastUwbParameters(params: Any) {
        toJson(params)?.let { sendPayload(null, it) }
    }

    override fun broadcastChatMessage(chatMsg: ChatPayloadDto) {
        sendPayload(null, moshi.adapter(ChatPayloadDto::class.java).toJson(chatMsg))
    }

    override fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>) {
        sendPayload(peerId, moshi.adapter(ChatHistoryPayloadDto::class.java).toJson(ChatHistoryPayloadDto(messages)))
    }

    override fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto) {
        sendPayload(peerId, moshi.adapter(ChatPayloadDto::class.java).toJson(chatMsg))
    }

    override fun sendNudgeToPeer(peerId: String, nudge: NudgePayloadDto) {
        sendPayload(peerId, moshi.adapter(NudgePayloadDto::class.java).toJson(nudge))
    }

    override fun broadcastNudge(nudge: NudgePayloadDto) {
        sendPayload(null, moshi.adapter(NudgePayloadDto::class.java).toJson(nudge))
    }

    override fun sendBeaconToPeer(peerId: String, beacon: BeaconPayloadDto) {
        sendPayload(peerId, moshi.adapter(BeaconPayloadDto::class.java).toJson(beacon))
    }

    override fun broadcastBeacon(beacon: BeaconPayloadDto) {
        sendPayload(null, moshi.adapter(BeaconPayloadDto::class.java).toJson(beacon))
    }

    companion object {
        const val DEFAULT_PORT = 8942
        private const val RECONNECT_DELAY_MILLIS = 2000L
    }
}
