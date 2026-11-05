package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.settings.DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS
import com.combustiblelemonlabs.pingring.data.settings.JOIN_APPROVAL_GRACE_MILLIS
import com.combustiblelemonlabs.pingring.data.JOIN_REQUEST_TIMEOUT_MILLIS
import com.combustiblelemonlabs.pingring.data.settings.MAX_JOIN_WAIT_TIMEOUT_MILLIS
import com.combustiblelemonlabs.pingring.data.settings.MIN_JOIN_WAIT_TIMEOUT_MILLIS
import com.combustiblelemonlabs.pingring.data.settings.normalizeJoinWaitTimeoutMillis
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
    fun `the default still waits long enough to outlast an approval`() {
        // The default, not the minimum. It is the only value used by a member who
        // never opens the setting, so it is the one that has to fit a human: a host
        // who has to notice a request and tap Allow gets 60s, which outlasts the
        // 45s approval window. Lowering the *range* below this is allowed because
        // that is a deliberate choice, but a member who makes no choice must not
        // be given a setting that fails against a host who is present.
        assertTrue(
            "the default must outlast the host's approval window",
            DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS > JOIN_REQUEST_TIMEOUT_MILLIS,
        )
        // A stored zero is clamped to the minimum, not to the default. The two are
        // separate on purpose: zero means "somebody set a value that is no longer
        // in range", and the minimum is the least surprising thing to do with that,
        // where substituting the default would silently override a short choice the
        // member made before the floor moved.
        assertEquals(
            MIN_JOIN_WAIT_TIMEOUT_MILLIS,
            normalizeJoinWaitTimeoutMillis(0L),
        )
        assertEquals(
            DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS,
            normalizeJoinWaitTimeoutMillis(DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS),
        )
    }

    @Test
    fun `a wait shorter than the host's window is allowed on purpose`() {
        // This is the trade, asserted rather than left to a comment. A member may
        // now wait 10s against a host who has 45s to answer, which means giving up
        // while the host is still deciding and reporting "could not reach anybody"
        // for a join that was about to be allowed.
        //
        // It is still the member's own choice and still the default that is safe,
        // so it is permitted deliberately - but the test names the consequence so
        // that a future change here is a decision rather than an accident.
        assertTrue(
            "the range is meant to reach below the host's window",
            MIN_JOIN_WAIT_TIMEOUT_MILLIS < JOIN_REQUEST_TIMEOUT_MILLIS,
        )
        assertEquals(10_000L, normalizeJoinWaitTimeoutMillis(5_000L))
    }

    @Test
    fun `the host's own window is unaffected by how long a guest is willing to wait`() {
        // The margin is still load-bearing even though the guest can now wait less
        // than it. A host who says no calls rejectConnection, which fails the
        // guest's socket immediately - so a refusal reaches a short-waiting guest
        // promptly without the guest having to sit out a longer window. This is why
        // the grace cannot be dropped just because the floor moved.
        assertTrue(
            "the host must still outlast nothing but have room to answer",
            JOIN_APPROVAL_GRACE_MILLIS > 0L,
        )
        assertTrue(
            "a refusal must have time to travel back",
            JOIN_REQUEST_TIMEOUT_MILLIS > 0L,
        )
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
        // The slider writes seconds in 5s steps across the whole range. If any of
        // them normalised to something else, the stored value would not match the
        // position shown - the slider would appear to snap back or to lie.
        val sliderValues = (MIN_JOIN_WAIT_TIMEOUT_MILLIS / 1_000L..MAX_JOIN_WAIT_TIMEOUT_MILLIS / 1_000L step 5L)
        sliderValues.forEach { seconds ->
            assertEquals(
                "the slider can produce $seconds s, so it must round-trip",
                seconds * 1_000L,
                normalizeJoinWaitTimeoutMillis(seconds * 1_000L),
            )
        }
    }

    @Test
    fun `the range is ordered and spans ten seconds to two minutes`() {
        assertTrue(MIN_JOIN_WAIT_TIMEOUT_MILLIS < MAX_JOIN_WAIT_TIMEOUT_MILLIS)
        // The stated range. Both ends are meant to be reachable: 10s for a host
        // who is present and answering, 120s for one who is busy, and 60s in
        // between as the default for somebody who has not thought about it.
        assertEquals(10_000L, MIN_JOIN_WAIT_TIMEOUT_MILLIS)
        assertEquals(120_000L, MAX_JOIN_WAIT_TIMEOUT_MILLIS)
        assertTrue(
            "the default must sit inside the range the slider offers",
            DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS >= MIN_JOIN_WAIT_TIMEOUT_MILLIS &&
                DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS <= MAX_JOIN_WAIT_TIMEOUT_MILLIS,
        )
    }
}