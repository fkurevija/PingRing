package com.fkurevija.pingring

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.widget.Toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.fkurevija.pingring.BuildConfig
import com.fkurevija.pingring.data.AppearanceSettingsRepository
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.LanDebugConnectionsManager
import com.fkurevija.pingring.data.NearbyTransport
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.data.ForegroundPresenceTracker
import com.fkurevija.pingring.data.InviteSecret
import com.fkurevija.pingring.data.NearbyConnectionsManager
import com.fkurevija.pingring.data.PingRingService
import com.fkurevija.pingring.data.SessionManager
import com.fkurevija.pingring.data.ThemeMode
import com.fkurevija.pingring.ui.navigation.AppSettingsKey
import com.fkurevija.pingring.ui.navigation.AppearanceSettingsKey
import com.fkurevija.pingring.ui.navigation.AccessibilitySettingsKey
import com.fkurevija.pingring.ui.navigation.DeveloperSettingsKey
import com.fkurevija.pingring.ui.navigation.NotificationSettingsKey
import com.fkurevija.pingring.ui.navigation.PingRingJoinKey
import com.fkurevija.pingring.ui.navigation.PingRingSettingsKey
import com.fkurevija.pingring.ui.navigation.HomeKey
import com.fkurevija.pingring.ui.navigation.HostSetupKey
import com.fkurevija.pingring.ui.navigation.RadarKey
import com.fkurevija.pingring.ui.permissions.PermissionWrapper
import com.fkurevija.pingring.ui.screens.AppSettingsScreen
import com.fkurevija.pingring.ui.screens.AppearanceSettingsScreen
import com.fkurevija.pingring.ui.screens.AccessibilitySettingsScreen
import com.fkurevija.pingring.ui.screens.DeveloperSettingsScreen
import com.fkurevija.pingring.ui.screens.ConnectionAuthenticationDialog
import com.fkurevija.pingring.ui.screens.NotificationSettingsScreen
import com.fkurevija.pingring.ui.screens.PingRingJoinScreen
import com.fkurevija.pingring.ui.screens.PingRingSettingsScreen
import com.fkurevija.pingring.ui.screens.HomeScreen
import com.fkurevija.pingring.ui.screens.HostSetupScreen
import com.fkurevija.pingring.ui.screens.RadarScreen
import com.fkurevija.pingring.ui.theme.PingRingTheme
import com.fkurevija.pingring.data.JOIN_REQUEST_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.PingRingRuntime
import java.util.UUID

/**
 * How long a guest waits for a join to complete before giving up.
 *
 * A host can be holding the request open for approval, so this has to outlast
 * [JOIN_REQUEST_TIMEOUT_MILLIS] rather than race it: the host giving up first
 * and the guest then timing out on a request that was already refused would
 * report a decision as a failure.
 */
private const val JOIN_WAIT_TIMEOUT_MILLIS = JOIN_REQUEST_TIMEOUT_MILLIS + 15_000L

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("PingRingCrash", "Unhandled exception on thread ${thread.name}", throwable)
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
        }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current.applicationContext
            val sessionManager = remember(context) { SessionManager(context = context) }
            val appearanceSettings = remember { AppearanceSettingsRepository(context) }
            val themeMode by appearanceSettings.themeMode.collectAsState(
                initial = ThemeMode.SYSTEM
            )
            val lanDebugEnabled by appearanceSettings.lanDebugEnabled.collectAsState(initial = false)
            val lanDebugHost by appearanceSettings.lanDebugHost.collectAsState(initial = "10.0.2.2")
            val nearbyConnectionsManager: NearbyTransport = remember(context, lanDebugEnabled, lanDebugHost) {
                if (BuildConfig.DEBUG && lanDebugEnabled) {
                    LanDebugConnectionsManager(host = lanDebugHost)
                } else {
                    NearbyConnectionsManager(context)
                }
            }
            val discoveryRepository = remember(nearbyConnectionsManager) {
                DiscoveryRepository(context, sessionManager, nearbyConnectionsManager)
            }
            PingRingRuntime.configure(discoveryRepository)

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, discoveryRepository) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> discoveryRepository.pauseSensors()
                        Lifecycle.Event.ON_RESUME -> discoveryRepository.resumeSensors()
                        else -> {}
                    }
                    // Chat notifications are suppressed while the user is
                    // actually watching the radar, so the activity's own
                    // foreground state has to be published. ON_START/ON_STOP
                    // rather than ON_RESUME/ON_PAUSE: a permission dialog or
                    // the end-event dialog pauses the activity, but the
                    // user has not left the app, and the radar is still what
                    // is on screen behind it.
                    when (event) {
                        Lifecycle.Event.ON_START -> ForegroundPresenceTracker.onActivityForegroundChanged(true)
                        Lifecycle.Event.ON_STOP -> ForegroundPresenceTracker.onActivityForegroundChanged(false)
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val sessionState by sessionManager.sessionState.collectAsState()
            val currentEvent = sessionState.currentEvent

            LaunchedEffect(currentEvent?.id) {
                if (currentEvent != null) {
                    PingRingService.start(context, currentEvent.id)
                } else {
                    PingRingService.stop(context)
                }
            }

            PingRingTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        PermissionWrapper {
                            MainNavigation(
                                sessionManager = sessionManager,
                                discoveryRepository = discoveryRepository,
                                appearanceSettings = appearanceSettings,
                                themeMode = themeMode
                            )
                        }
                    }
                }
            }
        }
    }
}

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
    val quickBeacon by appearanceSettings.quickBeacon.collectAsState(initial = null)
    val chatColorEventId by sessionManager.sessionState
        .map { it.currentEvent?.id.orEmpty() }
        .collectAsState(initial = sessionManager.sessionState.value.currentEvent?.id.orEmpty())
    val chatBubbleColorFlow = remember(chatColorEventId) {
        appearanceSettings.chatBubbleColor(chatColorEventId)
    }
    val chatBubbleColor by chatBubbleColorFlow.collectAsState(initial = null)
    val radarScreenReaderSummaryEnabled by appearanceSettings.radarScreenReaderSummaryEnabled.collectAsState(initial = false)
    val connectionRecoveryPromptEnabled by appearanceSettings.connectionRecoveryPromptEnabled.collectAsState(initial = true)
    val connectionRecoveryPromptDelayMillis by appearanceSettings.connectionRecoveryPromptDelayMillis.collectAsState(
        initial = 120_000L
    )
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
                is HomeKey -> {
                    val currentEvent = sessionState.currentEvent
                    if (currentEvent != null) {
                        LaunchedEffect(currentEvent.id, sessionState.isHost) {
                            val targetKey = if (sessionState.isHost) {
                                HostSetupKey(currentEvent.id)
                            } else {
                                RadarKey(currentEvent.id)
                            }
                            if (backStack.lastOrNull() != targetKey) {
                                backStack.resetTo(targetKey)
                            }
                        }
                    }

                    HomeScreen(
                        onHost = {
                            if (sessionManager.sessionState.value.currentEvent == null) {
                                val eventCode = generateEventCode()
                                sessionManager.hostEvent(
                                    Event.create(
                                        id = eventCode,
                                        hostPeerId = sessionManager.peerId.value,
                                        useUwb = discoveryRepository.isUwbSupported(),
                                        inviteSecret = InviteSecret.generate(),
                                    )
                                )
                                backStack.add(HostSetupKey(eventCode))
                            }
                        },
                        onJoin = { backStack.add(PingRingJoinKey) },
                        onSettings = { backStack.add(AppSettingsKey) }
                    )
                }

                is HostSetupKey -> {
                    val event = sessionState.currentEvent
                    if (event == null || event.id != key.eventCode || !sessionState.isHost) {
                        LaunchedEffect(key) {
                            if (backStack.lastOrNull() != HomeKey) {
                                backStack.resetTo(HomeKey)
                            }
                        }
                    } else HostSetupScreen(
                        eventCode = key.eventCode,
                        inviteSecret = event.inviteSecret,
                        savedUsername = savedUsername,
                        discoveryRepository = discoveryRepository,
                        // Confirmed by the End PingRing dialog on that screen,
                        // whichever way the user asked to leave it.
                        onEndEvent = {
                            discoveryRepository.announceEventClosure(event)
                            discoveryRepository.stopDiscovery()
                            sessionManager.endHostedEvent()
                            // resetTo, not popBack: this screen can also be the
                            // only entry on the stack (Home redirects here while
                            // an event is live), and popping the last entry
                            // would drop the user out of the app instead of
                            // back to Home.
                            backStack.resetTo(HomeKey)
                        },
                        onSaveUsername = { username ->
                            appearanceSettings.setUserName(username)
                            sessionManager.setUserName(username)
                        },
                        onView = {
                            backStack.resetTo(RadarKey(key.eventCode))
                        },
                        // PingRing Settings owns every event switch. This screen
                        // deliberately carries none of them, so there is no
                        // second copy here to drift out of step with the first.
                        onOpenSettings = {
                            backStack.add(PingRingSettingsKey(key.eventCode))
                        }
                    )
                }

                is PingRingJoinKey -> {
                    val context = LocalContext.current
                    var isConnecting by remember { mutableStateOf(false) }
                    val coroutineScope = rememberCoroutineScope()
                    val discoveredSessions by discoveryRepository.discoveredSessions
                        .collectAsState(initial = emptyList())
                    // Started when the screen appears rather than on every
                    // recomposition, and cancelled when it leaves, so a scan
                    // cannot outlive the screen that asked for it.
                    LaunchedEffect(Unit) { discoveryRepository.startSessionBrowsing() }
                    DisposableEffect(Unit) {
                        onDispose { discoveryRepository.stopSessionBrowsing() }
                    }

                    PingRingJoinScreen(
                        savedUsername = savedUsername,
                        existingMembers = sessionState.friends.map { it.name },
                        isConnecting = isConnecting,
                        // Browsing is this screen's own scanning, started and
                        // stopped with it: a scan running behind the join screen
                        // would keep the BLE radio busy for a user who never
                        // opened it.
                        discoveredSessions = discoveredSessions,
                        // Back from here (arrow or system) always lands on Home,
                        // whatever is underneath on the stack.
                        onBack = { if (!isConnecting) backStack.resetTo(HomeKey) },
                        onJoinEvent = { eventCode, username, inviteSecret ->
                            if (!isConnecting) {
                                isConnecting = true
                                appearanceSettings.setUserName(username)
                                sessionManager.setUserName(username)
                                val event = Event.create(id = eventCode, inviteSecret = inviteSecret)
                                if (sessionManager.joinEvent(event)) {
                                    coroutineScope.launch {
                                        // Long enough to outlast a host who is
                                        // being asked to approve, and no longer.
                                        // The old 2.5s was sized for a handshake
                                        // that is automatic; under "Approve Each
                                        // Join" a human is in the loop and a
                                        // timeout here reported a perfectly good
                                        // host as "not found".
                                        val connected = runCatching {
                                            withTimeout(JOIN_WAIT_TIMEOUT_MILLIS) {
                                                sessionManager.sessionState
                                                    .map { it.friends }
                                                    .first { it.isNotEmpty() }
                                            }
                                        }.isSuccess || sessionManager.sessionState.value.friends.isNotEmpty()

                                        if (connected) {
                                            isConnecting = false
                                            backStack.resetTo(RadarKey(eventCode))
                                        } else {
                                            Log.w("PingRingJoin", "Join timeout or failure for event $eventCode: host not found or unavailable.")
                                            sessionManager.leaveEvent()
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.join_failed),
                                                Toast.LENGTH_LONG
                                            ).show()
                                            isConnecting = false
                                        }
                                    }
                                } else {
                                    isConnecting = false
                                }
                            }
                        }
                    )
                }

                is AppSettingsKey -> {
                    AppSettingsScreen(
                        selectedLanguageTag = AppCompatDelegate.getApplicationLocales().toLanguageTags(),
                        onLanguageSelected = { languageTag ->
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
                        },
                        onOpenAppearance = { backStack.add(AppearanceSettingsKey) },
                        onOpenNotifications = { backStack.add(NotificationSettingsKey) },
                        onOpenAccessibility = { backStack.add(AccessibilitySettingsKey) },
                        connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
                        connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
                        onConnectionRecoveryPromptEnabledChange = appearanceSettings::setConnectionRecoveryPromptEnabled,
                        onConnectionRecoveryPromptDelayChange = appearanceSettings::setConnectionRecoveryPromptDelayMillis,
                        showDeveloperOptions = BuildConfig.DEBUG,
                        onOpenDeveloperOptions = { backStack.add(DeveloperSettingsKey) },
                        onBack = { backStack.popBack() }
                    )
                }

                is AppearanceSettingsKey -> {
                    AppearanceSettingsScreen(
                        themeMode = currentThemeMode,
                        onThemeModeChange = appearanceSettings::setThemeMode,
                        onBack = { backStack.popBack() },
                    )
                }

                is NotificationSettingsKey -> {
                    NotificationSettingsScreen(
                        appearanceSettings = appearanceSettings,
                        onBack = { backStack.popBack() },
                    )
                }

                is DeveloperSettingsKey -> {
                    DeveloperSettingsScreen(
                        appearanceSettings = appearanceSettings,
                        onBack = { backStack.popBack() },
                    )
                }

                is AccessibilitySettingsKey -> {
                    AccessibilitySettingsScreen(
                        radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
                        onRadarScreenReaderSummaryChange = appearanceSettings::setRadarScreenReaderSummaryEnabled,
                        onBack = { backStack.popBack() },
                    )
                }

                is PingRingSettingsKey -> {
                    val event = sessionState.currentEvent
                    if (event != null && event.id == key.eventId) {
                        val isHost = sessionState.isHost
                        PingRingSettingsScreen(
                            event = event,
                            chatBubbleColorArgb = chatBubbleColor,
                            onChatBubbleColorChange = { color ->
                                appearanceSettings.setChatBubbleColor(event.id, color)
                            },
                            uwbHardwareSupported = discoveryRepository.isUwbSupported(),
                            isHost = isHost,
                            onBack = { backStack.popBack() },
                            onSave = {
                                sessionManager.updateEvent(it)
                                discoveryRepository.broadcastEventControl(it)
                                backStack.popBack()
                            },
                            // Ending announces closure first so every member is
                            // told, then tears the session down and returns to
                            // home. Leaving only affects this device - the
                            // transport reports the disconnect itself, which is
                            // what tells the others.
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

                is RadarKey -> {
                    val event = sessionState.currentEvent
                    if (event == null || event.id != key.eventId) {
                        LaunchedEffect(key) {
                            if (backStack.lastOrNull() != HomeKey) {
                                backStack.resetTo(HomeKey)
                            }
                        }
                    } else RadarScreen(
                        eventId = key.eventId,
                        discoveryRepository = discoveryRepository,
                        isHost = sessionState.isHost,
                        trackHost = sessionState.currentEvent?.trackHost == true,
                        coordinationError = syncStatus.errorMessage,
                        sessionState = sessionState,
                        quickBeacon = quickBeacon,
                        chatBubbleColorArgb = chatBubbleColor,
                        radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
                        connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
                        connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
                        hostConnectionLostAtMillis = hostConnectionLostAtMillis,
                        lastKnownPeerDirections = lastKnownPeerDirections,
                        isTopDestination = topKey == key,
                        onQuickBeaconChange = appearanceSettings::setQuickBeacon,
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

private fun NavBackStack<NavKey>.resetTo(key: NavKey) {
    add(key)
    while (size > 1) {
        removeAt(0)
    }
}

private fun NavBackStack<NavKey>.popBack() {
    if (size > 1) {
        removeLastOrNull()
    } else if (firstOrNull() != HomeKey) {
        resetTo(HomeKey)
    }
}

private fun generateEventCode(): String =
    UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
