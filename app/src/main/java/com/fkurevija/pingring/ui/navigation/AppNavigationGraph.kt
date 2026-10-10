package com.fkurevija.pingring.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.fkurevija.pingring.data.ChatBubblePalette
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.ForegroundPresenceTracker
import com.fkurevija.pingring.data.SessionManager
import com.fkurevija.pingring.data.discovery.ConnectivityStatus
import com.fkurevija.pingring.data.settings.AppearanceSettingsRepository
import com.fkurevija.pingring.data.settings.DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.settings.ThemeMode
import com.fkurevija.pingring.ui.screens.ConnectionAuthenticationDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Composable
fun MainNavigation(
    sessionManager: SessionManager,
    discoveryRepository: DiscoveryRepository,
    appearanceSettings: AppearanceSettingsRepository,
    themeMode: ThemeMode
) {
    val backStack: NavBackStack<NavKey> = rememberNavBackStack(HomeKey)
    val currentThemeMode by rememberUpdatedState(themeMode)
    val savedUsername by appearanceSettings.userName.collectAsState(initial = "")
    val chatColorEventId by sessionManager.sessionState
        .map { it.currentEvent?.id.orEmpty() }
        .collectAsState(initial = sessionManager.sessionState.value.currentEvent?.id.orEmpty())
    val chatBubbleColorFlow = remember(chatColorEventId) {
        appearanceSettings.chatBubbleColor(chatColorEventId)
    }
    val chatBubbleColor by chatBubbleColorFlow.collectAsState(initial = null)

    // Give every member a bubble colour on the way into a PingRing, rather than
    // leaving them without one until they go looking for it.
    //
    // A LaunchedEffect keyed on the ring id rather than an inline `?:` so the
    // draw happens once per entry: computing it during composition would pick a
    // new random colour on every recomposition, and two phones in the same
    // conversation would keep disagreeing about what colour somebody is.
    LaunchedEffect(chatColorEventId) {
        if (chatColorEventId.isNotEmpty() &&
            appearanceSettings.chatBubbleColor(chatColorEventId).first() == null
        ) {
            appearanceSettings.setChatBubbleColor(chatColorEventId, ChatBubblePalette.random())
        }
    }
            // For the About screen's capability rows. Seeded to both radios
            // on, so the report cannot claim a radio is off before the
            // repository has said anything; a wrong No in a pasted bug report is
            // worse than a late Yes.
    val connectivityStatus by discoveryRepository.connectivityStatus.collectAsState(
        initial = ConnectivityStatus(isBluetoothEnabled = true, isWifiEnabled = true)
    )
    val radarScreenReaderSummaryEnabled by appearanceSettings.radarScreenReaderSummaryEnabled.collectAsState(initial = false)
    val reduceMotionEnabled by appearanceSettings.reduceMotionEnabled.collectAsState(initial = false)
    val proximityHapticsEnabled by appearanceSettings.proximityHapticsEnabled.collectAsState(initial = true)
    val spokenRadarUpdatesEnabled by appearanceSettings.spokenRadarUpdatesEnabled.collectAsState(initial = false)
    val visualFlashAlertsEnabled by appearanceSettings.visualFlashAlertsEnabled.collectAsState(initial = false)
    val peerLinkDiagnosticsEnabled by appearanceSettings.peerLinkDiagnosticsEnabled.collectAsState(initial = false)
    val batterySaverEnabled by appearanceSettings.batterySaverEnabled.collectAsState(initial = false)
    val connectionRecoveryPromptEnabled by appearanceSettings.connectionRecoveryPromptEnabled.collectAsState(initial = true)
    val connectionRecoveryPromptDelayMillis by appearanceSettings.connectionRecoveryPromptDelayMillis.collectAsState(
        initial = 120_000L
    )
    // Collected here rather than at the top with the other settings because this
    // is where the join and App Settings destinations can already see it: the join
    // timeout is read inside a destination block, which is a deeper scope.
    val joinWaitTimeoutMillis by appearanceSettings.joinWaitTimeoutMillis.collectAsState(
        initial = DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS
    )
    // The notification switches live on the App Settings screen itself rather
    // than behind a Notifications submenu, so they are collected here with the
    // rest of the appearance settings and handed straight down.
    val chatNotificationsEnabled by appearanceSettings.chatNotificationsEnabled.collectAsState(initial = true)
    val readReceiptsEnabled by appearanceSettings.readReceiptsEnabled.collectAsState(initial = true)
    val notificationSoundEnabled by appearanceSettings.notificationSoundEnabled.collectAsState(initial = true)
    val notificationVibrationEnabled by appearanceSettings.notificationVibrationEnabled.collectAsState(initial = true)
    val nearbyDigitComparisonEnabled by appearanceSettings.nearbyDigitComparisonEnabled.collectAsState(initial = true)
    val hostConnectionLostAtMillis by discoveryRepository.hostConnectionLostAtMillis.collectAsState(initial = null)
    val lastKnownPeerDirections by discoveryRepository.lastKnownPeerDirections.collectAsState(initial = emptyList())
    // Devices waiting on the host to approve. Collected here rather than inside
    // the screen because the screen has to compose with no repository at all,
    // which is the only way its layout is testable.
    val pendingJoinRequests by discoveryRepository.pendingJoinRequests
        .collectAsState(initial = emptyList())
    val pendingAuthentications by discoveryRepository.pendingAuthentications
        .collectAsState(initial = emptyList())

    // Chat notifications are suppressed only while the radar/chat window is
    // the destination actually on top. Being inside the app is not enough:
    // from home, host setup or the settings screens a chat message is still
    // something the user cannot see arrive, so it has to notify.
    //
    // The top entry is derived from the back stack rather than from whether
    // RadarScreen happens to be composed, because NavDisplay keeps composing
    // entries that are buried in the stack.
    val topKey = backStack.lastOrNull()
    DisposableEffect(topKey) {
        ForegroundPresenceTracker.onRadarWindowVisibilityChanged(topKey is RadarKey)
        onDispose { ForegroundPresenceTracker.onRadarWindowVisibilityChanged(false) }
    }

    Box(Modifier.fillMaxSize()) {
    NavDisplay(backStack) { key ->
        NavEntry(key) {
            val sessionState by sessionManager.sessionState.collectAsState()
            val syncStatus by sessionManager.syncStatus.collectAsState()
            when (key) {
                HomeKey -> HomeDestination(
                    sessionState = sessionState,
                    sessionManager = sessionManager,
                    discoveryRepository = discoveryRepository,
                    backStack = backStack,
                    reduceMotionEnabled = reduceMotionEnabled,
                )
                is HostSetupKey -> HostSetupDestination(
                    key = key,
                    sessionState = sessionState,
                    savedUsername = savedUsername,
                    sessionManager = sessionManager,
                    discoveryRepository = discoveryRepository,
                    appearanceSettings = appearanceSettings,
                    backStack = backStack,
                    pendingJoinRequests = pendingJoinRequests,
                )
                PingRingJoinKey -> JoinDestination(
                    savedUsername = savedUsername,
                    sessionState = sessionState,
                    sessionManager = sessionManager,
                    discoveryRepository = discoveryRepository,
                    appearanceSettings = appearanceSettings,
                    backStack = backStack,
                    joinWaitTimeoutMillis = joinWaitTimeoutMillis,
                )
                AppSettingsKey -> AppSettingsDestination(
                    themeMode = currentThemeMode,
                    appearanceSettings = appearanceSettings,
                    backStack = backStack,
                )
                NotificationSettingsKey -> NotificationSettingsDestination(
                    appearanceSettings = appearanceSettings,
                    chatNotificationsEnabled = chatNotificationsEnabled,
                    notificationSoundEnabled = notificationSoundEnabled,
                    notificationVibrationEnabled = notificationVibrationEnabled,
                    backStack = backStack,
                )
                ConnectionSettingsKey -> ConnectionSettingsDestination(
                    appearanceSettings = appearanceSettings,
                    nearbyDigitComparisonEnabled = nearbyDigitComparisonEnabled,
                    connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
                    connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
                    joinWaitTimeoutMillis = joinWaitTimeoutMillis,
                    peerLinkDiagnosticsEnabled = peerLinkDiagnosticsEnabled,
                    batterySaverEnabled = batterySaverEnabled,
                    backStack = backStack,
                )
                DeveloperSettingsKey -> DeveloperSettingsDestination(
                    appearanceSettings = appearanceSettings,
                    backStack = backStack,
                )
                AboutKey -> AboutDestination(
                    discoveryRepository = discoveryRepository,
                    connectivityStatus = connectivityStatus,
                    reduceMotionEnabled = reduceMotionEnabled,
                    backStack = backStack,
                )
                AccessibilitySettingsKey -> AccessibilitySettingsDestination(
                    appearanceSettings = appearanceSettings,
                    radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
                    reduceMotionEnabled = reduceMotionEnabled,
                    proximityHapticsEnabled = proximityHapticsEnabled,
                    spokenRadarUpdatesEnabled = spokenRadarUpdatesEnabled,
                    visualFlashAlertsEnabled = visualFlashAlertsEnabled,
                    backStack = backStack,
                )
                is PingRingSettingsKey -> PingRingSettingsDestination(
                    key = key,
                    sessionState = sessionState,
                    sessionManager = sessionManager,
                    discoveryRepository = discoveryRepository,
                    backStack = backStack,
                )
                is RadarKey -> RadarDestination(
                    key = key,
                    sessionState = sessionState,
                    syncErrorMessage = syncStatus.errorMessage,
                    discoveryRepository = discoveryRepository,
                    sessionManager = sessionManager,
                    appearanceSettings = appearanceSettings,
                    backStack = backStack,
                    chatBubbleColor = chatBubbleColor,
                    radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
                    reduceMotionEnabled = reduceMotionEnabled,
                    proximityHapticsEnabled = proximityHapticsEnabled,
                    spokenRadarUpdatesEnabled = spokenRadarUpdatesEnabled,
                    readReceiptsEnabled = readReceiptsEnabled,
                    connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
                    connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
                    hostConnectionLostAtMillis = hostConnectionLostAtMillis,
                    lastKnownPeerDirections = lastKnownPeerDirections,
                    isTopDestination = topKey == key,
                    pendingJoinRequests = pendingJoinRequests,
                )
            }
        }
    }
        pendingAuthentications.firstOrNull()?.let { request ->
            ConnectionAuthenticationDialog(
                request = request,
                onConfirm = { discoveryRepository.confirmConnectionAuthentication(request.endpointId) },
                onReject = { discoveryRepository.rejectConnectionAuthentication(request.endpointId) },
            )
        }
    }
}

internal fun NavBackStack<NavKey>.resetTo(key: NavKey) {
    add(key)
    while (size > 1) {
        removeAt(0)
    }
}

internal fun NavBackStack<NavKey>.popBack() {
    if (size > 1) {
        removeLastOrNull()
    } else if (firstOrNull() != HomeKey) {
        resetTo(HomeKey)
    }
}
