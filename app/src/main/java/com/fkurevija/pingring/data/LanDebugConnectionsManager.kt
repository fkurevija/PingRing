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

    private val _peerDisconnected = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val peerDisconnected = _peerDisconnected.asSharedFlow()

    private val _eventClosed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val eventClosed = _eventClosed.asSharedFlow()

    private val _receivedNudge = MutableSharedFlow<NudgePayloadDto>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val receivedNudge = _receivedNudge.asSharedFlow()

    private val _receivedBeacon = MutableSharedFlow<BeaconPayloadDto>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val receivedBeacon = _receivedBeacon.asSharedFlow()
    override var acceptsNewMembers: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()

    // Bumped on every connect()/stopAll() call. A retrying/closing coroutine
    // from a superseded connection attempt checks this before touching the
    // shared socket/writer fields, so it can never clobber a newer, live
    // connection that was already established by the time it wakes up from
    // a blocking read that was interrupted by the old socket being closed.
    private val connectionGeneration = AtomicInteger(0)

    // Envelopes that couldn't be sent immediately (e.g. the relay connection
    // was mid-reconnect) are queued here and flushed in order as soon as a
    // new connection is established, so a brief drop never silently loses a
    // chat message, nudge, or beacon.
    private val pendingEnvelopes = ArrayDeque<RelayEnvelope>()

    private var localPeerId: String = ""
    private var localEventId: String = ""

    // Told to the relay in the join envelope so it knows which connection owns
    // the event and can therefore end it for everyone else.
    private var localRole: String = RelayEnvelope.ROLE_MEMBER

    // Set when the relay reports that the event was closed by its host. The
    // retry loop checks it so a booted client can never silently rejoin the
    // event it was just thrown out of.
    @Volatile
    private var eventClosedByRelay = false

    override fun startAdvertising(eventId: String, localPeerId: String, useWifiDirect: Boolean, useHotspot: Boolean) {
        connect(eventId, localPeerId, RelayEnvelope.ROLE_HOST)
    }

    override fun startDiscovery(eventId: String, localPeerId: String) {
        connect(eventId, localPeerId, RelayEnvelope.ROLE_MEMBER)
    }

    /**
     * Tells the relay this host is ending the PingRing. The relay answers by
     * telling the members the event is closed and disconnecting them, which is
     * the LAN debug equivalent of the BLE closure advertisement the real
     * transport relies on.
     */
    override fun announceEventClosed() {
        eventClosedByRelay = true
        val envelope = RelayEnvelope(
            type = RelayEnvelope.END,
            eventId = localEventId,
            senderPeerId = localPeerId
        )
        // The app tears this connection down on the very click that ends the
        // event, so the envelope has to be on the wire before this returns --
        // but clicks arrive on the UI thread, where Android forbids socket
        // writes (NetworkOnMainThreadException), so hand the write to an IO
        // thread and wait for it to land.
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
            // Already connected/connecting for this exact event+peer (e.g.
            // both HostSetupScreen and RadarScreen call startAdvertising/
            // startDiscovery for the same session) — don't tear down a
            // working connection and lose in-flight messages.
            Log.d(TAG, "Already connecting/connected for event $eventId as $localPeerId, ignoring duplicate request")
            return
        }
        Log.i(TAG, "connect() called for event $eventId as $localPeerId/$role (was event=${this.localEventId} peer=${this.localPeerId} role=${this.localRole})")
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
                        // A newer connect() call superseded us while we were
                        // opening the socket; drop this one immediately.
                        Log.w(TAG, "Generation $myGeneration superseded by ${connectionGeneration.get()} right after socket open, discarding")
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

                    // Some network paths used for testing (the emulator's
                    // virtual NAT, adb reverse over USB) silently drop TCP
                    // connections that sit idle for a few seconds. A small
                    // periodic heartbeat keeps traffic flowing so those idle
                    // timeouts never trigger and the connection — and any
                    // broadcast messages relying on it — stays alive.
                    val heartbeatJob = scope.launch {
                        while (isActive) {
                            kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MILLIS)
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
                            Log.d(TAG, "Received from relay: $line")
                            handleLine(line)
                            if (eventClosedByRelay) {
                                // The host ended the event; the relay is closing
                                // this connection too, so stop reading instead of
                                // treating the close as a drop to retry.
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
                    // Booted from an event that no longer exists; the app is
                    // already leaving it, and rejoining would only resurrect a
                    // dead session on the radar.
                    Log.i(TAG, "Event was closed by its host, stopping retry loop (gen $myGeneration)")
                    break
                }
                if (connectionGeneration.get() != myGeneration) {
                    // Superseded by a newer connection attempt — don't null
                    // out its (possibly already-live) socket/writer, and
                    // don't keep retrying on its behalf.
                    Log.w(TAG, "Generation $myGeneration superseded by ${connectionGeneration.get()} after disconnect, stopping retry loop")
                    break
                }
                socket = null
                writer = null
                kotlinx.coroutines.delay(RECONNECT_DELAY_MILLIS)
            }
        }
    }

    override fun stopAll() {
        val newGen = connectionGeneration.incrementAndGet()
        Log.i(TAG, "stopAll() called, bumping generation to $newGen")
        connectionJob?.cancel()
        connectionJob = null
        runCatching { socket?.close() }
        socket = null
        writer = null
    }

    private fun handleLine(line: String) {
        val envelope = runCatching { envelopeAdapter.fromJson(line) }.getOrNull() ?: return
        when (envelope.type) {
            RelayEnvelope.PEER_JOINED -> {
                Log.i(TAG, "Peer joined: ${envelope.senderPeerId}")
                envelope.senderPeerId?.let { _connectionEstablished.tryEmit(it) }
            }
            RelayEnvelope.PEER_LEFT -> {
                // The relay drops a peer from the event the moment its
                // connection goes away, so the radar can forget it right away
                // instead of waiting for the staleness sweep.
                Log.i(TAG, "Peer left: ${envelope.senderPeerId}")
                envelope.senderPeerId?.let { _peerDisconnected.tryEmit(it) }
            }
            RelayEnvelope.EVENT_CLOSED -> {
                // The host ended the PingRing. This is the LAN debug stand-in
                // for the BLE closure advertisement a real device would see.
                Log.i(TAG, "Event ${envelope.eventId} was closed by host ${envelope.senderPeerId}")
                eventClosedByRelay = true
                _eventClosed.tryEmit(Unit)
            }
            RelayEnvelope.DATA -> {
                val json = envelope.payload ?: return
                val innerEnvelope = runCatching { envelopeAdapter.fromJson(json) }.getOrNull()
                if (innerEnvelope != null && innerEnvelope.payload != null) {
                    when (innerEnvelope.type) {
                        "chat" -> runCatching { moshi.adapter(ChatPayloadDto::class.java).fromJson(innerEnvelope.payload) }
                            .getOrNull()?.let { _receivedChatMessage.tryEmit(it) }
                        "chat-history" -> runCatching { moshi.adapter(ChatHistoryPayloadDto::class.java).fromJson(innerEnvelope.payload) }
                            .getOrNull()?.let { _receivedChatHistory.tryEmit(it) }
                        "nudge" -> runCatching { moshi.adapter(NudgePayloadDto::class.java).fromJson(innerEnvelope.payload) }
                            .getOrNull()?.let { _receivedNudge.tryEmit(it) }
                        "beacon" -> runCatching { moshi.adapter(BeaconPayloadDto::class.java).fromJson(innerEnvelope.payload) }
                            .getOrNull()?.let { _receivedBeacon.tryEmit(it) }
                        "uwb-address" -> runCatching { moshi.adapter(UwbPeerAddressDto::class.java).fromJson(innerEnvelope.payload) }
                            .getOrNull()?.let { _receivedPeerAddress.tryEmit(it) }
                        "uwb-params" -> runCatching { moshi.adapter(UwbControllerParametersDto::class.java).fromJson(innerEnvelope.payload) }
                            .getOrNull()?.let { _receivedControllerParams.tryEmit(it) }
                    }
                    return
                }
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
        // Socket writes must never happen on the caller's thread (often the
        // UI thread from a Compose click handler), so hop onto the IO scope.
        scope.launch {
            val activeWriter = writer
            if (activeWriter == null) {
                // Not connected right now (e.g. mid-reconnect) — queue it so
                // it's flushed as soon as a connection comes back up instead
                // of silently disappearing.
                Log.w(TAG, "Not connected to relay, queuing envelope: $envelope")
                synchronized(writeLock) { pendingEnvelopes.addLast(envelope) }
                return@launch
            }
            if (!writeEnvelopeNow(activeWriter, envelope)) {
                synchronized(writeLock) { pendingEnvelopes.addLast(envelope) }
            }
        }
    }

    /** Writes synchronously on the calling (IO) thread/coroutine. Returns false on failure. */
    private fun writeEnvelopeNow(activeWriter: BufferedWriter, envelope: RelayEnvelope): Boolean {
        val json = envelopeAdapter.toJson(envelope)
        synchronized(writeLock) {
            return try {
                activeWriter.write(json)
                activeWriter.newLine()
                activeWriter.flush()
                Log.d(TAG, "Sent to relay: $json")
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
        private const val HEARTBEAT_INTERVAL_MILLIS = 1500L
    }
}
