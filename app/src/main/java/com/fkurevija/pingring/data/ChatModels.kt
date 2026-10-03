package com.fkurevija.pingring.data

import com.squareup.moshi.JsonClass

const val MAX_CHAT_LENGTH = 500
const val MAX_BEACON_TITLE_LENGTH = 100
const val MAX_USERNAME_LENGTH = 30

/**
 * What a beacon whose message field was left blank is titled, so the beacon that
 * gets sent is named the same everywhere it is shown rather than rendering empty
 * on somebody else's screen.
 */
const val DEFAULT_BEACON_TITLE = "Active Beacon"

/**
 * One thing in the chat transcript, with the moment it happened.
 *
 * A beacon and a message are different things to render, but they are the same
 * thing to *order*: both carry a timestamp, and both were said at a time. This
 * is that shared shape, so the transcript can be assembled as one list and the
 * render step can decide how each looks.
 */
sealed interface ChatTimelineItem {
    /** When it happened, in epoch millis. */
    val timestamp: Long

    /**
     * Stable across updates.
     *
     * The transcript is laid out from the newest end backwards, so every entry
     * arrives at the near end of the list. Without a key the list identifies
     * items by position, and a message landing while the user is reading back
     * through history would shift what they are looking at by one entry; with
     * one, the list holds on to the same message and leaves the new one to the
     * end they have to drag back to.
     *
     * This is also what lets a beacon survive its own end: the same beacon keeps
     * the same key as it moves from active to history, so the list follows the
     * entry rather than destroying it and building a new one somewhere else.
     */
    val key: String

    data class Msg(val message: ChatMessage) : ChatTimelineItem {
        override val timestamp: Long get() = message.timestamp
        override val key = "m:${message.id}"
    }

    data class Beacon(val beacon: BeaconInfo) : ChatTimelineItem {
        override val timestamp: Long get() = beacon.timestamp
        override val key = "b:${beacon.id}"
    }
}

/**
 * How messages and beacons share one transcript.
 *
 * A beacon used to sit permanently at the near end of the chat, because the
 * transcript was built as "all messages, then all live beacons". That is right
 * for a beacon that is *running* - it is current state, and the reader is
 * looking at that end of the screen anyway - and wrong for one that has stopped.
 * A beacon that has ended is a thing that was said, and it should sit where it
 * was said, among the messages said around it.
 *
 * So: active beacons are pinned to the near end, ended beacons are ordered by
 * timestamp among the messages, and neither changes how it renders. A beacon
 * therefore stays put while it runs and then relocates once, when it ends.
 *
 * Kept free of Compose types so the ordering is directly testable.
 */
object ChatTimelinePolicy {

    /**
     * Builds the transcript, newest last.
     *
     * [activeBeacons] are appended after the sorted history so they always sit
     * at the near end of the reversed list, however long the history grows.
     * [endedBeacons] and the messages are merged by timestamp; the sort is stable
     * so entries sharing a millisecond keep the order they were supplied in, and
     * two things that "happened at the same time" cannot swap places between
     * recompositions and make the list flicker.
     */
    fun build(
        messages: List<ChatMessage>,
        activeBeacons: List<BeaconInfo>,
        endedBeacons: List<BeaconInfo>,
    ): List<ChatTimelineItem> {
        val history = buildList {
            messages.mapTo(this) { ChatTimelineItem.Msg(it) }
            endedBeacons.mapTo(this) { ChatTimelineItem.Beacon(it) }
        }.sortedBy { it.timestamp }

        return history + activeBeacons.map { ChatTimelineItem.Beacon(it) }
    }
}

@JsonClass(generateAdapter = true)
data class ChatPayloadDto(
    val id: String,
    val senderPeerId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    /** Null preserves the themed bubble used by messages from older builds. */
    val colorArgb: Long? = null,
)

@JsonClass(generateAdapter = true)
data class ChatHistoryPayloadDto(
    val messages: List<ChatPayloadDto>
)

@JsonClass(generateAdapter = true)
data class BeaconPayloadDto(
    val id: String,
    val senderPeerId: String,
    val senderName: String,
    val title: String,
    val colorArgb: Long,
    val vibrationPattern: String,
    val isActive: Boolean,
    val timestamp: Long
)

enum class BeaconVibrationPattern(val displayName: String, val pattern: LongArray) {
    SOS_URGENT("SOS / Urgent", longArrayOf(0, 150, 100, 150, 100, 150, 250, 400, 100, 400, 100, 400, 250, 150, 100, 150, 100, 150)),
    RAPID_ALERT("Rapid Alert", longArrayOf(0, 200, 100, 200, 400, 200, 100, 200)),
    STEADY_PULSE("Steady Pulse", longArrayOf(0, 350, 300, 350, 300, 350)),
    GENTLE_PULSE("Gentle Pulse", longArrayOf(0, 120, 150, 120, 600)),
    SILENT("No Vibration", longArrayOf(0));

    companion object {
        fun fromName(name: String): BeaconVibrationPattern =
            entries.find { it.name == name } ?: STEADY_PULSE
    }
}

/**
 * The twelve chat bubble colours a member can pick from.
 *
 * A fixed list, not a generated one. This used to hash the ring and the member
 * id into a shuffled four-colour subset, which meant every device derived a
 * *different* palette for the same person - so the "preset" a sender was shown
 * was not the preset that had been applied to their messages, and the choice
 * had to be made without being able to see what the alternatives were.
 *
 * Twelve fixed colours, chosen to stay legible under both light and dark
 * themes: each one is dark enough for white text to read on it, and distinct
 * enough from its neighbours to be told apart at the size of a chat bubble.
 * There is no custom picker and no "use the theme colour" escape hatch. A member
 * who wanted a colour nobody else could match could pick one, but a member
 * wanted to be *findable* in a crowded transcript, and two phones rendering
 * the same message in different colours defeats that.
 */
object ChatBubblePalette {

    /** Every selectable colour, in the order they are offered. */
    val presets: List<Long> = listOf(
        0xFF00695C, 0xFFAD1457, 0xFF8D5A00, 0xFF455A64,
        0xFF1565C0, 0xFF2E7D32, 0xFF6A1B9A, 0xFFB71C1C,
        0xFF00838F, 0xFF4527A0, 0xFF9E4700, 0xFF37474F,
    )

    /**
     * The colour a member starts a PingRing with, chosen at random.
     *
     * Random rather than a fixed first entry so that two people who join the
     * same event are not reliably the same colour, and not chosen by hashing
     * the ring either: that would hand the same colour to the same member in
     * every ring, which is a quieter version of the same problem. Called once
     * on entry and then persisted, so the colour does not change under the
     * user while they are looking at it.
     */
    fun random(): Long = presets.random()
}

enum class BeaconPreset(
    val label: String,
    val iconEmoji: String,
    val defaultTitle: String,
    val defaultColorArgb: Long,
    val defaultPattern: BeaconVibrationPattern
) {
    FOUND("Found Something", "âœ¨", "Found Something!", 0xFF00E676, BeaconVibrationPattern.STEADY_PULSE),
    REGROUP("Regroup Here", "ðŸ“", "Regroup at my location", 0xFF00E5FF, BeaconVibrationPattern.RAPID_ALERT),
    CAUTION("Caution", "âš ï¸", "Caution", 0xFFFF9100, BeaconVibrationPattern.GENTLE_PULSE),
    SOS("Emergency", "ðŸš¨", "Emergency!", 0xFFFF1744, BeaconVibrationPattern.SOS_URGENT),
    CUSTOM("Custom", "âš¡", "Beacon Active", 0xFFD500F9, BeaconVibrationPattern.STEADY_PULSE)
}
