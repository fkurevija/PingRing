package com.fkurevija.pingring.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object EventJoinKey : NavKey

@Serializable
data object HomeKey : NavKey

@Serializable
data class HostSetupKey(val eventCode: String) : NavKey

@Serializable
data object AppSettingsKey : NavKey

@Serializable
data class EventSettingsKey(val eventId: String) : NavKey

@Serializable
data class RadarKey(val eventId: String) : NavKey
