package com.fkurevija.pingring.data

/**
 * Which single member the radar tracks, and when a UWB ranging session should
 * follow that choice.
 *
 * With up to eight members on screen at once the radar is hard to read, and
 * the UWB radio cannot usefully range everyone at once either. Both problems
 * have the same answer: let the user pick one person to track, and spend the
 * UWB session on exactly that person. Everyone still *receives* every message;
 * this only decides what the radar draws and what gets precisely measured.
 *
 * There is no host-owned "track the host" policy. It used to exist, and it made
 * the host's radar a control surface over every guest's: a guest could see the
 * member picker and be unable to use it, with nothing to explain why. Choosing
 * who to follow is now always the local user's own decision, and the defaults
 * below are only defaults - one tap overrides them.
 *
 * Kept free of Android and Compose types so the rules are directly testable.
 */
object FocusPolicy {

    /**
     * Resolves the member the radar displays and the UWB radio ranges.
     *
     * Rules, in order:
     *  - A selection the user made wins, as long as that peer is still present.
     *    Ephemeral ids rotate, so a stale selection has to fall back rather
     *    than leaving the radar pointed at nobody.
     *  - Otherwise a guest falls back to the host, and the host to the member
     *    that has been in the ring longest.
     *  - Null is only possible when no other member is available. The repository
     *    publishes the resolved default as the selection when a member arrives.
     *
     * The fallbacks are role-based rather than distance-based on purpose. A
     * guest's job in a ring is to follow the host and a host's is to watch
     * whoever arrived first - neither is "whoever happens to be closest right
     * now", which changes underneath the user as people move. It also means the
     * two roles get different answers without either one being configured: the
     * asymmetry falls out of the role, not out of a switch somebody set.
     *
     * [peerJoinedAt] maps peer id to when that peer reported joining, and is
     * what makes "arrived first" mean what it says. It used to be
     * [availablePeerIds] order, which is discovery order, and that is a
     * different thing: a member who joins late and happens to be nearest is
     * heard first, so the host would default to whoever wandered in last.
     *
     * Peers missing from the map - one seen only over the debug transport, or
     * one whose advert predates the field - sort *after* every peer that has
     * said when it arrived, rather than tying at some arbitrary point. Among
     * those, [availablePeerIds] order breaks the tie so the answer is stable
     * rather than dependent on map iteration.
     */
    fun resolveFocus(
        selectedPeerId: String?,
        availablePeerIds: List<String>,
        peerJoinedAt: Map<String, Long?> = emptyMap(),
        isHost: Boolean,
        localPeerId: String,
        hostPeerId: String?,
    ): String? {
        val candidates = availablePeerIds.filter { it != localPeerId }
        if (candidates.isEmpty()) return null

        if (selectedPeerId != null && selectedPeerId in candidates) {
            return selectedPeerId
        }
        // A guest tracks the host when it can. The host is not one of its own
        // radar peers - discovery filters the others out - so this only fires
        // when both happen to be visible.
        if (!isHost && hostPeerId != null && hostPeerId in candidates) {
            return hostPeerId
        }
        return earliestJoiner(candidates, peerJoinedAt)
    }

    /**
     * The candidate that reported joining first, or the first candidate when
     * none did.
     *
     * Sorted stably by timestamp with unknown last, so the result does not
     * depend on the order of the incoming map. Kotlin's [sortedWith] is stable,
     * which is what lets peers that tie - two that never said when they arrived
     * - keep [candidates] order instead of being reordered arbitrarily. No
     * secondary key is given on purpose: adding one, such as peer id, would
     * sort ties alphabetically and quietly turn discovery order back into
     * something else.
     */
    private fun earliestJoiner(
        candidates: List<String>,
        peerJoinedAt: Map<String, Long?>,
    ): String = candidates.sortedWith(
        // A missing or zero timestamp sorts as unknown, and unknown loses.
        compareBy { peerJoinedAt[it]?.takeIf { millis -> millis > 0L } ?: Long.MAX_VALUE }
    ).first()

    /**
     * The single peer this device should hold a UWB session with, if any.
     *
     * Returning null means "do not range anyone right now", which is the
     * correct answer whenever the choice is ambiguous. One session at a time is
     * deliberate: it is what the platform is designed for, and it is the
     * reason a filter also makes ranging more reliable rather than just less
     * cluttered. [RangingTarget] names the host explicitly so a guest ranging
     * the host is not confused with a guest ranging another guest.
     */
    fun rangingTarget(
        focusPeerId: String?,
        hostPeerId: String?,
        connectedPeerIds: Set<String>,
    ): RangingTarget {
        val focus = focusPeerId ?: return RangingTarget.None
        // Only range a peer that is actually connected. A BLE sighting with no
        // transport connection has no way to exchange the UWB handshake, and
        // opening a session for it would just fail and be retried forever.
        return if (focus == hostPeerId) {
            if (focus in connectedPeerIds) RangingTarget.Peer(focus, isHost = true)
            else RangingTarget.None
        } else {
            if (focus in connectedPeerIds) RangingTarget.Peer(focus, isHost = false)
            else RangingTarget.None
        }
    }

    /** Who to range, and whether that peer is the event host. */
    sealed interface RangingTarget {
        /** Nothing to range right now: no focus, or the peer is not connected. */
        data object None : RangingTarget
        data class Peer(val peerId: String, val isHost: Boolean) : RangingTarget
    }
}
