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
private val notificationSoundEnabledKey = booleanPreferencesKey("notification_sound_enabled")
private val notificationVibrationEnabledKey = booleanPreferencesKey("notification_vibration_enabled")
private val lanDebugEnabledKey = booleanPreferencesKey("lan_debug_enabled")
private val lanDebugHostKey = stringPreferencesKey("lan_debug_host")
// Key names are part of the on-disk format, not the Kotlin vocabulary: they
// are what an installed app already wrote. The Kotlin identifiers around them
// were renamed to QuickBeacon when the UI label changed, but these strings
// were left alone deliberately - renaming them would orphan every existing
// user's saved Quick Beacon on upgrade, silently reverting them to "not set".
private val quickBeaconTitleKey = stringPreferencesKey("favourite_beacon_title")
private val quickBeaconColorKey = longPreferencesKey("favourite_beacon_color")
private val quickBeaconPatternKey = stringPreferencesKey("favourite_beacon_pattern")

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

    /**
     * The configuration the Quick Beacon button sends, or null when none is set.
     *
     * All three keys have to be there to count: a half-written Quick Beacon
     * would send a beacon with a colour nobody picked, which is worse than
     * sending nothing and opening the beacon screen instead.
     */
    val quickBeacon: Flow<QuickBeacon?> = context.appearanceDataStore.data.map { preferences ->
        val title = preferences[quickBeaconTitleKey]
        val color = preferences[quickBeaconColorKey]
        val pattern = preferences[quickBeaconPatternKey]
        if (title != null && title.isNotBlank() && color != null && pattern != null) {
            QuickBeacon(title, color, BeaconVibrationPattern.fromName(pattern))
        } else {
            null
        }
    }

    fun setQuickBeacon(quickBeacon: QuickBeacon?) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                if (quickBeacon == null) {
                    preferences.remove(quickBeaconTitleKey)
                    preferences.remove(quickBeaconColorKey)
                    preferences.remove(quickBeaconPatternKey)
                } else {
                    preferences[quickBeaconTitleKey] = quickBeacon.title
                    preferences[quickBeaconColorKey] = quickBeacon.colorArgb
                    preferences[quickBeaconPatternKey] = quickBeacon.pattern.name
                }
            }
        }
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

