package com.fkurevija.pingring

import com.fkurevija.pingring.data.DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.JOIN_APPROVAL_GRACE_MILLIS
import com.fkurevija.pingring.data.JOIN_REQUEST_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.MAX_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.MIN_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.normalizeJoinWaitTimeoutMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join timeout window, and the invariant the two halves of it depend on.
 *
 * Grouped by the failure each case prevents rather than by function, because the
 * destructive case is the one a later "let people tune this lower" edit would
 * reintroduce: shorten the guest's wait below the host's approval window and every
 * refused join is reported to the guest as a failure to reach anybody.
 */
class JoinWaitTimeoutTest {

    @Test
    fun `a guest cannot give up before the host has had a chance to refuse`() {
        // The invariant the whole feature hangs on. A code-only join is held by the
        // host, so the host gives up first and its refusal travels back. A guest that
        // gave up first would have already shown "couldn't reach anybody" for a
        // decision the host actually made.
        assertTrue(
            "the guest's wait must outlast the host's approval window",
            MIN_JOIN_WAIT_TIMEOUT_MILLIS > JOIN_REQUEST_TIMEOUT_MILLIS,
        )
        assertTrue(
            "the floor must leave room for the refusal to travel back",
            MIN_JOIN_WAIT_TIMEOUT_MILLIS >= JOIN_REQUEST_TIMEOUT_MILLIS + JOIN_APPROVAL_GRACE_MILLIS,
        )
    }

    @Test
    fun `the default waits long enough to outlast an approval`() {
        assertTrue(
            "the default must not race the host",
            DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS > JOIN_REQUEST_TIMEOUT_MILLIS,
        )
        assertEquals(DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS, normalizeJoinWaitTimeoutMillis(0L))
    }

    @Test
    fun `a configured wait is clamped to the supported range`() {
        // Zero and Long.MAX_VALUE both have to land somewhere sane rather than
        // producing a zero-length wait that fails every join or one that never
        // expires. The repository reads a stored value that may predate this
        // setting, so an absent or nonsensical one is normal, not exceptional.
        assertEquals(MIN_JOIN_WAIT_TIMEOUT_MILLIS, normalizeJoinWaitTimeoutMillis(0L))
        assertEquals(MIN_JOIN_WAIT_TIMEOUT_MILLIS, normalizeJoinWaitTimeoutMillis(-5_000L))
        assertEquals(90_000L, normalizeJoinWaitTimeoutMillis(90_000L))
        assertEquals(MAX_JOIN_WAIT_TIMEOUT_MILLIS, normalizeJoinWaitTimeoutMillis(Long.MAX_VALUE))
    }

    @Test
    fun `every value the slider can produce survives normalisation`() {
        // The slider writes seconds in 15s steps across the whole range. If any of
        // them normalised to something else, the stored value would not match the
        // position shown - the slider would appear to snap back or to lie.
        val sliderValues = (MIN_JOIN_WAIT_TIMEOUT_MILLIS / 1_000L..MAX_JOIN_WAIT_TIMEOUT_MILLIS / 1_000L step 15L)
        sliderValues.forEach { seconds ->
            assertEquals(
                "the slider can produce $seconds s, so it must round-trip",
                seconds * 1_000L,
                normalizeJoinWaitTimeoutMillis(seconds * 1_000L),
            )
        }
    }

    @Test
    fun `the range is ordered and spans the minutes it claims to`() {
        assertTrue(MIN_JOIN_WAIT_TIMEOUT_MILLIS < MAX_JOIN_WAIT_TIMEOUT_MILLIS)
        // One to three minutes. Anything much wider and the top of the slider is a
        // wait nobody should sit through; much narrower and there is nothing to tune.
        assertEquals(60_000L, MIN_JOIN_WAIT_TIMEOUT_MILLIS)
        assertEquals(180_000L, MAX_JOIN_WAIT_TIMEOUT_MILLIS)
    }
}