package com.fkurevija.pingring.data

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

const val MAX_PARTICIPANTS = 8

@Serializable
data class InviteSecretPayloadDto(
    val eventCode: String,
    val inviteSecret: String,
    val hostPeerId: String,
)

@Serializable
data class JoinProofPayloadDto(
    val eventCode: String,
    val inviteSecret: String?,
)

@Serializable
data class EventControlPayloadDto(
    val eventCode: String,
    val hostPeerId: String,
    val trackHost: Boolean,
    val useUwb: Boolean,
    val useWifiDirect: Boolean,
    val acceptsNewMembers: Boolean,
    val isFull: Boolean,
    val isClosed: Boolean = false,
)

object InviteSecret {
    private const val SECRET_BYTES = 32
    private val secureRandom = SecureRandom()

    fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(SECRET_BYTES).also(secureRandom::nextBytes)
    )

    fun decode(value: String): ByteArray? {
        if (value.length != 43) return null
        return runCatching {
            Base64.getUrlDecoder().decode(value).takeIf { it.size == SECRET_BYTES }
        }.getOrNull()
    }
}

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
    /**
     * Whether this event advertises itself as one a nearby guest may find and
     * join without being given a code.
     *
     * Distinct from [acceptsNewMembers], which is about the *host* saying yes.
     * This is about the session being findable at all: a host that does not set
     * it is still perfectly joinable by code, it simply does not appear in a
     * guest's list of nearby sessions. Off by default, because a session that
     * shows up unasked is a session somebody can wander into.
     */
    val isDiscoverable: Boolean = false,
    /**
     * Whether a device asking to join is held for the host to approve rather
     * than admitted on arrival.
     *
     * Off by default: taking a join away by default would silently stop people
     * joining events they could previously join, and a host who wants to vet
     * arrivals has to ask for it. Distinct from [acceptsNewMembers], which is
     * the standing yes/no; this is about whether a human sees each request.
     */
    val requiresApproval: Boolean = false,
    val chatNotificationsEnabled: Boolean = true,
    val inviteSecret: String? = null,
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
            inviteSecret: String? = null,
        ): Event = Event(
            id = id,
            name = "PingRing $id",
            description = "PingRing Event",
            expiryTimestamp = System.currentTimeMillis() + DURATION_MILLIS,
            hostPeerId = hostPeerId,
            useUwb = useUwb,
            inviteSecret = inviteSecret,
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
    val timestamp: Long,
    /** The sender's chosen bubble colour, or null for the legacy themed style. */
    val colorArgb: Long? = null,
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
    val userName: String = "",
    /**
     * When this device joined the current event, in epoch millis.
     *
     * Set once, on join or host, and advertised from then on. It is the fact
     * "first to join" is decided on - without it the host can only fall back to
     * discovery order, which answers a different question (who it happened to
     * hear first). Cleared with the event, so a new event starts from a fresh
     * arrival time rather than inheriting the last one's.
     */
    val joinedAtMillis: Long? = null
)
