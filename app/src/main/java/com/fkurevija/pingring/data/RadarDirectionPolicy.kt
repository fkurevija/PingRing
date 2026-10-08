package com.fkurevija.pingring.data

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * The shape of the radar's directional segment: a lit arc of the ring pointing
 * at the tracked member, whose width encodes how close they are.
 *
 * A thin dot/sliver means far away; the arc widens as they approach and is a full
 * highlighted circle once they are within arms reach.
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

    /** Roughly arm's length. At or below this the ring is a full highlighted circle. */
    const val ARMS_REACH_METERS = 1.5f

    /**
     * Beyond this the segment is at its thinnest and stops narrowing.
     *
     * Set past the useful UWB range on purpose: without it a peer at 200 m -
     * or a nonsensical reading from a bad session - would produce a negative or
     * vanishing width, so the floor has to clamp rather than extrapolate.
     */
    const val MAX_USEFUL_RANGE_METERS = 30f

    /** Half-angle of the dot at maximum range: 3 degrees (6 degrees sweep). */
    const val MIN_HALF_ANGLE_DEGREES = 3f

    /** Broadest directional segment: 180 degrees half-angle yields a full 360-degree highlighted circle. */
    const val MAX_HALF_ANGLE_DEGREES = 180f

    /** Duration over which a last-known bearing fades back to a neutral ring. */
    const val LAST_KNOWN_DIRECTION_FADE_MILLIS = 30_000L

    fun lastKnownDirectionAlpha(ageMillis: Long): Float =
        (1f - ageMillis.coerceAtLeast(0L).toFloat() / LAST_KNOWN_DIRECTION_FADE_MILLIS)
            .coerceIn(0f, 1f)

    /**
     * Half-angle of the lit arc for a member [distanceMeters] away.
     *
     * Returns [MAX_HALF_ANGLE_DEGREES] (180°) at or under arms reach (full circle) and
     * [MIN_HALF_ANGLE_DEGREES] (3°) at or beyond [MAX_USEFUL_RANGE_METERS] (dot).
     */
    fun halfAngleDegrees(distanceMeters: Float?): Float {
        val distance = distanceMeters ?: return MAX_HALF_ANGLE_DEGREES
        if (distance.isNaN()) return MAX_HALF_ANGLE_DEGREES
        if (distance <= ARMS_REACH_METERS) return MAX_HALF_ANGLE_DEGREES
        if (distance >= MAX_USEFUL_RANGE_METERS) return MIN_HALF_ANGLE_DEGREES

        val low = ln(ARMS_REACH_METERS.toDouble())
        val high = ln(MAX_USEFUL_RANGE_METERS.toDouble())
        val here = ln(distance.toDouble())
        val t = ((here - low) / (high - low)).toFloat().coerceIn(0f, 1f)

        return MIN_HALF_ANGLE_DEGREES +
            (MAX_HALF_ANGLE_DEGREES - MIN_HALF_ANGLE_DEGREES) * (1f - t)
    }

    /**
     * The arc to draw for a member at [azimuthDegrees], or a default segment when null.
     */
    fun segment(azimuthDegrees: Float?, distanceMeters: Float?): Segment {
        val half = halfAngleDegrees(distanceMeters)
        val centre = if (azimuthDegrees != null && azimuthDegrees.isFinite()) {
            ((azimuthDegrees % 360f) + 360f) % 360f - 90f
        } else {
            -90f
        }
        return Segment(
            startAngleDegrees = centre - half,
            sweepAngleDegrees = half * 2f,
            halfAngleDegrees = half,
        )
    }

    /**
     * The ring's stroke width as a fraction of the canvas' smaller side.
     */
    const val RING_STROKE_FRACTION = 0.08f

    /**
     * The circle the ring is drawn on, inside a [canvasWidthPx] by
     * [canvasHeightPx] pixel canvas.
     */
    fun ringGeometry(canvasWidthPx: Float, canvasHeightPx: Float): RingGeometry {
        val smallest = min(canvasWidthPx, canvasHeightPx)
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
