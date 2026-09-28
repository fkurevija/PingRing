package com.fkurevija.pingring.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    val chatNotificationsEnabled: Flow<Boolean> = context.appearanceDataStore.data.map { preferences ->
        preferences[chatNotificationsEnabledKey] ?: true
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
        preferences[lanDebugHostKey] ?: "10.0.2.2"
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
