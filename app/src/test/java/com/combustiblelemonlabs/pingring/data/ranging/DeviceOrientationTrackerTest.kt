package com.combustiblelemonlabs.pingring.data.ranging
import com.combustiblelemonlabs.pingring.data.ranging.DeviceOrientationTracker
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The compass-corrected arrow on the radar.
 *
 * A peer is placed at a bearing measured by the UWB radio, which is relative to
 * whatever way the phone was pointing when the measurement was made. Drawing it
 * without correcting for that would make the arrow swing as the user turned the
 * phone, so the bearing has to be rotated into world space first - which is what
 * these tests pin down, since a wrong rotation still looks plausible on screen.
 */
class DeviceOrientationTrackerTest {

    /** No rotation at all: the device is upright and pointing where it was aimed. */
    private val identity = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    /** Yaw of 90 degrees about the vertical axis, i.e. the phone turned to face right. */
    private val yawed90 = floatArrayOf(
        0f, 1f, 0f,
        -1f, 0f, 0f,
        0f, 0f, 1f
    )

    private fun worldDirection(matrix: FloatArray, azimuth: Float?, elevation: Float? = 0f) =
        DeviceOrientationTracker.worldDirection(matrix, azimuth, elevation)

    @Test
    fun anUnrotatedPeerStraightAheadPointsAlongY() {
        val (x, y, z) = worldDirection(identity, azimuth = 0f)

        assertEquals(0f, x, TOLERANCE)
        assertEquals(1f, y, TOLERANCE)
        assertEquals(0f, z, TOLERANCE)
    }

    @Test
    fun anUnrotatedPeerToTheRightPointsAlongX() {
        // Azimuth is measured clockwise from straight ahead, so 90 degrees is to
        // the right of the device and lands on +x.
        val (x, y, z) = worldDirection(identity, azimuth = 90f)

        assertEquals(1f, x, TOLERANCE)
        assertEquals(0f, y, TOLERANCE)
        assertEquals(0f, z, TOLERANCE)
    }

    @Test
    fun turningThePhoneMovesTheArrowRatherThanThePeer() {
        // The point of the correction: the same measured bearing, drawn against a
        // device that has turned 90 degrees, must not come out where it did before.
        val (_, straightAheadBefore) = worldDirection(identity, azimuth = 0f)
        val (x, straightAheadAfter, _) = worldDirection(yawed90, azimuth = 0f)

        assertEquals(1f, straightAheadBefore, TOLERANCE)
        assertEquals(0f, straightAheadAfter, TOLERANCE)
        assertEquals(1f, x, TOLERANCE)
    }

    @Test
    fun aRotatedDevicePreservesTheDistanceOfTheDirection() {
        // A rotation cannot change the length of a direction vector; if it did, the
        // radar would be drawing peers at the wrong distance from centre.
        val (_, straightAhead) = worldDirection(identity, azimuth = 0f)
        val (x, y, z) = worldDirection(yawed90, azimuth = 0f)

        assertEquals(sqrt(straightAhead * straightAhead), sqrt(x * x + y * y + z * z), TOLERANCE)
    }

    @Test
    fun elevationLiftsTheDirectionOutOfTheHorizontalPlane() {
        val (_, _, level) = worldDirection(identity, azimuth = 0f, elevation = 0f)
        val (_, _, raised) = worldDirection(identity, azimuth = 0f, elevation = 45f)

        assertEquals(0f, level, TOLERANCE)
        assertEquals(SQRT_HALF, raised, TOLERANCE)
    }

    @Test
    fun aPeerWithNoMeasuredBearingPointsStraightAhead() {
        // A peer seen only over BLE has distance but no direction. It must land
        // somewhere harmless rather than at a random spot on the ring.
        val (x, y, z) = worldDirection(identity, azimuth = null, elevation = null)

        assertEquals(0f, x, TOLERANCE)
        assertEquals(1f, y, TOLERANCE)
        assertEquals(0f, z, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
        const val SQRT_HALF = 0.70710678f
    }
}
