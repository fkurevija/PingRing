package com.combustiblelemonlabs.pingring.data.ranging
import com.combustiblelemonlabs.pingring.data.session.Event
/**
 * Joining a Wi-Fi RTT ranging result to the PingRing member it describes.
 *
 * Production phone-to-phone ranging uses a RangingRequest targeting the
 * focused member's origin-signed Wi-Fi Aware MAC address. The result's MAC
 * must match that request. No access point, USD record, or result-order guess
 * is involved. The opaque-ID attribution helpers below are retained for
 * compatibility; production uses the reading validation here and the signed
 * address binding in WifiAwareRangingManager.
 *
 * That makes attribution a table lookup with a genuine possibility of failure,
 * and the whole design here follows from one rule: **a result that cannot be
 * attributed is discarded, never guessed.** The tempting fallback - hand an
 * unattributable distance to the member the app happens to be focused on, or
 * the nearest one - is what would make this feature dangerous rather than
 * merely absent. A member's position is safety-relevant; showing one person's
 * distance under another person's name is worse than showing nothing, and it
 * would be undetectable afterwards, because the number looks ordinary.
 *
 * Kept pure and Android-free for the same reason as [RangingSourcePolicy]: the
 * mapping is the part worth asserting, and it can be asserted exhaustively
 * here without two RTT-capable phones and without settling whether USD peer ids
 * ever appear on a real result at all.
 */
object WifiRttCorrelation {

    /**
     * A member as the ranging path knows it: the app's own id, and the USD peer
     * id it published, if service discovery has seen it.
     *
     * [usdPeerId] is null in the ordinary case, because most members never
     * publish a USD record - the P2P path is optional and currently unwired.
     * That is not a failure state here; it means the member is simply not
     * reachable by this source and stays on the rung below.
     */
    data class RangeablePeer(
        val peerId: String,
        val usdPeerId: String? = null,
    )

    /** What a ranging result turned out to be about. */
    sealed interface Attribution {
        /** The result names exactly one member. */
        data class Matched(val peerId: String) : Attribution

        /** Why the result could not be attached to a member. */
        data class Rejected(val reason: RejectionReason) : Attribution
    }

    /**
     * Why a result was not used.
     *
     * Separate cases rather than one "unusable" because they mean different
     * things to whoever is debugging: [NO_USD_PEER_ID] is the shape of the
     * platform API, [UNKNOWN_PEER] is a stranger on the same Wi-Fi, and
     * [AMBIGUOUS_BINDING] is a bug in ours.
     */
    enum class RejectionReason {
        /**
         * The result carried no USD peer id.
         *
         * The documented shape for a peer the platform discovered but could not
         * tie to a USD record - typically a router, or a phone that has never
         * published one. Expected, and not worth a warning.
         */
        NO_USD_PEER_ID,

        /**
         * The USD peer id belongs to nobody in this event.
         *
         * A device nearby that is ranging with us over the same channel but is
         * not a member. Discarding it is the whole point - it is exactly the
         * case where guessing would put a stranger's distance on the radar.
         */
        UNKNOWN_PEER,

        /**
         * Two or more members claim the same USD peer id.
         *
         * Refused rather than resolved by picking one. This should be
         * impossible while peer ids are unique per session, so if it happens
         * something is wrong upstream and guessing would paper over it.
         */
        AMBIGUOUS_BINDING,
    }

    /**
     * Attributes one ranging result to a member, or explains why it cannot be.
     *
    * [resultUsdPeerId] is an optional opaque discovery ID. Null is handled as
    * its own rejection rather than
     * being allowed to match nothing, so the caller gets a reason it can log.
     *
     * Ordering is deliberate: null first, then membership, then ambiguity.
     * Checking ambiguity before membership would be wrong - a duplicate id
     * among *other* peers says nothing about whether this result is ours.
     */
    fun attribute(
        resultUsdPeerId: String?,
        peers: Collection<RangeablePeer>,
    ): Attribution {
        if (resultUsdPeerId == null) {
            return Attribution.Rejected(RejectionReason.NO_USD_PEER_ID)
        }

        val claimants = peers.filter { it.usdPeerId == resultUsdPeerId }
        if (claimants.isEmpty()) {
            return Attribution.Rejected(RejectionReason.UNKNOWN_PEER)
        }
        if (claimants.size > 1) {
            return Attribution.Rejected(RejectionReason.AMBIGUOUS_BINDING)
        }
        return Attribution.Matched(claimants.single().peerId)
    }

    /**
     * Whether ranging this peer is even worth attempting.
     *
     * Distinct from [attribute]: this is asked *before* a session is started, to
     * decide who to try, and the answer is about a member's own readiness
     * rather than about any particular result.
     *
     * A member with no USD record is not rangeable yet - and the reason it has
     * none is the open question in the roadmap, not something this function can
     * fix. It is reported rather than assumed away so a caller can tell "this"
     * member will never be rangeable on this build" apart from "this member is
     * not ready yet".
     */
    fun isRangeable(peer: RangeablePeer): Boolean = peer.usdPeerId != null

    /**
     * A ranging reading, checked before it is allowed near the radar.
     *
     * 802.11mc reports a distance and a standard deviation, and a result can
     * come back with no usable distance in it at all - the peer replied, so a
     * result object exists, while the measurement inside it is absent or
     * nonsensical. Passing those through would put `NaN` on the radar, or a
     * negative distance, and either is visibly broken in a way that erodes
     * trust in the readings that are fine.
     */
    data class Reading(
        val distanceMeters: Float?,
        val standardDeviationMeters: Float? = null,
    )

    /** Whether a reading is sound enough to display. */
    fun isUsable(reading: Reading): Boolean {
        val distance = reading.distanceMeters ?: return false
        if (!distance.isFinite() || distance <= 0f) return false
        if (distance > MAX_PLAUSIBLE_DISTANCE_METERS) return false
        // A negative or absurd spread means the exchange failed to decode
        // properly even though a distance came out of it.
        val deviation = reading.standardDeviationMeters ?: return true
        return deviation.isFinite() && deviation >= 0f && deviation <= MAX_PLAUSIBLE_DEVIATION_METERS
    }

    /**
     * Beyond this a result is not believed.
     *
     * Loose on purpose. RTT is accurate to roughly a metre indoors and this is
     * a sanity bound on a decode, not a claim about the radio's accuracy - the
     * point is only to reject numbers that cannot have come from a real round
     * trip. A tighter bound would throw away genuine long-range readings.
     */
    const val MAX_PLAUSIBLE_DISTANCE_METERS = 1_000f

    /**
     * Widest accepted spread, which is a failed round trip rather than a long
     * one. Far above anything a real measurement produces.
     */
    const val MAX_PLAUSIBLE_DEVIATION_METERS = 100f

    /** A sentence for each rejection, for logcat and the settings screen. */
    fun describe(reason: RejectionReason): String = when (reason) {
        RejectionReason.NO_USD_PEER_ID ->
            "Ranging result carried no Wi-Fi Direct service-discovery peer id."
        RejectionReason.UNKNOWN_PEER ->
            "Ranging result was from a device that is not a member of this event."
        RejectionReason.AMBIGUOUS_BINDING ->
            "Several members claim the same service-discovery peer id."
    }
}
