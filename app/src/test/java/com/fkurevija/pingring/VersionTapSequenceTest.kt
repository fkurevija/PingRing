package com.fkurevija.pingring

import com.fkurevija.pingring.ui.screens.VersionTapSequence
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTapSequenceTest {
    @Test
    fun sevenQuickTapsCountDownToZeroAndResetTheSequence() {
        // The number returned is what the countdown toast announces, so it has
        // to be the number of taps actually left: an off-by-one here would make
        // the toast promise the prompt one tap before (or after) it arrives.
        val sequence = VersionTapSequence()
        assertEquals(6, sequence.tap(0L))
        assertEquals(5, sequence.tap(100L))
        assertEquals(4, sequence.tap(200L))
        assertEquals(3, sequence.tap(300L))
        assertEquals(2, sequence.tap(400L))
        assertEquals(1, sequence.tap(500L))
        assertEquals(0, sequence.tap(600L))

        // 0 is also the signal to open the prompt, so the sequence must start
        // counting again rather than keep reporting a completed run.
        assertEquals(6, sequence.tap(700L))
    }

    @Test
    fun longGapsAndClockRegressionRestartTheCount() {
        val sequence = VersionTapSequence()
        assertEquals(6, sequence.tap(0L))
        assertEquals(5, sequence.tap(100L))
        // Past MAX_GAP_MILLIS the burst is over and the count restarts: a
        // straggler tap minutes later must not inherit progress from it.
        assertEquals(6, sequence.tap(3_000L))
        // A clock that went backwards is treated as a gap, not as a negative one.
        assertEquals(6, sequence.tap(2_000L))
        repeat(5) { assertEquals(5 - it, sequence.tap(2_100L + it * 100L)) }
        assertEquals(0, sequence.tap(2_600L))
    }

    @Test
    fun onlyTheLastThreeTapsAreWorthAToast() {
        // Exhaustive over every value the sequence can report, plus the
        // impossible ones: a toast on all seven taps would be noise the user
        // taps through, and a countdown starting later than 3 would begin too
        // close to the prompt to read as a countdown.
        val announced = (0..VersionTapSequence.REQUIRED_TAPS)
            .filter { VersionTapSequence.shouldAnnounceCountdown(it) }
        assertEquals(listOf(1, 2, 3), announced)
        assertEquals(false, VersionTapSequence.shouldAnnounceCountdown(-1))
    }
}