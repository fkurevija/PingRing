package com.fkurevija.pingring

import com.fkurevija.pingring.data.BeaconWaveform
import com.fkurevija.pingring.data.BeaconAlertPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value a repeated beacon alert is played from.
 *
 * The equality override is the point of this class existing, and it was written
 * to hold two array-bearing objects equal by content. Kotlin's generated equals
 * compares a `LongArray` with `===`, so without the override two identical
 * waveforms compare unequal - which is invisible in a test that only reads the
 * fields back.
 */
class BeaconWaveformTest {

    @Test
    fun `two waveforms with the same timings are equal`() {
        val a = BeaconWaveform(longArrayOf(0L, 100L, 50L, 100L), repeatFromIndex = 0)
        val b = BeaconWaveform(longArrayOf(0L, 100L, 50L, 100L), repeatFromIndex = 0)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `the same timings looping from a different index are not equal`() {
        // The loop point is the whole difference between "buzz once" and "buzz
        // forever", so it cannot be collapsed into the timings comparison.
        val once = BeaconWaveform(longArrayOf(0L, 100L), repeatFromIndex = -1)
        val looping = BeaconWaveform(longArrayOf(0L, 100L), repeatFromIndex = 0)

        assertNotEquals(once, looping)
    }

    @Test
    fun `waveforms of different lengths are not equal`() {
        assertNotEquals(
            BeaconWaveform(longArrayOf(0L, 100L), -1),
            BeaconWaveform(longArrayOf(0L, 100L, 50L), -1),
        )
    }

    @Test
    fun `equality does not depend on the loop index when both match`() {
        val a = BeaconWaveform(longArrayOf(0L, 100L), 1)
        val b = BeaconWaveform(longArrayOf(0L, 100L), 1)

        assertEquals(a, b)
    }

    @Test
    fun `equal waveforms do not share their timings array`() {
        // Equality by content, but the arrays must stay independent - otherwise a
        // caller mutating one waveform would silently change another.
        val a = BeaconWaveform(longArrayOf(0L, 100L), 0)
        val b = BeaconWaveform(longArrayOf(0L, 100L), 0)
        b.timings[1] = 999L

        assertEquals(100L, a.timings[1])
        assertNotEquals(a, b)
    }

    @Test
    fun `a waveform produced by the policy equals a hand-built one`() {
        // The reason equality is overridden: the app compares a waveform it
        // derived against one it holds, and the derived one is built field by
        // field from a LongArray.
        val policy = BeaconAlertPolicy.waveformFor(
            longArrayOf(0L, 100L, 50L, 100L),
            com.fkurevija.pingring.data.BeaconAlertSettings(
                repeat = com.fkurevija.pingring.data.BeaconAlertRepeat.REPEAT,
                repeatGapMillis = 5_000L,
            ),
        )

        assertEquals(
            BeaconWaveform(longArrayOf(0L, 100L, 50L, 100L, 5_000L), 0),
            policy,
        )
    }

    @Test
    fun `a waveform is not equal to null or to another type`() {
        val wave = BeaconWaveform(longArrayOf(0L), -1)

        assertTrue(wave != null)
        assertNotEquals(wave, "not a waveform")
    }
}