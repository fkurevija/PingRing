package com.fkurevija.pingring

import com.fkurevija.pingring.data.BleSignalWarningTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hysteretic "weak BLE signal" warning.
 *
 * Hysteresis is the whole design: a warning that flickered with every reading
 * would be worse than none, so it needs a weak signal *sustained* before it
 * fires and a recovered one before it clears. The two thresholds are different
 * for exactly that reason, and the branches between them are where the bugs are.
 */
class BleSignalWarningTrackerTest {

    private val warnAt = -85f
    private val recoverAt = -80f
    private val duration = 1_500L

    private fun tracker() = BleSignalWarningTracker(
        warningThresholdDbm = warnAt,
        recoveryThresholdDbm = recoverAt,
        warningDurationMillis = duration,
    )

    @Test
    fun `a healthy signal never warns`() {
        val t = tracker()
        assertFalse(t.update("ana", -50f, 0L))
        assertFalse(t.update("ana", -50f, 10_000L))
    }

    @Test
    fun `one weak reading is not enough to warn`() {
        // The duration exists so a single dip - somebody walking past - does not
        // raise a warning.
        val t = tracker()

        assertFalse(t.update("ana", -90f, 0L))
        assertFalse(t.update("ana", -90f, 1_499L))
    }

    @Test
    fun `a weak signal held for the duration warns`() {
        val t = tracker()

        t.update("ana", -90f, 0L)

        assertTrue(t.update("ana", -90f, 1_500L))
    }

    @Test
    fun `a recovered signal clears the warning`() {
        val t = tracker()
        t.update("ana", -90f, 0L)
        assertTrue(t.update("ana", -90f, 1_500L))

        // Recovery is a different, higher threshold than the one that raised it.
        assertFalse(t.update("ana", -79f, 1_600L))
    }

    @Test
    fun `a reading between the two thresholds holds the current state`() {
        // The gap between -85 and -80 is the hysteresis band: too strong to be
        // counted as recovered, too weak to start a new warning. A peer already
        // warning stays warning; a healthy peer stays quiet and does not start
        // its clock.
        val warning = tracker()
        warning.update("ana", -90f, 0L)
        assertTrue(warning.update("ana", -90f, 1_500L))
        assertTrue(warning.update("ana", -82f, 2_000L))

        val quiet = tracker()
        assertFalse(quiet.update("ana", -82f, 0L))
        assertFalse(quiet.update("ana", -82f, 5_000L))
    }

    @Test
    fun `a peer that recovers then goes weak again must wait the full duration`() {
        // The timer has to restart, or a signal oscillating around the threshold
        // would fire a warning without ever having been weak for long.
        val t = tracker()
        t.update("ana", -90f, 0L)
        assertTrue(t.update("ana", -90f, 1_500L))
        t.update("ana", -79f, 2_000L)

        assertFalse(t.update("ana", -90f, 2_100L))
        assertTrue(t.update("ana", -90f, 3_600L))
    }

    @Test
    fun `a missing reading holds the current state rather than clearing it`() {
        // A peer we have stopped hearing is not a peer that has recovered. The
        // expiry of presence is a separate concern; silently clearing the
        // warning here would drop it a moment early.
        val t = tracker()
        t.update("ana", -90f, 0L)
        assertTrue(t.update("ana", -90f, 1_500L))

        assertTrue(t.update("ana", null, 2_000L))
    }

    @Test
    fun `one peer going weak never warns about another`() {
        val t = tracker()
        t.update("ana", -90f, 0L)

        assertFalse(t.update("bo", -90f, 0L))
        assertTrue(t.update("ana", -90f, 1_500L))
    }

    @Test
    fun `removing a peer forgets it completely`() {
        val t = tracker()
        t.update("ana", -90f, 0L)
        assertTrue(t.update("ana", -90f, 1_500L))

        t.remove("ana")

        // No carried-over clock: the same weak signal starts from zero again.
        assertFalse(t.update("ana", -90f, 1_600L))
    }

    @Test
    fun `clearing forgets every peer`() {
        val t = tracker()
        t.update("ana", -90f, 0L)
        t.update("bo", -90f, 0L)
        t.update("ana", -90f, 1_500L)

        t.clear()

        assertFalse(t.update("ana", -90f, 2_000L))
        assertFalse(t.update("bo", -90f, 2_000L))
    }
}