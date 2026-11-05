package com.combustiblelemonlabs.pingring.data.ranging
import com.combustiblelemonlabs.pingring.data.ranging.UwbRetentionPolicy
import com.combustiblelemonlabs.pingring.data.ranging.UwbRetentionPolicy.RetainedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for holding a UWB session open across a transport drop.
 *
 * The cases that matter most are the negative ones: a member who left, or whose
 * ring has ended, must never stay rangeable. Getting that wrong is worse than
 * the slow reconnection the retention is meant to avoid.
 */
class UwbRetentionPolicyTest {

    private fun retain(
        peerId: String = "peer-a",
        rangedTargetId: String? = "peer-a",
        address: String? = "0x1234",
        ringActive: Boolean = true,
        ringOver: Boolean = false,
    ) = UwbRetentionPolicy.retainOnDisconnect(
        peerId = peerId,
        rangedTargetId = rangedTargetId,
        knownUwbAddress = address,
        ringIsActive = ringActive,
        ringIsOver = ringOver,
    )

    @Test
    fun `retains the peer currently being ranged`() {
        assertEquals("peer-a", retain())
    }

    @Test
    fun `does not retain a peer that is not the one being ranged`() {
        // Holding a spare session open would spend the single UWB session on
        // someone the user is not even looking at.
        assertNull(retain(rangedTargetId = "peer-b"))
    }

    @Test
    fun `does not retain a peer that never shared a UWB address`() {
        assertNull(retain(address = null))
        assertNull(retain(address = "   "))
    }

    @Test
    fun `does not retain when nothing is being ranged`() {
        assertNull(retain(rangedTargetId = null))
    }

    // --- The cases that must never retain, whatever else is true ---

    @Test
    fun `does not retain once the ring has ended`() {
        assertNull(retain(ringOver = true))
    }

    @Test
    fun `does not retain when there is no active ring`() {
        assertNull(retain(ringActive = false))
    }

    @Test
    fun `a peer that left is never retained even while fully ranged`() {
        // The exact scenario this whole policy exists to get right: a member who
        // left must not stay rangeable, and there is no ordering of the
        // arguments that gets around it.
        assertNull(
            UwbRetentionPolicy.retainOnDisconnect(
                peerId = "leaver",
                rangedTargetId = "leaver",
                knownUwbAddress = "0xabcd",
                ringIsActive = true,
                ringIsOver = true,
            )
        )
    }

    @Test
    fun `retained peer survives a short transport gap`() {
        val droppedAt = 1_000L
        assertTrue(UwbRetentionPolicy.isWithinRetentionWindow(droppedAt, droppedAt + 1_000))
    }

    @Test
    fun `retained peer is released once the window lapses`() {
        val droppedAt = 1_000L
        val afterWindow = droppedAt + UwbRetentionPolicy.RETENTION_MILLIS + 1
        assertFalse(UwbRetentionPolicy.isWithinRetentionWindow(droppedAt, afterWindow))
    }

    @Test
    fun `UWB direction becomes last known after measurement silence`() {
        val measuredAt = 10_000L
        assertFalse(
            UwbRetentionPolicy.isDirectionLastKnown(
                measuredAt,
                measuredAt + UwbRetentionPolicy.DIRECTION_FRESHNESS_MILLIS - 1,
            )
        )
        assertTrue(
            UwbRetentionPolicy.isDirectionLastKnown(
                measuredAt,
                measuredAt + UwbRetentionPolicy.DIRECTION_FRESHNESS_MILLIS,
            )
        )
    }

    @Test
    fun `last known direction expires after its display window`() {
        val recordedAt = 10_000L
        assertTrue(
            UwbRetentionPolicy.isLastKnownDirectionWithinWindow(
                recordedAt,
                recordedAt + UwbRetentionPolicy.LAST_KNOWN_DIRECTION_TTL_MILLIS - 1,
            )
        )
        assertFalse(
            UwbRetentionPolicy.isLastKnownDirectionWithinWindow(
                recordedAt,
                recordedAt + UwbRetentionPolicy.LAST_KNOWN_DIRECTION_TTL_MILLIS,
            )
        )
    }

    @Test
    fun `a live UWB session is reported as active`() {
        assertEquals(
            RetainedState.UWB_ACTIVE,
            UwbRetentionPolicy.classify(millisSinceUwbMeasurement = 500, uwbSilentAfterMillis = 5_000)
        )
    }

    @Test
    fun `a silent UWB session is reported as silent`() {
        assertEquals(
            RetainedState.UWB_SILENT,
            UwbRetentionPolicy.classify(millisSinceUwbMeasurement = 9_000, uwbSilentAfterMillis = 5_000)
        )
    }

    @Test
    fun `a silent retained peer is reported as unreachable`() {
        // The far end stopping ranging is indistinguishable from it walking
        // away, so the app reports unreachability rather than tracking a ghost.
        assertTrue(UwbRetentionPolicy.shouldReportAsUnreachable(RetainedState.UWB_SILENT, ringIsOver = false))
    }

    @Test
    fun `an active retained peer is not reported as unreachable`() {
        assertFalse(UwbRetentionPolicy.shouldReportAsUnreachable(RetainedState.UWB_ACTIVE, ringIsOver = false))
    }

    @Test
    fun `an ended ring reports unreachable regardless of session state`() {
        assertTrue(UwbRetentionPolicy.shouldReportAsUnreachable(RetainedState.UWB_ACTIVE, ringIsOver = true))
    }
}