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

    @Test
    fun `the lit arc is inscribed in the ring's own circle`() {
        // The defect this guards. The highlight was drawn into an oval offset by
        // the ring's own radius - an oval's centre is its top-left plus half its
        // size - which put the arc's centre at the canvas' bottom-right corner
        // region rather than its middle. It rendered as a stray swoosh beside
        // the ring, clipped where it left the canvas. Concentricity is the whole
        // property: the oval's centre must be the canvas centre the ring is
        // drawn around.
        val ring = RadarDirectionPolicy.ringGeometry(400f, 400f)
        assertEquals(200f, ring.topLeftXPx + ring.arcSizePx / 2f, 0.001f)
        assertEquals(200f, ring.topLeftYPx + ring.arcSizePx / 2f, 0.001f)
        // The same circle, not merely a shared centre: an arc inscribed in that
        // oval has its centre line on the ring's centre line.
        assertEquals(ring.radiusPx, ring.arcSizePx / 2f, 0.001f)
    }

    @Test
    fun `the ring fills the canvas without spilling past it`() {
        // radius + stroke / 2 == smallest side / 2 is what keeps the band inside
        // the canvas: any larger and the outer half of the stroke is clipped
        // away, which is what turns a ring into a chamfered square.
        val ring = RadarDirectionPolicy.ringGeometry(300f, 500f)
        assertEquals(150f, ring.radiusPx + ring.strokePx / 2f, 0.001f)
        assertEquals(300f * RadarDirectionPolicy.RING_STROKE_FRACTION, ring.strokePx, 0.001f)
    }

    @Test
    fun `a non-square canvas keeps the ring centred`() {
        // The radar is sized from the available width but its canvas is not
        // guaranteed square; the radius comes off the smaller side while the
        // origin stays on the middle of both.
        val ring = RadarDirectionPolicy.ringGeometry(300f, 500f)
        assertEquals(150f, ring.topLeftXPx + ring.arcSizePx / 2f, 0.001f)
        assertEquals(250f, ring.topLeftYPx + ring.arcSizePx / 2f, 0.001f)
    }
}