package com.fkurevija.pingring.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
 *
 * It also gives the development machine a way to feed the app something it
 * otherwise could never measure: tools/mock_radar.py scripts distance/azimuth
 * reports and the relay forwards them, so the radar can be tested on an
 * emulator that has no UWB or BLE radio.
 */
class LanDebugConnectionsManager(
    private val host: String,
    private val port: Int = DEFAULT_PORT
) : NearbyTransport {
    private val TAG = "LanDebugTransport"
    // Every flow this transport exposes is declared once, in TransportEvents:
    // the two transports are interchangeable to DiscoveryRepository, so their
    // buffering behaviour has to be identical rather than merely similar.
    private val events = TransportEvents(TAG)

    override val receivedPeerAddress: SharedFlow<UwbPeerAddressDto> get() = events.receivedPeerAddress
    override val receivedControllerParams: SharedFlow<UwbControllerParametersDto> get() = events.receivedControllerParams

    /**
     * A ranging offer aimed at this guest: the relay fans messages out by
     * targetPeerId, so one arrives here already addressed.
     */
    override val receivedUwbRangingOffer: SharedFlow<UwbRangingOfferDto> get() = events.receivedUwbRangingOffer
    override val receivedChatMessage: SharedFlow<ChatPayloadDto> get() = events.receivedChatMessage
    override val receivedChatHistory: SharedFlow<ChatHistoryPayloadDto> get() = events.receivedChatHistory
    override val connectionEstablished: SharedFlow<String> get() = events.connectionEstablished
    override val peerDisconnected: SharedFlow<String> get() = events.peerDisconnected
    override val eventClosed: SharedFlow<Unit> get() = events.eventClosed
    override val receivedBeacon: SharedFlow<BeaconPayloadDto> get() = events.receivedBeacon
    override val receivedInviteSecret: SharedFlow<InviteSecretPayloadDto> get() = events.receivedInviteSecret
    override val receivedEventControl: SharedFlow<EventControlPayloadDto> get() = events.receivedEventControl
    override val receivedMockRanging: SharedFlow<MockRangingDto> get() = events.receivedMockRanging


    override var acceptsNewMembers: Boolean = true

    /**
     * The relay admits a member the moment it connects, so there is no
     * handshake to interrupt and nothing for a host to approve.
     *
     * Deliberately inert rather than emulated: the debug transport exists to
     * exercise chat, ranging and discovery between an emulator and a device,
     * and a fake approval prompt in front of it would stop the two transports
     * behaving alike for no gain. The approval rules themselves are unit tested
     * against [decideJoinRequest] rather than through this path.
     */
    override var requiresApproval: Boolean = false

    /** Always empty here, for the reason given on [requiresApproval]. */
    override val pendingJoinRequests: StateFlow<List<PendingJoinRequest>> =
        MutableStateFlow(emptyList())
    override val pendingAuthentications: StateFlow<List<ConnectionAuthenticationRequest>> =
        MutableStateFlow(emptyList())

    override fun approveJoinRequest(endpointId: String) {
        Log.w(TAG, "Ignoring approval of $endpointId: the debug transport has no join requests")
    }

    override fun denyJoinRequest(endpointId: String) {
        Log.w(TAG, "Ignoring denial of $endpointId: the debug transport has no join requests")
    }

    override fun confirmConnectionAuthentication(endpointId: String) = Unit

    override fun rejectConnectionAuthentication(endpointId: String) = Unit

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
    // chat message or beacon.
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
    * Tells the relay this host is ending the PingRing. The relay tells the
    * members the event is closed and disconnects them; Nearby uses EventControl.
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
                            Log.d(TAG, "Received ${line.length} characters from relay")
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
        val envelope = RelayCodec.decodeEnvelope(line).getOrNull() ?: return
        when (envelope.type) {
            RelayEnvelope.PEER_JOINED -> {
                Log.i(TAG, "Peer joined: ${envelope.senderPeerId}")
                envelope.senderPeerId?.let { events.onPeerConnected(it) }
            }
            RelayEnvelope.PEER_LEFT -> {
                // The relay drops a peer from the event the moment its
                // connection goes away, so the radar can forget it right away
                // instead of waiting for the staleness sweep.
                Log.i(TAG, "Peer left: ${envelope.senderPeerId}")
                envelope.senderPeerId?.let { events.onPeerDisconnected(it) }
            }
            RelayEnvelope.EVENT_CLOSED -> {
                // The relay's explicit closure signal mirrors Nearby EventControl.
                Log.i(TAG, "Event ${envelope.eventId} was closed by host ${envelope.senderPeerId}")
                eventClosedByRelay = true
                events.onEventClosed()
            }
            RelayEnvelope.DATA -> {
                val json = envelope.payload ?: return
                val wrapped = RelayCodec.decodeEnvelope(json).getOrNull()?.takeIf { it.payload != null }
                if (wrapped != null) {
                    deliver(wrapped)
                } else {
                    // A sender that wrote no envelope at all: recognise the body
                    // on its own, the same way the Nearby transport does.
                    RelayCodec.decodeUnwrapped(json)?.let(::deliver)
                }
            }
        }
    }

    /**
     * Hands one forwarded payload to the flow that carries it.
     *
     * Unlike the Nearby transport there is nothing to relay here: the relay
     * already fans a message out by targetPeerId, so an offer arrives at the
     * guest it was aimed at, and a scripted range report has nowhere else to go.
     */
    private fun deliver(envelope: RelayEnvelope): Boolean {
        val body = envelope.payload ?: return false
        val payload = RelayCodec.decode(envelope.type, body)?.getOrNull() ?: return false
        return deliver(payload)
    }

    private fun deliver(payload: DecodedPayload): Boolean = when (payload) {
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

    private fun sendEnvelope(envelope: RelayEnvelope) {
        // Socket writes must never happen on the caller's thread (often the
        // UI thread from a Compose click handler), so hop onto the IO scope.
        scope.launch {
            val activeWriter = writer
            if (activeWriter == null) {
                // Not connected right now (e.g. mid-reconnect) — queue it so
                // it's flushed as soon as a connection comes back up instead
                // of silently disappearing.
                Log.w(TAG, "Not connected to relay, queuing ${envelope.type} envelope")
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

    private fun sendPayload(targetPeerId: String?, params: Any) {
        // One mapping from DTO to wire type for the whole app, so a payload type
        // added on one side of the app cannot go missing on the other.
        val encoded = RelayCodec.encode(params) ?: return
        val innerEnvelope = RelayEnvelope(type = encoded.wireType, payload = encoded.json)
        val innerJson = RelayCodec.encodeEnvelope(innerEnvelope)
        sendEnvelope(
            RelayEnvelope(
                type = RelayEnvelope.DATA,
                eventId = localEventId,
                senderPeerId = localPeerId,
                targetPeerId = targetPeerId,
                payload = innerJson
            )
        )
    }

    override fun sendUwbParameters(peerId: String, params: Any) {
        sendPayload(peerId, params)
    }

    /**
     * Sends a guest's UWB ranging offer to the guest it names.
     *
     * On the LAN transport the relay already routes by targetPeerId, so the
     * offer reaches its destination with no host hop at all. That is a
     * simplification of the real transport, where two guests have no direct
     * link and the host has to make the hop, so this path is not a faithful
     * test of the real relay behaviour.
     */
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

    override fun sendEventControl(peerId: String, control: EventControlPayloadDto) {
        sendPayload(peerId, control)
    }

    override fun sendJoinProofToHost(proof: JoinProofPayloadDto) = Unit

    override fun broadcastBeacon(beacon: BeaconPayloadDto) {
        sendPayload(null, beacon)
    }

    companion object {
        const val DEFAULT_PORT = 8942
        private const val RECONNECT_DELAY_MILLIS = 2000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 1500L
    }
}
