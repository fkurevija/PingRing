package com.combustiblelemonlabs.pingring.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.combustiblelemonlabs.pingring.data.BeaconAlertRepeat
import com.combustiblelemonlabs.pingring.data.BeaconAlertSettings
import com.combustiblelemonlabs.pingring.data.DEFAULT_BEACON_REPEAT_DURATION_MILLIS
import com.combustiblelemonlabs.pingring.data.DEFAULT_BEACON_REPEAT_GAP_MILLIS
import com.combustiblelemonlabs.pingring.data.normalizeBeaconRepeatDurationMillis
import com.combustiblelemonlabs.pingring.data.normalizeBeaconRepeatGapMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class BeaconSettingsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val alertSettings: Flow<BeaconAlertSettings> =
        context.appearanceDataStore.data.map { preferences ->
            val repeat = BeaconAlertRepeat.entries
                .firstOrNull { it.name == preferences[beaconAlertRepeatKey] }
                ?: BeaconAlertRepeat.ONCE
            BeaconAlertSettings(
                repeat = repeat,
                repeatGapMillis = normalizeBeaconRepeatGapMillis(
                    preferences[beaconRepeatGapMillisKey] ?: DEFAULT_BEACON_REPEAT_GAP_MILLIS,
                ),
                repeatDurationMillis = normalizeBeaconRepeatDurationMillis(
                    preferences[beaconRepeatDurationMillisKey] ?: DEFAULT_BEACON_REPEAT_DURATION_MILLIS,
                ),
            )
        }

    val silencedBeaconIds: Flow<Set<String>> =
        context.appearanceDataStore.data.map { preferences ->
            preferences[silencedBeaconIdsKey] ?: emptySet()
        }

    val customTemplates: Flow<Set<String>> =
        context.appearanceDataStore.data.map { preferences ->
            preferences[customBeaconTemplatesKey] ?: emptySet()
        }

    fun setBeaconSilenced(beaconId: String, silenced: Boolean) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                val current = preferences[silencedBeaconIdsKey] ?: emptySet()
                preferences[silencedBeaconIdsKey] =
                    if (silenced) current + beaconId else current - beaconId
            }
        }
    }

    fun setAlertSettings(settings: BeaconAlertSettings) {
        val normalized = settings.normalized()
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                preferences[beaconAlertRepeatKey] = normalized.repeat.name
                preferences[beaconRepeatGapMillisKey] = normalized.repeatGapMillis
                preferences[beaconRepeatDurationMillisKey] = normalized.repeatDurationMillis
            }
        }
    }

    fun addCustomTemplate(template: String) {
        if (template.isBlank()) return
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                val existing = preferences[customBeaconTemplatesKey] ?: emptySet()
                preferences[customBeaconTemplatesKey] = existing + template.trim()
            }
        }
    }

    fun removeCustomTemplate(template: String) {
        scope.launch {
            context.appearanceDataStore.edit { preferences ->
                val existing = preferences[customBeaconTemplatesKey] ?: emptySet()
                preferences[customBeaconTemplatesKey] = existing - template
            }
        }
    }
}
