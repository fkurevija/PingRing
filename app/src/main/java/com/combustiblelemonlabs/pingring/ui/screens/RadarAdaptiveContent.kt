package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.combustiblelemonlabs.pingring.data.session.BeaconInfo
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository
import com.combustiblelemonlabs.pingring.data.session.SessionState
import com.combustiblelemonlabs.pingring.data.discovery.ConnectivityStatus
import com.combustiblelemonlabs.pingring.data.radar.RadarPeerUpdate

@Composable
internal fun RadarAdaptiveContent(
    maxWidth: Dp,
    maxHeight: Dp,
    sessionState: SessionState,
    peers: List<RadarPeerUpdate>,
    focusedPeer: RadarPeerUpdate?,
    focusedBeacon: BeaconInfo?,
    activeBeacons: Map<String, BeaconInfo>,
    endedBeacons: List<BeaconInfo>,
    localBeacon: BeaconInfo?,
    connectivity: ConnectivityStatus,
    discoveryRepository: DiscoveryRepository?,
    localPeerId: String?,
    senderName: String,
    chatBubbleColorArgb: Long?,
    readReceiptsVisible: Boolean,
    silencedBeaconIds: Set<String>,
    isHost: Boolean,
    radarScreenReaderSummaryEnabled: Boolean,
    reduceMotionEnabled: Boolean,
    spokenUpdateText: String?,
    trackedDirectionAlpha: Float,
    showLastKnownDirection: Boolean,
    onShowChatSettings: () -> Unit,
    onShowBeaconSheet: () -> Unit,
) {
    if (maxWidth >= 600.dp) {
        val paneWidth = (maxWidth - 20.dp) / 2
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RadarContent(
                focusedPeer,
                peers.isNotEmpty(),
                focusedBeacon,
                radarScreenReaderSummaryEnabled,
                reduceMotionEnabled,
                spokenUpdateText,
                Modifier.width(paneWidth),
                trackedDirectionAlpha,
                showLastKnownDirection,
            )
            PeerList(
                peers,
                connectivity,
                discoveryRepository,
                activeBeacons,
                focusedPeerId = focusedPeer?.peerId,
                onFocusPeer = { discoveryRepository?.setFocusedPeer(it) },
                isHost = isHost,
                onRemovePeer = { discoveryRepository?.removeMember(it) },
                modifier = Modifier.width(paneWidth),
            )
        }
    } else {
        RadarCompactChatPane(
            maxHeight = maxHeight,
            sessionState = sessionState,
            peers = peers,
            focusedPeer = focusedPeer,
            focusedBeacon = focusedBeacon,
            activeBeacons = activeBeacons,
            endedBeacons = endedBeacons,
            localBeacon = localBeacon,
            discoveryRepository = discoveryRepository,
            localPeerId = localPeerId,
            senderName = senderName,
            chatBubbleColorArgb = chatBubbleColorArgb,
            readReceiptsVisible = readReceiptsVisible,
            silencedBeaconIds = silencedBeaconIds,
            radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
            reduceMotionEnabled = reduceMotionEnabled,
            spokenUpdateText = spokenUpdateText,
            trackedDirectionAlpha = trackedDirectionAlpha,
            showLastKnownDirection = showLastKnownDirection,
            onShowChatSettings = onShowChatSettings,
            onShowBeaconSheet = onShowBeaconSheet,
        )
    }
}
