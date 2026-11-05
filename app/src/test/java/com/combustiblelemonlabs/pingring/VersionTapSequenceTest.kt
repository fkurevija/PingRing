package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.ui.screens.VersionTapSequence
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTapSequenceTest {
    @Test
    fun sevenTapsCountDownToZeroAndResetTheSequence() {
        // The number returned is what decides when the prompt opens, so it
        // has to be the number of taps actually left: an off-by-one here
        // would open the prompt one tap before (or after) it should.
        val sequence = VersionTapSequence()
        assertEquals(6, sequence.tap())
        assertEquals(5, sequence.tap())
        assertEquals(4, sequence.tap())
        assertEquals(3, sequence.tap())
        assertEquals(2, sequence.tap())
        assertEquals(1, sequence.tap())
        assertEquals(0, sequence.tap())

        // 0 is also the signal to open the prompt, so the sequence must start
        // counting again rather than keep reporting a completed run.
        assertEquals(6, sequence.tap())
    }

    @Test
    fun nothingButCompletionOrLeavingTheScreenRestartsTheCount() {
        // There is no clock in the sequence: a tap minutes after the previous
        // one keeps the progress it built. The old 2-second window punished a
        // deliberate tapper pausing between taps, so a regression that
        // reintroduces any gap check has to fail here (it would need a
        // timestamp to do so, so it also cannot compile against this test).
        //
        // A fresh instance stands for the only other reset there is: the About
        // screen owns the sequence in `remember`, so leaving the screen drops
        // it and coming back starts over.
        val sequence = VersionTapSequence()
        assertEquals(6, sequence.tap())
        assertEquals(5, sequence.tap())
        assertEquals(4, sequence.tap())
        assertEquals(6, VersionTapSequence().tap())
    }
}