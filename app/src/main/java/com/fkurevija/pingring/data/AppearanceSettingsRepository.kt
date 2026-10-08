package com.fkurevija.pingring.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")
private val themeModeKey = stringPreferencesKey("theme_mode")
private val userNameKey = stringPreferencesKey("user_name")
private val radarScreenReaderSummaryEnabledKey = booleanPreferencesKey("radar_screen_reader_summary_enabled")
private val reduceMotionEnabledKey = booleanPreferencesKey("reduce_motion_enabled")
private val proximityHapticsEnabledKey = booleanPreferencesKey("proximity_haptics_enabled")
private val spokenRadarUpdatesEnabledKey = booleanPreferencesKey("spoken_radar_updates_enabled")
private val visualFlashAlertsEnabledKey = booleanPreferencesKey("visual_flash_alerts_enabled")
private val chatNotificationsEnabledKey = booleanPreferencesKey("chat_notifications_enabled")
private val connectionRecoveryPromptEnabledKey = booleanPreferencesKey("connection_recovery_prompt_enabled")
private val connectionRecoveryPromptDelayKey = longPreferencesKey("connection_recovery_prompt_delay_millis")
private val joinWaitTimeoutMillisKey = longPreferencesKey("join_wait_timeout_millis")
private val notificationSoundEnabledKey = booleanPreferencesKey("notification_sound_enabled")
private val notificationVibrationEnabledKey = booleanPreferencesKey("notification_vibration_enabled")
private val nearbyDigitComparisonEnabledKey = booleanPreferencesKey("nearby_digit_comparison_enabled")
private val beaconAlertRepeatKey = stringPreferencesKey("beacon_alert_repeat")
private val beaconRepeatGapMillisKey = longPreferencesKey("beacon_repeat_gap_millis")
private val beaconRepeatDurationMillisKey = longPreferencesKey("beacon_repeat_duration_millis")
private val silencedBeaconIdsKey = stringSetPreferencesKey("silenced_beacon_ids")
private val lanDebugEnabledKey = booleanPreferencesKey("lan_debug_enabled")
private val lanDebugHostKey = stringPreferencesKey("lan_debug_host")
private val batterySaverEnabledKey = booleanPreferencesKey("battery_saver_enabled")
private val peerLinkDiagnosticsEnabledKey = booleanPreferencesKey("peer_link_diagnostics_enabled")
private val customBeaconTemplatesKey = stringSetPreferencesKey("custom_beacon_templates")
private val realTimePolicyVisualizationEnabledKey = booleanPreferencesKey("realtime_policy_visualization_enabled")
private val dataExportApiEnabledKey = booleanPreferencesKey("data_export_api_enabled")

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

    /**
     * Whether a beacon alert plays once or repeats, and how long the pause is.
     *
     * Per-device on purpose: it is not part of the beacon payload, because the
     * member raising a beacon has no business deciding how often somebody else's
     * phone buzzes. See [BeaconAlertRepeat].
     *
     * An unrecognised stored name falls back to ONCE rather than to REPEAT - if a
     * future build adds a mode this one does not know, the safe reading is the
     * quiet one.
     */
    val beaconAlertSettings: Flow<BeaconAlertSettings> =
        context.appearanceDataStore.data.map { preferences ->
            val repeat = BeaconAlertRepeat.entries
                .firstOrNull { it.name == preferences[beaconAlertRepeatKey] }
                ?: BeaconAlertRepeat.ONCE
            BeaconAlertSettings(
                repeat = repeat,
                repeatGapMillis = normalizeBeaconRepeatGapMillis(
                    preferences[beaconRepeatGapMillisKey] ?: DEFAULT_BEACON_REPEAT_GAP_MILLIS
                ),
                repeatDurationMillis = normalizeBeaconRepeatDurationMillis(
                    preferences[beaconRepeatDurationMillisKey] ?: DEFAULT_BEACON_REPEAT_DURATION_MILLIS
                ),
            )
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

    val customBeaconTemplates: Flow<Set<String>> = context.appearanceDataStore.data.map { preferences ->
        preferences[customBeaconTemplatesKey] ?: emptySet()
    }

    fun addCustomBeaconTemplate(template: String) {
        if (template.isBlank()) return
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                val existing = preferences[customBeaconTemplatesKey] ?: emptySet()
                preferences[customBeaconTemplatesKey] = existing + template.trim()
            }
        }
    }

    fun removeCustomBeaconTemplate(template: String) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                val existing = preferences[customBeaconTemplatesKey] ?: emptySet()
                preferences[customBeaconTemplatesKey] = existing - template
            }
        }
    }

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

    /**
     * Beacon ids this device has been told to stop alerting for.
     *
     * Ids, not senders. "Silence this beacon" is a statement about one alarm,
     * and the next thing Ana raises is a different alarm that may matter more.
     * Banning a sender instead would mean a member could silence a person, and
     * the natural reading of "this one is annoying" is not "that person is
     * annoying".
     */
    val silencedBeaconIds: Flow<Set<String>> = context.appearanceDataStore.data.map { preferences ->
        preferences[silencedBeaconIdsKey] ?: emptySet()
    }

    /**
     * Stop, or resume, alerting for one beacon.
     *
     * Additive on the way in and subtractive on the way out, so a write cannot
     * race away a decision made elsewhere on another thread.
     */
    fun setBeaconSilenced(beaconId: String, silenced: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                val current = preferences[silencedBeaconIdsKey] ?: emptySet()
                preferences[silencedBeaconIdsKey] =
                    if (silenced) current + beaconId else current - beaconId
            }
        }
    }

    /**
     * Save how this device alerts for beacons.
     *
     * Normalised on the way in as well as on the way out, so a value that reaches
     * the store is always one the policy can use.
     */
    fun setBeaconAlertSettings(settings: BeaconAlertSettings) {
        val normalized = settings.normalized()
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[beaconAlertRepeatKey] = normalized.repeat.name
                preferences[beaconRepeatGapMillisKey] = normalized.repeatGapMillis
                preferences[beaconRepeatDurationMillisKey] = normalized.repeatDurationMillis
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
