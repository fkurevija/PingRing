package com.fkurevija.pingring.data.radar

import android.content.Context
import android.os.SystemClock
import androidx.core.uwb.RangingResult
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import com.fkurevija.pingring.data.ranging.DeviceOrientationTracker
import com.fkurevija.pingring.data.ranging.MockRangingDto
import com.fkurevija.pingring.data.ranging.MockRangingSource
import com.fkurevija.pingring.data.ranging.RangingConfidence
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy
import com.fkurevija.pingring.data.ranging.RssiDistanceEstimator
import com.fkurevija.pingring.data.ranging.RssiKalmanFilter
import com.fkurevija.pingring.data.ranging.UwbRetentionPolicy
import com.fkurevija.pingring.data.session.Event
import com.fkurevija.pingring.data.session.Friend
import com.fkurevija.pingring.data.session.SessionManager
import com.fkurevija.pingring.data.session.SessionState

internal interface PeerGnssAccess {
    fun currentLocalFix(): GnssFixDto?
    fun currentFix(): GnssFixDto?
    fun setEnabled(enabled: Boolean)
}

internal interface PeerMembershipAccess {
    val sessionState: StateFlow<SessionState>
    val localPeerId: String
    fun updateFriends(friends: List<Friend>)
}

internal interface PeerRangingAccess {
    val retainedPeerIds: Set<String>
    fun hasUwbMeasurement(peerId: String): Boolean
    fun recordUwbMeasurement(peerId: String, measuredAtMillis: Long)
    fun markUwbMeasurement(peerId: String)
    fun forgetPeer(peerId: String)
}

/**
 * Owns live radar peer state, membership reconciliation, and direction/ranging freshness.
 * Members remain listed from session membership even when their live radar reading expires.
 */
internal class PeerRadarCoordinator(
    private val context: Context,
    private val membership: PeerMembershipAccess,
    private val orientationTracker: DeviceOrientationTracker,
    private val gnssRanging: PeerGnssAccess,
    private val rangingCoordinator: PeerRangingAccess,
    private val onPeerNameChanged: (String, String) -> Unit,
    private val onBeaconEnded: (String) -> Unit,
    private val onHostConnectionRestored: () -> Unit,
    private val isLanDebugTransport: () -> Boolean,
) {
    private val _peerUpdates = MutableStateFlow<Map<String, RadarPeerUpdate>>(emptyMap())
    val peerUpdatesById: StateFlow<Map<String, RadarPeerUpdate>> = _peerUpdates.asStateFlow()
    val peerUpdates: Flow<List<RadarPeerUpdate>> = _peerUpdates.map { it.values.toList() }
    val members: Flow<List<RadarPeerUpdate>> = combine(membership.sessionState, _peerUpdates) { state, live ->
        state.friends.map { friend ->
            live[friend.peerId] ?: RadarPeerUpdate(
                peerId = friend.peerId,
                name = friend.name,
                rssi = null,
                proximity = "Unknown",
                distance = null,
                azimuth = null,
                elevation = null,
                worldDirectionX = null,
                worldDirectionY = null,
                worldDirectionZ = null,
                lastSeen = friend.lastSeen,
            )
        }
    }
    private val _lastKnownPeerDirections = MutableStateFlow<List<LastKnownPeerDirection>>(emptyList())
    val lastKnownPeerDirections: StateFlow<List<LastKnownPeerDirection>> = _lastKnownPeerDirections.asStateFlow()

    private val additionalRangingSequences = ConcurrentHashMap<String, Long>()
    private val rssiFilters = mutableMapOf<String, RssiKalmanFilter>()
    private val bleSignalWarningTracker = BleSignalWarningTracker()
    private val _connectedPeerIds = mutableSetOf<String>()
    private val _departedPeerIds = mutableSetOf<String>()
    private val peerDisplayNames = PeerDisplayNames()
    private val directionCoordinator = PeerDirectionCoordinator(
        membership = membership,
        orientationTracker = orientationTracker,
        gnssRanging = gnssRanging,
        rangingCoordinator = rangingCoordinator,
        peerUpdates = _peerUpdates,
        lastKnownPeerDirections = _lastKnownPeerDirections,
        isLanDebugTransport = isLanDebugTransport,
        updateAdditionalDistance = { peerId, distance, accuracy, source, age ->
            updateAdditionalDistance(peerId, distance, accuracy, source, age)
        },
        clearAdditionalDistances = ::clearAdditionalDistances,
    )

    val peerCount: Int get() = _peerUpdates.value.size
    val peerIds: Set<String> get() = _peerUpdates.value.keys
    val joinedAtByPeer: Map<String, Long?> get() = _peerUpdates.value.mapValues { it.value.joinedAtMillis }
    fun containsPeer(peerId: String): Boolean = peerId in _peerUpdates.value
    fun isDeparted(peerId: String): Boolean = peerId in _departedPeerIds
    fun connectedPeerIds(): Set<String> = _connectedPeerIds.toSet()
    fun addConnectedPeerId(peerId: String) { _connectedPeerIds.add(peerId) }
    fun removeConnectedPeerId(peerId: String) { _connectedPeerIds.remove(peerId) }
    fun markPeerDeparted(peerId: String) {
        _departedPeerIds.add(peerId)
        _connectedPeerIds.remove(peerId)
    }
    fun isMeshPresenceFresh(peerId: String, nowMillis: Long): Boolean =
        _peerUpdates.value[peerId]?.meshPresenceLastSeenAtMillis?.let { nowMillis - it < PEER_EXPIRY_TIMEOUT_MILLIS } == true
    fun hasLocalWifiAnchors(): Boolean = directionCoordinator.hasLocalWifiAnchors()
    fun clearLastKnownDirections() { directionCoordinator.clearLastKnownDirections() }
    fun forgetPeerDisplayName(peerId: String) { peerDisplayNames.forget(peerId) }
    fun clearPeerDisplayNames() { peerDisplayNames.clear() }
    fun clearForStop() {
        directionCoordinator.clearForStop()
        additionalRangingSequences.clear()
        _connectedPeerIds.clear()
        _departedPeerIds.clear()
        bleSignalWarningTracker.clear()
        directionCoordinator.clearLastKnownDirections()
    }
    fun clearPeerUpdates() { _peerUpdates.value = emptyMap() }
    fun acceptRangingSequence(peerId: String, sequence: Long): Boolean {
        if (sequence < 0L || isDeparted(peerId)) return false
        var accepted = false
        additionalRangingSequences.compute(peerId) { _, previous ->
            if (previous == null || sequence > previous) {
                accepted = true
                sequence
            } else previous
        }
        return accepted
    }
    fun recordPeerGnssFix(peerId: String, fix: GnssFixDto) = directionCoordinator.recordPeerGnssFix(peerId, fix)
    fun sweepExpiredPeers(nowMillis: Long, retainedPeerIds: Set<String>): List<String> {
        expireAdditionalDistances(nowMillis)
        refreshPeerWorldDirections()
        val expiredPeerIds = _peerUpdates.value.values
            .filter { peer ->
                nowMillis - maxOf(peer.lastSeen, peer.meshPresenceLastSeenAtMillis ?: 0L) >= PEER_EXPIRY_TIMEOUT_MILLIS &&
                    peer.peerId !in retainedPeerIds
            }
            .map { it.peerId }
        expiredPeerIds.forEach(::rememberLastKnownPeerDirection)
        expiredPeerIds.forEach(::removeWifiAnchor)
        _connectedPeerIds.removeAll(expiredPeerIds.toSet())
        _peerUpdates.update { current ->
            current.filter { (peerId, peer) ->
                nowMillis - maxOf(peer.lastSeen, peer.meshPresenceLastSeenAtMillis ?: 0L) < PEER_EXPIRY_TIMEOUT_MILLIS ||
                    peerId in retainedPeerIds
            }
        }
        _lastKnownPeerDirections.update { locations ->
            locations.filter { UwbRetentionPolicy.isLastKnownDirectionWithinWindow(it.recordedAtMillis, nowMillis) }
        }
        return expiredPeerIds
    }
    fun setGnssEnabled(useGnss: Boolean, useLanWifiAnchor: Boolean) {
        directionCoordinator.setGnssEnabled(useGnss, useLanWifiAnchor)
    }

    private companion object {
        const val PEER_EXPIRY_TIMEOUT_MILLIS = 15_000L
    }
    fun updatePeerFromBleAdvertisement(decoded: BleIdentityAdvertisement.Decoded, rssi: Int) {
        val peerId = decoded.peerId
        val proximity = if (rssi >= -70) "Hot" else "Cold"

        // Find or create existing update
        val existing = _peerUpdates.value[peerId]
        // Never fall back to the peer id: it is an internal routing key, not
        // something a person can read or act on.
        val peerName = existing?.name ?: peerDisplayNames.labelFor(peerId) {
            context.getString(R.string.member_name, it)
        }

        // A UWB measurement is real ranging, so it always wins over an RSSI
        // guess. Only peers that have never been ranged fall back to the
        // estimate, which is a time-aware Kalman-filtered RSSI value. Applying
        // the path-loss curve after filtering keeps a reflected packet from
        // directly becoming a large distance jump.
        val nowMillis = System.currentTimeMillis()
        val filterNowMillis = SystemClock.elapsedRealtime()
        val rssiFilter = rssiFilters.getOrPut(peerId) { RssiKalmanFilter() }
        val rssiEstimate = rssiFilter.add(rssi, filterNowMillis)
        val signalWeak = bleSignalWarningTracker.update(
            peerId = peerId,
            filteredRssiDbm = rssiFilter.filteredRssi(filterNowMillis),
            nowMillis = filterNowMillis,
        )
        val hasUwbFix = existing?.distanceSource == RangingSourcePolicy.RangingSource.UWB &&
            rangingCoordinator.hasUwbMeasurement(peerId)
        val hasAdditionalFix = existing?.lastAdditionalRangingAtMillis?.let {
            nowMillis - it in 0..additionalDistanceLifetime(existing.distanceSource)
        } == true

        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = peerName,
            rssi = rssi,
            proximity = proximity,
            distance = if (hasUwbFix || hasAdditionalFix) existing?.distance else rssiEstimate ?: existing?.distance,
            azimuth = existing?.azimuth,
            elevation = existing?.elevation,
            worldDirectionX = existing?.worldDirectionX,
            worldDirectionY = existing?.worldDirectionY,
            worldDirectionZ = existing?.worldDirectionZ,
            lastSeen = nowMillis,
            signalWeak = signalWeak,
            syntheticRanging = (hasUwbFix || hasAdditionalFix) && existing?.syntheticRanging == true,
            meshPresenceLastSeenAtMillis = existing?.meshPresenceLastSeenAtMillis,
            distanceSource = if (hasUwbFix || hasAdditionalFix) existing?.distanceSource else RangingSourcePolicy.RangingSource.BLE_RSSI,
            distanceAccuracyMeters = existing?.distanceAccuracyMeters.takeIf { hasUwbFix || hasAdditionalFix },
            lastAdditionalRangingAtMillis = existing?.lastAdditionalRangingAtMillis.takeIf { hasAdditionalFix },
            lastUwbMeasurementAtMillis = existing?.lastUwbMeasurementAtMillis,
            directionIsLastKnown = existing?.directionIsLastKnown ?: false,
            estimatedDirection = existing?.estimatedDirection,
            // Cross-checked only once a UWB fix exists to check against. Before
            // that there is a single source and the answer is UNKNOWN, which the
            // radar shows as an estimate rather than as a confirmed distance.
            rangingConfidence = if (hasUwbFix) {
                RangingSourcePolicy.confidence(rssi, rssiEstimate)
            } else {
                RangingConfidence.UNKNOWN
            },
            // From the advert, and sticky: a packet read without one must not
            // erase a timestamp an earlier advert carried, or a peer's place in
            // the arrival order would reset whenever one of its adverts was
            // short. Zero means "did not say", which is null here rather than
            // epoch 1970 - see RadarPeerUpdate.joinedAtMillis.
            joinedAtMillis = decoded.joinedAtMillis.takeIf { it > 0L } ?: existing?.joinedAtMillis
        )

        _peerUpdates.update { current ->
            current + (peerId to updated)
        }

        syncFriend(updated)
    }

    /**
     * Mirrors the latest radar reading of [peer] into the SessionManager's
     * friend list: added on first sight, then only its last-seen time and
     * distance are refreshed.
     */

    private fun syncFriend(peer: RadarPeerUpdate) {
        val friends = membership.sessionState.value.friends
        val distance = peer.distance?.toDouble()
        val friendExists = friends.any { it.peerId == peer.peerId }
        if (!friendExists) {
            val updatedFriends = friends + Friend(
                peerId = peer.peerId,
                name = peer.name,
                lastSeen = peer.lastSeen,
                distance = distance
            )
            membership.updateFriends(updatedFriends)
        } else {
            val updatedFriends = friends.map {
                if (it.peerId == peer.peerId) it.copy(lastSeen = peer.lastSeen, distance = distance) else it
            }
            membership.updateFriends(updatedFriends)
        }
    }

    fun removePeer(peerId: String, preserveLastKnownDirection: Boolean = true) {
        if (preserveLastKnownDirection) rememberLastKnownPeerDirection(peerId)
        else _lastKnownPeerDirections.update { current -> current.filterNot { it.peerId == peerId } }
        _peerUpdates.update { current -> current - peerId }
        // A peer that walks away mid-beacon has its beacon ended rather than
        // dropped: it happened, and the transcript should still show where.
        onBeaconEnded(peerId)
        // A peer that reconnects must be re-ranged from scratch: keeping the
        // old flags would suppress its RSSI estimate, and keeping the old
        // average would seed it with a distance from a session that is over.
        rangingCoordinator.forgetPeer(peerId)
        rssiFilters.remove(peerId)
        bleSignalWarningTracker.remove(peerId)
        removeWifiAnchor(peerId)
        // The display label is keyed to the member rather than to their
        // proximity, so it survives a drop. Forgetting it renumbered a member who
        // had merely stepped out of range, which is how two people ended up shown
        // under different numbers depending on who had dropped out of contact.
        // Nothing else consults it for a peer that is not present, so keeping it
        // costs nothing while a member is away.
    }


    private fun removeWifiAnchor(peerId: String) {
        directionCoordinator.removeWifiAnchor(peerId)
    }
    fun addConnectedPeer(peerId: String) {
        if (peerId.isBlank() || peerId == membership.localPeerId) return
        _lastKnownPeerDirections.update { current -> current.filterNot { it.peerId == peerId } }
        val existing = _peerUpdates.value[peerId]
        val nowMillis = System.currentTimeMillis()
        val hasFreshMeasurement = existing != null && !existing.meshOnlyPresence &&
            nowMillis - existing.lastSeen < PEER_EXPIRY_TIMEOUT_MILLIS
        // Never fall back to the peer id: it is an internal routing key, not
        // something a person can read or act on.
        val peerName = existing?.name ?: peerDisplayNames.labelFor(peerId) {
            context.getString(R.string.member_name, it)
        }
        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = peerName,
            rssi = existing?.takeIf { hasFreshMeasurement }?.rssi,
            proximity = existing?.takeIf { hasFreshMeasurement }?.proximity ?: "Unknown",
            distance = existing?.takeIf { hasFreshMeasurement || peerId in rangingCoordinator.retainedPeerIds }?.distance,
            azimuth = existing?.takeIf { hasFreshMeasurement || peerId in rangingCoordinator.retainedPeerIds }?.azimuth,
            elevation = existing?.takeIf { hasFreshMeasurement || peerId in rangingCoordinator.retainedPeerIds }?.elevation,
            worldDirectionX = existing?.takeIf { hasFreshMeasurement || peerId in rangingCoordinator.retainedPeerIds }?.worldDirectionX,
            worldDirectionY = existing?.takeIf { hasFreshMeasurement || peerId in rangingCoordinator.retainedPeerIds }?.worldDirectionY,
            worldDirectionZ = existing?.takeIf { hasFreshMeasurement || peerId in rangingCoordinator.retainedPeerIds }?.worldDirectionZ,
            lastSeen = existing?.takeIf { hasFreshMeasurement }?.lastSeen ?: nowMillis,
            meshOnlyPresence = existing?.meshOnlyPresence ?: false,
            meshPresenceLastSeenAtMillis = existing?.meshPresenceLastSeenAtMillis,
            // Carried over, not reset: a transport connection says nothing about
            distanceSource = existing?.distanceSource,
            distanceAccuracyMeters = existing?.distanceAccuracyMeters,
            lastAdditionalRangingAtMillis = existing?.lastAdditionalRangingAtMillis,
            lastUwbMeasurementAtMillis = existing?.lastUwbMeasurementAtMillis,
            // when the peer joined, and clearing it here would drop a member to
            // the bottom of the arrival order every time its connection blipped.
            joinedAtMillis = existing?.joinedAtMillis,
            directionIsLastKnown = existing?.directionIsLastKnown ?: false,
            estimatedDirection = existing?.estimatedDirection,
        )
        _peerUpdates.update { current -> current + (peerId to updated) }
        _lastKnownPeerDirections.update { current -> current.filterNot { it.peerId == peerId } }
        syncFriend(updated)
    }
    fun updateMeshPresence(peerId: String) {
        if (peerId.isBlank() || peerId == membership.localPeerId || peerId in _departedPeerIds) return
        _connectedPeerIds.add(peerId)
        val state = membership.sessionState.value
        if (!state.isHost && state.currentEvent?.hostPeerId == peerId) {
            onHostConnectionRestored()
        }
        val nowMillis = System.currentTimeMillis()
        val existing = _peerUpdates.value[peerId]
        val hasFreshLocalMeasurement = existing != null && !existing.meshOnlyPresence &&
            nowMillis - existing.lastSeen < PEER_EXPIRY_TIMEOUT_MILLIS &&
            (existing.rssi != null || existing.lastUwbMeasurementAtMillis != null)
        val retainedUwb = peerId in rangingCoordinator.retainedPeerIds
        if (existing != null && !hasFreshLocalMeasurement && !retainedUwb) {
            rememberLastKnownPeerDirection(peerId)
        }
        val peerName = existing?.name ?: peerDisplayNames.labelFor(peerId) {
            context.getString(R.string.member_name, it)
        }
        val updated = if (existing == null) {
            RadarPeerUpdate(
                peerId = peerId,
                name = peerName,
                rssi = null,
                proximity = "Unknown",
                distance = null,
                azimuth = null,
                elevation = null,
                worldDirectionX = null,
                worldDirectionY = null,
                worldDirectionZ = null,
                lastSeen = nowMillis,
                meshOnlyPresence = true,
                meshPresenceLastSeenAtMillis = nowMillis,
            )
        } else if (hasFreshLocalMeasurement) {
            existing.copy(meshPresenceLastSeenAtMillis = nowMillis)
        } else if (retainedUwb) {
            existing.copy(
                meshOnlyPresence = true,
                meshPresenceLastSeenAtMillis = nowMillis,
            )
        } else {
            existing.copy(
                lastSeen = nowMillis,
                meshOnlyPresence = true,
                meshPresenceLastSeenAtMillis = nowMillis,
            )
        }
        _peerUpdates.update { current -> current + (peerId to updated) }
        syncFriend(updated)
    }
    fun updatePeerName(peerId: String, name: String) {
        if (name.isBlank() || peerId == membership.localPeerId) return
        val existing = _peerUpdates.value[peerId]
        val updated = if (existing != null) {
            existing.copy(name = name)
        } else {
            RadarPeerUpdate(
                peerId = peerId,
                name = name,
                rssi = null,
                proximity = "Unknown",
                distance = null,
                azimuth = null,
                elevation = null,
                worldDirectionX = null,
                worldDirectionY = null,
                worldDirectionZ = null,
                lastSeen = System.currentTimeMillis()
            )
        }
        _peerUpdates.update { current -> current + (peerId to updated) }
        onPeerNameChanged(peerId, name)
        syncFriend(updated)
    }
    fun refreshPeerWorldDirections() {
        directionCoordinator.refreshPeerWorldDirections()
    }
    fun rememberLastKnownPeerDirection(peerId: String) =
        directionCoordinator.rememberLastKnownPeerDirection(peerId)

    fun updateUwbPosition(peerId: String, result: RangingResult) =
        directionCoordinator.updateUwbPosition(peerId, result)

    fun hasFreshUwbMeasurement(peerId: String): Boolean =
        directionCoordinator.hasFreshUwbMeasurement(peerId)
    fun updateGnssDistances() {
        directionCoordinator.updateGnssDistances()
    }
    fun updateAdditionalDistance(
        peerId: String,
        distance: Float,
        accuracy: Float,
        source: RangingSourcePolicy.RangingSource,
        measurementAgeMillis: Long = 0L,
        synthetic: Boolean = false,
    ) {
        val state = membership.sessionState.value
        val event = state.currentEvent ?: return
        if (peerId in _departedPeerIds || peerId == localPeerId() ||
            (source == RangingSourcePolicy.RangingSource.GNSS && !event.useGnss) ||
            !distance.isFinite() || !accuracy.isFinite()
        ) return
        if (hasFreshUwbMeasurement(peerId)) return
        val existing = _peerUpdates.value[peerId]
        if (source == RangingSourcePolicy.RangingSource.GNSS &&
            existing?.distanceSource == RangingSourcePolicy.RangingSource.WIFI_RTT &&
            System.currentTimeMillis() - (existing.lastAdditionalRangingAtMillis ?: 0L) in 0..5_000L
        ) return
        if (existing == null) addConnectedPeer(peerId)
        rememberLastKnownPeerDirection(peerId)
        val now = System.currentTimeMillis()
        _peerUpdates.update { peers ->
            val peer = peers[peerId] ?: return@update peers
            peers + (peerId to peer.copy(
                distance = distance,
                distanceSource = source,
                distanceAccuracyMeters = accuracy,
                lastAdditionalRangingAtMillis = now - measurementAgeMillis,
                azimuth = null,
                elevation = null,
                worldDirectionX = null,
                worldDirectionY = null,
                worldDirectionZ = null,
                lastSeen = now,
                meshOnlyPresence = false,
                directionIsLastKnown = false,
                rangingConfidence = RangingConfidence.UNKNOWN,
                syntheticRanging = synthetic,
            ))
        }
        _peerUpdates.value[peerId]?.let(::syncFriend)
    }


    private fun additionalDistanceLifetime(source: RangingSourcePolicy.RangingSource?): Long =
        if (source == RangingSourcePolicy.RangingSource.WIFI_RTT) 5_000L else GnssPolicy.MAX_FIX_AGE_MILLIS
    fun clearAdditionalDistances(source: RangingSourcePolicy.RangingSource) {
        _peerUpdates.update { peers -> peers.mapValues { (_, peer) ->
            if (peer.distanceSource == source) peer.copy(
                distance = null, distanceSource = null, distanceAccuracyMeters = null,
                lastAdditionalRangingAtMillis = null,
            ) else peer
        } }
    }
    fun expireAdditionalDistances(nowMillis: Long) {
        _peerUpdates.update { peers -> peers.mapValues { (_, peer) ->
            val measuredAt = peer.lastAdditionalRangingAtMillis
            if (measuredAt != null && nowMillis - measuredAt !in 0..additionalDistanceLifetime(peer.distanceSource)) {
                peer.copy(distance = null, distanceSource = null, distanceAccuracyMeters = null, lastAdditionalRangingAtMillis = null)
            } else peer
        } }
    }

    /**
     * Applies a scripted range report received over the LAN debug transport
     * (see tools/mock_radar.py).
     *
     * Applies source-shaped synthetic reports through the corresponding radar
     * paths: BLE RSSI uses the real signal filter, Wi-Fi RTT uses the real
     * distance-only source path, and UWB-shaped reports drive the real range
     * and direction rendering. None of these reports exercise radio hardware.
     *
     * Unlike a UWB update this also *creates* the peer entry when it is
     * missing, because a device on the LAN debug transport never sees a BLE
     * scan result for the other device: without that, the radar would stay
     * empty on the emulator and the injected distances would be dropped.
     */
    fun updateMockRanging(dto: MockRangingDto) {
        val peerId = dto.peerId
        if (peerId.isEmpty() || peerId == membership.localPeerId) return
        if (membership.sessionState.value.currentEvent == null) return
        if (dto.source == MockRangingSource.WIFI_RTT) {
            val distance = dto.distance?.takeIf { it.isFinite() && it > 0f } ?: return
            val accuracy = dto.accuracyMeters?.takeIf { it.isFinite() && it > 0f } ?: return
            updateAdditionalDistance(
                peerId = peerId,
                distance = distance,
                accuracy = accuracy,
                source = RangingSourcePolicy.RangingSource.WIFI_RTT,
                synthetic = true,
            )
            return
        }
        if (dto.source == MockRangingSource.BLE_RSSI && (dto.distance != null || dto.rssi == null)) return

        // A scripted report carries a distance but no real signal strength, so
        // an RSSI is derived by inverting the curve the BLE scan path uses, via
        // RssiDistanceEstimator. Without it the hot/cold ring and the dBm
        // readout would sit at their "no signal" values and stop reacting to
        // the scripted movement, which is half of what the radar has to be
        // tested for. A scripted distance is a real measurement, so it also
        // pins the peer the way a UWB fix would.
        val existing = _peerUpdates.value[peerId]
        val rssi: Int? = dto.rssi
            ?: dto.distance?.let { RssiDistanceEstimator.rssiForDistance(it) }?.toInt()

        // Two quite different things can arrive here, and conflating them is
        // what made the scripted radar too clean to be useful:
        //
        //  - A distance (with or without an explicit dBm) is a ranging fix. It
        //    is precise by definition, so it pins the peer and clears any
        //    signal history: a UWB measurement outranks an estimate, and the
        //    two must not be blended.
        //  - An rssi with no distance is a *signal* reading, which is what a
        //    non-UWB peer actually provides. It goes through RssiKalmanFilter on
        //    the same path a BLE advert takes, so scripted noise, outliers and
        //    dropouts exercise the real filter instead of bypassing it.
        val isRangingFix = dto.source == MockRangingSource.UWB && dto.distance != null
        if (isRangingFix) {
            rangingCoordinator.markUwbMeasurement(peerId)
            rssiFilters.remove(peerId)
        }
        // Mirrors the BLE path: the estimate is what the distance becomes, and
        // a null estimate means the peer has dropped below the noise floor, in
        // which case the last good distance stands rather than being invented.
        val rssiEstimate = if (isRangingFix) {
            null
        } else {
            val filterNowMillis = SystemClock.elapsedRealtime()
            rssi?.let { rssiFilters.getOrPut(peerId) { RssiKalmanFilter() }.add(it, filterNowMillis) }
        }

        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = dto.name?.takeIf { it.isNotBlank() } ?: existing?.name ?: peerDisplayNames.labelFor(peerId) {
                context.getString(R.string.member_name, it)
            },
            rssi = rssi,
            proximity = when {
                rssi == null -> "Unknown"
                rssi >= -70 -> "Hot"
                else -> "Cold"
            },
            distance = dto.distance ?: rssiEstimate ?: existing?.distance,
            azimuth = dto.azimuth ?: existing?.azimuth,
            elevation = dto.elevation ?: existing?.elevation,
            worldDirectionX = existing?.worldDirectionX,
            worldDirectionY = existing?.worldDirectionY,
            worldDirectionZ = existing?.worldDirectionZ,
            lastSeen = System.currentTimeMillis(),
            lastUwbMeasurementAtMillis = if (isRangingFix) System.currentTimeMillis() else existing?.lastUwbMeasurementAtMillis,
            distanceSource = when (dto.source) {
                MockRangingSource.UWB -> RangingSourcePolicy.RangingSource.UWB.takeIf { isRangingFix }
                MockRangingSource.BLE_RSSI -> RangingSourcePolicy.RangingSource.BLE_RSSI
                MockRangingSource.WIFI_RTT -> RangingSourcePolicy.RangingSource.WIFI_RTT
            },
            distanceAccuracyMeters = null,
            lastAdditionalRangingAtMillis = null,
            rangingConfidence = RangingConfidence.UNKNOWN,
            syntheticRanging = true,
            // Mock ranging reports carry no advert, so this peer has no arrival
            // time to learn from one. Carrying the existing value keeps it
            // ranked where BLE put it rather than dropping it to last.
            joinedAtMillis = existing?.joinedAtMillis
        )

        _peerUpdates.update { current ->
            current + (peerId to updated)
        }
        if (dto.rssi != null && !isRangingFix) {
            val initialDistance = (rssiEstimate ?: existing?.distance)
                ?.takeIf { it.isFinite() && it > 0f }
            if (initialDistance != null) {
                directionCoordinator.rememberWifiRssiAnchorDistance(peerId, initialDistance)
            }
        }
        syncFriend(updated)
        refreshPeerWorldDirections()
    }

    private fun localPeerId(): String = membership.localPeerId
}
