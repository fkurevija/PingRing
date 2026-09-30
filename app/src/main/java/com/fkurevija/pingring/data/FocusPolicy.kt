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
 * Kept free of Android and Compose types so the rules are directly testable.
 */
object FocusPolicy {

    /**
     * The member currently being tracked, or null for "auto", which means the
     * nearest peer. Null-as-auto rather than a sentinel id so it survives a
     * peer leaving and rejoining with a new ephemeral id.
     */
    const val AUTO = "auto"

    /**
     * Resolves the member the radar displays and the UWB radio ranges.
     *
     * Rules, in order:
     *  - When "track host" is on and this device is not the host, the host is
     *    the subject, because the host set that policy deliberately. That is
     *    not a default the user can change here - see [canUserSelect].
     *  - Otherwise a selection the user made wins, as long as that peer is
     *    still present. Ephemeral ids rotate, so a stale selection has to fall
     *    back rather than leaving the radar pointed at nobody.
     *  - Otherwise a guest falls back to the host, and the host to the member
     *    that has been in the ring longest.
     *
     * The fallbacks are role-based rather than distance-based on purpose. A
     * guest's job in a ring is to follow the host and a host's is to watch
     * whoever arrived first - neither is "whoever happens to be closest right
     * now", which changes underneath the user as people move.
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
        trackHost: Boolean,
        localPeerId: String,
        hostPeerId: String?,
    ): String? {
        if (shouldTrackHost(isHost, trackHost) && hostPeerId != null) {
            return hostPeerId
        }

        val candidates = availablePeerIds.filter { it != localPeerId }
        if (candidates.isEmpty()) return null

        if (selectedPeerId != null && selectedPeerId in candidates) {
            return selectedPeerId
        }
        // A guest tracks the host when it can. Under "track host" the host is
        // not one of its radar peers - discovery filters the others out - so
        // this only fires with the policy off and both visible.
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
     * Whether the local user may change what is being tracked.
     *
     * Only false for a guest under "track host", where the host owns the
     * decision. The host is never locked - the policy asks *members* to track
     * it, it does not constrain the host - and a guest picks freely once that
     * policy is off, which is the difference between "the host chose this" and
     * "this is the only thing you can see".
     */
    fun canUserSelect(isHost: Boolean, trackHost: Boolean): Boolean =
        !shouldTrackHost(isHost, trackHost)

    /**
     * How a guest should find the host, since the host is not one of its own
     * radar peers but is the member it tracks when the host has enabled
     * "track host".
     */
    fun shouldTrackHost(isHost: Boolean, trackHost: Boolean): Boolean = !isHost && trackHost

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
        isHost: Boolean,
        trackHost: Boolean,
        hostPeerId: String?,
        connectedPeerIds: Set<String>,
    ): RangingTarget {
        // A guest under "track host" ranges the host, not the radar's focused
        // member: the host is the one member it is allowed to see.
        if (shouldTrackHost(isHost, trackHost)) {
            val host = hostPeerId
            return if (host != null && host in connectedPeerIds) {
                RangingTarget.Peer(host, isHost = true)
            } else {
                RangingTarget.None
            }
        }

        val focus = focusPeerId ?: return RangingTarget.None
        // Only range a peer that is actually connected. A BLE sighting with no
        // transport connection has no way to exchange the UWB handshake, and
        // opening a session for it would just fail and be retried forever.
        if (focus == hostPeerId) {
            return if (focus in connectedPeerIds) RangingTarget.Peer(focus, isHost = true)
            else RangingTarget.None
        }
        return if (focus in connectedPeerIds) RangingTarget.Peer(focus, isHost = false)
        else RangingTarget.None
    }

    /** Who to range, and whether that peer is the event host. */
    sealed interface RangingTarget {
        /** Nothing to range right now: no focus, or the peer is not connected. */
        data object None : RangingTarget
        data class Peer(val peerId: String, val isHost: Boolean) : RangingTarget
    }
}
