package com.fkurevija.pingring.data

/**
 * Whether a UWB ranging session should be kept alive across a transport drop.
 *
 * UWB reaches further than the transport that carries PingRing's messages: a
 * member can walk out of BLE/Nearby range while still being precisely
 * rangeable, and that is exactly the band this retention exists to cover.
 * Tearing the session down at the moment the transport drops means the next
 * contact has to renegotiate a channel and a fresh STS key from scratch, which
 * is the slow path.
 *
 * Retaining is only safe while the session is still ours to keep. Anything that
 * ends the ring - leaving, the host ending it, a new event - must discard every
 * retained address, or a member that left could be ranged by a session that no
 * longer has any right to it. [onSessionEnded] is the single place that clears
 * this, and [retainOnDisconnect] refuses to retain once the ring is over.
 *
 * Kept free of Android and Compose types so the rules are directly testable.
 */
object UwbRetentionPolicy {

    /**
     * How long a retained session is kept after the transport drops.
     *
     * Deliberately shorter than [DiscoveryRepository.PEER_EXPIRY_TIMEOUT_MILLIS]
     * would be if applied to UWB, and well short of a "member still here"
     * claim: this is a window for walking back into range, not membership.
     */
    const val RETENTION_MILLIS = 45_000L
    const val DIRECTION_FRESHNESS_MILLIS = 2_000L
    const val LAST_KNOWN_DIRECTION_TTL_MILLIS = 120_000L

    fun isDirectionLastKnown(lastMeasurementAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - lastMeasurementAtMillis >= DIRECTION_FRESHNESS_MILLIS

    fun isLastKnownDirectionWithinWindow(
        recordedAtMillis: Long,
        nowMillis: Long,
        windowMillis: Long = LAST_KNOWN_DIRECTION_TTL_MILLIS,
    ): Boolean = nowMillis - recordedAtMillis < windowMillis

    /**
     * Decides whether to keep ranging [peerId] after its transport dropped.
     *
     * Only the peer currently being ranged is worth retaining. Holding a spare
     * session open for someone who is not on screen would spend the single UWB
     * session the user did not ask for, and would leave no room for the focus
     * to move if they change it.
     *
     * Returns the peer id to retain, or null to forget it entirely.
     */
    fun retainOnDisconnect(
        peerId: String,
        rangedTargetId: String?,
        knownUwbAddress: String?,
        ringIsActive: Boolean,
        ringIsOver: Boolean,
    ): String? {
        // A ring that has ended is the case that must never be got wrong: a
        // member that left or was dropped must not stay rangeable afterwards.
        if (!ringIsActive || ringIsOver) return null
        // No address means no session to keep; without one there is nothing to
        // reconnect to and retaining the id would only resurrect a ghost pin.
        if (knownUwbAddress.isNullOrBlank()) return null
        // Only the member actually on the radar holds a session to preserve.
        if (rangedTargetId != peerId) return null
        return peerId
    }

    /**
     * Whether a retained session is still within its window.
     *
     * Measured from when the transport dropped, so a long-established UWB
     * session does not count as fresh: the point is how long the *gap* has
     * lasted, not how long ranging has been running.
     *
     * [windowMillis] defaults to [RETENTION_MILLIS] but is overridable because
     * [RadioPowerPolicy] shortens it when the other radios are already busy.
     * The default keeps every existing caller - and the tests - on the value
     * this constant describes.
     */
    fun isWithinRetentionWindow(
        droppedAtMillis: Long,
        nowMillis: Long,
        windowMillis: Long = RETENTION_MILLIS,
    ): Boolean = nowMillis - droppedAtMillis < windowMillis

    /**
     * How a retained peer should be described, given whether its UWB session is
     * still producing measurements.
     *
     * The distinction matters to the user because it is the only signal they
     * get. A member whose messages are undeliverable must not look identical to
     * one that is merely out of Bluetooth range, and neither may look like
     * someone still in the ring once the session has gone quiet for good.
     */
    enum class RetainedState {
        /** Transport is down, UWB is still ranging. Reconnect will be instant. */
        UWB_ACTIVE,

        /**
         * Transport is down and UWB has gone quiet. The member may simply be
         * out of range, but they have also left or the ring may have ended -
         * nothing distinguishes those from here.
         */
        UWB_SILENT,
    }

    /**
     * Classifies a retained peer.
     *
     * [millisSinceUwbMeasurement] is the gap since the last ranging result,
     * which is the only evidence available that the far end still has a live
     * session.
     */
    fun classify(millisSinceUwbMeasurement: Long, uwbSilentAfterMillis: Long): RetainedState =
        if (millisSinceUwbMeasurement < uwbSilentAfterMillis) {
            RetainedState.UWB_ACTIVE
        } else {
            RetainedState.UWB_SILENT
        }

    /**
     * Whether a member whose UWB has gone silent should be reported as gone.
     *
     * This is a guess, and the README records it as one. The far end stopping
     * ranging is indistinguishable from it walking away; the app reports the
     * member as unreachable so the user is not left tracking a ghost, and says
     * plainly that it is a guess rather than a confirmed leave.
     */
    fun shouldReportAsUnreachable(state: RetainedState, ringIsOver: Boolean): Boolean =
        state == RetainedState.UWB_SILENT || ringIsOver
}