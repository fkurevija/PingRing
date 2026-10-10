package com.fkurevija.pingring.data

import com.fkurevija.pingring.data.transport.NearbyAdmissionCoordinator
import com.fkurevija.pingring.data.transport.NearbyInboundMeshDispatcher
import com.fkurevija.pingring.data.transport.NearbyMessageReceiver
import com.fkurevija.pingring.data.transport.NearbyMeshOutboundSender
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.security.SecureRandom
import java.util.Base64
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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

internal fun hasDuplicatePeerConnection(
    peerId: String,
    endpointId: String,
    endpointByPeerId: Map<String, String>,
    pendingEndpointsByPeerId: Map<String, String>,
): Boolean = listOfNotNull(endpointByPeerId[peerId], pendingEndpointsByPeerId[peerId])
    .any { it != endpointId }

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
    private val admissionCoordinator = NearbyAdmissionCoordinator(
        onPendingAuthenticationRemoved = events::removePendingAuthentication,
        onPendingAuthenticationsCleared = { events.setPendingAuthentications(emptyList()) },
        onAuthenticationExpired = { request ->
            refuseJoinControlChannel(request.endpointId)
            Log.w(TAG, "Join control channel timed out before admission")
        },
        onJoinRequestsExpired = { expired ->
            Log.i(
                TAG,
                "Refusing ${expired.size} join request(s) unanswered for over " +
                    "${JOIN_REQUEST_TIMEOUT_MILLIS / 1000}s: " +
                    expired.joinToString { "${it.deviceName} (${it.endpointId})" }
            )
            expired.forEach { refuseJoinControlChannel(it.endpointId) }
        },
    )

    override val receivedPeerAddress: SharedFlow<UwbPeerAddressDto> get() = events.receivedPeerAddress
    override val receivedControllerParams: SharedFlow<UwbControllerParametersDto> get() = events.receivedControllerParams

    /** Only the addressed guest receives a ranging offer; intermediate peers forward it. */
    override val receivedUwbRangingOffer: SharedFlow<UwbRangingOfferDto> get() = events.receivedUwbRangingOffer
    override val receivedChatMessage: SharedFlow<ChatPayloadDto> get() = events.receivedChatMessage
    override val receivedChatHistory: SharedFlow<ChatHistoryPayloadDto> get() = events.receivedChatHistory
    override val receivedChatReceipt: SharedFlow<ChatReceiptPayloadDto> get() = events.receivedChatReceipt
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
    override val peerNames: SharedFlow<Pair<String, String>> get() = events.peerNames

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
    private val incomingEndpoints = mutableSetOf<String>()
    private val pendingInviteProofEndpoints = mutableSetOf<String>()
    private val pendingJoinProofs = mutableMapOf<String, JoinProofPayloadDto>()
    private var authenticatedHostEndpoint: String? = null
    private var discoveredHostPeerId: String? = null
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
    override var userNameProvider: (() -> String?)? = null
    var meshIdentityKeyProvider: ((String) -> BleIdentityKeyPayloadDto?)? = null
    var meshPrivateKeyProvider: (() -> java.security.PrivateKey?)? = null
    private val meshOutboundSender by lazy {
        NearbyMeshOutboundSender(
            tag = TAG,
            connectionsClient = connectionsClient,
            state = NearbyMeshOutboundSender.State(
                eventId = { localEventId },
                localPeerId = { localPeerId },
                privateKey = { meshPrivateKeyProvider?.invoke() },
                authorizedEndpointIds = { authorizedEndpoints.toSet() },
                connectedEndpointForPeer = connectedEndpoints::get,
                connectedEndpointIds = { connectedEndpoints.values.toSet() },
                endpointPeerId = endpointToPeerId::get,
            ),
            duplicateCache = meshDuplicateCache,
        )
    }

    // Endpoints this device has accepted, i.e. the ones a PayloadCallback is
    // registered for. Nearby only hands payloads to an accepted connection, and
    // a dropped connection has to be accepted again to get a new callback.
    private val acceptedEndpoints = mutableSetOf<String>()

    // Endpoints a connection has already been requested for, so a repeated
    // discovery callback (advertisements come in continuously) does not keep
    // re-requesting a connection that is already up.
    private val requestedEndpoints = mutableSetOf<String>()
    private var localPeerId: String = ""
    @Volatile
    var requireDigitComparison: Boolean = true
    override var acceptsNewMembers: Boolean = true

    override val pendingJoinRequests: StateFlow<List<PendingJoinRequest>>
        get() = admissionCoordinator.pendingJoinRequests
    override val pendingAuthentications: StateFlow<List<ConnectionAuthenticationRequest>> =
        events.pendingAuthentications

    private fun hasOtherEndpointForPeer(peerId: String, endpointId: String): Boolean {
        val pendingPeerEndpoints = buildMap {
            admissionCoordinator.authenticationRequestValues
                .filter { it.endpointId != endpointId && it.deviceName.isNotBlank() }
                .forEach { put(it.deviceName, it.endpointId) }
            admissionCoordinator.pendingJoinRequests.value.forEach { request ->
                if (request.endpointId != endpointId) {
                    endpointToPeerId[request.endpointId]?.let { put(it, request.endpointId) }
                }
            }
        }
        return hasDuplicatePeerConnection(
            peerId = peerId,
            endpointId = endpointId,
            endpointByPeerId = connectedEndpoints,
            pendingEndpointsByPeerId = pendingPeerEndpoints,
        )
    }

    private fun acceptJoinControlChannel(request: ConnectionAuthenticationRequest) {
        val endpointId = request.endpointId
        endpointToPeerId[endpointId] = request.deviceName
        if (request.isIncoming) {
            incomingEndpoints.add(endpointId)
            val reserved = authorizedEndpoints.size + admissionCoordinator.pendingJoinRequests.value.size
            if (!acceptsNewMembers || reserved >= MAX_PARTICIPANTS - 1) {
                admissionCoordinator.removeAuthenticationRequest(endpointId)
                events.removePendingAuthentication(endpointId)
                scheduleAuthenticationSweep()
                connectionsClient.rejectConnection(endpointId)
                return
            }
        } else {
            authenticatedHostEndpoint = endpointId
        }
        // Ordinary payloads remain blocked until QR proof or host approval.
        acceptWithPayloadCallback(endpointId, "join control channel")
    }

    /** Platform callback-thread scheduling for transport lifecycle work. */
    private val handler = Handler(Looper.getMainLooper())
    private var pendingClosureSends = 0
    private var closureStopRequested = false
    private var forceClosureStopRunnable: Runnable? = null
    private var meshPresenceRunnable: Runnable? = null

    private fun scheduleAuthenticationSweep() = admissionCoordinator.scheduleAuthenticationSweep()

    private fun refuseJoinControlChannel(endpointId: String) {
        acceptedEndpoints.remove(endpointId)
        incomingEndpoints.remove(endpointId)
        pendingInviteProofEndpoints.remove(endpointId)
        pendingJoinProofs.remove(endpointId)
        if (endpointId in connectedEndpoints.values) {
            // Reject only applies to pending requests, not established control channels.
            connectionsClient.disconnectFromEndpoint(endpointId)
        } else {
            if (authenticatedHostEndpoint == endpointId) authenticatedHostEndpoint = null
            endpointToPeerId.remove(endpointId)
            requestedEndpoints.remove(endpointId)
            connectionsClient.rejectConnection(endpointId)
        }
    }

    override fun approveJoinRequest(endpointId: String) {
        val request = admissionCoordinator.takePendingJoinRequest(endpointId)
        if (request == null) {
            Log.w(TAG, "Ignoring approval of $endpointId: no such pending request")
            return
        }
        if (endpointId !in pendingInviteProofEndpoints) {
            refuseJoinControlChannel(endpointId)
            scheduleJoinRequestSweep()
            return
        }
        pendingInviteProofEndpoints.remove(endpointId)
        authorizeEndpoint(endpointId)
        Log.i(TAG, "Approved join request from ${request.deviceName} ($endpointId)")
        scheduleJoinRequestSweep()
    }

    override fun denyJoinRequest(endpointId: String) {
        val request = admissionCoordinator.takePendingJoinRequest(endpointId)
        if (request == null) {
            // A normal race: the request may have expired, or the guest may have
            // gone, between the host tapping Deny and this arriving.
            Log.w(TAG, "Ignoring denial of $endpointId: no such pending request")
            return
        }
        refuseJoinControlChannel(endpointId)
        Log.i(TAG, "Denied join request from ${request.deviceName} ($endpointId)")
        scheduleJoinRequestSweep()
    }

    override fun confirmConnectionAuthentication(endpointId: String) {
        val request = admissionCoordinator.authenticationRequest(endpointId)
        if (request == null || endpointId !in events.pendingAuthentications.value.map { it.endpointId }) {
            Log.w(TAG, "Ignoring authentication confirmation for $endpointId: no pending request")
            return
        }
        events.removePendingAuthentication(endpointId)
        acceptJoinControlChannel(request)
        Log.i(TAG, "Nearby digit comparison confirmed for endpoint $endpointId")
    }

    override fun rejectConnectionAuthentication(endpointId: String) {
        if (endpointId !in events.pendingAuthentications.value.map { it.endpointId }) {
            Log.w(TAG, "Ignoring authentication rejection for $endpointId: no pending request")
            return
        }
        admissionCoordinator.removeAuthenticationRequest(endpointId)
        events.removePendingAuthentication(endpointId)
        refuseJoinControlChannel(endpointId)
        scheduleAuthenticationSweep()
        Log.i(TAG, "Nearby connection rejected before admission")
    }

    private fun scheduleJoinRequestSweep() = admissionCoordinator.scheduleJoinRequestSweep()

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
            NearbyDiscoveryIdentity.encode(localPeerId, isHost),
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
                broadcastMemberPresence(MeshPresencePayloadDto(eventCode, localPeerId, userNameProvider?.invoke()))
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
        val hostPeerId = hostPeerIdProvider?.invoke() ?: discoveredHostPeerId
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
        meshOutboundSender.clearPendingMessages()
        meshHandshakeEndpoints.clear()
        pendingMeshChallenges.clear()
        liveKeyControl.clear()
        meshAuthenticationTimeouts.values.forEach(handler::removeCallbacks)
        meshAuthenticationTimeouts.clear()
        authenticatedHostEndpoint = null
        discoveredHostPeerId = null
        localEventId = null
        // Held requests and their timers belong to this event, not the next one.
        admissionCoordinator.clear()
    }

    /** Nearby peers receive closure through the authenticated EventControl payload. */
    override fun announceEventClosed() = Unit

    override fun sendUwbParameters(peerId: String, params: Any) {
        sendMeshTargetedPayload(peerId, params)
    }

    override fun broadcastUwbParameters(params: Any) {
        sendMeshBroadcast(params)
    }

    override fun broadcastMemberPresence(presence: MeshPresencePayloadDto) {
        sendMeshBroadcast(presence)
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

    override fun broadcastChatHistory(messages: List<ChatPayloadDto>) {
        sendMeshBroadcast(ChatHistoryPayloadDto(messages))
    }

    override fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>) {
        sendMeshTargetedPayload(peerId, ChatHistoryPayloadDto(messages))
    }

    override fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto) {
        sendMeshTargetedPayload(peerId, chatMsg)
    }

    override fun sendChatReceiptToPeer(peerId: String, receipt: ChatReceiptPayloadDto) {
        sendMeshTargetedPayload(peerId, receipt)
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

    override fun removeMember(
        peerId: String,
        departure: MemberDeparturePayloadDto,
        removalControl: EventControlPayloadDto,
    ) {
        if (!isLocalHost || peerId == localPeerId || departure.peerId != peerId ||
            departure.eventCode != localEventId || removalControl.eventCode != localEventId ||
            !removalControl.isClosed
        ) {
            Log.w(TAG, "Ignoring invalid host member removal request")
            return
        }
        val endpointId = connectedEndpoints[peerId]
        events.onMemberDeparture(departure)
        val pendingSends = java.util.concurrent.atomic.AtomicInteger(2)
        val finishRemoval = {
            if (pendingSends.decrementAndGet() == 0) {
                if (endpointId != null) {
                    connectedEndpoints.remove(peerId, endpointId)
                    connectedEndpoints.remove(endpointId)
                    authorizedEndpoints.remove(endpointId)
                    connectionsClient.disconnectFromEndpoint(endpointId)
                }
            }
        }
        // These notifications are courtesy only. The departure above removes
        // the member from this host immediately, even if the guest is already
        // unreachable or either send fails.
        sendMeshBroadcast(departure, finishRemoval)
        sendMeshTargetedPayload(peerId, removalControl, finishRemoval)
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

    private fun sendPayloadToEndpoint(endpointId: String, params: Any, onComplete: (() -> Unit)? = null) =
        meshOutboundSender.sendPayloadToEndpoint(endpointId, params, onComplete)

    private fun sendMeshBroadcast(params: Any, onComplete: (() -> Unit)? = null) =
        meshOutboundSender.sendMeshBroadcast(params, onComplete)

    private fun sendMeshTargetedPayload(peerId: String, params: Any, onComplete: (() -> Unit)? = null) =
        meshOutboundSender.sendMeshTargetedPayload(peerId, params, onComplete)

    private fun forwardToMeshNeighbors(
        envelope: RelayEnvelope,
        excludedEndpointId: String?,
        onComplete: (() -> Unit)? = null,
    ) = meshOutboundSender.forwardToMeshNeighbors(envelope, excludedEndpointId, onComplete)

    private fun replayPendingMessages(endpointId: String) = meshOutboundSender.replayPendingMessages(endpointId)

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
        pendingJoinProofs.remove(endpointId)?.guestName?.let { events.onPeerName(peerId, it) }
        events.onPeerConnected(peerId)
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val remoteIdentity = NearbyDiscoveryIdentity.decode(info.endpointName)
            if (remoteIdentity == null) {
                Log.w(TAG, "Rejecting Nearby endpoint with an invalid discovery identity")
                connectionsClient.rejectConnection(endpointId)
                return
            }
            val remotePeerId = remoteIdentity.peerId
            if (remotePeerId != localPeerId && hasOtherEndpointForPeer(remotePeerId, endpointId)) {
                Log.w(TAG, "Rejecting duplicate connection endpoint for peer $remotePeerId")
                connectionsClient.rejectConnection(endpointId)
                return
            }
            if (remotePeerId != localPeerId && meshIdentityKeyProvider?.invoke(remotePeerId) != null) {
                endpointToPeerId[endpointId] = remotePeerId
                meshHandshakeEndpoints.add(endpointId)
                acceptWithPayloadCallback(endpointId, "registered mesh member")
                return
            }
            if (!isLocalHost && remotePeerId != (hostPeerIdProvider?.invoke() ?: discoveredHostPeerId)) {
                connectionsClient.rejectConnection(endpointId)
                return
            }
            val isIncoming = info.isIncomingConnection
            val now = System.currentTimeMillis()
            if (!admissionCoordinator.mayPromptForAuthentication(endpointId, now)) {
                connectionsClient.rejectConnection(endpointId)
                return
            }
            Log.i(
                TAG,
                "Connection initiated for $endpointId " +
                    "(${if (isIncoming) "requested by peer" else "answering our own request"})"
            )
            val request = ConnectionAuthenticationRequest(
                endpointId = endpointId,
                deviceName = remotePeerId,
                authenticationDigits = info.authenticationDigits,
                isIncoming = isIncoming,
            )
            admissionCoordinator.recordAuthenticationPrompt(request, now)
            if (requireDigitComparison) {
                events.setPendingAuthentications(events.pendingAuthentications.value + request)
            }
            scheduleAuthenticationSweep()
            if (requireDigitComparison) {
                Log.i(TAG, "Waiting for Nearby digit comparison for endpoint $endpointId")
            } else {
                acceptJoinControlChannel(request)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                // Held back pending approval. Accepting now would hand the guest
                // its payload channel before the host has said yes, and the
                // onDisconnected below is what tears the request down if the
                // host says no.
                if (admissionCoordinator.containsPendingJoinRequest(endpointId)) {
                    Log.i(TAG, "Connection to $endpointId established; still awaiting host approval")
                    return
                }
                // Both sides have to accept for payloads to flow, and on the
                // side that requested the connection this is the only place
                // left to do it. Idempotent, so a host that already accepted in
                // onConnectionInitiated is unaffected.
                acceptWithPayloadCallback(endpointId, "connection established")
                val peerId = endpointToPeerId[endpointId]?.ifBlank { null } ?: endpointId
                if (isLocalHost && endpointId in incomingEndpoints &&
                    hasOtherEndpointForPeer(peerId, endpointId)
                ) {
                    Log.w(TAG, "Rejecting duplicate connection result for peer $peerId")
                    admissionCoordinator.removeAuthenticationRequest(endpointId)
                    events.removePendingAuthentication(endpointId)
                    incomingEndpoints.remove(endpointId)
                    pendingJoinProofs.remove(endpointId)
                    pendingInviteProofEndpoints.remove(endpointId)
                    acceptedEndpoints.remove(endpointId)
                    endpointToPeerId.remove(endpointId)
                    connectionsClient.disconnectFromEndpoint(endpointId)
                    return
                }
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
                        JoinProofPayloadDto(eventCode, inviteSecretProvider?.invoke(), userNameProvider?.invoke()),
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
                admissionCoordinator.removeAuthenticationRequest(endpointId)
                events.removePendingAuthentication(endpointId)
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
            admissionCoordinator.takePendingJoinRequest(endpointId)
            admissionCoordinator.removeAuthenticationRequest(endpointId)
            events.removePendingAuthentication(endpointId)
            scheduleAuthenticationSweep()
            // The guest is gone, so whatever was left of its request goes with
            // it; the sweep has nothing left to refuse and must not wake for it.
            scheduleJoinRequestSweep()
            val peerId = endpointToPeerId.remove(endpointId)
            liveKeyControl.invalidate(endpointId, peerId)
            if (peerId != null) {
                if (connectedEndpoints[peerId] == endpointId) {
                    connectedEndpoints.remove(peerId)
                }
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
            val identity = NearbyDiscoveryIdentity.decode(info.endpointName)
            if (identity == null) {
                Log.w(TAG, "Ignoring Nearby endpoint with an invalid discovery identity")
                return
            }
            val peerId = identity.peerId
            discoveredHostPeerId = NearbyDiscoveryIdentity.hostCandidate(
                knownHostPeerId = hostPeerIdProvider?.invoke(),
                discoveredHostPeerId = discoveredHostPeerId,
                identity = identity,
                localPeerId = localPeerId,
                isLocalHost = isLocalHost,
            )
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

    private fun receiveJoinProof(proof: JoinProofPayloadDto, endpointId: String): Boolean {
        if (!isLocalHost || proof.eventCode != localEventId) return false
        if (endpointId !in acceptedEndpoints || endpointId !in incomingEndpoints) return false
        if (endpointId in authorizedEndpoints) return true
        val peerId = endpointToPeerId[endpointId] ?: return false
        val activeEndpoint = connectedEndpoints[peerId]
        if (activeEndpoint != null && activeEndpoint != endpointId) {
            Log.w(TAG, "Rejecting duplicate join proof from already-connected peer $peerId")
            refuseJoinControlChannel(endpointId)
            return true
        }
        val otherPendingEndpoint = admissionCoordinator.pendingJoinRequests.value.firstOrNull { request ->
            request.endpointId != endpointId && endpointToPeerId[request.endpointId] == peerId
        }?.endpointId
        if (otherPendingEndpoint != null) {
            Log.w(TAG, "Rejecting duplicate join proof from peer $peerId with another request pending")
            refuseJoinControlChannel(endpointId)
            return true
        }
        val guestName = proof.guestName?.takeIf { it.isNotBlank() }
            ?: pendingJoinProofs[endpointId]?.guestName?.takeIf { it.isNotBlank() }
        pendingJoinProofs[endpointId] = proof.copy(guestName = guestName)
        if (endpointId !in connectedEndpoints.values) {
            return true
        }
        val expectedSecret = inviteSecretProvider?.invoke() ?: return false
        admissionCoordinator.removeAuthenticationRequest(endpointId)
        events.removePendingAuthentication(endpointId)
        scheduleAuthenticationSweep()
        val hasValidInvite = proof.inviteSecret == expectedSecret &&
            InviteSecret.decode(proof.inviteSecret.orEmpty()) != null
        when (
            decideJoinRequest(
                isIncoming = true,
                requiresApproval = joinRequiresHostApproval(hasValidInvite),
                acceptsNewMembers = acceptsNewMembers,
                connectedPeerCount = authorizedEndpoints.size + admissionCoordinator.pendingJoinRequests.value.size,
            )
        ) {
            JoinRequestDecision.Refuse -> {
                refuseJoinControlChannel(endpointId)
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
        val previousRequest = admissionCoordinator.pendingJoinRequest(endpointId)
        val request = PendingJoinRequest(
            endpointId = endpointId,
            deviceName = guestName ?: "Guest device",
            requestedAtMillis = previousRequest?.requestedAtMillis ?: System.currentTimeMillis(),
        )
        admissionCoordinator.addPendingJoinRequest(request)
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
        admissionCoordinator.removeAuthenticationRequest(endpointId)
        events.removePendingAuthentication(endpointId)
        scheduleAuthenticationSweep()
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

    private val messageReceiver by lazy {
        NearbyMessageReceiver(
            tag = TAG,
            endpointPeerId = { endpointId -> endpointToPeerId[endpointId] },
            events = events,
            duplicateCache = meshDuplicateCache,
            state = NearbyInboundMeshDispatcher.State(
                isLocalHost = { isLocalHost },
                localEventId = { localEventId },
                localPeerId = { localPeerId },
                authenticatedHostEndpoint = { authenticatedHostEndpoint },
                hostPeerId = { hostPeerIdProvider?.invoke() },
                isEndpointAuthorized = { it in authorizedEndpoints },
                endpointPeerId = { endpointId -> endpointId?.let(endpointToPeerId::get) },
                meshIdentityKey = { meshIdentityKeyProvider?.invoke(it) },
            ),
            callbacks = NearbyInboundMeshDispatcher.Callbacks(
                receiveJoinProof = ::receiveJoinProof,
                receiveInviteSecret = ::receiveInviteSecret,
                receiveMeshPeerAuthentication = ::receiveMeshPeerAuthentication,
                receiveBleIdentityKey = ::receiveBleIdentityKey,
                receiveMemberDeparture = ::receiveMemberDeparture,
                broadcastMemberDeparture = ::broadcastMemberDeparture,
                forwardMeshEnvelope = { envelope, endpointId ->
                    forwardToMeshNeighbors(envelope, excludedEndpointId = endpointId)
                },
                relayOffer = ::relayOffer,
                onUwbRangingOfferReceived = events::onUwbRangingOfferReceived,
            ),
        )
    }

    private val payloadCallback get() = messageReceiver.payloadCallback

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

}