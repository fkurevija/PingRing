package com.fkurevija.pingring

import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.Job
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
import com.fkurevija.pingring.data.ChatBubblePalette
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
import com.fkurevija.pingring.ui.navigation.AccessibilitySettingsKey
import com.fkurevija.pingring.ui.navigation.AboutKey
import com.fkurevija.pingring.ui.navigation.DeveloperSettingsKey
import com.fkurevija.pingring.ui.navigation.PingRingJoinKey
import com.fkurevija.pingring.ui.navigation.PingRingSettingsKey
import com.fkurevija.pingring.ui.navigation.HomeKey
import com.fkurevija.pingring.ui.navigation.HostSetupKey
import com.fkurevija.pingring.ui.navigation.RadarKey
import com.fkurevija.pingring.ui.permissions.PermissionWrapper
import com.fkurevija.pingring.ui.screens.AppSettingsScreen
import com.fkurevija.pingring.ui.screens.AccessibilitySettingsScreen
import com.fkurevija.pingring.data.BugReportPolicy
import com.fkurevija.pingring.data.DiagnosticsPolicy
import com.fkurevija.pingring.ui.screens.AboutScreen
import com.fkurevija.pingring.ui.screens.diagnosticLabelRes
import com.fkurevija.pingring.ui.screens.DeveloperSettingsScreen
import com.fkurevija.pingring.ui.screens.rememberDiagnosticsFacts
import com.fkurevija.pingring.ui.screens.ConnectionAuthenticationDialog
import com.fkurevija.pingring.ui.screens.PingRingJoinScreen
import com.fkurevija.pingring.ui.screens.PingRingSettingsScreen
import com.fkurevija.pingring.ui.screens.HomeScreen
import com.fkurevija.pingring.ui.screens.HostSetupScreen
import com.fkurevija.pingring.ui.screens.RadarScreen
import com.fkurevija.pingring.ui.theme.PingRingTheme
import com.fkurevija.pingring.data.ConnectivityStatus
import com.fkurevija.pingring.data.DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.PingRingRuntime
import java.util.UUID

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
    val notificationSoundEnabled by appearanceSettings.notificationSoundEnabled.collectAsState(initial = true)
    val notificationVibrationEnabled by appearanceSettings.notificationVibrationEnabled.collectAsState(initial = true)
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
                        reduceMotionEnabled = reduceMotionEnabled,
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
                        },
                        pendingJoinRequests = pendingJoinRequests,
                        onApproveJoinRequest = discoveryRepository::approveJoinRequest,
                        onDenyJoinRequest = discoveryRepository::denyJoinRequest,
                    )
                }

                is PingRingJoinKey -> {
                    val context = LocalContext.current
                    var isConnecting by remember { mutableStateOf(false) }
                    val coroutineScope = rememberCoroutineScope()
                    // The join attempt itself, so back can abandon it rather
                    // than only being refused while it is in flight.
                    //
                    // Back used to be inert here for the whole attempt, which
                    // made a join that never resolved - a host out of range, a
                    // code typed wrongly, a phone that never sees the host - a
                    // dead end: the screen sat on "connecting" until the timeout
                    // eventually expired, with the only control on it doing
                    // nothing. The timeout is still the backstop for a host who
                    // simply never answers, but it is no longer the only way out.
                    var joinJob by remember { mutableStateOf<Job?>(null) }

                    fun cancelJoin() {
                        joinJob?.cancel()
                        joinJob = null
                        sessionManager.leaveEvent()
                        isConnecting = false
                    }

                    PingRingJoinScreen(
                        savedUsername = savedUsername,
                        existingMembers = sessionState.friends.map { it.name },
                        isConnecting = isConnecting,
                        // Back from here (arrow or system) always lands on Home,
                        // whatever is underneath on the stack, and takes the
                        // attempt down with it on the way out.
                        onBack = {
                            cancelJoin()
                            backStack.resetTo(HomeKey)
                        },
                        onJoinEvent = { eventCode, username, inviteSecret ->
                            if (!isConnecting) {
                                isConnecting = true
                                appearanceSettings.setUserName(username)
                                sessionManager.setUserName(username)
                                val event = Event.create(id = eventCode, inviteSecret = inviteSecret)
                                if (sessionManager.joinEvent(event)) {
                                    joinJob = coroutineScope.launch {
                                        // Exactly what the member set, with no floor applied.
                                        // There used to be a maxOf here against the host's
                                        // approval window, which meant a member who chose a
                                        // short wait silently got a long one and the setting
                                        // appeared not to work below a threshold. Choosing to
                                        // wait less than a slow host is the point of the
                                        // setting; the cost is documented on the constant
                                        // rather than hidden by overriding the choice here.
                                        val joinWaitMillis = joinWaitTimeoutMillis
                                        Log.i(
                                            "PingRingJoin",
                                            "Joining $eventCode, giving up after ${joinWaitMillis}ms",
                                        )
                                        val connected = runCatching {
                                            withTimeout(joinWaitMillis) {
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
                    // Named distinctly from the composable's own `context`:
                    // inside this destination block that name is shadowed by a
                    // parameter, so reaching for it here resolves to the wrong
                    // thing. Captured locally from the composition so the
                    // callback below - which is not itself composable - can
                    // still start an activity.
                    val appSettingsContext = LocalContext.current.applicationContext
                    AppSettingsScreen(
                        selectedLanguageTag = AppCompatDelegate.getApplicationLocales().toLanguageTags(),
                        onLanguageSelected = { languageTag ->
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
                        },
                        themeMode = currentThemeMode,
                        onThemeModeChange = appearanceSettings::setThemeMode,
                        chatNotificationsEnabled = chatNotificationsEnabled,
                        onChatNotificationsEnabledChange = appearanceSettings::setChatNotificationsEnabled,
                        notificationSoundEnabled = notificationSoundEnabled,
                        onNotificationSoundEnabledChange = appearanceSettings::setNotificationSoundEnabled,
                        notificationVibrationEnabled = notificationVibrationEnabled,
                        onNotificationVibrationEnabledChange = appearanceSettings::setNotificationVibrationEnabled,
                        onOpenAccessibility = { backStack.add(AccessibilitySettingsKey) },
                        connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
                        connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
                        onConnectionRecoveryPromptEnabledChange = appearanceSettings::setConnectionRecoveryPromptEnabled,
                        onConnectionRecoveryPromptDelayChange = appearanceSettings::setConnectionRecoveryPromptDelayMillis,
                        joinWaitTimeoutMillis = joinWaitTimeoutMillis,
                        onJoinWaitTimeoutChange = appearanceSettings::setJoinWaitTimeoutMillis,
                        showDeveloperOptions = BuildConfig.DEBUG,
                        onOpenDeveloperOptions = { backStack.add(DeveloperSettingsKey) },
                        onOpenAbout = { backStack.add(AboutKey) },
                        onBack = { backStack.popBack() }
                    )
                }

                is DeveloperSettingsKey -> {
                    DeveloperSettingsScreen(
                        appearanceSettings = appearanceSettings,
                        onBack = { backStack.popBack() },
                    )
                }

                is AboutKey -> {
                    // Not `context`: inside this branch that name resolves to
                    // Compose's `context` scope function rather than to the
                    // activity context, and the failure reads as a confusing
                    // "block expected" instead of a missing variable.
                    val aboutContext = LocalContext.current.applicationContext
                    // Probed here rather than inside the screen so the screen
                    // needs no Context, package manager or vibrator to compose in
                    // a test - the same split PeerList and ActiveBeaconEntry use.
                    val aboutFacts = rememberDiagnosticsFacts(
                        isDebugBuild = BuildConfig.DEBUG,
                        uwbSupported = discoveryRepository.isUwbSupported(),
                        bluetoothEnabled = connectivityStatus.isBluetoothEnabled,
                        wifiEnabled = connectivityStatus.isWifiEnabled,
                    )
                    AboutScreen(
                        facts = aboutFacts,
                        onBack = { backStack.popBack() },
                        onReportAProblem = {
                            // The report and the title are assembled here, from the
                            // same facts the screen is showing, so the link cannot
                            // describe a different device from the one on screen.
                            //
                            // The URL is a constant and the encoding is
                            // BugReportPolicy's, both unit tested; all that is left
                            // to go wrong here is starting an intent, which is the
                            // one thing worth guarding because a device with no
                            // browser at all would otherwise crash on tap.
                            //
                            // Uses the application context rather than the composable's own: an
                            // ACTION_VIEW hand-off has no business holding an
                            // activity, and the destination context is scoped to
                            // a screen that may not be on screen when this
                            // callback runs.
                            val report = DiagnosticsPolicy.report(aboutFacts) { key ->
                                aboutContext.getString(key.diagnosticLabelRes())
                            }
                            val version = aboutFacts.versionName
                            val device = "${aboutFacts.deviceManufacturer} " +
                                aboutFacts.deviceModel
                            val issueUrl = Uri.parse(
                                BugReportPolicy.issueUrl(
                                    title = BugReportPolicy.titleFor(version, device.trim()),
                                    body = report,
                                )
                            )
                            runCatching {
                                aboutContext.startActivity(
                                    Intent(Intent.ACTION_VIEW, issueUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure {
                                Toast.makeText(aboutContext, R.string.report_a_problem_failed, Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }

                is AccessibilitySettingsKey -> {
                    AccessibilitySettingsScreen(
                        radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
                        onRadarScreenReaderSummaryChange = appearanceSettings::setRadarScreenReaderSummaryEnabled,
                        reduceMotionEnabled = reduceMotionEnabled,
                        onReduceMotionChange = appearanceSettings::setReduceMotionEnabled,
                        proximityHapticsEnabled = proximityHapticsEnabled,
                        onProximityHapticsChange = appearanceSettings::setProximityHapticsEnabled,
                        spokenRadarUpdatesEnabled = spokenRadarUpdatesEnabled,
                        onSpokenRadarUpdatesChange = appearanceSettings::setSpokenRadarUpdatesEnabled,
                        onBack = { backStack.popBack() },
                    )
                }

                is PingRingSettingsKey -> {
                    val event = sessionState.currentEvent
                    if (event != null && event.id == key.eventId) {
                        val isHost = sessionState.isHost
                        PingRingSettingsScreen(
                            event = event,
                            uwbHardwareSupported = discoveryRepository.isUwbSupported(),
                            isHost = isHost,
                            onBack = { backStack.popBack() },
                            // Pushed onto the same stack, so backing out of app
                            // settings returns to this screen rather than to Home
                            // or out of the app.
                            onOpenAppSettings = { backStack.add(AppSettingsKey) },
                            // Applied the moment a control is touched, and the
                            // screen stays where it is. This used to pop the back
                            // stack, which was right for a Save button and wrong
                            // now: toggling two switches in a row would have
                            // thrown the member back to the radar after the
                            // first one. Leaving is the arrow's job, which is
                            // also what makes the loss of Cancel honest.
                            onEventChanged = {
                                sessionManager.updateEvent(it)
                                discoveryRepository.broadcastEventControl(it)
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
                        coordinationError = syncStatus.errorMessage,
                        sessionState = sessionState,
                        chatBubbleColorArgb = chatBubbleColor,
                        onChatBubbleColorChange = { color ->
                            appearanceSettings.setChatBubbleColor(key.eventId, color)
                        },
                        radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
                        reduceMotionEnabled = reduceMotionEnabled,
                        proximityHapticsEnabled = proximityHapticsEnabled,
                        spokenRadarUpdatesEnabled = spokenRadarUpdatesEnabled,
                        connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
                        connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
                        hostConnectionLostAtMillis = hostConnectionLostAtMillis,
                        lastKnownPeerDirections = lastKnownPeerDirections,
                        isTopDestination = topKey == key,
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
