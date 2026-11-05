package com.combustiblelemonlabs.pingring

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.combustiblelemonlabs.pingring.BuildConfig
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository
import com.combustiblelemonlabs.pingring.data.ForegroundPresenceTracker
import com.combustiblelemonlabs.pingring.data.LanDebugConnectionsManager
import com.combustiblelemonlabs.pingring.data.NearbyConnectionsManager
import com.combustiblelemonlabs.pingring.data.NearbyTransport
import com.combustiblelemonlabs.pingring.data.PingRingRuntime
import com.combustiblelemonlabs.pingring.data.PingRingService
import com.combustiblelemonlabs.pingring.data.nfc.NfcInviteExchange
import com.combustiblelemonlabs.pingring.data.session.SessionManager
import com.combustiblelemonlabs.pingring.data.settings.AppearanceSettingsRepository
import com.combustiblelemonlabs.pingring.data.settings.ThemeMode
import com.combustiblelemonlabs.pingring.ui.navigation.MainNavigation
import com.combustiblelemonlabs.pingring.ui.permissions.PermissionWrapper
import com.combustiblelemonlabs.pingring.ui.theme.PingRingTheme
import com.combustiblelemonlabs.pingring.data.session.Event
import com.combustiblelemonlabs.pingring.data.session.SessionState

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
                    LanDebugConnectionsManager(host = lanDebugHost, context = context)
                } else {
                    NearbyConnectionsManager(context)
                }
            }
            val discoveryRepository = remember(nearbyConnectionsManager) {
                DiscoveryRepository(context, sessionManager, nearbyConnectionsManager)
            }
            LaunchedEffect(discoveryRepository, appearanceSettings) {
                appearanceSettings.nearbyDigitComparisonEnabled.collect { enabled ->
                    discoveryRepository.setNearbyDigitComparisonEnabled(enabled)
                }
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
            val isHost = sessionState.isHost

            DisposableEffect(currentEvent?.id, currentEvent?.inviteSecret, isHost, lifecycleOwner) {
                val eventCode = currentEvent?.id
                val secret = currentEvent?.inviteSecret
                fun updateNfc() {
                    val isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (isHost && eventCode != null && secret != null && isResumed) {
                        NfcInviteExchange.activate(eventCode, secret)
                    } else {
                        if (eventCode != null && secret != null) {
                            NfcInviteExchange.deactivate("pingring://join?event=$eventCode&secret=$secret")
                        }
                    }
                }
                val observer = LifecycleEventObserver { _, _ -> updateNfc() }
                lifecycleOwner.lifecycle.addObserver(observer)
                updateNfc()
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    if (eventCode != null && secret != null) {
                        NfcInviteExchange.deactivate("pingring://join?event=$eventCode&secret=$secret")
                    }
                }
            }

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
