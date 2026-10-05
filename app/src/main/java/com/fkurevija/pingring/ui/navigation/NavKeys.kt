package com.fkurevija.pingring.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object PingRingJoinKey : NavKey

@Serializable
data object HomeKey : NavKey

@Serializable
data class HostSetupKey(val eventCode: String) : NavKey

@Serializable
data object AppSettingsKey : NavKey

@Serializable
data object DeveloperSettingsKey : NavKey

/**
 * The About screen: build version and the device's capabilities.
 *
 * A key like every other destination rather than a flag on AppSettings, so the
 * back stack behaves the way it does for Accessibility and Developer options.
 */
data object AboutKey : NavKey

@Serializable
data object AccessibilitySettingsKey : NavKey

@Serializable
data class PingRingSettingsKey(val eventId: String) : NavKey

@Serializable
data class RadarKey(val eventId: String) : NavKey
