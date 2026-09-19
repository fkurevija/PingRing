package com.fkurevija.pingring.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object EventJoinKey : NavKey

@Serializable
data class RadarKey(val eventId: String) : NavKey
