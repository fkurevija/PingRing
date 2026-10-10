package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.data.discovery.ConnectivityStatus
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.radar.RadarPeerUpdate
import com.fkurevija.pingring.data.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RadarScreenOverlays(
    eventId: String,
    sessionState: SessionState,
    isHost: Boolean,
    discoveryRepository: DiscoveryRepository?,
    peers: List<RadarPeerUpdate>,
    connectivity: ConnectivityStatus,
    activeBeacons: Map<String, BeaconInfo>,
    localBeacon: BeaconInfo?,
    focusedPeerId: String?,
    senderName: String,
    showEndConfirmation: Boolean,
    onDismissEndConfirmation: () -> Unit,
    onLeaveEvent: () -> Unit,
    onEndEvent: () -> Unit,
    showConnectionLostDialog: Boolean,
    onDismissConnectionLost: () -> Unit,
    onLeaveFromConnectionLost: () -> Unit,
    onSilenceConnectionLost: () -> Unit,
    connectionRecoveryPromptDelayMillis: Long,
    showChatSettings: Boolean,
    onDismissChatSettings: () -> Unit,
    chatBubbleColorArgb: Long?,
    onUsernameChange: (String) -> Unit,
    onChatBubbleColorChange: (Long) -> Unit,
    onColourClaimResult: (Long, Boolean) -> Unit,
    colourHolders: Map<Long, String>,
    localPeerId: String?,
    refusedChatColourArgb: Long?,
    refusedChatColourFallback: Long?,
    readReceiptsEnabled: Boolean,
    onReadReceiptsChanged: (Boolean) -> Unit,
    showBeaconSheet: Boolean,
    onDismissBeaconSheet: () -> Unit,
    showMemberSheet: Boolean,
    onDismissMemberSheet: () -> Unit,
    showQrCode: Boolean,
    onDismissQrCode: () -> Unit,
) {
    if (showEndConfirmation) {
        EndPingRingDialog(
            isHost = isHost,
            onConfirm = {
                onDismissEndConfirmation()
                if (isHost) onEndEvent() else onLeaveEvent()
            },
            onDismiss = onDismissEndConfirmation,
        )
    }
    if (showConnectionLostDialog) {
        ConnectionLostDialog(
            onDismiss = onDismissConnectionLost,
            onLeave = onLeaveFromConnectionLost,
            onSilence = onSilenceConnectionLost,
            connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
        )
    }
    if (showChatSettings) {
        ChatSettingsDialog(
            selectedColorArgb = chatBubbleColorArgb,
            username = sessionState.userName,
            existingMemberNames = sessionState.friends.map { it.name },
            onUsernameChange = onUsernameChange,
            onColorSelected = { requested ->
                val repository = discoveryRepository
                if (repository == null) {
                    onChatBubbleColorChange(requested)
                } else {
                    repository.requestChatColour(requested) { granted ->
                        onColourClaimResult(requested, granted)
                        if (granted) onChatBubbleColorChange(requested)
                    }
                }
            },
            onDismiss = onDismissChatSettings,
            colourHolders = colourHolders,
            memberNameFor = { peerId -> peers.firstOrNull { it.peerId == peerId }?.name ?: peerId },
            localPeerId = localPeerId,
            refusedColorArgb = refusedChatColourArgb ?: refusedChatColourFallback,
            readReceiptsEnabled = readReceiptsEnabled,
            onReadReceiptsChanged = onReadReceiptsChanged,
        )
    }
    if (showBeaconSheet) {
        BeaconCustomizationDialog(
            localBeacon = localBeacon,
            onDismiss = onDismissBeaconSheet,
            onActivate = { title, color, pattern ->
                discoveryRepository?.activateBeacon(title, color, pattern, senderName)
            },
            onStop = { discoveryRepository?.cancelLocalBeacon() },
            onTestVibrate = { pattern -> discoveryRepository?.playVibrationPattern(pattern) },
        )
    }
    if (showMemberSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissMemberSheet,
            sheetState = sheetState,
        ) {
            PeerList(
                peers = peers,
                connectivity = connectivity,
                repository = discoveryRepository,
                activeBeacons = activeBeacons,
                focusedPeerId = focusedPeerId,
                onFocusPeer = { discoveryRepository?.setFocusedPeer(it) },
                isHost = isHost,
                onRemovePeer = { discoveryRepository?.removeMember(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
    if (showQrCode) {
        AlertDialog(
            onDismissRequest = onDismissQrCode,
            title = { Text(stringResource(R.string.scan_to_join)) },
            text = {
                PingRingCodeBlock(
                    eventCode = eventId,
                    inviteSecret = sessionState.currentEvent?.inviteSecret,
                    qrSize = 200.dp,
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissQrCode) { Text(stringResource(R.string.close)) }
            },
        )
    }
}
