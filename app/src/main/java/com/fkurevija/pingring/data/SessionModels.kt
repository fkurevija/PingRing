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
data class BleIdentityKeyPayloadDto(
    val eventCode: String,
    val peerId: String,
    val publicKey: String,
)

@Serializable
data class MemberDeparturePayloadDto(
    val eventCode: String,
    val peerId: String,
)

@Serializable
data class MeshPresencePayloadDto(
    val eventCode: String,
    val peerId: String,
)

@Serializable
data class MeshLinkAuthenticationPayloadDto(
    val eventCode: String,
    val challengerPeerId: String,
    val responderPeerId: String,
    val challenge: String,
    val signature: String? = null,
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
    val useUwb: Boolean,
    val useWifiDirect: Boolean,
    val acceptsNewMembers: Boolean,
    val isFull: Boolean,
    val isClosed: Boolean = false,
    val useGnss: Boolean = false,
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
    val useUwb: Boolean = true,
    /**
     * Whether members broadcast their own GNSS fixes to the ring.
     *
     * Always true. It used to be a host-controlled opt-in, which turned out to
     * be the wrong shape twice over: a guest could not see whether coordinates
     * were live, so two phones could silently disagree about whether the
     * feature was on, and a ring where only some devices were publishing could
     * show a distance estimate that was quietly missing half its sources. The
     * receiver never draws a range it cannot attribute anyway - see
     * [GnssPolicy] - so publishing costs one 10-second fix that is already being
     * taken for the radar's own sake.
     *
     * The field is kept rather than deleted so the value still travels on the
     * wire in one place; it is not written by any screen.
     */
    val useGnss: Boolean = true,
    val useWifiDirect: Boolean = true,
    val useHotspot: Boolean = false,
    /**
     * Whether this PingRing is open to new members at all.
     *
     * This is the host's only remaining admission control, and it is a single
     * ring-wide yes/no rather than a per-join decision. Per-join approval was
     * removed on purpose: with the QR invitation generated only on the host
     * device, a scan is already the host having offered the ring, so holding
     * those joins for approval meant the host could not hand out a QR code
     * without also having to watch the approval queue for the rest of the
     * event. What is left is "is the ring open", which is the question a host
     * actually asks when they close the ring to new arrivals.
     */
    val acceptsNewMembers: Boolean = true,
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
