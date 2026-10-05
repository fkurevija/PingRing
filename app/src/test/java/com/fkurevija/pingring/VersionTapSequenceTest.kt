package com.fkurevija.pingring

import com.fkurevija.pingring.ui.screens.VersionTapSequence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTapSequenceTest {
    @Test
    fun sevenQuickTapsUnlockAndResetTheSequence() {
        val sequence = VersionTapSequence()
        repeat(6) { assertFalse(sequence.tap(it * 100L)) }
        assertTrue(sequence.tap(600L))
        assertFalse(sequence.tap(700L))
    }

    @Test
    fun longGapsAndClockRegressionRestartTheCount() {
        val sequence = VersionTapSequence()
        repeat(6) { assertFalse(sequence.tap(it * 100L)) }
        assertFalse(sequence.tap(3_000L))
        assertFalse(sequence.tap(2_000L))
        repeat(5) { assertFalse(sequence.tap(2_100L + it * 100L)) }
        assertTrue(sequence.tap(2_600L))
    }
}