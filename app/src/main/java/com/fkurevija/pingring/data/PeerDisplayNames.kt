package com.fkurevija.pingring.data

/**
 * Human-facing labels for members whose display name has not arrived yet.
 *
 * A peer id is an internal routing key derived from a device's BLE identity. It
 * is not something a person chose, cannot be recalled, and means nothing to
 * anyone looking at the screen - so it must never be shown. Until a member
 * shares a real name the app labels them "Member 1", "Member 2", and so on.
 *
 * The number is positional and assigned in first-seen order, which is
 * meaningless to an outsider but stable enough that a member keeps the same
 * label for as long as they are on the radar. Ids are ephemeral, so the mapping
 * is rebuilt per session and [clear] is called when the ring ends.
 *
 * Kept free of Android and Compose types so the rules are directly testable.
 */
class PeerDisplayNames {

    private val assigned = mutableMapOf<String, Int>()
    private var next = 1

    /**
     * The label to show for [peerId] before a real name is known.
     *
     * Idempotent: the same peer always gets the same label until [clear], and a
     * peer that is seen again after being removed does not consume a second
     * number unless it was explicitly forgotten first.
     */
    fun labelFor(peerId: String): String {
        assigned[peerId]?.let { return "Member $it" }
        val number = next++
        assigned[peerId] = number
        return "Member $number"
    }

    /** Forgets [peerId] so it is renumbered if it is ever seen again. */
    fun forget(peerId: String) {
        assigned.remove(peerId)
    }

    /**
     * Drops every assignment, and the counter with it.
     *
     * Called when the ring ends: numbering restarts for the next event, and no
     * trace of the previous membership is kept.
     */
    fun clear() {
        assigned.clear()
        next = 1
    }

    /** How many members are currently labelled. Exposed for tests. */
    val count: Int get() = assigned.size
}