package com.fkurevija.pingring

import com.fkurevija.pingring.data.MockRangingDto
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun aSignalOnlyReportParsesWithoutADistance() {
        // What mock_radar.py sends under --rssi-only. The app treats this as a
        // BLE advert and smooths it, rather than as a ranging fix it trusts
        // exactly, so it is the only report that can exercise the filter.
        val dto = adapter.fromJson("""{"peerId":"M1","rssi":-71,"azimuth":90.0}""")
        assertEquals("M1", dto?.peerId)
        assertEquals(-71, dto?.rssi)
        assertNull("a signal report must not claim a distance", dto?.distance)
    }

    @Test
    fun aReportCanCarryBothADistanceAndASignalStrength() {
        // The default mode: the distance is the ranging fix and the dBm is the
        // noisy reading the radio produced alongside it.
        val dto = adapter.fromJson("""{"peerId":"M1","distance":2.0,"rssi":-66}""")
        assertEquals(2.0f, dto?.distance)
        assertEquals(-66, dto?.rssi)
    }

    @Test
    fun anAbsentSignalFieldIsNullRatherThanZero() {
        // Zero dBm is the strongest possible reading. Defaulting to it would
        // turn "the script sent no signal data" into "the peer is in your
        // pocket", which the filter would then dutifully smooth.
        val dto = adapter.fromJson("""{"peerId":"M1","distance":2.0}""")
        assertNull(dto?.rssi)
    }

    @Test
    fun aFractionalSignalStrengthIsRejectedRatherThanTruncated() {
        // RSSI is an Int on both sides of the wire. Moshi rejects a fractional
        // dBm outright rather than quietly truncating it, which is the right
        // failure: a script sending -43.7 should be noticed, not read as -43.
        // This is why mock_radar.py rounds before sending.
        try {
            adapter.fromJson("""{"peerId":"M1","rssi":-43.7}""")
            fail("a fractional dBm must not parse")
        } catch (expected: JsonDataException) {
            assertTrue(
                "the failure should name the offending field",
                expected.message?.contains("rssi") == true
            )
        }
    }
}