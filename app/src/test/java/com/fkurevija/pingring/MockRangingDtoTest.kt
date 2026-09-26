package com.fkurevija.pingring

import com.fkurevija.pingring.data.MockRangingDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins down the wire format of the debug-only range reports that
 * tools/mock_radar.py streams through the LAN debug relay. The script writes
 * these fields by name, so a rename here would silently stop the radar from
 * being driven.
 */
class MockRangingDtoTest {

    private val adapter = Moshi.Builder().build().adapter(MockRangingDto::class.java)

    @Test
    fun parsesAFullRangeReport() {
        val dto = adapter.fromJson(
            """{"peerId":"a1b2c3d4","distance":1.25,"azimuth":90.0,"elevation":-3.0,"name":"Alice"}"""
        )
        assertEquals("a1b2c3d4", dto?.peerId)
        assertEquals(1.25f, dto?.distance)
        assertEquals(90.0f, dto?.azimuth)
        assertEquals(-3.0f, dto?.elevation)
        assertEquals("Alice", dto?.name)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        // So the script can grow a new field before the app knows about it.
        val dto = adapter.fromJson("""{"peerId":"M1","distance":4.0,"speed":1.2}""")
        assertEquals("M1", dto?.peerId)
        assertEquals(4.0f, dto?.distance)
    }

    @Test
    fun everyFieldButThePeerIdIsOptional() {
        val dto = adapter.fromJson("""{"peerId":"M1"}""")
        assertEquals("M1", dto?.peerId)
        assertNull(dto?.distance)
        assertNull(dto?.azimuth)
        assertNull(dto?.elevation)
        assertNull(dto?.name)
    }

    @Test
    fun reportsAreSerializedBackIntoTheSameShape() {
        val json = adapter.toJson(
            MockRangingDto(peerId = "M1", distance = 0.5f, azimuth = 12.0f, elevation = 0.0f)
        )
        assertEquals(
            """{"peerId":"M1","distance":0.5,"azimuth":12.0,"elevation":0.0}""",
            json
        )
    }
}