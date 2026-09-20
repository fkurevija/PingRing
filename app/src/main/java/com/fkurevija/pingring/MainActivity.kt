package com.fkurevija.pingring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.Event
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
            PingRingTheme {
                val sessionManager = remember { SessionManager() }
                val sessionState by sessionManager.sessionState.collectAsState()
                val context = LocalContext.current.applicationContext
                val discoveryRepository = remember { DiscoveryRepository(context, sessionManager) }

                PermissionWrapper {
                    MainNavigation(
                        sessionManager = sessionManager,
                        discoveryRepository = discoveryRepository,
                        isVisible = sessionState.isVisible
                    )
                }
            }
        }
    }
}

@Composable
fun MainNavigation(
    sessionManager: SessionManager,
    discoveryRepository: DiscoveryRepository,
    isVisible: Boolean
) {
    val backStack: NavBackStack<NavKey> = rememberNavBackStack(HomeKey)
    val sessionState by sessionManager.sessionState.collectAsState()

    NavDisplay(backStack) { key ->
        NavEntry(key) {
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
                            sessionManager.joinEvent(
                                Event(
                                    id = eventCode,
                                    name = "Event $eventCode",
                                    description = "PingRing Event",
                                    expiryTimestamp = System.currentTimeMillis() + 3600000
                                )
                            )
                            backStack.clear()
                            backStack.add(RadarKey(eventCode))
                        }
                    )
                }

                is AppSettingsKey -> {
                    AppSettingsScreen(onBack = { backStack.removeLastOrNull() })
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
                    RadarScreen(
                        eventId = key.eventId,
                        isVisible = isVisible,
                        discoveryRepository = discoveryRepository,
                        isHost = sessionState.isHost,
                        trackHost = sessionState.currentEvent?.trackHost == true,
                        onVisibilityToggle = { sessionManager.setVisibility(it) },
                        onEventSettings = { backStack.add(EventSettingsKey(key.eventId)) },
                        onLeaveEvent = {
                            sessionManager.leaveEvent()
                            backStack.clear()
                            backStack.add(HomeKey)
                        }
                    )
                }
            }
        }
    }
}
