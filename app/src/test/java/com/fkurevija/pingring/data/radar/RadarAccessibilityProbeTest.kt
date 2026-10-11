package com.fkurevija.pingring.data.radar
import com.fkurevija.pingring.data.radar.RadarAccessibilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class RadarAccessibilityProbeTest {

    @Test
    fun describeFormatIsConsistentWithPolicy() {
        val result = RadarAccessibilityPolicy.describe(
            peerName = "Alex",
            distanceMeters = 3.5f,
            azimuthDegrees = 45f,
            proximity = "Hot",
            beaconTitle = "Meeting",
        )
        assertEquals("Radar for Alex. 3.5 meters away. Direction 45 degrees. Active beacon: Meeting.", result)
    }

    @Test
    fun describeFormatWithNoPeerNameReturnsDefault() {
        val result = RadarAccessibilityPolicy.describe(
            peerName = null,
            distanceMeters = null,
            azimuthDegrees = null,
            proximity = null,
            beaconTitle = null,
        )
        assertEquals("Radar. No tracked member. Direction unavailable.", result)
    }
}
