package com.combustiblelemonlabs.pingring.data.radar
import com.combustiblelemonlabs.pingring.data.radar.RadarDirectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radar's directional geometry and how wide the lit arc is.
 *
 * The properties that matter are that the arc is *monotonic* in distance - a
 * further member must never show a wider arc - and that it never degenerates
 * into a negative or zero width, which would make the ring unreadable.
 */
class RadarDirectionPolicyTest {

    @Test
    fun `within arms reach the directional segment is a full circle`() {
        assertEquals(
            RadarDirectionPolicy.MAX_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(1.0f),
            0.001f
        )
        assertEquals(
            360f,
            RadarDirectionPolicy.segment(0f, 1.0f).sweepAngleDegrees,
            0.001f
        )
    }

    @Test
    fun `at maximum range the arc is a thin dot sliver`() {
        assertEquals(
            RadarDirectionPolicy.MIN_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(RadarDirectionPolicy.MAX_USEFUL_RANGE_METERS),
            0.001f
        )
    }

    @Test
    fun `the arc narrows monotonically as distance grows`() {
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
        assertEquals(
            RadarDirectionPolicy.MAX_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(0f),
            0.001f
        )
        assertEquals(
            RadarDirectionPolicy.MAX_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(-5f),
            0.001f
        )
        assertEquals(
            RadarDirectionPolicy.MAX_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(Float.NEGATIVE_INFINITY),
            0.001f
        )
    }

    @Test
    fun `an undefined reading is treated as no reading`() {
        assertEquals(
            RadarDirectionPolicy.MAX_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(Float.NaN),
            0.001f
        )
    }

    @Test
    fun `a missing distance is treated as in reach`() {
        assertEquals(
            RadarDirectionPolicy.MAX_HALF_ANGLE_DEGREES,
            RadarDirectionPolicy.halfAngleDegrees(null),
            0.001f
        )
    }

    @Test
    fun `no azimuth defaults to top bearing rather than null`() {
        assertNotNull(RadarDirectionPolicy.segment(null, 3f))
    }

    @Test
    fun `the segment is centred on the bearing`() {
        val segment = RadarDirectionPolicy.segment(90f, 10f)
        val centre = segment.startAngleDegrees + segment.sweepAngleDegrees / 2f
        assertEquals(0f, centre, 0.01f)
    }

    @Test
    fun `a negative bearing produces a valid segment`() {
        val segment = RadarDirectionPolicy.segment(-90f, 5f)
        assertTrue(segment.sweepAngleDegrees > 0f)
    }

    @Test
    fun `last known direction fades linearly and expires after thirty seconds`() {
        assertEquals(1f, RadarDirectionPolicy.lastKnownDirectionAlpha(0L), 0.001f)
        assertEquals(0.5f, RadarDirectionPolicy.lastKnownDirectionAlpha(15_000L), 0.001f)
        assertEquals(0f, RadarDirectionPolicy.lastKnownDirectionAlpha(30_000L), 0.001f)
        assertEquals(0f, RadarDirectionPolicy.lastKnownDirectionAlpha(40_000L), 0.001f)
        assertEquals(1f, RadarDirectionPolicy.lastKnownDirectionAlpha(-1L), 0.001f)
    }

    @Test
    fun `a close member retains a directional gap in the ring highlight`() {
        assertTrue(RadarDirectionPolicy.segment(0f, 1.6f).sweepAngleDegrees < 360f)
        assertTrue(RadarDirectionPolicy.segment(0f, 1.6f).sweepAngleDegrees > RadarDirectionPolicy.segment(0f, 25f).sweepAngleDegrees)
    }

    @Test
    fun `the lit width is the sweep the ring actually draws`() {
        val segment = RadarDirectionPolicy.segment(45f, 8f)
        assertEquals(segment.halfAngleDegrees * 2f, segment.sweepAngleDegrees, 0.001f)
    }

    @Test
    fun `the lit arc is inscribed in the ring's own circle`() {
        val ring = RadarDirectionPolicy.ringGeometry(400f, 400f)
        assertEquals(200f, ring.topLeftXPx + ring.arcSizePx / 2f, 0.001f)
        assertEquals(200f, ring.topLeftYPx + ring.arcSizePx / 2f, 0.001f)
        assertEquals(ring.radiusPx, ring.arcSizePx / 2f, 0.001f)
    }

    @Test
    fun `the ring fills the canvas without spilling past it`() {
        val ring = RadarDirectionPolicy.ringGeometry(300f, 500f)
        assertEquals(150f, ring.radiusPx + ring.strokePx / 2f, 0.001f)
        assertEquals(300f * RadarDirectionPolicy.RING_STROKE_FRACTION, ring.strokePx, 0.001f)
    }

    @Test
    fun `the ring stays centred on non-square canvas`() {
        val ring = RadarDirectionPolicy.ringGeometry(300f, 500f)
        assertEquals(150f, ring.topLeftXPx + ring.arcSizePx / 2f, 0.001f)
        assertEquals(250f, ring.topLeftYPx + ring.arcSizePx / 2f, 0.001f)
    }
}
