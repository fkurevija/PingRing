package com.combustiblelemonlabs.pingring.data.settings

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.combustiblelemonlabs.pingring.DeviceEnvironment
import com.combustiblelemonlabs.pingring.data.AccessibilityFeature
import com.combustiblelemonlabs.pingring.data.BeaconAlertSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.combustiblelemonlabs.pingring.data.LanDebugConnectionsManager

const val DEFAULT_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS = 120_000L
const val MIN_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS = 60_000L
const val MAX_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS = 600_000L

internal fun normalizeConnectionRecoveryPromptDelayMillis(delayMillis: Long): Long = delayMillis.coerceIn(
    MIN_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS,
    MAX_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS,
)

/**
 * How long a guest waits for a join to complete, before the app gives up on it.
 *
 * Two things have to fit inside this window. The first is a human being: a
 * code-only join is held by the host for approval, and that round trip is
 * unbounded - the host has to notice the request and tap Allow. The second is
 * the failure the timeout exists for, since a join to nobody sits in "connecting"
 * forever otherwise.
 *
 * Adjustable across a wide range, 10 s to 120 s, because the two ends are both
 * real choices. A host who is watching the screen answers in seconds and a short
 * wait is what they want; a host who is busy makes a short wait wrong, and the
 * member picks the longer one knowing their own host. Making the range wide
 * enough to hold both is better than picking a floor that is wrong for one of
 * them.
 *
 * The short end deliberately does NOT wait for a slow host. Below the host's own
 * approval window, a guest that gives up first is no longer racing the host's
 * refusal - it is giving up while the host is still deciding, and it reports
 * "could not reach anybody" for a join the host was about to allow. That is the
 * trade, and it is the member's to make: set it short for a host who is present,
 * and the failure it causes is a join that gives up early, which is exactly what
 * was asked for. [JOIN_APPROVAL_GRACE_MILLIS] still governs the host's own window
 * so that a *refusal* reaches the guest promptly rather than being inferred from
 * a dead socket.
 *
 * It is also a narrower window for a nearby stranger to answer in place of the
 * intended host, which is the one thing a join timeout should not make easier -
 * so the default is not the minimum.
 */
const val DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS = 60_000L
const val MIN_JOIN_WAIT_TIMEOUT_MILLIS = 10_000L
const val MAX_JOIN_WAIT_TIMEOUT_MILLIS = 120_000L

internal fun normalizeJoinWaitTimeoutMillis(timeoutMillis: Long): Long = timeoutMillis.coerceIn(
    MIN_JOIN_WAIT_TIMEOUT_MILLIS,
    MAX_JOIN_WAIT_TIMEOUT_MILLIS,
)

/**
 * The margin between the host giving up and the guest doing so.
 *
 * Covers the host's refusal travelling back to the guest, so that a host who says
 * no produces a prompt failure rather than one inferred from a dead socket. Kept
 * as a named constant because the host's sweep and the guest's wait are two
 * halves of one decision, and a literal in either would let them drift apart
 * silently.
 */
const val JOIN_APPROVAL_GRACE_MILLIS = 15_000L

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

internal val Context.appearanceDataStore by preferencesDataStore(name = "appearance")

class AppearanceSettingsRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val beaconSettings = BeaconSettingsRepository(context, scope)

    val themeMode: Flow<ThemeMode> = context.appearanceDataStore.data.map { preferences ->
        preferences[themeModeKey]
            ?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } }
            ?: ThemeMode.SYSTEM
    }

    val userName: Flow<String> = context.appearanceDataStore.data.map { preferences ->
        preferences[userNameKey] ?: ""
    }

    /** Null means that outgoing messages keep the current theme's bubble colour. */
    fun chatBubbleColor(eventId: String): Flow<Long?> = context.appearanceDataStore.data.map { preferences ->
        preferences[longPreferencesKey("chat_bubble_color_$eventId")]
    }

    val radarScreenReaderSummaryEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[radarScreenReaderSummaryEnabledKey]
            ?: AccessibilityFeature.RADAR_SCREEN_READER_SUMMARY.defaultEnabled
    }

    val chatNotificationsEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[chatNotificationsEnabledKey] ?: true
    }

    val readReceiptsEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[readReceiptsEnabledKey] ?: true
    }

    val reduceMotionEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[reduceMotionEnabledKey] ?: AccessibilityFeature.REDUCE_MOTION.defaultEnabled
    }

    val proximityHapticsEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[proximityHapticsEnabledKey] ?: AccessibilityFeature.PROXIMITY_HAPTICS.defaultEnabled
    }

    val spokenRadarUpdatesEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[spokenRadarUpdatesEnabledKey] ?: AccessibilityFeature.SPOKEN_RADAR_UPDATES.defaultEnabled
    }

    val visualFlashAlertsEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[visualFlashAlertsEnabledKey] ?: AccessibilityFeature.VISUAL_FLASH_ALERTS.defaultEnabled
    }

    val connectionRecoveryPromptEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[connectionRecoveryPromptEnabledKey] ?: true
    }

    val connectionRecoveryPromptDelayMillis: Flow<Long> = context.appearanceDataStore.data.map { preferences ->
        normalizeConnectionRecoveryPromptDelayMillis(
            preferences[connectionRecoveryPromptDelayKey] ?: DEFAULT_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS
        )
    }

    /**
     * Whether incoming chat notifications make a sound. Both this and
     * [notificationVibrationEnabled] default to on: an incoming message while
     * the app is closed should be as loud as the platform default, and the
     * user opts out rather than in.
     */
    val notificationSoundEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[notificationSoundEnabledKey] ?: true
    }

    val notificationVibrationEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[notificationVibrationEnabledKey] ?: true
    }

    val nearbyDigitComparisonEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[nearbyDigitComparisonEnabledKey] ?: false
    }

    val beaconAlertSettings: Flow<BeaconAlertSettings> get() = beaconSettings.alertSettings

    fun setThemeMode(mode: ThemeMode) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[themeModeKey] = mode.name
            }
        }
    }

    fun setUserName(name: String) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[userNameKey] = name
            }
        }
    }

    fun setChatBubbleColor(eventId: String, colorArgb: Long?) {
        val key = longPreferencesKey("chat_bubble_color_$eventId")
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                if (colorArgb == null) preferences.remove(key)
                else preferences[key] = colorArgb
            }
        }
    }

    fun setRadarScreenReaderSummaryEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[radarScreenReaderSummaryEnabledKey] = enabled
            }
        }
    }

    fun setReduceMotionEnabled(enabled: Boolean) {
        scope.launch { context.appearanceDataStore.edit { it[reduceMotionEnabledKey] = enabled } }
    }

    fun setProximityHapticsEnabled(enabled: Boolean) {
        scope.launch { context.appearanceDataStore.edit { it[proximityHapticsEnabledKey] = enabled } }
    }

    fun setSpokenRadarUpdatesEnabled(enabled: Boolean) {
        scope.launch { context.appearanceDataStore.edit { it[spokenRadarUpdatesEnabledKey] = enabled } }
    }

    fun setVisualFlashAlertsEnabled(enabled: Boolean) {
        scope.launch { context.appearanceDataStore.edit { it[visualFlashAlertsEnabledKey] = enabled } }
    }

    val batterySaverEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[batterySaverEnabledKey] ?: false
    }

    fun setBatterySaverEnabled(enabled: Boolean) {
        scope.launch { context.appearanceDataStore.edit { it[batterySaverEnabledKey] = enabled } }
    }

    val peerLinkDiagnosticsEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[peerLinkDiagnosticsEnabledKey] ?: false
    }

    fun setPeerLinkDiagnosticsEnabled(enabled: Boolean) {
        scope.launch { context.appearanceDataStore.edit { it[peerLinkDiagnosticsEnabledKey] = enabled } }
    }

    val customBeaconTemplates: Flow<Set<String>> get() = beaconSettings.customTemplates

    fun addCustomBeaconTemplate(template: String) = beaconSettings.addCustomTemplate(template)

    fun removeCustomBeaconTemplate(template: String) = beaconSettings.removeCustomTemplate(template)

    val realTimePolicyVisualizationEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[realTimePolicyVisualizationEnabledKey] ?: false
    }

    fun setRealTimePolicyVisualizationEnabled(enabled: Boolean) {
        scope.launch { context.appearanceDataStore.edit { it[realTimePolicyVisualizationEnabledKey] = enabled } }
    }

    val dataExportApiEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[dataExportApiEnabledKey] ?: false
    }

    fun setDataExportApiEnabled(enabled: Boolean) {
        scope.launch { context.appearanceDataStore.edit { it[dataExportApiEnabledKey] = enabled } }
    }

    fun setChatNotificationsEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[chatNotificationsEnabledKey] = enabled
            }
        }
    }

    fun setReadReceiptsEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[readReceiptsEnabledKey] = enabled
            }
        }
    }

    fun setConnectionRecoveryPromptEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[connectionRecoveryPromptEnabledKey] = enabled
            }
        }
    }

    fun setConnectionRecoveryPromptDelayMillis(delayMillis: Long) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[connectionRecoveryPromptDelayKey] =
                    normalizeConnectionRecoveryPromptDelayMillis(delayMillis)
            }
        }
    }

    val joinWaitTimeoutMillis: Flow<Long> = context.appearanceDataStore.data.map { preferences ->
        normalizeJoinWaitTimeoutMillis(
            preferences[joinWaitTimeoutMillisKey] ?: DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS
        )
    }

    fun setJoinWaitTimeoutMillis(timeoutMillis: Long) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[joinWaitTimeoutMillisKey] =
                    normalizeJoinWaitTimeoutMillis(timeoutMillis)
            }
        }
    }

    fun setNotificationSoundEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[notificationSoundEnabledKey] = enabled
            }
        }
    }

    val silencedBeaconIds: Flow<Set<String>> get() = beaconSettings.silencedBeaconIds

    fun setBeaconSilenced(beaconId: String, silenced: Boolean) =
        beaconSettings.setBeaconSilenced(beaconId, silenced)

    fun setBeaconAlertSettings(settings: BeaconAlertSettings) =
        beaconSettings.setAlertSettings(settings)

    fun setNotificationVibrationEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[notificationVibrationEnabledKey] = enabled
            }
        }
    }

    fun setNearbyDigitComparisonEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[nearbyDigitComparisonEnabledKey] = enabled
            }
        }
    }

    /**
     * Debug-only LAN transport settings, used to test chat/discovery between
     * the Android Emulator (which lacks real Bluetooth/Wi-Fi Direct radios)
     * and a physical device via a small local relay server. See
     * [LanDebugConnectionsManager] and tools/lan_debug_relay.py.
     */
    val lanDebugEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[lanDebugEnabledKey] ?: false
    }

    val lanDebugHost: Flow<String> = context.appearanceDataStore.data.map { preferences ->
        preferences[lanDebugHostKey] ?: if (DeviceEnvironment.isEmulator()) "10.0.2.2" else "127.0.0.1"
    }

    fun setLanDebugEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[lanDebugEnabledKey] = enabled
            }
        }
    }

    fun setLanDebugHost(host: String) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[lanDebugHostKey] = host
            }
        }
    }
}
