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
 * Twelve hues spanning the spectrum a person would name without hesitating:
 * red, orange, amber, yellow, lime, green, teal, cyan, blue, indigo, violet and
 * pink. The earlier set was Material 800 tones - teal, brown, blue-grey - which
 * are three or four neighbouring hues wearing different names, and a member
 * picking "the brown one" in a crowded transcript was relying on a difference
 * most people cannot see. Naming a colour and getting that colour is the whole
 * point of a palette a person chooses from.
 *
 * Brightness is safe here because the text on a bubble picks black or white by
 * contrast ratio (`Color.readableOnIt()` in RadarScreen) rather than assuming
 * white. That is what lets yellow and lime be genuinely yellow instead of a dark
 * ochre pretending to be one. AppRulesTest asserts that every preset clears
 * 4.5:1 against whichever of the two it ends up with, so brightening this list
 * again cannot quietly make a bubble unreadable.
 *
 * Still no custom picker and no "use the theme colour" escape hatch. A member
 * wanted to be *findable* in a crowded transcript, and two phones rendering the
 * same message in different colours defeats that.
 */
object ChatBubblePalette {

    /**
     * Every selectable colour, in the order they are offered.
     *
     * Twelve named hues a person would reach for without hesitating: red, orange,
     * amber, yellow, lime, green, teal, cyan, blue, indigo, violet and pink. The
     * set this replaces was Material 800 tones - teal, brown, blue-grey - which
     * are three or four neighbouring hues wearing different names, so a member
     * picking "the brown one" out of a crowded transcript was relying on a
     * difference most people cannot see.
     *
     * The order alternates warm and cool rather than running the spectrum, and
     * that is deliberate. Sorted by hue it looks tidier, but it puts the two
     * colours most likely to be confused next to each other: lime and green sit
     * at a CIE L*a*b* distance of 18 from each other, against 46 for red and
     * orange. In a wrapping FlowRow - where the end of one row sits beside the
     * start of the next - "adjacent" means adjacent on screen, so the rainbow
     * order offered two near-identical swatches as a pair to choose between.
     * Interleaving puts the worst neighbouring pair at 66, nearly four times
     * further apart, and still scans as a set of distinct colours.
     */
    val presets: List<Long> = listOf(
        0xFFE53935, // red
        0xFF00ACC1, // cyan
        0xFF7CB342, // lime
        0xFF1E88E5, // blue
        0xFFFFB300, // amber
        0xFF00897B, // teal
        0xFF8E24AA, // violet
        0xFFFB8C00, // orange
        0xFF3949AB, // indigo
        0xFF43A047, // green
        0xFFFDD835, // yellow
        0xFFD81B60, // pink
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
    FOUND("Found Something", "✨", "Found Something!", 0xFF00E676, BeaconVibrationPattern.STEADY_PULSE),
    REGROUP("Regroup Here", "📍", "Regroup at my location", 0xFF00E5FF, BeaconVibrationPattern.RAPID_ALERT),
    CAUTION("Caution", "⚠️", "Caution", 0xFFFF9100, BeaconVibrationPattern.GENTLE_PULSE),
    SOS("Emergency", "🚨", "Emergency!", 0xFFFF1744, BeaconVibrationPattern.SOS_URGENT),
    CUSTOM("Custom", "⚡", "Beacon Active", 0xFFD500F9, BeaconVibrationPattern.STEADY_PULSE)
}
