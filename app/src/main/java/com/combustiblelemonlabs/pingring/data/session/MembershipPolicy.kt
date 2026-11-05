package com.combustiblelemonlabs.pingring.data.session
/**
 * When a member stops being a member, as distinct from when they stop being
 * reachable.
 *
 * The bug this exists to prevent: losing transport contact was treated as
 * leaving. Changing the tracked member re-points the single UWB session, which
 * closes and reopens handshakes, and the peer that was being tracked lost its
 * link first - so `removePeer` stripped it from `sessionManager`'s friends. Each
 * device dropped whichever member *it* had been tracking, so two phones in one
 * ring each showed a single member: themselves. Both were still counted in the
 * ring, so the member list and the ring disagreed, with nothing on screen
 * explaining why.
 *
 * The rule is the obvious one once stated: contact is about reachability,
 * membership is about intent. Somebody who walks into another room has not left
 * a ring they were invited to, and the app must not say they have - it cannot
 * tell that apart from leaving, and guessing "left" is both wrong and alarming.
 *
 * So membership changes only on something that means it: an authenticated
 * graceful departure, or the host ending the ring. Everything else - a dropped
 * transport, an expired advert, a lapsed UWB retention window - stops the radar
 * drawing a pin and leaves the person in the list, showing as unreachable.
 *
 * Pure and Android-free so the rule is directly testable.
 */
object MembershipPolicy {

    /** Why a peer is no longer being drawn, which is not the same as having left. */
    enum class ContactLoss {
        /** The transport dropped and nothing else is keeping them present. */
        TRANSPORT_DROPPED,

        /** Nothing has been heard from them for long enough to be considered gone. */
        STALE,
    }

    /**
     * Whether a peer who has lost contact should also leave the member list.
     *
     * Always false. Stated as a function rather than inlined so the rule has one
     * place to be asserted, and so the reason survives a later edit that assumes
     * the two are the same thing - which is exactly the assumption that caused
     * this.
     */
    fun leavesRingOnContactLoss(loss: ContactLoss): Boolean = false

    /**
     * Whether an authenticated graceful departure removes a member.
     *
     * True, and the only loss that does. The departure is bound to the endpoint
     * that sent it and to the ring it names, so unlike a dropped connection it
     * is a statement rather than an observation.
     */
    fun leavesRingOnGracefulDeparture(
        departureEventCode: String,
        currentEventCode: String?,
        departurePeerId: String,
        localPeerId: String,
    ): Boolean =
        departureEventCode == currentEventCode &&
            departurePeerId != localPeerId
}