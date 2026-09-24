package com.fkurevija.pingring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.fkurevija.pingring.data.NearbyConnectionsManager
import com.fkurevija.pingring.data.SessionManager
import com.fkurevija.pingring.data.ThemeMode
import com.fkurevija.pingring.ui.navigation.AppSettingsKey
import com.fkurevija.pingring.ui.navigation.PingRingJoinKey
import com.fkurevija.pingring.ui.navigation.PingRingSettingsKey
import com.fkurevija.pingring.ui.navigation.HomeKey
import com.fkurevija.pingring.ui.navigation.HostSetupKey
import com.fkurevija.pingring.ui.navigation.RadarKey
import com.fkurevija.pingring.ui.permissions.PermissionWrapper
import com.fkurevija.pingring.ui.screens.AppSettingsScreen
import com.fkurevija.pingring.ui.screens.PingRingJoinScreen
import com.fkurevija.pingring.ui.screens.PingRingSettingsScreen
import com.fkurevija.pingring.ui.screens.HomeScreen
import com.fkurevija.pingring.ui.screens.HostSetupScreen
import com.fkurevija.pingring.ui.screens.RadarScreen
import com.fkurevija.pingring.ui.theme.PingRingTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                                    Event(
                                        id = eventCode,
                                        name = "PingRing $eventCode",
                                        description = "PingRing Event",
                                        expiryTimestamp = System.currentTimeMillis() + 3600000,
                                        hostPeerId = sessionManager.peerId.value,
                                        trackHost = false,
                                        useUwb = discoveryRepository.isUwbSupported(),
                                        useWifiDirect = true
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
                        savedUsername = savedUsername,
                        discoveryRepository = discoveryRepository,
                        onBack = {
                            discoveryRepository.announceEventClosure(event)
                            discoveryRepository.stopDiscovery()
                            sessionManager.endHostedEvent()
                            backStack.popBack()
                        },
                        onSaveUsername = { username ->
                            appearanceSettings.setUserName(username)
                            sessionManager.setUserName(username)
                        },
                        onEventChange = { trackHost, useUwb, useWifiDirect ->
                            sessionManager.updateEvent(
                                event.copy(
                                    trackHost = trackHost,
                                    useUwb = useUwb,
                                    useWifiDirect = useWifiDirect
                                )
                            )
                        },
                        onView = {
                            backStack.resetTo(RadarKey(key.eventCode))
                        }
                    )
                }

                is PingRingJoinKey -> {
                    PingRingJoinScreen(
                        savedUsername = savedUsername,
                        existingMembers = sessionState.friends.map { it.name },
                        onBack = { backStack.popBack() },
                        onJoinEvent = { eventCode, username ->
                            appearanceSettings.setUserName(username)
                            sessionManager.setUserName(username)
                            if (sessionManager.joinEvent(
                                Event(
                                    id = eventCode,
                                    name = "PingRing $eventCode",
                                    description = "PingRing Event",
                                    expiryTimestamp = System.currentTimeMillis() + 3600000
                                )
                            )) {
                                backStack.resetTo(RadarKey(eventCode))
                            }
                        }
                    )
                }

                is AppSettingsKey -> {
                    AppSettingsScreen(
                        themeMode = currentThemeMode,
                        onThemeModeChange = appearanceSettings::setThemeMode,
                        appearanceSettings = appearanceSettings,
                        onBack = { backStack.popBack() }
                    )
                }

                is PingRingSettingsKey -> {
                    val event = sessionState.currentEvent
                    if (event != null && event.id == key.eventId) {
                        PingRingSettingsScreen(
                            event = event,
                            uwbHardwareSupported = discoveryRepository.isUwbSupported(),
                            onBack = { backStack.popBack() },
                            onSave = {
                                sessionManager.updateEvent(it)
                                backStack.popBack()
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
                        onEventSettings = { backStack.add(PingRingSettingsKey(key.eventId)) },
                        onLeaveEvent = {
                            discoveryRepository.stopDiscovery()
                            sessionManager.leaveEvent()
                            backStack.resetTo(HomeKey)
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
