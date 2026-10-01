package com.fkurevija.pingring.data

enum class AccessibilityFeature(
    val title: String,
    val description: String,
    val defaultEnabled: Boolean = false,
) {
    RADAR_SCREEN_READER_SUMMARY(
        title = "Radar screen-reader summary",
        description = "Speak the tracked member, distance, direction, and active beacon",
    ),
}