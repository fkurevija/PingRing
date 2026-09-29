package com.fkurevija.pingring.data

import kotlinx.serialization.Serializable

const val MAX_PARTICIPANTS = 8

@Serializable
data class Event(
    val id: String,
    val name: String,
    val description: String,
    val expiryTimestamp: Long,
    val hostPeerId: String? = null,
    val trackHost: Boolean = false,
    val useUwb: Boolean = true,
    val useWifiDirect: Boolean = true,
    val useHotspot: Boolean = false,
    val acceptsNewMembers: Boolean = true,
    val chatNotificationsEnabled: Boolean = true,
) {
    companion object {
        /**
         * How long an event is offered for, i.e. how long a member who has not
         * opened the app can still be told what it is.
         */
        const val DURATION_MILLIS = 3_600_000L

        /**
         * The event a member starts out with, whether they are hosting a new code
         * or joining someone else's.
         *
         * Hosting and joining only differ in who the host is and whether this
         * device can range; everything else - the name, the description, the
         * lifetime - has to match on both devices, because a guest that joined an
         * event whose copy does not describe the same ring is a bug nobody can see
         * from inside the app.
         */
        fun create(
            id: String,
            hostPeerId: String? = null,
            useUwb: Boolean = true,
        ): Event = Event(
            id = id,
            name = "PingRing $id",
            description = "PingRing Event",
            expiryTimestamp = System.currentTimeMillis() + DURATION_MILLIS,
            hostPeerId = hostPeerId,
            useUwb = useUwb,
        )
    }
}

@Serializable
data class Friend(
    val peerId: String,
    val name: String,
    val lastSeen: Long,
    val distance: Double? = null
)

@Serializable
data class ChatMessage(
    val id: String,
    val senderPeerId: String,
    val senderName: String,
    val messageText: String,
    val timestamp: Long
)

@Serializable
data class BeaconInfo(
    val id: String,
    val senderPeerId: String,
    val senderName: String,
    val title: String,
    val colorArgb: Long,
    val vibrationPattern: String,
    val isActive: Boolean,
    val timestamp: Long
)

@Serializable
data class SessionState(
    val currentEvent: Event? = null,
    val isHost: Boolean = false,
    val friends: List<Friend> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val activeBeacons: Map<String, BeaconInfo> = emptyMap(),
    val userName: String = ""
)
