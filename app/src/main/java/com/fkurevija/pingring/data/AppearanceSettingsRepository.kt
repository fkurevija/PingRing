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

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")
private val themeModeKey = stringPreferencesKey("theme_mode")
private val userNameKey = stringPreferencesKey("user_name")
private val chatNotificationsEnabledKey = booleanPreferencesKey("chat_notifications_enabled")
private val notificationSoundEnabledKey = booleanPreferencesKey("notification_sound_enabled")
private val notificationVibrationEnabledKey = booleanPreferencesKey("notification_vibration_enabled")
private val lanDebugEnabledKey = booleanPreferencesKey("lan_debug_enabled")
private val lanDebugHostKey = stringPreferencesKey("lan_debug_host")
private val favouriteBeaconTitleKey = stringPreferencesKey("favourite_beacon_title")
private val favouriteBeaconColorKey = longPreferencesKey("favourite_beacon_color")
private val favouriteBeaconPatternKey = stringPreferencesKey("favourite_beacon_pattern")

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

    val chatNotificationsEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[chatNotificationsEnabledKey] ?: true
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

    fun setChatNotificationsEnabled(enabled: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[chatNotificationsEnabledKey] = enabled
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
     * The beacon starred in the beacon screen, or null when none is saved.
     *
     * All three keys have to be there to count: a half-written favourite would
     * send a beacon with a colour nobody picked, which is worse than sending
     * nothing and opening the beacon screen instead.
     */
    val favouriteBeacon: Flow<FavouriteBeacon?> = context.appearanceDataStore.data.map { preferences ->
        val title = preferences[favouriteBeaconTitleKey]
        val color = preferences[favouriteBeaconColorKey]
        val pattern = preferences[favouriteBeaconPatternKey]
        if (title != null && title.isNotBlank() && color != null && pattern != null) {
            FavouriteBeacon(title, color, BeaconVibrationPattern.fromName(pattern))
        } else {
            null
        }
    }

    fun setFavouriteBeacon(favourite: FavouriteBeacon?) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                if (favourite == null) {
                    preferences.remove(favouriteBeaconTitleKey)
                    preferences.remove(favouriteBeaconColorKey)
                    preferences.remove(favouriteBeaconPatternKey)
                } else {
                    preferences[favouriteBeaconTitleKey] = favourite.title
                    preferences[favouriteBeaconColorKey] = favourite.colorArgb
                    preferences[favouriteBeaconPatternKey] = favourite.pattern.name
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

