package com.fkurevija.pingring.ui.screens

import androidx.compose.runtime.Composable
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.SessionState
import com.fkurevija.pingring.data.PendingJoinRequest

@Composable
fun RadarScreen(
    eventId: String,
    isHost: Boolean,
    coordinationError: String?,
    discoveryRepository: DiscoveryRepository?,
    sessionState: SessionState,
    chatBubbleColorArgb: Long?,
    onChatBubbleColorChange: (Long) -> Unit,
    chatColourHoldersOverride: Map<Long, String>? = null,
    refusedChatColourArgb: Long? = null,
    localPeerIdOverride: String? = null,
    radarScreenReaderSummaryEnabled: Boolean,
    connectionRecoveryPromptEnabled: Boolean = true,
    connectionRecoveryPromptDelayMillis: Long = 120_000L,
    hostConnectionLostAtMillis: Long? = null,
    lastKnownPeerDirections: List<com.fkurevija.pingring.data.LastKnownPeerDirection> = emptyList(),
    isTopDestination: Boolean = true,
    reduceMotionEnabled: Boolean = false,
    proximityHapticsEnabled: Boolean = true,
    spokenRadarUpdatesEnabled: Boolean = false,
    pendingJoinRequests: List<PendingJoinRequest> = emptyList(),
    onApproveJoinRequest: (String) -> Unit = {},
    onDenyJoinRequest: (String) -> Unit = {},
    onUsernameChange: (String) -> Unit = {},
    onEventSettings: () -> Unit,
    onLeaveEvent: () -> Unit,
    onEndEvent: () -> Unit,
) {
    RadarScreenContent(
        eventId = eventId,
        isHost = isHost,
        coordinationError = coordinationError,
        discoveryRepository = discoveryRepository,
        sessionState = sessionState,
        chatBubbleColorArgb = chatBubbleColorArgb,
        onChatBubbleColorChange = onChatBubbleColorChange,
        chatColourHoldersOverride = chatColourHoldersOverride,
        refusedChatColourArgb = refusedChatColourArgb,
        localPeerIdOverride = localPeerIdOverride,
        radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
        connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
        connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
        hostConnectionLostAtMillis = hostConnectionLostAtMillis,
        lastKnownPeerDirections = lastKnownPeerDirections,
        isTopDestination = isTopDestination,
        reduceMotionEnabled = reduceMotionEnabled,
        proximityHapticsEnabled = proximityHapticsEnabled,
        spokenRadarUpdatesEnabled = spokenRadarUpdatesEnabled,
        pendingJoinRequests = pendingJoinRequests,
        onApproveJoinRequest = onApproveJoinRequest,
        onDenyJoinRequest = onDenyJoinRequest,
        onUsernameChange = onUsernameChange,
        onEventSettings = onEventSettings,
        onLeaveEvent = onLeaveEvent,
        onEndEvent = onEndEvent,
    )
}
