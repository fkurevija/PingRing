package com.fkurevija.pingring.data

import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val id: String,
    val name: String,
    val description: String,
    val expiryTimestamp: Long
)

@Serializable
data class Friend(
    val peerId: String,
    val name: String,
    val lastSeen: Long,
    val distance: Double? = null
)

@Serializable
data class SessionState(
    val currentEvent: Event? = null,
    val isVisible: Boolean = false,
    val friends: List<Friend> = emptyList()
)
