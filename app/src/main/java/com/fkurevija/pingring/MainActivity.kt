package com.fkurevija.pingring

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
import com.fkurevija.pingring.BuildConfig
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.ForegroundPresenceTracker
import com.fkurevija.pingring.data.LanDebugConnectionsManager
import com.fkurevija.pingring.data.NearbyConnectionsManager
import com.fkurevija.pingring.data.NearbyTransport
import com.fkurevija.pingring.data.PingRingRuntime
import com.fkurevija.pingring.data.PingRingService
import com.fkurevija.pingring.data.SessionManager
import com.fkurevija.pingring.data.settings.AppearanceSettingsRepository
import com.fkurevija.pingring.data.settings.ThemeMode
import com.fkurevija.pingring.ui.navigation.MainNavigation
import com.fkurevija.pingring.ui.permissions.PermissionWrapper
import com.fkurevija.pingring.ui.theme.PingRingTheme

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
