package com.combustiblelemonlabs.pingring.ui.navigation

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.combustiblelemonlabs.pingring.BuildConfig
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.BugReportPolicy
import com.combustiblelemonlabs.pingring.data.DiagnosticsPolicy
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository
import com.combustiblelemonlabs.pingring.data.discovery.ConnectivityStatus
import com.combustiblelemonlabs.pingring.data.settings.AppearanceSettingsRepository
import com.combustiblelemonlabs.pingring.data.settings.ThemeMode
import com.combustiblelemonlabs.pingring.ui.screens.AboutScreen
import com.combustiblelemonlabs.pingring.ui.screens.AccessibilitySettingsScreen
import com.combustiblelemonlabs.pingring.ui.screens.AppSettingsScreen
import com.combustiblelemonlabs.pingring.ui.screens.ConnectionSettingsScreen
import com.combustiblelemonlabs.pingring.ui.screens.DeveloperSettingsScreen
import com.combustiblelemonlabs.pingring.ui.screens.NotificationSettingsScreen
import com.combustiblelemonlabs.pingring.ui.screens.diagnosticLabelRes
import com.combustiblelemonlabs.pingring.ui.screens.rememberDiagnosticsFacts

@Composable
internal fun AppSettingsDestination(
    themeMode: ThemeMode,
    appearanceSettings: AppearanceSettingsRepository,
    backStack: NavBackStack<NavKey>,
) {
    AppSettingsScreen(
        selectedLanguageTag = AppCompatDelegate.getApplicationLocales().toLanguageTags(),
        onLanguageSelected = { languageTag ->
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        },
        themeMode = themeMode,
        onThemeModeChange = appearanceSettings::setThemeMode,
        onOpenNotifications = { backStack.add(NotificationSettingsKey) },
        onOpenConnection = { backStack.add(ConnectionSettingsKey) },
        onOpenAccessibility = { backStack.add(AccessibilitySettingsKey) },
        showDeveloperOptions = BuildConfig.DEBUG,
        onOpenDeveloperOptions = { backStack.add(DeveloperSettingsKey) },
        onOpenAbout = { backStack.add(AboutKey) },
        onBack = { backStack.popBack() }
    )
}

@Composable
internal fun NotificationSettingsDestination(
    appearanceSettings: AppearanceSettingsRepository,
    chatNotificationsEnabled: Boolean,
    notificationSoundEnabled: Boolean,
    notificationVibrationEnabled: Boolean,
    backStack: NavBackStack<NavKey>,
) {
    NotificationSettingsScreen(
        chatNotificationsEnabled = chatNotificationsEnabled,
        onChatNotificationsEnabledChange = appearanceSettings::setChatNotificationsEnabled,
        notificationSoundEnabled = notificationSoundEnabled,
        onNotificationSoundEnabledChange = appearanceSettings::setNotificationSoundEnabled,
        notificationVibrationEnabled = notificationVibrationEnabled,
        onNotificationVibrationEnabledChange = appearanceSettings::setNotificationVibrationEnabled,
        onBack = { backStack.popBack() },
    )
}

@Composable
internal fun ConnectionSettingsDestination(
    appearanceSettings: AppearanceSettingsRepository,
    nearbyDigitComparisonEnabled: Boolean,
    connectionRecoveryPromptEnabled: Boolean,
    connectionRecoveryPromptDelayMillis: Long,
    joinWaitTimeoutMillis: Long,
    peerLinkDiagnosticsEnabled: Boolean,
    batterySaverEnabled: Boolean,
    backStack: NavBackStack<NavKey>,
) {
    ConnectionSettingsScreen(
        nearbyDigitComparisonEnabled = nearbyDigitComparisonEnabled,
        onNearbyDigitComparisonEnabledChange = appearanceSettings::setNearbyDigitComparisonEnabled,
        connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
        connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
        onConnectionRecoveryPromptEnabledChange = appearanceSettings::setConnectionRecoveryPromptEnabled,
        onConnectionRecoveryPromptDelayChange = appearanceSettings::setConnectionRecoveryPromptDelayMillis,
        joinWaitTimeoutMillis = joinWaitTimeoutMillis,
        onJoinWaitTimeoutChange = appearanceSettings::setJoinWaitTimeoutMillis,
        peerLinkDiagnosticsEnabled = peerLinkDiagnosticsEnabled,
        onPeerLinkDiagnosticsEnabledChange = appearanceSettings::setPeerLinkDiagnosticsEnabled,
        batterySaverEnabled = batterySaverEnabled,
        onBatterySaverEnabledChange = appearanceSettings::setBatterySaverEnabled,
        onBack = { backStack.popBack() },
    )
}

@Composable
internal fun DeveloperSettingsDestination(
    appearanceSettings: AppearanceSettingsRepository,
    backStack: NavBackStack<NavKey>,
) {
    DeveloperSettingsScreen(
        appearanceSettings = appearanceSettings,
        onBack = { backStack.popBack() },
    )
}

@Composable
internal fun AboutDestination(
    discoveryRepository: DiscoveryRepository,
    connectivityStatus: ConnectivityStatus,
    reduceMotionEnabled: Boolean,
    backStack: NavBackStack<NavKey>,
) {
    val aboutContext = LocalContext.current.applicationContext
    val aboutFacts = rememberDiagnosticsFacts(
        isDebugBuild = BuildConfig.DEBUG,
        uwbSupported = discoveryRepository.isUwbSupported(),
        bluetoothEnabled = connectivityStatus.isBluetoothEnabled,
        wifiEnabled = connectivityStatus.isWifiEnabled,
    )
    AboutScreen(
        facts = aboutFacts,
        reduceMotionEnabled = reduceMotionEnabled,
        onBack = { backStack.popBack() },
        onReportAProblem = {
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

@Composable
internal fun AccessibilitySettingsDestination(
    appearanceSettings: AppearanceSettingsRepository,
    radarScreenReaderSummaryEnabled: Boolean,
    reduceMotionEnabled: Boolean,
    proximityHapticsEnabled: Boolean,
    spokenRadarUpdatesEnabled: Boolean,
    visualFlashAlertsEnabled: Boolean,
    backStack: NavBackStack<NavKey>,
) {
    AccessibilitySettingsScreen(
        radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
        onRadarScreenReaderSummaryChange = appearanceSettings::setRadarScreenReaderSummaryEnabled,
        reduceMotionEnabled = reduceMotionEnabled,
        onReduceMotionChange = appearanceSettings::setReduceMotionEnabled,
        proximityHapticsEnabled = proximityHapticsEnabled,
        onProximityHapticsChange = appearanceSettings::setProximityHapticsEnabled,
        spokenRadarUpdatesEnabled = spokenRadarUpdatesEnabled,
        onSpokenRadarUpdatesChange = appearanceSettings::setSpokenRadarUpdatesEnabled,
        visualFlashAlertsEnabled = visualFlashAlertsEnabled,
        onVisualFlashAlertsChange = appearanceSettings::setVisualFlashAlertsEnabled,
        onBack = { backStack.popBack() },
    )
}
