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
     *  - A selection the user made wins, as long as that peer is still
     *    present. Ephemeral ids rotate, so a stale selection has to fall back
     *    rather than leaving the radar pointed at nobody.
     *  - When "track host" is on and this device is not the host, the host is
     *    the subject, because the host set that policy deliberately.
     *  - Otherwise the nearest known peer, which is the useful default before
     *    anybody has chosen.
     *
     * [nearestPeerId] is passed in rather than recomputed here so this stays
     * a pure function of its arguments; the caller already has the distances.
     */
    fun resolveFocus(
        selectedPeerId: String?,
        availablePeerIds: List<String>,
        nearestPeerId: String?,
        isHost: Boolean,
        trackHost: Boolean,
        localPeerId: String,
        hostPeerId: String?,
    ): String? {
        // "Track host" is the host's policy for this event, and it takes
        // precedence over whatever the user last tapped: if the host has said
        // members should be tracking them, a local selection must not quietly
        // override it.
        if (shouldTrackHost(isHost, trackHost) && hostPeerId != null) {
            return hostPeerId
        }

        val candidates = availablePeerIds.filter { it != localPeerId }
        if (candidates.isEmpty()) return null

        if (selectedPeerId != null && selectedPeerId in candidates) {
            return selectedPeerId
        }
        return nearestPeerId?.takeIf { it in candidates }
    }

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
