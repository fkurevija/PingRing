package com.fkurevija.pingring.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.session.SessionManager
import com.fkurevija.pingring.data.session.SessionState
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.discovery.ConnectivityStatus
import com.fkurevija.pingring.data.radar.LastKnownPeerDirection
import com.fkurevija.pingring.data.settings.AppearanceSettingsRepository
import com.fkurevija.pingring.ui.screens.PingRingSettingsScreen
import com.fkurevija.pingring.ui.screens.RadarScreen
import com.fkurevija.pingring.data.BeaconAlertSettings
import com.fkurevija.pingring.data.session.Event

@Composable
internal fun PingRingSettingsDestination(
    key: PingRingSettingsKey,
    sessionState: SessionState,
    sessionManager: SessionManager,
    discoveryRepository: DiscoveryRepository,
    backStack: NavBackStack<NavKey>,
) {
    val event = sessionState.currentEvent
    if (event != null && event.id == key.eventId) {
        val isHost = sessionState.isHost
        val beaconAlertSettings by discoveryRepository.beaconAlertSettings.collectAsState()
        PingRingSettingsScreen(
            event = event,
            uwbHardwareSupported = discoveryRepository.isUwbSupported(),
            isHost = isHost,
            beaconAlertSettings = beaconAlertSettings,
            onBeaconAlertSettingsChange = discoveryRepository::setBeaconAlertSettings,
            onBack = { backStack.popBack() },
            onOpenAppSettings = { backStack.add(AppSettingsKey) },
            onEventChanged = {
                sessionManager.updateEvent(it)
                discoveryRepository.broadcastEventControl(it)
            },
            onEndEvent = {
                discoveryRepository.announceEventClosure(event)
                discoveryRepository.stopDiscovery()
                sessionManager.endHostedEvent()
                backStack.resetTo(HomeKey)
            },
            onLeaveEvent = {
                discoveryRepository.announceMemberDeparture {
                    discoveryRepository.stopDiscovery()
                    sessionManager.leaveEvent()
                    backStack.resetTo(HomeKey)
                }
            }
        )
    }
}

@Composable
internal fun RadarDestination(
    key: RadarKey,
    sessionState: SessionState,
    syncErrorMessage: String?,
    discoveryRepository: DiscoveryRepository,
    sessionManager: SessionManager,
    appearanceSettings: AppearanceSettingsRepository,
    backStack: NavBackStack<NavKey>,
    chatBubbleColor: Long?,
    radarScreenReaderSummaryEnabled: Boolean,
    reduceMotionEnabled: Boolean,
    proximityHapticsEnabled: Boolean,
    spokenRadarUpdatesEnabled: Boolean,
    readReceiptsEnabled: Boolean,
    connectionRecoveryPromptEnabled: Boolean,
    connectionRecoveryPromptDelayMillis: Long,
    hostConnectionLostAtMillis: Long?,
    lastKnownPeerDirections: List<LastKnownPeerDirection>,
    isTopDestination: Boolean,
    pendingJoinRequests: List<PendingJoinRequest>,
) {
    val event = sessionState.currentEvent
    if (event == null || event.id != key.eventId) {
        LaunchedEffect(key) {
            if (backStack.lastOrNull() != HomeKey) {
                backStack.resetTo(HomeKey)
            }
        }
    } else {
        RadarScreen(
            eventId = key.eventId,
            discoveryRepository = discoveryRepository,
            isHost = sessionState.isHost,
            coordinationError = syncErrorMessage,
            sessionState = sessionState,
            onUsernameChange = { username ->
                discoveryRepository.updateLocalUsername(username)
                appearanceSettings.setUserName(username)
            },
            chatBubbleColorArgb = chatBubbleColor,
            onChatBubbleColorChange = { color ->
                appearanceSettings.setChatBubbleColor(key.eventId, color)
            },
            radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
            reduceMotionEnabled = reduceMotionEnabled,
            proximityHapticsEnabled = proximityHapticsEnabled,
            spokenRadarUpdatesEnabled = spokenRadarUpdatesEnabled,
            readReceiptsEnabled = readReceiptsEnabled,
            onReadReceiptsChanged = appearanceSettings::setReadReceiptsEnabled,
            connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
            connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
            hostConnectionLostAtMillis = hostConnectionLostAtMillis,
            lastKnownPeerDirections = lastKnownPeerDirections,
            isTopDestination = isTopDestination,
            pendingJoinRequests = pendingJoinRequests,
            onApproveJoinRequest = { discoveryRepository.approveJoinRequest(it) },
            onDenyJoinRequest = { discoveryRepository.denyJoinRequest(it) },
            onEventSettings = { backStack.add(PingRingSettingsKey(key.eventId)) },
            onLeaveEvent = {
                discoveryRepository.announceMemberDeparture {
                    discoveryRepository.stopDiscovery()
                    sessionManager.leaveEvent()
                    backStack.resetTo(HomeKey)
                }
            },
            onEndEvent = {
                discoveryRepository.announceEventClosure(event)
                discoveryRepository.stopDiscovery()
                sessionManager.endHostedEvent()
                backStack.resetTo(HomeKey)
            }
        )
    }
}
