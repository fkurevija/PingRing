package com.fkurevija.pingring.data

import com.squareup.moshi.JsonClass

const val MAX_CHAT_LENGTH = 500
const val MAX_BEACON_TITLE_LENGTH = 100
const val MAX_USERNAME_LENGTH = 30

/**
 * What a beacon whose message field was left blank is titled, so activating it
 * and saving it as a favourite agree on one name instead of one sending a beacon
 * that renders empty everywhere it is shown.
 */
const val DEFAULT_BEACON_TITLE = "Active Beacon"

@JsonClass(generateAdapter = true)
data class ChatPayloadDto(
    val id: String,
    val senderPeerId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long
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

enum class BeaconPreset(
    val label: String,
    val iconEmoji: String,
    val defaultTitle: String,
    val defaultColorArgb: Long,
    val defaultPattern: BeaconVibrationPattern
) {
    SOS("Emergency", "🚨", "Emergency!", 0xFFFF1744, BeaconVibrationPattern.SOS_URGENT),
    FOUND("Found Something", "✨", "Found Something!", 0xFF00E676, BeaconVibrationPattern.STEADY_PULSE),
    REGROUP("Regroup Here", "📍", "Regroup at my location", 0xFF00E5FF, BeaconVibrationPattern.RAPID_ALERT),
    CAUTION("Caution", "⚠️", "Caution", 0xFFFF9100, BeaconVibrationPattern.GENTLE_PULSE),
    CUSTOM("Custom", "⚡", "Beacon Active", 0xFFD500F9, BeaconVibrationPattern.STEADY_PULSE)
}

/**
 * The beacon configuration the radar's Quick Beacon button sends in one tap.
 *
 * Starred in the beacon screen and kept in DataStore, so it survives the app
 * closing: the point of it is that a liked beacon does not have to be rebuilt
 * at the next event. Whole-configuration rather than just the preset, because
 * what people edit - the message, the colour, the rhythm - is what they want
 * back with one tap.
 */
data class FavouriteBeacon(
    val title: String,
    val colorArgb: Long,
    val pattern: BeaconVibrationPattern
)
