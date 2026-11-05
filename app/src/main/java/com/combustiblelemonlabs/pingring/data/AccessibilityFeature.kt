package com.combustiblelemonlabs.pingring.data

enum class AccessibilityFeature(
    val title: String,
    val description: String,
    val defaultEnabled: Boolean = false,
) {
    RADAR_SCREEN_READER_SUMMARY(
        title = "Radar screen-reader summary",
        description = "Speak the tracked member, distance, direction, and active beacon",
    ),
    REDUCE_MOTION(
        title = "Reduce motion",
        description = "Stop continuous decorative animation and honor system animation settings",
    ),
    PROXIMITY_HAPTICS(
        title = "Proximity haptics",
        description = "Vibrate when the tracked member is nearby, independently of beacon vibration",
        defaultEnabled = true,
    ),
    SPOKEN_RADAR_UPDATES(
        title = "Spoken radar updates",
        description = "Announce tracked-member and proximity changes without continuous speech",
    ),
    VISUAL_FLASH_ALERTS(
        title = "Visual flash alerts",
        description = "Flash screen border when a beacon alert triggers, assisting users who are hard of hearing",
    ),
}
