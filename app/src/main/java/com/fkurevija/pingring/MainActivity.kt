package com.fkurevija.pingring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.data.AppearanceSettingsRepository
import com.fkurevija.pingring.data.SessionManager
import com.fkurevija.pingring.ui.navigation.EventJoinKey
import com.fkurevija.pingring.ui.navigation.EventSettingsKey
import com.fkurevija.pingring.ui.navigation.HomeKey
import com.fkurevija.pingring.ui.navigation.HostSetupKey
import com.fkurevija.pingring.ui.navigation.AppSettingsKey
import com.fkurevija.pingring.ui.navigation.RadarKey
import com.fkurevija.pingring.ui.permissions.PermissionWrapper
import com.fkurevija.pingring.ui.screens.EventJoinScreen
import com.fkurevija.pingring.ui.screens.EventSettingsScreen
import com.fkurevija.pingring.ui.screens.HomeScreen
import com.fkurevija.pingring.ui.screens.HostSetupScreen
import com.fkurevija.pingring.ui.screens.AppSettingsScreen
import com.fkurevija.pingring.ui.screens.RadarScreen
import com.fkurevija.pingring.ui.theme.PingRingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current.applicationContext
            val sessionManager = remember(context) { SessionManager(context = context) }
            val appearanceSettings = remember { AppearanceSettingsRepository(context) }
            val themeMode by appearanceSettings.themeMode.collectAsState(
                initial = com.fkurevija.pingring.data.ThemeMode.SYSTEM
            )
            val discoveryRepository = remember { DiscoveryRepository(context, sessionManager) }

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
    themeMode: com.fkurevija.pingring.data.ThemeMode
) {
    val backStack: NavBackStack<NavKey> = rememberNavBackStack(HomeKey)
    val currentThemeMode by rememberUpdatedState(themeMode)

    NavDisplay(backStack) { key ->
        NavEntry(key) {
            val sessionState by sessionManager.sessionState.collectAsState()
            val syncStatus by sessionManager.syncStatus.collectAsState()
            when (key) {
                is HomeKey -> {
                    HomeScreen(
                        onHost = { backStack.add(HostSetupKey) },
                        onJoin = { backStack.add(EventJoinKey) },
                        onSettings = { backStack.add(AppSettingsKey) }
                    )
                }

                is HostSetupKey -> {
                    HostSetupScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onCreate = { name, eventCode, trackHost ->
                            val event = Event(
                                id = eventCode,
                                name = name,
                                description = "PingRing Event",
                                expiryTimestamp = System.currentTimeMillis() + 3600000,
                                hostPeerId = sessionManager.peerId.value,
                                trackHost = trackHost
                            )
                            sessionManager.hostEvent(event)
                            backStack.clear()
                            backStack.add(RadarKey(eventCode))
                        }
                    )
                }

                is EventJoinKey -> {
                    EventJoinScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onJoinEvent = { eventCode ->
                            if (sessionManager.joinEvent(
                                Event(
                                    id = eventCode,
                                    name = "Event $eventCode",
                                    description = "PingRing Event",
                                    expiryTimestamp = System.currentTimeMillis() + 3600000
                                )
                            )) {
                                backStack.clear()
                                backStack.add(RadarKey(eventCode))
                            }
                        }
                    )
                }

                is AppSettingsKey -> {
                    AppSettingsScreen(
                        themeMode = currentThemeMode,
                        onThemeModeChange = appearanceSettings::setThemeMode,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }

                is EventSettingsKey -> {
                    val event = sessionState.currentEvent
                    if (event != null && event.id == key.eventId) {
                        EventSettingsScreen(
                            event = event,
                            onBack = { backStack.removeLastOrNull() },
                            onSave = {
                                sessionManager.updateEvent(it)
                                backStack.removeLastOrNull()
                            }
                        )
                    }
                }

                is RadarKey -> {
                    val event = sessionState.currentEvent
                    if (event == null || event.id != key.eventId) {
                        androidx.compose.runtime.LaunchedEffect(key) {
                            backStack.clear()
                            backStack.add(HomeKey)
                        }
                    } else RadarScreen(
                        eventId = key.eventId,
                        isVisible = sessionState.isVisible,
                        discoveryRepository = discoveryRepository,
                        isHost = sessionState.isHost,
                        trackHost = sessionState.currentEvent?.trackHost == true,
                        coordinationError = syncStatus.errorMessage,
                        onVisibilityToggle = { sessionManager.setVisibility(it) },
                        onEventSettings = { backStack.add(EventSettingsKey(key.eventId)) },
                        onLeaveEvent = {
                            sessionManager.leaveEvent()
                            backStack.clear()
                            backStack.add(HomeKey)
                        },
                        onEndEvent = {
                            discoveryRepository.announceEventClosure(event)
                            sessionManager.endHostedEvent()
                            backStack.clear()
                            backStack.add(HomeKey)
                        }
                    )
                }
            }
        }
    }
}
