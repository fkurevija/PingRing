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

/**
 * A member's request to hold one chat bubble colour, and the host's answer.
 *
 * A guest asking for a colour rather than announcing one, because uniqueness has
 * to be decided by somebody. Two members choosing at the same moment would
 * otherwise both be told yes and both render the same colour - which is the
 * confusion the claim exists to prevent, arrived at by the exact route it was
 * meant to avoid.
 *
 * The host is the only member that can say yes, so a guest is told to wait for
 * the answer rather than assuming its pick was taken. [granted] false is a
 * refusal and is worth distinguishing from silence: a member who is told no can
 * pick something else, while one that never hears back is left with a colour it
 * does not have.
 */
@Serializable
data class ChatColourClaimPayloadDto(
    val eventCode: String,
    val peerId: String,
    /** The colour being claimed. Absent in the host's reply. */
    val colorArgb: Long? = null,
    /**
     * The host's answer: true grants it, false refuses it, null means this is a
     * request rather than a reply.
     */
    val granted: Boolean? = null,
    /**
     * When the claim was made, host-clock millis.
     *
     * Carried so the host can break a tie between two guests wanting the same
     * colour with a rule both can check: the earlier claim wins. Without it the
     * loser would have to simply be told, and would have no way to tell whether
     * the answer was about its own race or something else.
     */
    val requestedAtMillis: Long = 0L,
)

/**
 * Who holds which chat bubble colour, and whether a new claim can be granted.
 *
 * Host-authoritative, and that is the only arrangement that actually delivers
 * what the setting promises. Best-effort sharing would leave a window in which
 * two members believe they hold the same colour, and the user is relying on the
 * colour to tell people apart in a crowded transcript - so a colour that is
 * sometimes shared is worse than one that is occasionally refused.
 *
 * The host already arbitrates admission, holds the authoritative member list and
 * is the only member every other member is authenticated to, so it is the only
 * party whose answer all of them can believe.
 *
 * The other half is *releasing*. A claim is not permanent: switching colour hands
 * the old one back, because a colour nobody is using is not taken. Without that,
 * a member who changed their mind once would hold two colours for the rest of
 * the ring - or, worse, have their old colour permanently greyed out for
 * everybody else while they no longer render in it.
 *
 * Pure and Android-free so the rules are directly testable.
 */
object ChatColourPolicy {

    /**
     * Applies a granted claim, releasing whatever the member held before.
     *
     * The whole state transition in one function, because the two halves have to
     * happen together: releasing on its own would let a member briefly hold
     * nothing, and claiming on its own is the bug above. [evaluateClaim] decides
     * whether to call this at all; it is only ever called on a GRANTED decision.
     *
     * Returns a new map rather than mutating, so a host applying a claim cannot
     * half-update the state it is broadcasting.
     */
    fun applyGrantedClaim(
        currentHolders: Map<Long, String>,
        claimColorArgb: Long,
        claimPeerId: String,
    ): Map<Long, String> {
        // Drop this member's previous colour first. Without this the old one
        // stays held by somebody who is no longer rendering in it, and stays
        // unavailable to everyone else for the rest of the ring.
        val released = currentHolders.filterValues { it != claimPeerId }
        return released + (claimColorArgb to claimPeerId)
    }

    /**
     * What the host does with a claim for [colorArgb].
     *
     * Granting a colour somebody already holds is refused, not honoured, because
     * the point is that a colour identifies one person. The exception is a member
     * re-claiming the colour it already holds: that is not a conflict, and
     * refusing it would make the setting impossible to re-open and confirm.
     */
    fun evaluateClaim(
        claimColorArgb: Long,
        claimPeerId: String,
        currentHolders: Map<Long, String>,
        localPeerId: String,
    ): Decision {
        // A claim from ourselves is not a claim: the host is the authority, and
        // treating its own pick as a request would deadlock it into waiting for
        // permission from itself.
        if (claimPeerId == localPeerId) return Decision.GRANTED
        val holder = currentHolders[claimColorArgb]
        // Already ours, or unheld. Either way there is nothing to decide.
        if (holder == null || holder == claimPeerId) return Decision.GRANTED
        return Decision.REFUSED
    }

    /**
     * A member leaving the ring releases whatever colour it held.
     *
     * Otherwise a departed member keeps a colour reserved for the rest of the
     * event, and with eight members and twelve colours that is four colours gone
     * for good. The member list is authoritative for who is still here, so the
     * release is driven from that rather than from an explicit goodbye.
     */
    fun releaseDepartedMembers(
        currentHolders: Map<Long, String>,
        remainingPeerIds: Set<String>,
    ): Map<Long, String> = currentHolders.filterValues { it in remainingPeerIds }

    /**
     * Whether the host should grant [claimColorArgb], resolving a race between
     * two guests that asked for the same colour at the same moment.
     *
     * The earlier claim wins, by [requestedAtMillis] and then by peer id so that
     * two claims stamped identically still produce one answer rather than two.
     * The tie-break on peer id is arbitrary but it has to be *total* and it has
     * to be the same on every device, or the loser would be told it had won by
     * one host and lost by another.
     */
    fun shouldGrantAmongCompetingClaims(
        claimColorArgb: Long,
        claimPeerId: String,
        requestedAtMillis: Long,
        competingClaims: List<Pair<String, Long>>,
    ): Boolean {
        val winner = competingClaims.minWithOrNull(
            compareBy({ it.second }, { it.first })
        ) ?: return true
        val winnerIsUs = winner.first == claimPeerId && winner.second == requestedAtMillis
        return winnerIsUs
    }

    /**
     * What the host does with a claim.
     *
     * DISTINCT so the caller can treat a refusal visibly: a member told no can
     * choose again, which a member that never heard back cannot.
     */
    enum class Decision {
        GRANTED,
        REFUSED,
    }

    /**
     * The colours a member may still pick: the presets nobody else holds.
     *
     * Returned in palette order so the picker keeps a stable layout, and always
     * including the member's own colour - it is theirs, and greyed out it would
     * read as unavailable and leave them with no way to see their choice.
     */
    fun availableColors(
        localPeerId: String,
        currentHolders: Map<Long, String>,
        presets: List<Long>,
    ): List<Long> = presets.filter { color ->
        val holder = currentHolders[color]
        holder == null || holder == localPeerId
    }

    /**
     * Who holds [colorArgb], for labelling a swatch as taken.
     *
     * Null when it is free. The name is passed in rather than looked up so this
     * stays pure; the caller has the member list and the repository does not.
     */
    fun holderOf(colorArgb: Long, currentHolders: Map<Long, String>): String? =
        currentHolders[colorArgb]
}

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
