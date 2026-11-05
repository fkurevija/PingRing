package com.combustiblelemonlabs.pingring.data.ranging
import org.junit.Assert.*
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.radar.GnssFixDto
import com.combustiblelemonlabs.pingring.data.radar.GnssPolicy

class GnssPolicyTest {
    private val fix = GnssFixDto(45.123456, 16.123456, 5f, 0)

    @Test
    fun quantizationBoundsErrorAndCarriesUncertainty() {
        val quantized = GnssPolicy.quantize(fix)!!
        assertTrue(GnssPolicy.separationMeters(fix, quantized) < 8f)
        assertEquals(13f, quantized.accuracyMeters)
        assertEquals(3_000L, GnssPolicy.UPDATE_INTERVAL_MILLIS)
        assertTrue(GnssPolicy.isUsable(GnssPolicy.quantize(fix.copy(accuracyMeters = 50f))!!))
    }

    @Test
    fun invalidStaleAndInaccurateFixesAreRejected() {
        assertNull(GnssPolicy.quantize(fix.copy(latitude = Double.NaN)))
        assertNull(GnssPolicy.quantize(fix.copy(longitude = 181.0)))
        assertNull(GnssPolicy.quantize(fix.copy(accuracyMeters = 51f)))
        assertNull(GnssPolicy.quantize(fix.copy(ageMillis = 30_001)))
        assertNull(GnssPolicy.quantize(fix.copy(ageMillis = -1)))
    }

    @Test
    fun separationUsesBothCoordinatesAndHandlesTheDateline() {
        assertEquals(0f, GnssPolicy.separationMeters(fix, fix), 0.001f)
        val origin = GnssFixDto(0.0, 0.0, 1f, 0)
        assertEquals(111_195f, GnssPolicy.separationMeters(origin, origin.copy(longitude = 1.0)), 1f)
        assertTrue(GnssPolicy.separationMeters(origin.copy(longitude = 179.999), origin.copy(longitude = -179.999)) < 225f)
    }
}