package com.fkurevija.pingring.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

internal val themeModeKey = stringPreferencesKey("theme_mode")
internal val userNameKey = stringPreferencesKey("user_name")
internal val radarScreenReaderSummaryEnabledKey = booleanPreferencesKey("radar_screen_reader_summary_enabled")
internal val reduceMotionEnabledKey = booleanPreferencesKey("reduce_motion_enabled")
internal val proximityHapticsEnabledKey = booleanPreferencesKey("proximity_haptics_enabled")
internal val spokenRadarUpdatesEnabledKey = booleanPreferencesKey("spoken_radar_updates_enabled")
internal val visualFlashAlertsEnabledKey = booleanPreferencesKey("visual_flash_alerts_enabled")
internal val chatNotificationsEnabledKey = booleanPreferencesKey("chat_notifications_enabled")
internal val readReceiptsEnabledKey = booleanPreferencesKey("read_receipts_enabled")
internal val connectionRecoveryPromptEnabledKey = booleanPreferencesKey("connection_recovery_prompt_enabled")
internal val connectionRecoveryPromptDelayKey = longPreferencesKey("connection_recovery_prompt_delay_millis")
internal val joinWaitTimeoutMillisKey = longPreferencesKey("join_wait_timeout_millis")
internal val notificationSoundEnabledKey = booleanPreferencesKey("notification_sound_enabled")
internal val notificationVibrationEnabledKey = booleanPreferencesKey("notification_vibration_enabled")
internal val nearbyDigitComparisonEnabledKey = booleanPreferencesKey("nearby_digit_comparison_enabled")
internal val beaconAlertRepeatKey = stringPreferencesKey("beacon_alert_repeat")
internal val beaconRepeatGapMillisKey = longPreferencesKey("beacon_repeat_gap_millis")
internal val beaconRepeatDurationMillisKey = longPreferencesKey("beacon_repeat_duration_millis")
internal val silencedBeaconIdsKey = stringSetPreferencesKey("silenced_beacon_ids")
internal val lanDebugEnabledKey = booleanPreferencesKey("lan_debug_enabled")
internal val lanDebugHostKey = stringPreferencesKey("lan_debug_host")
internal val batterySaverEnabledKey = booleanPreferencesKey("battery_saver_enabled")
internal val peerLinkDiagnosticsEnabledKey = booleanPreferencesKey("peer_link_diagnostics_enabled")
internal val customBeaconTemplatesKey = stringSetPreferencesKey("custom_beacon_templates")
internal val realTimePolicyVisualizationEnabledKey = booleanPreferencesKey("realtime_policy_visualization_enabled")
internal val dataExportApiEnabledKey = booleanPreferencesKey("data_export_api_enabled")
