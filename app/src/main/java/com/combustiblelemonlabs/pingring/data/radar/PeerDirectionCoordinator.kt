package com.combustiblelemonlabs.pingring.data.radar

import android.hardware.GeomagneticField
import android.os.SystemClock
import androidx.core.uwb.RangingResult
import com.combustiblelemonlabs.pingring.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import com.combustiblelemonlabs.pingring.data.ranging.DeviceOrientationTracker
import com.combustiblelemonlabs.pingring.data.ranging.GnssDirectionPolicy
import com.combustiblelemonlabs.pingring.data.ranging.RangingSourcePolicy
import com.combustiblelemonlabs.pingring.data.ranging.UwbRetentionPolicy
import com.combustiblelemonlabs.pingring.data.ranging.WifiAnchorDirectionPolicy
import com.combustiblelemonlabs.pingring.data.session.Event
import com.combustiblelemonlabs.pingring.data.session.SessionState

/**
 * Owns GPS and local Wi-Fi-anchor direction estimates for live radar peers.
 * UWB direction aging is reconciled here as estimates are refreshed.
 */
internal class PeerDirectionCoordinator(
    private val membership: PeerMembershipAccess,
    private val orientationTracker: DeviceOrientationTracker,
    private val gnssRanging: PeerGnssAccess,
    private val rangingCoordinator: PeerRangingAccess,
    private val peerUpdates: MutableStateFlow<Map<String, RadarPeerUpdate>>,
    private val lastKnownPeerDirections: MutableStateFlow<List<LastKnownPeerDirection>>,
    private val isLanDebugTransport: () -> Boolean,
    private val updateAdditionalDistance: (
        String,
        Float,
        Float,
        RangingSourcePolicy.RangingSource,
        Long,
    ) -> Unit,
    private val clearAdditionalDistances: (RangingSourcePolicy.RangingSource) -> Unit,
) {
    private val peerGnssFixes = ConcurrentHashMap<String, Pair<GnssFixDto, Long>>()
    private val wifiRssiAnchorDistances = ConcurrentHashMap<String, Float>()
    private val wifiRssiAnchors = ConcurrentHashMap<String, WifiAnchorDirectionPolicy.Anchor>()

    fun hasLocalWifiAnchors(): Boolean =
        wifiRssiAnchorDistances.isNotEmpty() || wifiRssiAnchors.isNotEmpty()

    fun clearForStop() {
        wifiRssiAnchorDistances.clear()
        wifiRssiAnchors.clear()
        peerGnssFixes.clear()
    }

    fun clearLastKnownDirections() {
        lastKnownPeerDirections.value = emptyList()
    }

    fun setGnssEnabled(useGnss: Boolean, useLanWifiAnchor: Boolean) {
        gnssRanging.setEnabled(useGnss || useLanWifiAnchor || hasLocalWifiAnchors())
        if (!useGnss) {
            peerGnssFixes.clear()
            clearAdditionalDistances(RangingSourcePolicy.RangingSource.GNSS)
        }
    }

    fun recordPeerGnssFix(peerId: String, fix: GnssFixDto) {
        peerGnssFixes[peerId] = fix to SystemClock.elapsedRealtime()
    }

    fun removeWifiAnchor(peerId: String) {
        wifiRssiAnchorDistances.remove(peerId)
        wifiRssiAnchors.remove(peerId)
        val event = membership.sessionState.value.currentEvent
        val localLanSession = event != null && isLanDebugTransport()
        if (wifiRssiAnchorDistances.isEmpty() && wifiRssiAnchors.isEmpty() &&
            event?.useGnss != true && !localLanSession
        ) {
            gnssRanging.setEnabled(false)
        }
    }

    fun refreshPeerWorldDirections() {
        val nowMillis = System.currentTimeMillis()
        val event = membership.sessionState.value.currentEvent
        val hasLocalWifiAnchors = hasLocalWifiAnchors()
        val localFix = when {
            hasLocalWifiAnchors -> gnssRanging.currentLocalFix()
            event?.useGnss == true -> gnssRanging.currentFix()
            else -> null
        }
        val heading = orientationTracker.reliableScreenHeading()
        val elapsedNow = SystemClock.elapsedRealtime()
        val declination = localFix?.let {
            GeomagneticField(
                it.latitude.toFloat(), it.longitude.toFloat(), 0f, nowMillis,
            ).declination
        } ?: 0f
        if (localFix != null && heading != null) {
            wifiRssiAnchorDistances.entries.toList().forEach { (peerId, distance) ->
                val anchor = WifiAnchorDirectionPolicy.createAnchor(
                    fix = localFix,
                    magneticHeadingDegrees = heading,
                    declinationDegrees = declination,
                    distanceMeters = distance,
                )
                if (anchor != null && wifiRssiAnchors.putIfAbsent(peerId, anchor) == null) {
                    wifiRssiAnchorDistances.remove(peerId, distance)
                }
            }
        }
        peerUpdates.value.values
            .filter { peer ->
                val measuredAt = peer.lastUwbMeasurementAtMillis
                measuredAt != null && !peer.directionIsLastKnown &&
                    UwbRetentionPolicy.isDirectionLastKnown(measuredAt, nowMillis)
            }
            .forEach { peer -> rememberLastKnownPeerDirection(peer.peerId) }

        peerUpdates.update { current ->
            current.mapValues { (_, peer) ->
                val measuredAt = peer.lastUwbMeasurementAtMillis
                val remoteFix = peerGnssFixes[peer.peerId]?.let { (fix, receivedAt) ->
                    fix.copy(ageMillis = fix.ageMillis + elapsedNow - receivedAt)
                }
                val wifiAnchor = wifiRssiAnchors[peer.peerId]
                val withEstimate = if (wifiAnchor != null) {
                    val measuredBearingIsFresh = GnssDirectionPolicy.measuredBearingAvailable(peer, nowMillis)
                    val freshEstimate = if (measuredBearingIsFresh) null else localFix?.let {
                        WifiAnchorDirectionPolicy.estimate(it, wifiAnchor, heading, declination)
                    }
                    val keepLastEstimate = !measuredBearingIsFresh && freshEstimate == null &&
                        peer.estimatedDirection != null &&
                        peer.estimatedDirectionUpdatedAtMillis?.let {
                            nowMillis - it in 0..GnssDirectionPolicy.LAST_KNOWN_DIRECTION_MILLIS
                        } == true
                    peer.copy(
                        estimatedDirection = freshEstimate ?: peer.estimatedDirection.takeIf { keepLastEstimate },
                        estimatedDirectionUpdatedAtMillis = when {
                            freshEstimate != null -> nowMillis
                            keepLastEstimate -> peer.estimatedDirectionUpdatedAtMillis
                            else -> null
                        },
                        estimatedDirectionIsLastKnown = keepLastEstimate,
                    )
                } else {
                    GnssDirectionPolicy.supplement(
                        peer, localFix, remoteFix, heading, declination, nowMillis,
                    )
                }
                when {
                    peer.directionIsLastKnown -> withEstimate
                    measuredAt != null && UwbRetentionPolicy.isDirectionLastKnown(measuredAt, nowMillis) ->
                        withEstimate.copy(directionIsLastKnown = true)
                    peer.azimuth != null -> {
                        val (worldX, worldY, worldZ) =
                            orientationTracker.toWorldDirection(peer.azimuth, peer.elevation)
                        withEstimate.copy(
                            worldDirectionX = worldX,
                            worldDirectionY = worldY,
                            worldDirectionZ = worldZ,
                        )
                    }
                    else -> withEstimate
                }
            }
        }
    }

    fun updateGnssDistances() {
        refreshPeerWorldDirections()
        if (membership.sessionState.value.currentEvent?.useGnss != true) return
        val local = gnssRanging.currentFix() ?: return
        val now = SystemClock.elapsedRealtime()
        peerGnssFixes.forEach { (peerId, entry) ->
            val remote = entry.first.copy(ageMillis = entry.first.ageMillis + now - entry.second)
            if (!GnssPolicy.isUsable(remote)) {
                peerGnssFixes.remove(peerId, entry)
            } else {
                val separation = GnssPolicy.separationMeters(local, remote)
                if (separation <= MAX_LOCAL_RADAR_GNSS_DISTANCE_METERS) {
                    updateAdditionalDistance(
                        peerId,
                        separation,
                        local.accuracyMeters + remote.accuracyMeters,
                        RangingSourcePolicy.RangingSource.GNSS,
                        maxOf(local.ageMillis, remote.ageMillis),
                    )
                } else if (
                    peerUpdates.value[peerId]?.distanceSource == RangingSourcePolicy.RangingSource.GNSS
                ) {
                    clearAdditionalDistances(RangingSourcePolicy.RangingSource.GNSS)
                }
            }
        }
    }

    fun rememberLastKnownPeerDirection(peerId: String) {
        val peer = peerUpdates.value[peerId] ?: return
        val worldX = peer.worldDirectionX ?: return
        val worldY = peer.worldDirectionY ?: return
        val detectedAt = peer.lastUwbMeasurementAtMillis ?: return
        val location = LastKnownPeerDirection(
            peerId = peerId,
            name = peer.name,
            worldDirectionX = worldX,
            worldDirectionY = worldY,
            distance = peer.distance,
            lastDetectedAtMillis = detectedAt,
            recordedAtMillis = System.currentTimeMillis(),
        )
        lastKnownPeerDirections.update { current ->
            (current.filterNot { it.peerId == peerId } + location)
                .sortedByDescending { it.recordedAtMillis }
        }
    }

    fun updateUwbPosition(peerId: String, result: RangingResult) {
        val position = (result as? RangingResult.RangingResultPosition)?.position ?: return
        // Keep the per-peer RSSI filter alive so independent BLE readings can still
        // cross-check this fix for obstruction.
        val measuredAtMillis = System.currentTimeMillis()
        rangingCoordinator.recordUwbMeasurement(peerId, measuredAtMillis)
        lastKnownPeerDirections.update { current -> current.filterNot { it.peerId == peerId } }
        peerUpdates.update { peers ->
            val current = peers[peerId] ?: return@update peers
            peers + (
                peerId to current.copy(
                    distance = position.distance?.value ?: current.distance,
                    azimuth = position.azimuth?.value ?: current.azimuth,
                    elevation = position.elevation?.value ?: current.elevation,
                    lastSeen = measuredAtMillis,
                    lastUwbMeasurementAtMillis = measuredAtMillis,
                    distanceSource = RangingSourcePolicy.RangingSource.UWB,
                    distanceAccuracyMeters = null,
                    lastAdditionalRangingAtMillis = null,
                    directionIsLastKnown = false,
                    syntheticRanging = false,
                )
            )
        }
        refreshPeerWorldDirections()
    }

    fun hasFreshUwbMeasurement(peerId: String): Boolean =
        peerUpdates.value[peerId]?.lastUwbMeasurementAtMillis?.let {
            System.currentTimeMillis() - it in 0..UWB_DIRECTION_STALE_MILLIS
        } == true

    fun rememberWifiRssiAnchorDistance(peerId: String, distance: Float) {
        if (!wifiRssiAnchors.containsKey(peerId)) {
            wifiRssiAnchorDistances.putIfAbsent(peerId, distance)
        }
    }

    private companion object {
        const val MAX_LOCAL_RADAR_GNSS_DISTANCE_METERS = 100f
        const val UWB_DIRECTION_STALE_MILLIS = 2_000L
    }
}
