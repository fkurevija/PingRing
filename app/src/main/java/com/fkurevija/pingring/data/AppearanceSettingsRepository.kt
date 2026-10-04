package com.fkurevija.pingring.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fkurevija.pingring.DeviceEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
 * The default is a minute rather than the five to ten seconds that would fail
 * fastest. A short window does not make a failed join fail sooner in any way a
 * user benefits from - it makes joins that *would* have succeeded give up while
 * the host was still reading the request. It is also a narrower window for a
 * nearby stranger to answer in place of the intended host, which is the one
 * thing a join timeout should not make easier.
 *
 * Adjustable because the right value depends on the ring: a host watching the
 * screen needs far less than one busy on another call, and the cost of guessing
 * wrong is a join that fails for no visible reason.
 *
 * The floor is not arbitrary. The guest's wait has to outlast the host's approval
 * window plus a margin, or the guest gives up first and reports a host's refusal
 * as a join failure - the host said no, and the guest would say "couldn't reach
 * anybody". That invariant is why the minimum is 60s and not the 30s a slider
 * might otherwise offer: [JOIN_REQUEST_TIMEOUT_MILLIS] plus
 * [JOIN_APPROVAL_GRACE_MILLIS] is exactly 60s. Lowering the floor below that would
 * reintroduce the race, so the range is derived from those two rather than picked.
 */
const val DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS = 60_000L
const val MIN_JOIN_WAIT_TIMEOUT_MILLIS = 60_000L
const val MAX_JOIN_WAIT_TIMEOUT_MILLIS = 180_000L

internal fun normalizeJoinWaitTimeoutMillis(timeoutMillis: Long): Long = timeoutMillis.coerceIn(
    MIN_JOIN_WAIT_TIMEOUT_MILLIS,
    MAX_JOIN_WAIT_TIMEOUT_MILLIS,
)

/**
 * The margin between the host giving up and the guest doing so.
 *
 * Covers the host's refusal travelling back to the guest. Long enough that the
 * guest is not still waiting when the answer arrives; short enough that the guest
 * is not left sitting on a decision the host has already abandoned.
 */
const val JOIN_APPROVAL_GRACE_MILLIS = 15_000L

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")
private val themeModeKey = stringPreferencesKey("theme_mode")
private val userNameKey = stringPreferencesKey("user_name")
private val radarScreenReaderSummaryEnabledKey = booleanPreferencesKey("radar_screen_reader_summary_enabled")
private val reduceMotionEnabledKey = booleanPreferencesKey("reduce_motion_enabled")
private val proximityHapticsEnabledKey = booleanPreferencesKey("proximity_haptics_enabled")
private val spokenRadarUpdatesEnabledKey = booleanPreferencesKey("spoken_radar_updates_enabled")
private val chatNotificationsEnabledKey = booleanPreferencesKey("chat_notifications_enabled")
private val connectionRecoveryPromptEnabledKey = booleanPreferencesKey("connection_recovery_prompt_enabled")
private val connectionRecoveryPromptDelayKey = longPreferencesKey("connection_recovery_prompt_delay_millis")
private val joinWaitTimeoutMillisKey = longPreferencesKey("join_wait_timeout_millis")
private val notificationSoundEnabledKey = booleanPreferencesKey("notification_sound_enabled")
private val notificationVibrationEnabledKey = booleanPreferencesKey("notification_vibration_enabled")
private val lanDebugEnabledKey = booleanPreferencesKey("lan_debug_enabled")
private val lanDebugHostKey = stringPreferencesKey("lan_debug_host")

class AppearanceSettingsRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    val reduceMotionEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[reduceMotionEnabledKey] ?: AccessibilityFeature.REDUCE_MOTION.defaultEnabled
    }

    val proximityHapticsEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[proximityHapticsEnabledKey] ?: AccessibilityFeature.PROXIMITY_HAPTICS.defaultEnabled
    }

    val spokenRadarUpdatesEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[spokenRadarUpdatesEnabledKey] ?: AccessibilityFeature.SPOKEN_RADAR_UPDATES.defaultEnabled
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

    fun setChatNotificationsEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[chatNotificationsEnabledKey] = enabled
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

    fun setNotificationVibrationEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[notificationVibrationEnabledKey] = enabled
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

