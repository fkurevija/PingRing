package com.fkurevija.pingring

import com.fkurevija.pingring.data.RadarDirectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radar's directional geometry: where the needle points and how wide the lit
 * arc is.
 *
 * The properties that matter are that the arc is *monotonic* in distance - a
 * further member must never show a wider arc - and that it never degenerates
 * into a negative or zero width, which would make the ring unreadable.
 */
class RadarDirectionPolicyTest {

    @Test
    fun `within arms reach the ring is fully lit`() {
        assertEquals(
            RadarDirectionPolicy.FULL_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(1.0f),
            0.001f
        )
        assertEquals(
            RadarDirectionPolicy.FULL_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(RadarDirectionPolicy.ARMS_REACH_METERS),
            0.001f
        )
    }

    @Test
    fun `at maximum range the arc is a thin sliver`() {
        assertEquals(
            RadarDirectionPolicy.MIN_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(RadarDirectionPolicy.MAX_USEFUL_RANGE_METERS),
            0.001f
        )
    }

    @Test
    fun `the arc narrows monotonically as distance grows`() {
        // The core visual promise: further away must never look closer. Sampled
        // across the whole range rather than at two points, so an inversion
        // anywhere in between fails here.
        var previous = Float.MAX_VALUE
        var d = 1.6f
        while (d <= RadarDirectionPolicy.MAX_USEFUL_RANGE_METERS) {
            val half = RadarDirectionPolicy.halfAngleDegrees(d)
            assertTrue(
                "arc widened from $previous to $half at ${d}m",
                half <= previous
            )
            previous = half
            d += 0.4f
        }
    }

    @Test
    fun `an absurd distance clamps to the sliver instead of going negative`() {
        // A bad ranging session must not produce a negative sweep, which would
        // draw the arc backwards or not at all. Infinity in particular must read
        // as maximally far, not as "in your arms" - the isFinite() trap.
        assertEquals(
            RadarDirectionPolicy.MIN_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(10_000f),
            0.001f
        )
        assertEquals(
            RadarDirectionPolicy.MIN_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(Float.POSITIVE_INFINITY),
            0.001f
        )
    }

    @Test
    fun `a non-positive distance reads as in reach`() {
        // Zero and negative distances are nonsense readings rather than far
        // ones: there is no sense in which someone can be at minus five metres,
        // and the member is almost certainly right there. The <= comparison
        // catches these along with 0.
        assertEquals(
            RadarDirectionPolicy.FULL_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(0f),
            0.001f
        )
        assertEquals(
            RadarDirectionPolicy.FULL_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(-5f),
            0.001f
        )
        // Negative infinity is the same case, taken to its limit.
        assertEquals(
            RadarDirectionPolicy.FULL_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(Float.NEGATIVE_INFINITY),
            0.001f
        )
    }

    @Test
    fun `an undefined reading is treated as no reading`() {
        // NaN is genuinely ambiguous, so it is not a distance and must not be
        // treated as an extremely close one.
        assertEquals(
            RadarDirectionPolicy.FULL_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(Float.NaN),
            0.001f
        )
    }

    @Test
    fun `a missing distance is treated as in reach`() {
        // Standing next to someone with no reported distance should not make
        // them look far away; that would be the more misleading reading.
        assertEquals(
            RadarDirectionPolicy.FULL_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(null),
            0.001f
        )
    }

    @Test
    fun `no azimuth means no segment, rather than a made-up bearing`() {
        assertNull(RadarDirectionPolicy.segment(null, 3f))
        assertNull(RadarDirectionPolicy.needleRotationDegrees(null))
    }

    @Test
    fun `the segment is centred on the bearing`() {
        // 90 degrees is due east; the arc must straddle it symmetrically.
        val segment = RadarDirectionPolicy.segment(90f, 10f)
        assertNotNull(segment)
        val centre = segment!!.startAngleDegrees + segment.sweepAngleDegrees / 2f
        assertEquals(0f, centre, 0.01f)
    }

    @Test
    fun `the needle points where the bearing says`() {
        assertEquals(90f, RadarDirectionPolicy.needleRotationDegrees(90f)!!, 0.001f)
        assertEquals(180f, RadarDirectionPolicy.needleRotationDegrees(180f)!!, 0.001f)
    }

    @Test
    fun `bearings wrap instead of spinning the long way round`() {
        // 359 degrees is 1 degree past north, not -359 degrees, which would
        // animate the needle almost all the way around.
        assertEquals(359f, RadarDirectionPolicy.needleRotationDegrees(359f)!!, 0.001f)
        assertEquals(10f, RadarDirectionPolicy.needleRotationDegrees(370f)!!, 0.001f)
        assertEquals(350f, RadarDirectionPolicy.needleRotationDegrees(-10f)!!, 0.001f)
    }

    @Test
    fun `a negative bearing produces a valid segment`() {
        val segment = RadarDirectionPolicy.segment(-90f, 5f)
        assertNotNull(segment)
        assertTrue(segment!!.sweepAngleDegrees > 0f)
    }

    @Test
    fun `a close member is flagged as fully lit`() {
        assertTrue(RadarDirectionPolicy.segment(0f, 1f)!!.isFullyLit)
        assertTrue(!RadarDirectionPolicy.segment(0f, 25f)!!.isFullyLit)
    }

    @Test
    fun `the lit width is the sweep the ring actually draws`() {
        val segment = RadarDirectionPolicy.segment(45f, 8f)!!
        assertEquals(segment.halfAngleDegrees * 2f, segment.sweepAngleDegrees, 0.001f)
    }
}