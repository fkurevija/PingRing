package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.AccessibilityFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AccessibilityFeatureTest {

    @Test
    fun accessibilityFeatureEnumEntriesAreValid() {
        val entries = AccessibilityFeature.entries
        assertEquals(5, entries.size)
        assertNotNull(AccessibilityFeature.valueOf("REDUCE_MOTION"))
        assertNotNull(AccessibilityFeature.valueOf("PROXIMITY_HAPTICS"))
        assertNotNull(AccessibilityFeature.valueOf("SPOKEN_RADAR_UPDATES"))
        assertNotNull(AccessibilityFeature.valueOf("RADAR_SCREEN_READER_SUMMARY"))
        assertNotNull(AccessibilityFeature.valueOf("VISUAL_FLASH_ALERTS"))
    }
}
