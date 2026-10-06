package com.fkurevija.pingring.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether a connection this device has just been notified about has to be
 * turned down.
 *
 * "Allow nearby users to discover and join" and the [MAX_PARTICIPANTS] limit
 * are the *host's* admission policy, so they only get a say over a device
 * asking to join, which is what [isIncoming] reports. Answering the host's half
 * of a handshake this device started itself must never be gated by them: a
 * guest whose own copy of the event has the switch off would otherwise refuse
 * the host it just joined, be left with a connection that never establishes,
 * and receive nothing at all - which is indistinguishable from broken chat.
 */
internal fun shouldRefuseConnection(
    isIncoming: Boolean,
    acceptsNewMembers: Boolean,
    connectedPeerCount: Int
): Boolean = isIncoming && (!acceptsNewMembers || connectedPeerCount + 1 >= MAX_PARTICIPANTS)

const val SAS_PROMPT_WINDOW_MILLIS = 60_000L
const val MAX_SAS_PROMPTS_PER_WINDOW = 12

internal fun isWithinSasPromptRateLimit(recentAttempts: List<Long>, nowMillis: Long): Boolean =
    recentAttempts.count { nowMillis - it < SAS_PROMPT_WINDOW_MILLIS } < MAX_SAS_PROMPTS_PER_WINDOW

/**
 * Whether this particular join has to be put to the host.
 *
 * Only a guest that could not produce the invitation secret. The QR code is
 * rendered on the host device and carries that secret, so scanning it is the
 * host having offered the ring already; a guest holding only the typed
 * six-character code has proved nothing about who they are, and the host is
 * asked before their traffic is admitted.
 *
 * This used to take a second argument - the host's "approve each join" switch -
 * which let the host hold QR joins too. That is the same over-granularity the
 * switch itself was removed for: the host would have had to watch an approval
 * queue for every guest it had personally handed a QR code to. What is left is
 * a property of the join, not a policy anybody sets.
 */
internal fun joinRequiresHostApproval(hasValidInviteSecret: Boolean): Boolean =
    !hasValidInviteSecret

internal fun isPayloadSenderAuthorized(
    isLocalHost: Boolean,
    endpointPeerId: String,
    authenticatedHostPeerId: String?,
    claimedSenderPeerId: String,
): Boolean = if (isLocalHost) {
    endpointPeerId.isNotBlank() && endpointPeerId == claimedSenderPeerId
} else {
    authenticatedHostPeerId != null && endpointPeerId == authenticatedHostPeerId
}

internal fun isBleIdentityKeySenderAuthorized(
    isLocalHost: Boolean,
    endpointId: String,
    endpointPeerId: String?,
    endpointAuthorized: Boolean,
    authenticatedHostEndpoint: String?,
    identityPeerId: String,
): Boolean = if (isLocalHost) {
    endpointAuthorized && endpointPeerId == identityPeerId
} else {
    endpointId == authenticatedHostEndpoint
}

internal fun isMemberDepartureSenderAuthorized(
    isLocalHost: Boolean,
    endpointId: String,
    endpointPeerId: String?,
    endpointAuthorized: Boolean,
    authenticatedHostEndpoint: String?,
    departurePeerId: String,
): Boolean = if (isLocalHost) {
    endpointAuthorized && endpointPeerId == departurePeerId
} else {
    endpointId == authenticatedHostEndpoint
}

internal fun isMemberDepartureOriginAuthorized(
    originPeerId: String,
    departurePeerId: String,
    hostPeerId: String?,
): Boolean = originPeerId == departurePeerId || (!hostPeerId.isNullOrBlank() && originPeerId == hostPeerId)

/**
 * A device asking to join, waiting on the host's decision.
 *
 * The endpoint id is Nearby's opaque handle for the requester, not a peer id -
 * the two are learned at different moments, and the peer id is not known until
 * the request is accepted and the connection carries payloads.
 */
data class PendingJoinRequest(
    val endpointId: String,
    val deviceName: String,
    val requestedAtMillis: Long,
)

data class ConnectionAuthenticationRequest(
    val endpointId: String,
    val deviceName: String,
    val authenticationDigits: String,
    val isIncoming: Boolean,
    val requestedAtMillis: Long = System.currentTimeMillis(),
)

internal fun expiredAuthenticationRequests(
    requests: Collection<ConnectionAuthenticationRequest>,
    nowMillis: Long,
): List<ConnectionAuthenticationRequest> = requests.filter {
    nowMillis - it.requestedAtMillis > JOIN_REQUEST_TIMEOUT_MILLIS
}

/**
 * How long a join request may sit unanswered before the host gives up on it.
 *
 * A request is a live Nearby connection held open, and a human answer is
 * unbounded: a host who walks away mid-decision would otherwise leave a request
 * pinned forever and the guest waiting on it with no outcome either way. The
 * guest is told the request was dropped, so both sides end up in a known state
 * rather than a connection that never resolves.
 *
 * 45s rather than the previous two minutes. The guest's own wait is what a person
 * experiences as "connecting", and it has to be longer than this plus a margin -
 * so a long host window does not cost the guest nothing, it costs them two minutes
 * of staring at a spinner for a host who has already walked away. A minute is
 * still enough to notice a request and answer it; past that, the request is not
 * being worked on.
 *
 * Kept as its own constant rather than derived from the guest's setting, because
 * this is the host's window and the host is not the one waiting. The guest can
 * choose to wait less; the host cannot be made to decide faster.
 */
const val JOIN_REQUEST_TIMEOUT_MILLIS = 45_000L
private const val MAX_PENDING_AUTHENTICATIONS = 8
private const val MAX_PENDING_JOIN_REQUESTS = 8
private const val CLOSURE_SEND_GRACE_MILLIS = 2_000L
private const val MESH_AUTHENTICATION_TIMEOUT_MILLIS = LiveKeyControlTracker.CHALLENGE_TIMEOUT_MILLIS
private const val MESH_PRESENCE_INTERVAL_MILLIS = 5_000L

/**
 * What should happen to a connection request that has just arrived.
 *
 * Split out from the transport so the rules are testable without a radio, in
 * the same way [shouldRefuseConnection] is.
 */
sealed interface JoinRequestDecision {
    /** Turn it away now, and do not hold the request open. */
    data object Refuse : JoinRequestDecision

    /** Let it in without asking. */
    data object Admit : JoinRequestDecision

    /** Hold it open and put it in front of the host. */
    data object AwaitApproval : JoinRequestDecision
}

/**
 * Whether a request is refused outright, admitted outright, or held for the
 * host to decide.
 *
 * The order matters and is the whole point. Capacity and the closed switch are
 * the host's standing answer - a request that cannot be admitted is not worth
 * asking about - so they are checked first and refused without troubling
 * anyone. Only a request the host *could* admit becomes a question for a human.
 *
 * A guest is never asked anything: this is about devices asking to join, and
 * the host's half of a handshake a guest started must go through untouched, for
 * the reason given on [shouldRefuseConnection].
 *
 * [requiresApproval] is per-request rather than a host policy, because it is
 * derived entirely from [joinRequiresHostApproval]: a guest that scanned the
 * host's QR code carries the invitation secret and is admitted, and a guest
 * that only knows the six-character code does not, so the host is asked. There
 * is no switch that turns this on for everyone - the host does not get to
 * second-guess a guest it handed its own QR code to.
 */
fun decideJoinRequest(
    isIncoming: Boolean,
    requiresApproval: Boolean,
    acceptsNewMembers: Boolean,
    connectedPeerCount: Int,
): JoinRequestDecision = when {
    !isIncoming -> JoinRequestDecision.Admit
    !acceptsNewMembers -> JoinRequestDecision.Refuse
    // +1 for the device asking, and for the host itself.
    connectedPeerCount + 1 >= MAX_PARTICIPANTS -> JoinRequestDecision.Refuse
    requiresApproval -> JoinRequestDecision.AwaitApproval
    else -> JoinRequestDecision.Admit
}

/**
 * Drops a request that has waited too long.
 *
 * Returns the request to remove and the endpoints to refuse, which are the same
 * thing here but are named apart because they are not the same at the call site:
 * removal takes it off the host's list, refusal is what actually tells the
 * guest.
 */
fun expiredJoinRequest(
    request: PendingJoinRequest,
    nowMillis: Long,
): PendingJoinRequest? =
    if (nowMillis - request.requestedAtMillis > JOIN_REQUEST_TIMEOUT_MILLIS) request else null

/**
 * The requests that have waited too long, and how long until the next one does.
 *
 * Returns both because the caller needs them for different things: the expired
 * requests to refuse, and the delay before there is anything left worth waking
 * up for. With nothing pending the delay is null, so the caller stops the timer
 * entirely rather than waking every few seconds to find an empty queue - a sweep
 * that keeps running on an idle host is a wakeup nobody asked for.
 *
 * Pure, like the rest of the join rules, so the timing is testable without a
 * clock or a radio. Note the sweep only *reports*; refusing the endpoint is a
 * transport action and belongs to the caller.
 */
fun sweepExpiredJoinRequests(
    requests: List<PendingJoinRequest>,
    nowMillis: Long,
): ExpiredJoinRequestSweep {
    val expired = requests.filter { expiredJoinRequest(it, nowMillis) != null }
    val survivors = requests.filter { expiredJoinRequest(it, nowMillis) == null }
    val nextDue = survivors.minOfOrNull { request ->
        (request.requestedAtMillis + JOIN_REQUEST_TIMEOUT_MILLIS) - nowMillis
    }
    return ExpiredJoinRequestSweep(
        expired = expired,
        nextCheckInMillis = nextDue?.coerceAtLeast(0L),
    )
}

/** What a sweep found, and when to look again. */
data class ExpiredJoinRequestSweep(
    /** Requests past the window, which the caller must refuse. */
    val expired: List<PendingJoinRequest>,
    /** Milliseconds until the next request expires, or null when none remain. */
    val nextCheckInMillis: Long?,
)


class NearbyConnectionsManager(private val context: Context) : NearbyTransport {
    private val TAG = "NearbyConnections"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    // Every flow this transport exposes is declared once, in TransportEvents:
    // the two transports are interchangeable to DiscoveryRepository, so their
    // buffering behaviour has to be identical rather than merely similar.
    private val events = TransportEvents(TAG)

    override val receivedPeerAddress: SharedFlow<UwbPeerAddressDto> get() = events.receivedPeerAddress
    override val receivedControllerParams: SharedFlow<UwbControllerParametersDto> get() = events.receivedControllerParams

    /** Only the addressed guest receives a ranging offer; intermediate peers forward it. */
    override val receivedUwbRangingOffer: SharedFlow<UwbRangingOfferDto> get() = events.receivedUwbRangingOffer
    override val receivedChatMessage: SharedFlow<ChatPayloadDto> get() = events.receivedChatMessage
    override val receivedChatHistory: SharedFlow<ChatHistoryPayloadDto> get() = events.receivedChatHistory
    override val connectionEstablished: SharedFlow<String> get() = events.connectionEstablished
    override val peerDisconnected: SharedFlow<String> get() = events.peerDisconnected

    /** Nearby peers receive closure as authenticated EventControl, not this LAN-only event. */
    override val eventClosed: SharedFlow<Unit> get() = events.eventClosed
    override val receivedBeacon: SharedFlow<BeaconPayloadDto> get() = events.receivedBeacon
    override val receivedInviteSecret: SharedFlow<InviteSecretPayloadDto> get() = events.receivedInviteSecret
    override val receivedBleIdentityKey: SharedFlow<BleIdentityKeyPayloadDto> get() = events.receivedBleIdentityKey
    override val receivedMemberDeparture: SharedFlow<MemberDeparturePayloadDto> get() = events.receivedMemberDeparture
    override val receivedMeshPresence: SharedFlow<MeshPresencePayloadDto> get() = events.receivedMeshPresence
    override val receivedPeerRanging: SharedFlow<PeerRangingPayloadDto> get() = events.receivedPeerRanging
    override val receivedChatColourClaim: SharedFlow<ChatColourClaimPayloadDto>
        get() = events.receivedChatColourClaim
    override val receivedEventControl: SharedFlow<EventControlPayloadDto> get() = events.receivedEventControl

    /**
     * Never emitted: Nearby Connections has no way of being told a distance that
     * was not measured by this device's own UWB/BLE radios. Scripted ranges only
     * exist for the LAN debug transport, which gets them from tools/mock_radar.py.
     */
    override val receivedMockRanging: SharedFlow<MockRangingDto> get() = events.receivedMockRanging


    private val connectedEndpoints = mutableMapOf<String, String>()
    private val endpointToPeerId = mutableMapOf<String, String>()
    private val discoveredEndpointsByPeerId = mutableMapOf<String, String>()
    private val authorizedEndpoints = mutableSetOf<String>()
    private val meshHandshakeEndpoints = mutableSetOf<String>()
    private val pendingMeshChallenges = mutableMapOf<String, ByteArray>()
    private val liveKeyControl = LiveKeyControlTracker()
    private val meshAuthenticationTimeouts = mutableMapOf<String, Runnable>()
    private val meshDuplicateCache = MeshDuplicateCache()
    private val meshPendingMessages = MeshPendingMessageQueue()
    private val incomingEndpoints = mutableSetOf<String>()
    private val pendingInviteProofEndpoints = mutableSetOf<String>()
    private val pendingJoinProofs = mutableMapOf<String, JoinProofPayloadDto>()
    private var authenticatedHostEndpoint: String? = null
    private var localEventId: String? = null
    private var isLocalHost: Boolean = false

    /**
     * Supplies the current event's host peer id, set by [DiscoveryRepository].
     *
     * Injected rather than read from the session here so this class stays a
     * pure transport: it needs to know *which* peer is the host only to route
     * a guest's UWB ranging offer to it.
     */
    var hostPeerIdProvider: (() -> String?)? = null
    override var inviteSecretProvider: (() -> String?)? = null
    var meshIdentityKeyProvider: ((String) -> BleIdentityKeyPayloadDto?)? = null
    var meshPrivateKeyProvider: (() -> java.security.PrivateKey?)? = null

    // Endpoints this device has accepted, i.e. the ones a PayloadCallback is
    // registered for. Nearby only hands payloads to an accepted connection, and
    // a dropped connection has to be accepted again to get a new callback.
    private val acceptedEndpoints = mutableSetOf<String>()

    // Endpoints a connection has already been requested for, so a repeated
    // discovery callback (advertisements come in continuously) does not keep
    // re-requesting a connection that is already up.
    private val requestedEndpoints = mutableSetOf<String>()
    private var localPeerId: String = ""
    override var acceptsNewMembers: Boolean = true

    private val _pendingJoinRequests = MutableStateFlow<List<PendingJoinRequest>>(emptyList())
    override val pendingJoinRequests: StateFlow<List<PendingJoinRequest>> = _pendingJoinRequests.asStateFlow()
    private val authenticationRequests = linkedMapOf<String, ConnectionAuthenticationRequest>()
    private val sasPromptTimes = mutableListOf<Long>()
    override val pendingAuthentications: StateFlow<List<ConnectionAuthenticationRequest>>
        get() = events.pendingAuthentications

    override fun confirmConnectionAuthentication(endpointId: String) {
        val request = authenticationRequests.remove(endpointId) ?: return
        events.setPendingAuthentications(authenticationRequests.values.toList())
        scheduleAuthenticationSweep()
        endpointToPeerId[endpointId] = request.deviceName
        if (request.isIncoming) {
            incomingEndpoints.add(endpointId)
            val reserved = authorizedEndpoints.size + _pendingJoinRequests.value.size
            if (!acceptsNewMembers || reserved >= MAX_PARTICIPANTS - 1) {
                connectionsClient.rejectConnection(endpointId)
                return
            }
        } else {
            authenticatedHostEndpoint = endpointId
        }
        // Establish only the encrypted control channel here. Ordinary payloads
        // remain blocked until QR proof or explicit host approval authorizes it.
        acceptWithPayloadCallback(endpointId, "SAS confirmed")
    }

    override fun rejectConnectionAuthentication(endpointId: String) {
        if (authenticationRequests.remove(endpointId) == null) return
        events.setPendingAuthentications(authenticationRequests.values.toList())
        scheduleAuthenticationSweep()
        endpointToPeerId.remove(endpointId)
        requestedEndpoints.remove(endpointId)
        connectionsClient.rejectConnection(endpointId)
    }

    /**
     * Carries the expiry sweep. A [Handler] rather than a coroutine because the
     * transport already runs on the platform's callback thread and owns no scope
     * of its own - introducing one here would need cancelling in [stopAll] and
     * would leak if it were forgotten.
     */
    private val handler = Handler(Looper.getMainLooper())
    private var sweepRunnable: Runnable? = null
    private var authenticationSweepRunnable: Runnable? = null
    private var pendingClosureSends = 0
    private var closureStopRequested = false
    private var forceClosureStopRunnable: Runnable? = null
    private var meshPresenceRunnable: Runnable? = null

    private fun scheduleAuthenticationSweep() {
        authenticationSweepRunnable?.let(handler::removeCallbacks)
        authenticationSweepRunnable = null
        val oldest = authenticationRequests.values.minOfOrNull { it.requestedAtMillis } ?: return
        val delayMillis = (oldest + JOIN_REQUEST_TIMEOUT_MILLIS - System.currentTimeMillis()).coerceAtLeast(0L)
        authenticationSweepRunnable = Runnable { expireAuthenticationRequests() }.also { runnable ->
            handler.postDelayed(runnable, delayMillis)
        }
    }

    private fun expireAuthenticationRequests() {
        authenticationSweepRunnable = null
        val expired = expiredAuthenticationRequests(authenticationRequests.values, System.currentTimeMillis())
        expired.forEach { request ->
            authenticationRequests.remove(request.endpointId)
            endpointToPeerId.remove(request.endpointId)
            requestedEndpoints.remove(request.endpointId)
            connectionsClient.rejectConnection(request.endpointId)
        }
        events.setPendingAuthentications(authenticationRequests.values.toList())
        scheduleAuthenticationSweep()
    }

    override fun approveJoinRequest(endpointId: String) {
        val request = _pendingJoinRequests.value.firstOrNull { it.endpointId == endpointId }
        if (request == null) {
            Log.w(TAG, "Ignoring approval of $endpointId: no such pending request")
            return
        }
        cancelJoinRequestSweep()
        _pendingJoinRequests.update { current -> current.filterNot { it.endpointId == endpointId } }
        if (endpointId !in pendingInviteProofEndpoints) {
            connectionsClient.rejectConnection(endpointId)
            scheduleJoinRequestSweep()
            return
        }
        pendingInviteProofEndpoints.remove(endpointId)
        authorizeEndpoint(endpointId)
        Log.i(TAG, "Approved join request from ${request.deviceName} ($endpointId)")
        scheduleJoinRequestSweep()
    }

    override fun denyJoinRequest(endpointId: String) {
        val request = _pendingJoinRequests.value.firstOrNull { it.endpointId == endpointId }
        if (request == null) {
            // A normal race: the request may have expired, or the guest may have
            // gone, between the host tapping Deny and this arriving.
            Log.w(TAG, "Ignoring denial of $endpointId: no such pending request")
            return
        }
        cancelJoinRequestSweep()
        _pendingJoinRequests.update { current -> current.filterNot { it.endpointId == endpointId } }
        // Rejecting is what tells the guest the answer. Accepted state is cleared
        // too, so a guest that somehow still holds a channel cannot use it after
        // being turned away.
        acceptedEndpoints.remove(endpointId)
        pendingInviteProofEndpoints.remove(endpointId)
        connectionsClient.rejectConnection(endpointId)
        Log.i(TAG, "Denied join request from ${request.deviceName} ($endpointId)")
        scheduleJoinRequestSweep()
    }

    /**
     * Refuses every request that has waited past [JOIN_REQUEST_TIMEOUT_MILLIS].
     *
     * Without this, a host who puts the phone down mid-decision leaves a request
     * pinned forever: the guest is told nothing, the host's list shows somebody
     * waiting who cannot be admitted, and re-tapping Allow much later accepts a
     * connection the guest has long since given up on. The guest times out on
     * its own side ([JOIN_WAIT_TIMEOUT_MILLIS] in MainActivity, deliberately
     * longer than this window), so refusing here is what tells it the request
     * was dropped rather than leaving it to infer that from a dead socket.
     */
    private fun sweepExpiredJoinRequests() {
        val now = System.currentTimeMillis()
        val sweep = sweepExpiredJoinRequests(_pendingJoinRequests.value, now)
        if (sweep.expired.isNotEmpty()) {
            Log.i(
                TAG,
                "Refusing ${sweep.expired.size} join request(s) unanswered for over " +
                    "${JOIN_REQUEST_TIMEOUT_MILLIS / 1000}s: " +
                    sweep.expired.joinToString { "${it.deviceName} (${it.endpointId})" }
            )
        }
        // Removed from the queue whatever the outcome, so a refusal cannot loop
        // on a request that somehow stays visible.
        if (sweep.expired.isNotEmpty()) {
            val expiredEndpoints = sweep.expired.map { it.endpointId }.toSet()
            _pendingJoinRequests.update { current ->
                current.filterNot { it.endpointId in expiredEndpoints }
            }
            sweep.expired.forEach {
                acceptedEndpoints.remove(it.endpointId)
                connectionsClient.rejectConnection(it.endpointId)
            }
        }
        scheduleJoinRequestSweep()
    }

    /**
     * Arms a single one-shot check for whenever the oldest request is due, and
     * disarms entirely once nothing is pending.
     *
     * Self-rescheduling rather than a repeating timer: an idle host should cost
     * nothing, and a repeating 2-minute wakeup on a phone that is only hosting
     * would be a battery cost bought for a case that cannot happen.
     */
    private fun scheduleJoinRequestSweep() {
        cancelJoinRequestSweep()
        val sweep = sweepExpiredJoinRequests(_pendingJoinRequests.value, System.currentTimeMillis())
        val delay = sweep.nextCheckInMillis ?: return
        sweepRunnable = Runnable { sweepExpiredJoinRequests() }.also { runnable ->
            handler.postDelayed(runnable, delay)
        }
    }

    private fun cancelJoinRequestSweep() {
        sweepRunnable?.let(handler::removeCallbacks)
        sweepRunnable = null
    }

    private fun getServiceId(eventId: String): String = "com.fkurevija.pingring.$eventId"

    override fun startAdvertising(eventId: String, localPeerId: String, useWifiDirect: Boolean, useHotspot: Boolean) {
        startMeshDiscovery(eventId, localPeerId, isHost = true)
    }

    override fun startDiscovery(eventId: String, localPeerId: String) {
        startMeshDiscovery(eventId, localPeerId, isHost = false)
    }

    private fun startMeshDiscovery(eventId: String, localPeerId: String, isHost: Boolean) {
        this.localPeerId = localPeerId
        this.localEventId = eventId
        this.isLocalHost = isHost
        val serviceId = getServiceId(eventId)
        val strategy = Strategy.P2P_CLUSTER
        Log.i(TAG, "Starting P2P_CLUSTER mesh for $serviceId as $localPeerId (host=$isHost)")
        connectionsClient.startAdvertising(
            localPeerId,
            serviceId,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build(),
        ).addOnSuccessListener {
            Log.i(TAG, "Mesh advertising started for $serviceId as $localPeerId")
        }.addOnFailureListener { error ->
            Log.e(TAG, "Mesh advertising failed for $serviceId", error)
        }
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build(),
        ).addOnSuccessListener {
            Log.i(TAG, "Mesh discovery started for $serviceId as $localPeerId")
        }.addOnFailureListener { error ->
            Log.e(TAG, "Mesh discovery failed for $serviceId", error)
        }
        scheduleMeshPresenceHeartbeat()
    }

    private fun scheduleMeshPresenceHeartbeat() {
        meshPresenceRunnable?.let(handler::removeCallbacks)
        val heartbeat = object : Runnable {
            override fun run() {
                val eventCode = localEventId ?: return
                sendMeshBroadcast(MeshPresencePayloadDto(eventCode, localPeerId))
                handler.postDelayed(this, MESH_PRESENCE_INTERVAL_MILLIS)
            }
        }
        meshPresenceRunnable = heartbeat
        handler.post(heartbeat)
    }

    private fun stopMeshPresenceHeartbeat() {
        meshPresenceRunnable?.let(handler::removeCallbacks)
        meshPresenceRunnable = null
    }

    fun hasLiveKeyControl(peerId: String): Boolean {
        val identity = meshIdentityKeyProvider?.invoke(peerId) ?: return false
        if (liveKeyControl.isFresh(peerId, identity.publicKey, SystemClock.elapsedRealtime())) return true
        handler.post {
            val currentIdentity = meshIdentityKeyProvider?.invoke(peerId) ?: return@post
            if (liveKeyControl.isFresh(peerId, currentIdentity.publicKey, SystemClock.elapsedRealtime())) return@post
            val endpointId = connectedEndpoints[peerId]
            if (endpointId != null && endpointId in authorizedEndpoints) {
                meshHandshakeEndpoints.add(endpointId)
                beginMeshAuthentication(endpointId, peerId)
            } else {
                connectToPeer(peerId)
            }
        }
        return false
    }

    override fun connectToPeer(peerId: String) {
        if (peerId == localPeerId) return
        val registeredIdentity = meshIdentityKeyProvider?.invoke(peerId)
        val connectedEndpoint = connectedEndpoints[peerId]
        if (registeredIdentity != null && connectedEndpoint in authorizedEndpoints) {
            if (meshHandshakeEndpoints.add(connectedEndpoint!!)) {
                beginMeshAuthentication(connectedEndpoint, peerId)
            }
            return
        }
        val hostPeerId = hostPeerIdProvider?.invoke()
        val alreadyAdmitted = registeredIdentity != null
        if (!MeshRoutingPolicy.shouldConnectToDiscoveredPeer(
                localPeerId = localPeerId,
                discoveredPeerId = peerId,
                isHost = isLocalHost,
                hostPeerId = hostPeerId,
                alreadyAdmitted = alreadyAdmitted,
            )
        ) return
        val endpointId = discoveredEndpointsByPeerId[peerId] ?: return
        requestConnectionToEndpoint(peerId, endpointId)
    }

    private fun requestConnectionToEndpoint(peerId: String, endpointId: String) {
        if (!requestedEndpoints.add(endpointId)) return
        endpointToPeerId.putIfAbsent(endpointId, peerId)
        connectionsClient.requestConnection(localPeerId, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { Log.i(TAG, "Requested mesh connection to $peerId ($endpointId)") }
            .addOnFailureListener { error ->
                requestedEndpoints.remove(endpointId)
                Log.w(TAG, "Request mesh connection to $peerId failed: ${error.message}")
            }
    }

    override fun stopAll() {
        Log.i(TAG, "stopAll called; stopping advertising/discovery and clearing connected endpoints")
        stopMeshPresenceHeartbeat()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        if (pendingClosureSends > 0) {
            closureStopRequested = true
            forceClosureStopRunnable?.let(handler::removeCallbacks)
            forceClosureStopRunnable = Runnable { finishStopAll() }.also { runnable ->
                handler.postDelayed(runnable, CLOSURE_SEND_GRACE_MILLIS)
            }
            return
        }
        finishStopAll()
    }

    private fun finishStopAll() {
        stopMeshPresenceHeartbeat()
        forceClosureStopRunnable?.let(handler::removeCallbacks)
        forceClosureStopRunnable = null
        closureStopRequested = false
        pendingClosureSends = 0
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        endpointToPeerId.clear()
        discoveredEndpointsByPeerId.clear()
        acceptedEndpoints.clear()
        requestedEndpoints.clear()
        authorizedEndpoints.clear()
        incomingEndpoints.clear()
        pendingInviteProofEndpoints.clear()
        pendingJoinProofs.clear()
        meshDuplicateCache.clear()
        meshPendingMessages.clear()
        meshHandshakeEndpoints.clear()
        pendingMeshChallenges.clear()
        liveKeyControl.clear()
        meshAuthenticationTimeouts.values.forEach(handler::removeCallbacks)
        meshAuthenticationTimeouts.clear()
        authenticatedHostEndpoint = null
        localEventId = null
        // Held requests die with the endpoints holding them; leaving them would
        // greet the next event with approvals the host never gave.
        _pendingJoinRequests.value = emptyList()
        authenticationRequests.clear()
        events.setPendingAuthentications(emptyList())
        // Disarmed before clearing, so a sweep already queued cannot fire into
        // the next event and refuse an endpoint that now belongs to somebody else.
        cancelJoinRequestSweep()
        authenticationSweepRunnable?.let(handler::removeCallbacks)
        authenticationSweepRunnable = null
    }

    /** Nearby peers receive closure through the authenticated EventControl payload. */
    override fun announceEventClosed() = Unit

    override fun sendUwbParameters(peerId: String, params: Any) {
        sendMeshTargetedPayload(peerId, params)
    }

    override fun broadcastUwbParameters(params: Any) {
        sendMeshBroadcast(params)
    }

    /** Routes the offer to its named guest; authenticated relays can forward it. */
    override fun sendUwbRangingOfferToHost(params: Any) {
        val offer = params as? UwbRangingOfferDto
        if (offer == null || offer.targetPeerId.isBlank()) {
            Log.w(TAG, "Cannot route a UWB ranging offer without a target peer")
            return
        }
        sendMeshTargetedPayload(offer.targetPeerId, offer)
    }

    override fun broadcastChatColourClaim(claim: ChatColourClaimPayloadDto) {
        sendMeshBroadcast(claim)
    }

    override fun broadcastChatMessage(chatMsg: ChatPayloadDto) {
        sendMeshBroadcast(chatMsg)
    }

    override fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>) {
        sendMeshTargetedPayload(peerId, ChatHistoryPayloadDto(messages))
    }

    override fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto) {
        sendMeshTargetedPayload(peerId, chatMsg)
    }

    override fun sendBeaconToPeer(peerId: String, beacon: BeaconPayloadDto) {
        sendMeshTargetedPayload(peerId, beacon)
    }

    override fun sendInviteSecret(peerId: String, secret: InviteSecretPayloadDto) {
        val endpointId = connectedEndpoints[peerId] ?: return
        sendPayloadToEndpoint(endpointId, secret)
    }

    override fun sendBleIdentityKey(peerId: String, identityKey: BleIdentityKeyPayloadDto) {
        val endpointId = connectedEndpoints[peerId] ?: return
        sendPayloadToEndpoint(endpointId, identityKey)
    }

    override fun broadcastBleIdentityKey(identityKey: BleIdentityKeyPayloadDto) {
        sendMeshBroadcast(identityKey)
    }

    override fun announceMemberDeparture(departure: MemberDeparturePayloadDto, onComplete: () -> Unit) {
        if (isLocalHost || departure.eventCode != localEventId || departure.peerId != localPeerId) {
            onComplete()
            return
        }
        val hostEndpoint = authenticatedHostEndpoint
        if (hostEndpoint == null) {
            onComplete()
            return
        }
        sendPayloadToEndpoint(hostEndpoint, departure, onComplete)
    }

    override fun broadcastMemberDeparture(departure: MemberDeparturePayloadDto) {
        if (!isLocalHost || departure.eventCode != localEventId) return
        sendMeshBroadcast(departure)
    }

    override fun sendEventControl(peerId: String, control: EventControlPayloadDto) {
        if (control.isClosed) {
            pendingClosureSends++
            sendMeshTargetedPayload(peerId, control) {
                handler.post {
                    pendingClosureSends = (pendingClosureSends - 1).coerceAtLeast(0)
                    if (closureStopRequested && pendingClosureSends == 0) finishStopAll()
                }
            }
        } else {
            sendMeshTargetedPayload(peerId, control)
        }
    }

    override fun sendJoinProofToHost(proof: JoinProofPayloadDto) {
        val endpointId = authenticatedHostEndpoint
            ?: hostPeerIdProvider?.invoke()?.let(connectedEndpoints::get)
            ?: return
        sendPayloadToEndpoint(endpointId, proof)
    }

    override fun broadcastBeacon(beacon: BeaconPayloadDto) {
        sendMeshBroadcast(beacon)
    }

    override fun broadcastPeerRanging(ranging: PeerRangingPayloadDto) {
        sendMeshBroadcast(ranging)
    }

    private fun buildSignedMeshEnvelope(params: Any, targetPeerId: String? = null): Pair<RelayEnvelope, String>? {
        val encoded = RelayCodec.encode(params) ?: run {
            return null
        }
        val baseEnvelope = RelayEnvelope(
            type = encoded.wireType,
            eventId = localEventId,
            senderPeerId = localPeerId,
            targetPeerId = targetPeerId,
            payload = encoded.json,
        )
        val envelope = if (MeshRoutingPolicy.isForwardable(encoded.wireType)) {
            val privateKey = meshPrivateKeyProvider?.invoke() ?: run {
                Log.w(TAG, "Dropping ${encoded.wireType}: local mesh signing key is unavailable")
                return null
            }
            val identified = baseEnvelope.copy(
                messageId = UUID.randomUUID().toString(),
                originPeerId = localPeerId,
            )
            identified.copy(
                originSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    BleIdentityCrypto.sign(privateKey, MeshPeerAuthentication.envelopeSigningBytes(identified))
                )
            )
        } else baseEnvelope
        return envelope to encoded.wireType
    }

    private fun sendPayloadToEndpoint(endpointId: String, params: Any, onComplete: (() -> Unit)? = null) {
        val (envelope, wireType) = buildSignedMeshEnvelope(params, endpointToPeerId[endpointId]) ?: run {
            onComplete?.invoke()
            return
        }
        val outgoing = if (MeshRoutingPolicy.isForwardable(wireType)) {
            envelope.copy(hopCount = MeshRoutingPolicy.nextHopCount(0) ?: return)
        } else envelope
        sendEnvelopeToEndpoint(endpointId, outgoing, wireType, onComplete)
    }

    private fun sendMeshBroadcast(params: Any) {
        val (envelope, wireType) = buildSignedMeshEnvelope(params) ?: return
        if (!MeshRoutingPolicy.isForwardable(wireType)) {
            authorizedEndpoints.forEach { endpointId -> sendEnvelopeToEndpoint(endpointId, envelope, wireType) }
            return
        }
        val messageId = MeshRoutingPolicy.messageId(envelope)
        if (!meshDuplicateCache.markIfNew(messageId)) return
        forwardToMeshNeighbors(envelope, excludedEndpointId = null)
    }

    private fun sendMeshTargetedPayload(peerId: String, params: Any, onComplete: (() -> Unit)? = null) {
        if (peerId == localPeerId) {
            onComplete?.invoke()
            return
        }
        val (envelope, wireType) = buildSignedMeshEnvelope(params, peerId) ?: return
        if (!MeshRoutingPolicy.isForwardable(wireType)) {
            val endpointId = connectedEndpoints[peerId]
            if (endpointId == null) onComplete?.invoke()
            else sendEnvelopeToEndpoint(endpointId, envelope, wireType, onComplete)
            return
        }
        val messageId = MeshRoutingPolicy.messageId(envelope)
        if (!meshDuplicateCache.markIfNew(messageId)) return
        val directEndpoint = connectedEndpoints[peerId]
        if (directEndpoint != null && directEndpoint in authorizedEndpoints) {
            val hopCount = MeshRoutingPolicy.nextHopCount(0) ?: return
            sendEnvelopeToEndpoint(directEndpoint, envelope.copy(hopCount = hopCount), wireType, onComplete)
        } else {
            forwardToMeshNeighbors(envelope, excludedEndpointId = null, onComplete = onComplete)
        }
    }

    private fun forwardToMeshNeighbors(
        envelope: RelayEnvelope,
        excludedEndpointId: String?,
        onComplete: (() -> Unit)? = null,
    ) {
        val hopCount = MeshRoutingPolicy.nextHopCount(envelope.hopCount)
        if (hopCount == null) {
            Log.w(TAG, "Dropping mesh message ${MeshRoutingPolicy.messageId(envelope)} at hop limit")
            onComplete?.invoke()
            return
        }
        val wireType = envelope.type
        val outgoing = envelope.copy(senderPeerId = localPeerId, hopCount = hopCount)
        val endpoints = authorizedEndpoints
            .asSequence()
            .filter { it != excludedEndpointId && it in connectedEndpoints.values }
            .distinct()
            .toList()
        if (endpoints.isEmpty()) {
            if (!MeshRoutingPolicy.isEphemeral(wireType)) meshPendingMessages.enqueue(envelope)
            onComplete?.invoke()
            return
        }
        var remaining = endpoints.size
        endpoints.forEach { endpointId ->
            sendEnvelopeToEndpoint(
                endpointId,
                outgoing,
                wireType,
                onComplete = {
                    remaining--
                    if (remaining == 0) onComplete?.invoke()
                },
                onFailure = { if (!MeshRoutingPolicy.isEphemeral(wireType)) meshPendingMessages.enqueue(envelope) },
            )
        }
    }

    private fun replayPendingMessages(endpointId: String) {
        if (endpointId !in authorizedEndpoints) return
        meshPendingMessages.snapshot().forEach { envelope ->
            val nextHop = MeshRoutingPolicy.nextHopCount(envelope.hopCount) ?: return@forEach
            val outgoing = envelope.copy(senderPeerId = localPeerId, hopCount = nextHop)
            sendEnvelopeToEndpoint(
                endpointId,
                outgoing,
                envelope.type,
                onSuccess = { meshPendingMessages.remove(MeshRoutingPolicy.messageId(envelope)) },
            )
        }
    }

    private fun sendEnvelopeToEndpoint(
        endpointId: String,
        envelope: RelayEnvelope,
        wireType: String,
        onComplete: (() -> Unit)? = null,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) {
        // One mapping from DTO to wire type for the whole app, so a payload type
        // added on one side of the app cannot go missing on the other.
        val envelopeJson = RelayCodec.encodeEnvelope(envelope)
        val payload = Payload.fromBytes(envelopeJson.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                Log.d(TAG, "Sent $wireType (${envelopeJson.length} chars) to $endpointId [peer=${endpointToPeerId[endpointId] ?: "?"}]")
                onSuccess?.invoke()
            }
            .addOnFailureListener { error ->
                // The status code is what says why, and guessing from the UI is
                // hopeless: 4003 is "no such connection", 4006 "advertising or
                // discovery is not running", 5xxx "we rejected it ourselves".
                val status = (error as? com.google.android.gms.common.api.ApiException)?.statusCode
                Log.e(TAG, "sendPayload($wireType) to $endpointId failed, status=$status: ${error.message}", error)
                onFailure?.invoke()
            }
            .addOnCompleteListener { onComplete?.invoke() }
    }

    /**
     * Accepts [endpointId] and thereby registers [payloadCallback] for it.
     *
     * This is the whole ball game for chat: Nearby delivers payloads only to
     * connections this app has accepted, and *both* ends of a connection have
     * to accept before any data can move. The host accepts in
     * [ConnectionLifecycleCallback.onConnectionInitiated] because it received
     * the request, but a guest is the side that called `requestConnection()`,
     * so accepting there and there alone left the guest with no payload
     * callback at all: it could send chat to the host, and would then never
     * see the host's messages, the chat the host relays from other guests, or
     * the chat history replayed on join. The LAN debug transport is unaffected
     * by any of this, which is exactly why this only ever showed up on real
     * devices.
     *
     * Accepting an already-accepted endpoint is a no-op here, so it is safe to
     * call from both the initiation and the result callback.
     */
    private fun acceptWithPayloadCallback(endpointId: String, reason: String) {
        if (!acceptedEndpoints.add(endpointId)) {
            Log.d(TAG, "Endpoint $endpointId already accepted; nothing to do ($reason)")
            return
        }
        connectionsClient.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener {
                Log.i(TAG, "Accepted $endpointId ($reason): payload callback registered")
            }
            .addOnFailureListener { error ->
                // Let a later attempt try again rather than pretending this
                // endpoint is covered when it is not.
                acceptedEndpoints.remove(endpointId)
                Log.w(TAG, "acceptConnection for $endpointId ($reason) failed: ${error.message}")
            }
    }

    private fun authorizeEndpoint(endpointId: String) {
        if (endpointId !in connectedEndpoints.values) return
        if (isLocalHost && (!acceptsNewMembers || authorizedEndpoints.size >= MAX_PARTICIPANTS - 1)) {
            connectionsClient.rejectConnection(endpointId)
            return
        }
        if (!authorizedEndpoints.add(endpointId)) return
        val peerId = endpointToPeerId[endpointId]?.ifBlank { null } ?: endpointId
        events.onPeerConnected(peerId)
    }

    private fun queueJoinApproval(endpointId: String, deviceName: String): Boolean {
        if (_pendingJoinRequests.value.any { it.endpointId == endpointId }) return true
        if (_pendingJoinRequests.value.size >= MAX_PENDING_JOIN_REQUESTS) {
            connectionsClient.rejectConnection(endpointId)
            return false
        }
        val request = PendingJoinRequest(
            endpointId = endpointId,
            deviceName = deviceName.ifBlank { "Unknown device" },
            requestedAtMillis = System.currentTimeMillis(),
        )
        _pendingJoinRequests.update { (it + request).sortedBy { pending -> pending.requestedAtMillis } }
        scheduleJoinRequestSweep()
        return true
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val remotePeerId = info.endpointName
            if (remotePeerId != localPeerId && meshIdentityKeyProvider?.invoke(remotePeerId) != null) {
                endpointToPeerId[endpointId] = remotePeerId
                meshHandshakeEndpoints.add(endpointId)
                acceptWithPayloadCallback(endpointId, "registered mesh member")
                return
            }
            if (!isLocalHost && remotePeerId != hostPeerIdProvider?.invoke()) {
                connectionsClient.rejectConnection(endpointId)
                return
            }
            val isIncoming = info.isIncomingConnection
            val now = System.currentTimeMillis()
            sasPromptTimes.removeAll { now - it >= SAS_PROMPT_WINDOW_MILLIS }
            if (!isWithinSasPromptRateLimit(sasPromptTimes, now)) {
                connectionsClient.rejectConnection(endpointId)
                return
            }
            if (authenticationRequests.size >= MAX_PENDING_AUTHENTICATIONS && endpointId !in authenticationRequests) {
                connectionsClient.rejectConnection(endpointId)
                return
            }
            sasPromptTimes += now
            Log.i(
                TAG,
                "Connection initiated for $endpointId " +
                    "(${if (isIncoming) "requested by peer" else "answering our own request"})"
            )
            authenticationRequests[endpointId] = ConnectionAuthenticationRequest(
                endpointId = endpointId,
                deviceName = info.endpointName,
                authenticationDigits = info.authenticationDigits,
                isIncoming = isIncoming,
            )
            events.setPendingAuthentications(authenticationRequests.values.toList())
            scheduleAuthenticationSweep()
            Log.i(TAG, "Waiting for authentication confirmation from endpoint $endpointId")
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                // Held back pending approval. Accepting now would hand the guest
                // its payload channel before the host has said yes, and the
                // onDisconnected below is what tears the request down if the
                // host says no.
                if (endpointId in _pendingJoinRequests.value.map { it.endpointId }) {
                    Log.i(TAG, "Connection to $endpointId established; still awaiting host approval")
                    return
                }
                // Both sides have to accept for payloads to flow, and on the
                // side that requested the connection this is the only place
                // left to do it. Idempotent, so a host that already accepted in
                // onConnectionInitiated is unaffected.
                acceptWithPayloadCallback(endpointId, "connection established")
                val peerId = endpointToPeerId[endpointId]?.ifBlank { null } ?: endpointId
                connectedEndpoints[peerId] = endpointId
                connectedEndpoints[endpointId] = endpointId
                if (endpointId in meshHandshakeEndpoints) {
                    beginMeshAuthentication(endpointId, peerId)
                    Log.i(TAG, "Connected to admitted mesh peer $peerId; verifying registered-key proof")
                    return
                }
                if (isLocalHost && endpointId in incomingEndpoints) {
                    val pendingProof = pendingJoinProofs.remove(endpointId)
                    if (pendingProof != null) {
                        receiveJoinProof(pendingProof, endpointId)
                    } else {
                        pendingInviteProofEndpoints.add(endpointId)
                    }
                } else if (!isLocalHost) {
                    val eventCode = localEventId ?: return
                    sendPayloadToEndpoint(
                        endpointId,
                        JoinProofPayloadDto(eventCode, inviteSecretProvider?.invoke()),
                    )
                } else {
                    authorizeEndpoint(endpointId)
                }
                Log.i(TAG, "Connection SUCCESS to $endpointId (Peer ID: $peerId). Active connected endpoints: $connectedEndpoints")
            } else {
                liveKeyControl.invalidate(endpointId, endpointToPeerId[endpointId])
                endpointToPeerId.remove(endpointId)
                meshHandshakeEndpoints.remove(endpointId)
                pendingMeshChallenges.remove(endpointId)
                meshAuthenticationTimeouts.remove(endpointId)?.let(handler::removeCallbacks)
                authenticationRequests.remove(endpointId)
                events.setPendingAuthentications(authenticationRequests.values.toList())
                scheduleAuthenticationSweep()
                incomingEndpoints.remove(endpointId)
                pendingInviteProofEndpoints.remove(endpointId)
                pendingJoinProofs.remove(endpointId)
                authenticatedHostEndpoint = authenticatedHostEndpoint?.takeUnless { it == endpointId }
                // A failed attempt is not "already requested": let the next
                // advertisement try again.
                requestedEndpoints.remove(endpointId)
                Log.w(TAG, "Connection FAILED to $endpointId: status code ${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            // A request that ends without an answer is a guest that gave up or
            // walked out of range, not a decision, so it leaves the host's list
            // silently rather than being reported as denied.
            _pendingJoinRequests.update { current -> current.filterNot { it.endpointId == endpointId } }
            authenticationRequests.remove(endpointId)
            events.setPendingAuthentications(authenticationRequests.values.toList())
            scheduleAuthenticationSweep()
            // The guest is gone, so whatever was left of its request goes with
            // it; the sweep has nothing left to refuse and must not wake for it.
            scheduleJoinRequestSweep()
            val peerId = endpointToPeerId.remove(endpointId)
            liveKeyControl.invalidate(endpointId, peerId)
            if (peerId != null) {
                connectedEndpoints.remove(peerId)
            }
            connectedEndpoints.remove(endpointId)
            authorizedEndpoints.remove(endpointId)
            incomingEndpoints.remove(endpointId)
            pendingInviteProofEndpoints.remove(endpointId)
            pendingJoinProofs.remove(endpointId)
            authenticatedHostEndpoint = authenticatedHostEndpoint?.takeUnless { it == endpointId }
            // The payload callback died with the connection, and a reconnect
            // has to be accepted again to get a working one.
            acceptedEndpoints.remove(endpointId)
            requestedEndpoints.remove(endpointId)
            meshHandshakeEndpoints.remove(endpointId)
            pendingMeshChallenges.remove(endpointId)
            meshAuthenticationTimeouts.remove(endpointId)?.let(handler::removeCallbacks)
            Log.i(TAG, "Disconnected from endpoint $endpointId (Peer: $peerId)")
            // Let the radar drop this peer immediately rather than waiting for
            // the staleness timeout to notice it went quiet.
            if (!peerId.isNullOrBlank()) {
                events.onPeerDisconnected(peerId)
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "Endpoint found: $endpointId")
            // Remember the advertised peer id straight away. This is the only
            // place a requester can learn it, and without it the endpoint has
            // to be keyed by its opaque Nearby id, which then no longer matches
            // the peer ids used everywhere else (the host peer id learned from
            // the BLE advertisement, say) - so anything addressed to that peer
            // by id, such as the chat history replayed on join, is dropped.
            val peerId = info.endpointName
            endpointToPeerId.putIfAbsent(endpointId, peerId)
            discoveredEndpointsByPeerId[peerId] = endpointId
            connectToPeer(peerId)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "Endpoint lost: $endpointId")
            endpointToPeerId[endpointId]?.let { peerId ->
                if (discoveredEndpointsByPeerId[peerId] == endpointId && connectedEndpoints[peerId] != endpointId) {
                    discoveredEndpointsByPeerId.remove(peerId)
                }
            }
        }
    }

    /**
     * Decodes a payload body, or says out loud why it could not.
     *
     * The whole transport used to swallow these failures (`runCatching { }
     * .getOrNull()`), so a malformed or unexpected payload looked exactly like
     * a device that simply never sent anything - which is the worst possible
     * outcome when debugging "messages are not arriving".
     */
    /**
     * Decodes and delivers the body of one envelope, saying why when it cannot.
     *
     * Failures used to be swallowed (`runCatching { }.getOrNull()`), so a
     * malformed or unexpected payload looked exactly like a device that simply
     * never sent anything - the worst possible outcome when the thing being
     * debugged is "messages are not arriving".
     */
    private fun dispatchEnvelope(envelope: RelayEnvelope, endpointId: String): Boolean {
        val body = envelope.payload ?: return false
        val decoded = RelayCodec.decode(envelope.type, body)
        if (decoded == null) {
            Log.w(TAG, "Unknown payload type '${envelope.type}' from $endpointId; dropped")
            return false
        }
        decoded.onFailure { error ->
            Log.e(TAG, "Could not parse a '${envelope.type}' payload from $endpointId: ${error.message}", error)
        }
        val payload = decoded.getOrNull() ?: return false
        when (payload) {
            is DecodedPayload.JoinProof -> return receiveJoinProof(payload.proof, endpointId)
            is DecodedPayload.InviteSecret -> return receiveInviteSecret(payload.secret, endpointId)
            is DecodedPayload.MeshPeerAuthentication -> return receiveMeshPeerAuthentication(payload.authentication, endpointId)
            is DecodedPayload.BleIdentityKey ->
                if (isLocalHost || endpointId == authenticatedHostEndpoint) {
                    return receiveBleIdentityKey(payload.identityKey, endpointId)
                }
            else -> Unit
        }
        if (MeshRoutingPolicy.isForwardable(envelope.type)) {
            return receiveMeshEnvelope(envelope, payload, endpointId)
        }
        if (deliver(payload, envelope, endpointId)) return true
        Log.w(TAG, "Payload of known type '${envelope.type}' from $endpointId could not be delivered; dropped")
        return false
    }

    private fun receiveMeshEnvelope(
        envelope: RelayEnvelope,
        payload: DecodedPayload,
        endpointId: String,
    ): Boolean {
        if (endpointId !in authorizedEndpoints || envelope.eventId != localEventId) return false
        if (envelope.hopCount !in 1..MeshRoutingPolicy.MAX_HOPS) return false
        if (envelope.senderPeerId != endpointToPeerId[endpointId]) return false
        val originPeerId = envelope.originPeerId ?: return false
        val messageId = envelope.messageId?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return false
        val identityKey = meshIdentityKeyProvider?.invoke(originPeerId) ?: return false
        if (identityKey.eventCode != localEventId || identityKey.peerId != originPeerId) return false
        val publicKey = BleIdentityCrypto.decodePublicKey(identityKey.publicKey) ?: return false
        val signature = runCatching { Base64.getUrlDecoder().decode(envelope.originSignature.orEmpty()) }
            .getOrNull() ?: return false
        if (!BleIdentityCrypto.verify(publicKey, MeshPeerAuthentication.envelopeSigningBytes(envelope), signature)) {
            return false
        }
        if (envelope.targetPeerId != null && meshIdentityKeyProvider?.invoke(envelope.targetPeerId) == null) return false
        if (!meshDuplicateCache.markIfNew(messageId)) return true

        val isForThisPeer = MeshRoutingPolicy.shouldDeliver(envelope.targetPeerId, localPeerId)
        var delivered = false
        if (isForThisPeer) {
            delivered = deliverMeshPayload(payload, originPeerId, envelope)
            if (!delivered) return false
        }
        if (!isForThisPeer || envelope.targetPeerId == null) {
            forwardToMeshNeighbors(envelope, excludedEndpointId = endpointId)
        }
        return true
    }

    private fun deliverMeshPayload(
        payload: DecodedPayload,
        originPeerId: String,
        envelope: RelayEnvelope,
    ): Boolean = when (payload) {
        is DecodedPayload.Chat -> payload.message.senderPeerId == originPeerId && RelayCodec.route(payload, events)
        is DecodedPayload.Beacon -> payload.beacon.senderPeerId == originPeerId && RelayCodec.route(payload, events)
        is DecodedPayload.ChatHistory ->
            originPeerId == hostPeerIdProvider?.invoke() && RelayCodec.route(payload, events)
        is DecodedPayload.BleIdentityKey -> originPeerId == hostPeerIdProvider?.invoke() && run {
            if (BleIdentityCrypto.decodePublicKey(payload.identityKey.publicKey) == null) return false
            events.onBleIdentityKey(payload.identityKey)
            true
        }
        is DecodedPayload.MemberDeparture ->
            payload.departure.eventCode == localEventId &&
                isMemberDepartureOriginAuthorized(
                    originPeerId,
                    payload.departure.peerId,
                    hostPeerIdProvider?.invoke(),
                ) && run {
            if (isLocalHost) broadcastMemberDeparture(payload.departure)
            events.onMemberDeparture(payload.departure)
            true
        }
        is DecodedPayload.MeshPresence -> originPeerId == payload.presence.peerId &&
            payload.presence.eventCode == localEventId && run {
                events.onMeshPresence(payload.presence)
                true
            }
        is DecodedPayload.PeerRanging -> originPeerId == payload.ranging.peerId &&
            payload.ranging.eventCode == localEventId && payload.ranging.sequence >= 0 &&
            RelayCodec.route(payload, events)
        is DecodedPayload.EventControl -> originPeerId == hostPeerIdProvider?.invoke() &&
            payload.control.hostPeerId == originPeerId && payload.control.eventCode == localEventId &&
            RelayCodec.route(payload, events)
        is DecodedPayload.UwbAddress -> payload.address.peerId == originPeerId && RelayCodec.route(payload, events)
        is DecodedPayload.UwbParameters -> originPeerId == hostPeerIdProvider?.invoke() &&
            payload.params.hostPeerId == originPeerId && RelayCodec.route(payload, events)
        is DecodedPayload.UwbOffer -> payload.offer.fromPeerId == originPeerId &&
            payload.offer.targetPeerId == envelope.targetPeerId && payload.offer.targetPeerId == localPeerId && run {
                events.onUwbRangingOfferReceived(payload.offer)
                true
            }
        else -> false
    }

    /** Handles non-mesh payloads received directly over an authorized endpoint. */
    private fun deliver(payload: DecodedPayload, envelope: RelayEnvelope, endpointId: String): Boolean = when (payload) {
        is DecodedPayload.JoinProof -> receiveJoinProof(payload.proof, endpointId)
        is DecodedPayload.InviteSecret -> receiveInviteSecret(payload.secret, endpointId)
        is DecodedPayload.BleIdentityKey -> receiveBleIdentityKey(payload.identityKey, endpointId)
        is DecodedPayload.MemberDeparture -> receiveMemberDeparture(payload.departure, endpointId)
        is DecodedPayload.MeshPeerAuthentication -> receiveMeshPeerAuthentication(payload.authentication, endpointId)
        else -> {
            if (endpointId !in authorizedEndpoints) return false
            val endpointPeerId = endpointToPeerId[endpointId] ?: return false
            when (payload) {
                is DecodedPayload.Chat ->
                    if (isPayloadSenderAuthorized(
                            isLocalHost,
                            endpointPeerId,
                            endpointToPeerId[authenticatedHostEndpoint],
                            payload.message.senderPeerId,
                        )
                    ) {
                        RelayCodec.route(payload, events)
                    } else false
                is DecodedPayload.Beacon ->
                    if (isPayloadSenderAuthorized(
                            isLocalHost,
                            endpointPeerId,
                            endpointToPeerId[authenticatedHostEndpoint],
                            payload.beacon.senderPeerId,
                        )
                    ) {
                        RelayCodec.route(payload, events)
                    } else false
                is DecodedPayload.ChatHistory ->
                    !isLocalHost && endpointId == authenticatedHostEndpoint && RelayCodec.route(payload, events)
                is DecodedPayload.UwbAddress ->
                    isPayloadSenderAuthorized(
                        isLocalHost,
                        endpointPeerId,
                        endpointToPeerId[authenticatedHostEndpoint],
                        payload.address.peerId,
                    ) && RelayCodec.route(payload, events)
                is DecodedPayload.UwbParameters ->
                    (!isLocalHost && endpointId == authenticatedHostEndpoint && payload.params.hostPeerId == endpointPeerId) &&
                        RelayCodec.route(payload, events)
                is DecodedPayload.ChatColourClaim ->
                    // Authorized exactly like chat, and for the same reason: the
                    // claim names the member holding the colour, so a peer able to
                    // send one on another's behalf could take their colour and
                    // grey it out for them. Only the host may send a granted reply.
                    if (isPayloadSenderAuthorized(
                            isLocalHost,
                            endpointPeerId,
                            endpointToPeerId[authenticatedHostEndpoint],
                            payload.claim.peerId,
                        )
                    ) {
                        RelayCodec.route(payload, events)
                    } else false
                is DecodedPayload.EventControl ->
                    (!isLocalHost && endpointId == authenticatedHostEndpoint &&
                        payload.control.eventCode == localEventId &&
                        payload.control.hostPeerId == endpointPeerId) && RelayCodec.route(payload, events)
                is DecodedPayload.UwbOffer -> if (isLocalHost) {
                    payload.offer.fromPeerId == endpointPeerId && relayOffer(payload.offer, endpointId)
                } else if (endpointId == authenticatedHostEndpoint && payload.offer.targetPeerId == localPeerId) {
                    events.onUwbRangingOfferReceived(payload.offer)
                    true
                } else false
                is DecodedPayload.InviteSecret, is DecodedPayload.BleIdentityKey,
                is DecodedPayload.MemberDeparture, is DecodedPayload.MeshPeerAuthentication,
                is DecodedPayload.MeshPresence, is DecodedPayload.PeerRanging,
                is DecodedPayload.JoinProof, is DecodedPayload.MockRanging -> false
            }
        }
    }

    private fun receiveJoinProof(proof: JoinProofPayloadDto, endpointId: String): Boolean {
        if (!isLocalHost || proof.eventCode != localEventId) return false
        if (endpointId !in acceptedEndpoints || endpointId !in incomingEndpoints) return false
        if (endpointId !in connectedEndpoints.values) {
            pendingJoinProofs[endpointId] = proof
            return true
        }
        if (endpointId in authorizedEndpoints) return true

        val expectedSecret = inviteSecretProvider?.invoke() ?: return false
        val hasValidInvite = proof.inviteSecret == expectedSecret &&
            InviteSecret.decode(proof.inviteSecret.orEmpty()) != null
        when (
            decideJoinRequest(
                isIncoming = true,
                requiresApproval = joinRequiresHostApproval(hasValidInvite),
                acceptsNewMembers = acceptsNewMembers,
                connectedPeerCount = authorizedEndpoints.size + _pendingJoinRequests.value.size,
            )
        ) {
            JoinRequestDecision.Refuse -> {
                pendingInviteProofEndpoints.remove(endpointId)
                connectionsClient.rejectConnection(endpointId)
                return true
            }
            JoinRequestDecision.Admit -> {
                pendingInviteProofEndpoints.remove(endpointId)
                authorizeEndpoint(endpointId)
                return true
            }
            JoinRequestDecision.AwaitApproval -> Unit
        }

        pendingInviteProofEndpoints.add(endpointId)
        val request = PendingJoinRequest(
            endpointId = endpointId,
            deviceName = endpointToPeerId[endpointId].orEmpty().ifBlank { "Unknown device" },
            requestedAtMillis = System.currentTimeMillis(),
        )
        _pendingJoinRequests.update { current ->
            (current.filterNot { it.endpointId == endpointId } + request)
                .sortedBy { it.requestedAtMillis }
        }
        scheduleJoinRequestSweep()
        return true
    }

    private fun receiveInviteSecret(secret: InviteSecretPayloadDto, endpointId: String): Boolean {
        if (isLocalHost || endpointId != authenticatedHostEndpoint) return false
        if (secret.eventCode != localEventId) return false
        if (secret.hostPeerId != endpointToPeerId[endpointId]) return false
        if (InviteSecret.decode(secret.inviteSecret) == null) return false
        val existingSecret = inviteSecretProvider?.invoke()
        if (existingSecret != null && existingSecret != secret.inviteSecret) return false
        authorizedEndpoints.add(endpointId)
        events.onPeerConnected(secret.hostPeerId)
        events.onInviteSecret(secret)
        return true
    }

    private fun receiveBleIdentityKey(identityKey: BleIdentityKeyPayloadDto, endpointId: String): Boolean {
        if (identityKey.eventCode != localEventId) return false
        if (!identityKey.peerId.matches(Regex("[0-9a-fA-F]{8}"))) return false
        if (BleIdentityCrypto.decodePublicKey(identityKey.publicKey) == null) return false
        if (!isBleIdentityKeySenderAuthorized(
                isLocalHost = isLocalHost,
                endpointId = endpointId,
                endpointPeerId = endpointToPeerId[endpointId],
                endpointAuthorized = endpointId in authorizedEndpoints,
                authenticatedHostEndpoint = authenticatedHostEndpoint,
                identityPeerId = identityKey.peerId,
            )
        ) return false
        events.onBleIdentityKey(identityKey)
        return true
    }

    private fun receiveMemberDeparture(departure: MemberDeparturePayloadDto, endpointId: String): Boolean {
        if (departure.eventCode != localEventId) return false
        if (!departure.peerId.matches(Regex("[0-9a-fA-F]{8}"))) return false
        if (!isMemberDepartureSenderAuthorized(
                isLocalHost = isLocalHost,
                endpointId = endpointId,
                endpointPeerId = endpointToPeerId[endpointId],
                endpointAuthorized = endpointId in authorizedEndpoints,
                authenticatedHostEndpoint = authenticatedHostEndpoint,
                departurePeerId = departure.peerId,
            )
        ) return false
        if (isLocalHost) broadcastMemberDeparture(departure)
        events.onMemberDeparture(departure)
        return true
    }

    private fun beginMeshAuthentication(endpointId: String, peerId: String) {
        val eventCode = localEventId ?: return
        val identity = meshIdentityKeyProvider?.invoke(peerId) ?: return
        if (identity.eventCode != eventCode) return
        val nowMillis = SystemClock.elapsedRealtime()
        if (liveKeyControl.hasPending(endpointId, nowMillis)) return
        if (meshPrivateKeyProvider?.invoke() == null) {
            connectionsClient.disconnectFromEndpoint(endpointId)
            return
        }
        val challenge = ByteArray(MeshPeerAuthentication.CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        pendingMeshChallenges[endpointId] = challenge
        liveKeyControl.begin(endpointId, peerId, identity.publicKey, challenge, nowMillis)
        sendPayloadToEndpoint(
            endpointId,
            MeshLinkAuthenticationPayloadDto(
                eventCode = eventCode,
                challengerPeerId = localPeerId,
                responderPeerId = peerId,
                challenge = MeshPeerAuthentication.encodeChallenge(challenge),
            ),
        )
        meshAuthenticationTimeouts.remove(endpointId)?.let(handler::removeCallbacks)
        meshAuthenticationTimeouts[endpointId] = Runnable {
            if (endpointId !in authorizedEndpoints) {
                Log.w(TAG, "Mesh key proof timed out for $peerId; disconnecting the unverified link")
                connectionsClient.disconnectFromEndpoint(endpointId)
            }
        }.also { handler.postDelayed(it, MESH_AUTHENTICATION_TIMEOUT_MILLIS) }
    }

    private fun receiveMeshPeerAuthentication(
        authentication: MeshLinkAuthenticationPayloadDto,
        endpointId: String,
    ): Boolean {
        if (endpointId !in meshHandshakeEndpoints || authentication.eventCode != localEventId) return false
        val endpointPeerId = endpointToPeerId[endpointId] ?: return false
        val challenge = MeshPeerAuthentication.decodeChallenge(authentication.challenge) ?: return false
        if (authentication.signature == null) {
            if (authentication.challengerPeerId != endpointPeerId || authentication.responderPeerId != localPeerId) {
                return false
            }
            val privateKey = meshPrivateKeyProvider?.invoke() ?: return false
            val signedBytes = MeshPeerAuthentication.signingBytes(
                authentication.eventCode,
                authentication.challengerPeerId,
                authentication.responderPeerId,
                challenge,
            )
            val signature = BleIdentityCrypto.sign(privateKey, signedBytes)
            sendPayloadToEndpoint(
                endpointId,
                authentication.copy(
                    signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
                ),
            )
            return true
        }

        if (authentication.challengerPeerId != localPeerId || authentication.responderPeerId != endpointPeerId) {
            return false
        }
        val pendingChallenge = pendingMeshChallenges[endpointId] ?: return false
        if (!pendingChallenge.contentEquals(challenge)) return false
        val registeredIdentity = meshIdentityKeyProvider?.invoke(endpointPeerId) ?: return false
        val publicKey = BleIdentityCrypto.decodePublicKey(registeredIdentity.publicKey) ?: return false
        val signature = runCatching { Base64.getUrlDecoder().decode(authentication.signature) }.getOrNull()
            ?: return false
        val signedBytes = MeshPeerAuthentication.signingBytes(
            authentication.eventCode,
            authentication.challengerPeerId,
            authentication.responderPeerId,
            challenge,
        )
        if (!BleIdentityCrypto.verify(publicKey, signedBytes, signature)) return false
        if (!liveKeyControl.complete(
                endpointId,
                endpointPeerId,
                registeredIdentity.publicKey,
                challenge,
                SystemClock.elapsedRealtime(),
            )
        ) return false

        pendingMeshChallenges.remove(endpointId)
        meshAuthenticationTimeouts.remove(endpointId)?.let(handler::removeCallbacks)
        if (authorizedEndpoints.add(endpointId)) {
            connectedEndpoints[endpointPeerId] = endpointId
            connectedEndpoints[endpointId] = endpointId
            if (!isLocalHost && endpointPeerId == hostPeerIdProvider?.invoke()) {
                authenticatedHostEndpoint = endpointId
            }
        }
        replayPendingMessages(endpointId)
        return true
    }

    private fun relayOffer(offer: UwbRangingOfferDto, endpointId: String): Boolean {
        val targetEndpoint = connectedEndpoints[offer.targetPeerId]
        if (targetEndpoint == null) {
            Log.w(
                TAG,
                "UWB ranging offer from ${offer.fromPeerId} cannot be relayed: " +
                    "no connection to ${offer.targetPeerId} (arrived from $endpointId)"
            )
        } else {
            Log.i(TAG, "Relaying a UWB ranging offer from ${offer.fromPeerId} to ${offer.targetPeerId}")
            sendPayloadToEndpoint(targetEndpoint, offer)
        }
        // Consumed either way: a host has no UWB session of its own for this
        // offer, and reporting it as delivered keeps it off every other flow.
        return true
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes()
            if (bytes == null) {
                Log.w(TAG, "Ignoring a non-bytes payload (type=${payload.type}) from $endpointId")
                return
            }
            // Explicit UTF-8, matching how it was written with toByteArray().
            // String(ByteArray) would use the platform default charset, which
            // is only ever accidentally right.
            val json = bytes.toString(Charsets.UTF_8)
            val peer = endpointToPeerId[endpointId] ?: "?"
            Log.d(TAG, "Received ${bytes.size} bytes from $endpointId [peer=$peer]")

            // First try parsing as structured RelayEnvelope
            val envelope = RelayCodec.decodeEnvelope(json).getOrElse { error ->
                Log.e(TAG, "Could not parse the envelope from $endpointId: ${error.message}", error)
                null
            }
            if (envelope != null && envelope.payload != null) {
                // [dispatchEnvelope] has already said why when it cannot deliver,
                // so there is nothing left to report here.
                dispatchEnvelope(envelope, endpointId)
                return
            }

            // A mesh payload without its signed routing envelope cannot prove
            // its origin or participate in deduplication/hop limits.
            val legacy = RelayCodec.decodeUnwrapped(json)
            Log.w(
                TAG,
                if (legacy == null) "Unrecognised ${bytes.size}-byte payload from $endpointId, dropped"
                else "Unwrapped ${legacy::class.simpleName} payload from $endpointId lacks mesh authentication, dropped"
            )
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

}