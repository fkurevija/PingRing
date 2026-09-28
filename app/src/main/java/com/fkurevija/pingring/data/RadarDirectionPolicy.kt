package com.fkurevija.pingring.data

import kotlin.math.ln
import kotlin.math.pow

/**
 * The shape of the radar's directional segment: a lit arc of the ring pointing
 * at the tracked member, whose width encodes how close they are.
 *
 * A thin sliver means far away; the arc widens as they approach and is fully lit
 * once they are within arms reach. The needle in the middle of the radar points
 * the same way, so direction and closeness are read from one glance at the same
 * place.
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

    /** Half of the full circle: the arc's width when the member is in reach. */
    const val FULL_HALF_ANGLE_DEGREES = 180f

    /**
     * Half-angle of the lit arc for a member [distanceMeters] away.
     *
     * Returns [FULL_HALF_ANGLE_DEGREES] at or under arms reach and
     * [MIN_HALF_ANGLE_DEGREES] at or beyond [MAX_USEFUL_RANGE_METERS]. A null or
     * non-positive distance is treated as "in reach" rather than rejected: a
     * member standing next to you has no meaningful distance to report, and
     * showing them as far away would be the more misleading answer.
     */
    fun halfAngleDegrees(distanceMeters: Float?): Float {
        val distance = distanceMeters ?: return FULL_HALF_ANGLE_DEGREES
        // Infinity is not "no reading" - it is an absurdly large one, and it has
        // to clamp to the sliver like any other far distance. Checking
        // isFinite() alongside the null case would return the fully-lit value
        // for it, which is exactly backwards: the worst possible reading would
        // render as somebody standing in your arms. NaN is the ambiguous one
        // and is treated as no reading.
        if (distance.isNaN()) return FULL_HALF_ANGLE_DEGREES
        if (distance <= ARMS_REACH_METERS) return FULL_HALF_ANGLE_DEGREES
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
            (FULL_HALF_ANGLE_DEGREES - MIN_HALF_ANGLE_DEGREES) * (1f - t)
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
     * The needle's rotation, in degrees, for a member at [azimuthDegrees].
     *
     * Zero points up, matching the radar's twelve o'clock convention, and the
     * value is wrapped so a bearing of 359 degrees does not spin the needle the
     * long way round.
     */
    fun needleRotationDegrees(azimuthDegrees: Float?): Float? {
        if (azimuthDegrees == null || !azimuthDegrees.isFinite()) return null
        return ((azimuthDegrees % 360f) + 360f) % 360f
    }

    /** The lit arc: where it starts, how far it sweeps, and its half-width. */
    data class Segment(
        val startAngleDegrees: Float,
        val sweepAngleDegrees: Float,
        val halfAngleDegrees: Float,
    ) {
        /** True when the member is close enough for the whole ring to be lit. */
        val isFullyLit: Boolean get() = halfAngleDegrees >= FULL_HALF_ANGLE_DEGREES
    }
}