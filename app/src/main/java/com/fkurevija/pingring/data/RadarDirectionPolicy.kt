package com.fkurevija.pingring.data

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * The shape of the radar's directional segment: a lit arc of the ring pointing
 * at the tracked member, whose width encodes how close they are.
 *
 * A thin sliver means far away; the arc widens as they approach and is fully lit
 * once they are within arms reach. The arc itself carries both direction and
 * closeness.
 *
 * Distance is interpolated logarithmically rather than linearly. UWB distances
 * span roughly 0.1 m to 30 m, and a linear map spends almost its whole range on
 * the first two metres - the part that matters most - and renders everything
 * beyond 5 m as the same sliver. Log spacing gives each doubling of distance an
 * equal share of the arc, which is what makes the gradient readable across the
 * whole range.
 *
 * Kept free of Android and Compose types so the geometry is directly testable.
 */
object RadarDirectionPolicy {

    /** Roughly arm's length. At or below this the ring is fully lit. */
    const val ARMS_REACH_METERS = 1.5f

    /**
     * Beyond this the segment is at its thinnest and stops narrowing.
     *
     * Set past the useful UWB range on purpose: without it a peer at 200 m -
     * or a nonsensical reading from a bad session - would produce a negative or
     * vanishing width, so the floor has to clamp rather than extrapolate.
     */
    const val MAX_USEFUL_RANGE_METERS = 30f

    /** Half-angle of the sliver at maximum range: 4 degrees, a visible tick. */
    const val MIN_HALF_ANGLE_DEGREES = 4f

    /** Broadest directional segment; leave a gap so its bearing remains visible. */
    const val MAX_HALF_ANGLE_DEGREES = 120f

    /** Duration over which a last-known bearing fades back to a neutral ring. */
    const val LAST_KNOWN_DIRECTION_FADE_MILLIS = 30_000L

    fun lastKnownDirectionAlpha(ageMillis: Long): Float =
        (1f - ageMillis.coerceAtLeast(0L).toFloat() / LAST_KNOWN_DIRECTION_FADE_MILLIS)
            .coerceIn(0f, 1f)

    /**
     * Half-angle of the lit arc for a member [distanceMeters] away.
     *
     * Returns [MAX_HALF_ANGLE_DEGREES] at or under arms reach and
     * [MIN_HALF_ANGLE_DEGREES] at or beyond [MAX_USEFUL_RANGE_METERS]. A null or
     * non-positive distance is treated as "in reach" rather than rejected: a
     * member standing next to you has no meaningful distance to report, and
     * showing them as far away would be the more misleading answer.
     */
    fun halfAngleDegrees(distanceMeters: Float?): Float {
        val distance = distanceMeters ?: return MAX_HALF_ANGLE_DEGREES
        // Infinity is not "no reading" - it is an absurdly large one, and it has
        // to clamp to the sliver like any other far distance. Checking
        // isFinite() alongside the null case would return the fully-lit value
        // for it, which is exactly backwards: the worst possible reading would
        // render as somebody standing in your arms. NaN is the ambiguous one
        // and is treated as no reading.
        if (distance.isNaN()) return MAX_HALF_ANGLE_DEGREES
        if (distance <= ARMS_REACH_METERS) return MAX_HALF_ANGLE_DEGREES
        // The >= comparison catches +Infinity too, so it needs no special case.
        if (distance >= MAX_USEFUL_RANGE_METERS) return MIN_HALF_ANGLE_DEGREES

        // Position along the log scale between arms reach and max range.
        val low = ln(ARMS_REACH_METERS.toDouble())
        val high = ln(MAX_USEFUL_RANGE_METERS.toDouble())
        val here = ln(distance.toDouble())
        val t = ((here - low) / (high - low)).toFloat().coerceIn(0f, 1f)

        // Interpolate from full circle down to the sliver. t = 0 at arms reach
        // (full) and t = 1 at max range (thinnest), so the scale is inverted.
        return MIN_HALF_ANGLE_DEGREES +
            (MAX_HALF_ANGLE_DEGREES - MIN_HALF_ANGLE_DEGREES) * (1f - t)
    }

    /**
     * The arc to draw for a member at [azimuthDegrees], or null when there is
     * nothing to point at.
     *
     * A null azimuth means ranging is not locked - typically a peer being tracked
     * by RSSI alone, where there is no direction information at all. Drawing an
     * directional segment then would point at a made-up bearing, so the ring
     * stays plain instead.
     */
    fun segment(azimuthDegrees: Float?, distanceMeters: Float?): Segment? {
        if (azimuthDegrees == null || !azimuthDegrees.isFinite()) return null
        val half = halfAngleDegrees(distanceMeters)
        // Normalised to 0..360 with 0 at twelve o'clock, matching Compose's
        // drawArc convention, so the caller can pass startAngle straight through.
        val centre = ((azimuthDegrees % 360f) + 360f) % 360f - 90f
        return Segment(
            startAngleDegrees = centre - half,
            sweepAngleDegrees = half * 2f,
            halfAngleDegrees = half,
        )
    }

    /**
     * The ring's stroke width as a fraction of the canvas' smaller side.
     *
     * The ring is the app's own circle rather than a chart axis, so the band is
     * drawn thick: 8% of the canvas reads as a band from arm's length and still
     * leaves the middle free. A fraction rather than a dp value, so
     * it scales with the radar instead of sinking into it on a tablet and
     * swallowing it on a small phone.
     */
    const val RING_STROKE_FRACTION = 0.08f

    /**
     * The circle the ring is drawn on, inside a [canvasWidthPx] by
     * [canvasHeightPx] pixel canvas.
     *
     * The ring and the lit arc on it have to be two strokes of *one* circle, so
     * both are derived from here. Anything that works out its own radius or
     * origin instead drifts, and a highlight a few pixels off the ring stops
     * reading as a highlight and starts reading as a rendering bug.
     *
     * [RingGeometry.topLeftXPx] and [RingGeometry.topLeftYPx] are the top-left
     * of the square the arc's bounding oval occupies, which is what `drawArc`
     * takes. Its centre - that top-left plus half [RingGeometry.arcSizePx] - is
     * the canvas centre the ring is centred on.
     */
    fun ringGeometry(canvasWidthPx: Float, canvasHeightPx: Float): RingGeometry {
        val smallest = min(canvasWidthPx, canvasHeightPx)
        // At radius + stroke / 2 = smallest / 2 the outer edge of the band
        // lands exactly on the canvas edge: the ring fills the space it is
        // given without the outer half of its stroke being clipped away.
        val stroke = smallest * RING_STROKE_FRACTION
        val radius = (smallest - stroke) / 2f
        return RingGeometry(
            strokePx = stroke,
            radiusPx = radius,
            topLeftXPx = canvasWidthPx / 2f - radius,
            topLeftYPx = canvasHeightPx / 2f - radius,
        )
    }

    /** The lit arc: where it starts, how far it sweeps, and its half-width. */
    data class Segment(
        val startAngleDegrees: Float,
        val sweepAngleDegrees: Float,
        val halfAngleDegrees: Float,
    )

    /**
     * The ring's stroke, its radius, and the square an arc on it is inscribed
     * in - everything needed to draw the ring and to light part of it up in the
     * very same place.
     */
    data class RingGeometry(
        val strokePx: Float,
        val radiusPx: Float,
        val topLeftXPx: Float,
        val topLeftYPx: Float,
    ) {
        /** The side of the square bounding oval an arc of the ring is drawn in. */
        val arcSizePx: Float get() = radiusPx * 2f
    }
}