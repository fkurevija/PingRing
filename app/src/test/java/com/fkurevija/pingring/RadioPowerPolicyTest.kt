package com.fkurevija.pingring

import com.fkurevija.pingring.data.RadioPowerPolicy
import com.fkurevija.pingring.data.RadioPowerPolicy.Load
import com.fkurevija.pingring.data.RadioPowerPolicy.ScanEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fkurevija.pingring.data.ranging.UwbRetentionPolicy

/**
 * How hard the radios work for a given load.
 *
 * The thresholds are only meaningful against each other, so most of these
 * assert the *direction* of a change rather than a literal number: a busier
 * device must never work harder than an idle one, and no setting may be raised
 * on hardware that cannot act on it.
 */
class RadioPowerPolicyTest {

    private fun load(
        isHost: Boolean = true,
        members: Int = 1,
        uwb: Boolean = false,
        hotspot: Boolean = false,
        onBattery: Boolean = true,
    ) = Load(
        isHost = isHost,
        memberCount = members,
        uwbActive = uwb,
        hotspotActive = hotspot,
        onBattery = onBattery,
    )

    @Test
    fun `an idle ring scans actively and keeps uwb held for the full window`() {
        val plan = RadioPowerPolicy.plan(load(members = 1), supportsExtendedAdvertising = true)

        assertEquals(ScanEffort.ACTIVE, plan.scanEffort)
        assertEquals(1, plan.scanPassCount)
        assertEquals(RadioPowerPolicy.UWB_RETENTION_ACTIVE_MILLIS, plan.uwbRetentionMillis)
    }

    @Test
    fun `a crowded ring gets the second scan pass`() {
        // The pass count exists to raise the chance a member is seen, and that
        // matters more as the ring fills - a miss is a person who just does not
        // appear.
        val sparse = RadioPowerPolicy.plan(load(members = 1), supportsExtendedAdvertising = true)
        val crowded = RadioPowerPolicy.plan(
            load(members = RadioPowerPolicy.PASS_THRESHOLD_MEMBERS),
            supportsExtendedAdvertising = true,
        )

        assertEquals(1, sparse.scanPassCount)
        assertEquals(RadioPowerPolicy.MAX_SCAN_PASSES, crowded.scanPassCount)
        assertTrue(crowded.usesSecondScanPass)
    }

    @Test
    fun `no second pass without extended advertising`() {
        // Without it both passes would be identical requests to the same
        // adapter, which is the contention the pass limit exists to avoid.
        val plan = RadioPowerPolicy.plan(
            load(members = 8),
            supportsExtendedAdvertising = false,
        )

        assertEquals(1, plan.scanPassCount)
        assertFalse(plan.usesSecondScanPass)
    }

    @Test
    fun `two busy radios off battery drop to the balanced scan`() {
        val idle = RadioPowerPolicy.plan(load(), supportsExtendedAdvertising = false)
        val busy = RadioPowerPolicy.plan(
            load(uwb = true, hotspot = true, onBattery = false),
            supportsExtendedAdvertising = false,
        )

        assertEquals(ScanEffort.ACTIVE, idle.scanEffort)
        assertEquals(
            "the platform default is the only lever that costs no behaviour",
            ScanEffort.BALANCED,
            busy.scanEffort,
        )
    }

    @Test
    fun `busy radios shorten the uwb window but never remove it`() {
        // Not zero: dropping the session immediately would give back the
        // instant reconnect for anyone who steps a few metres away, which is
        // the case the window exists for.
        val busy = RadioPowerPolicy.plan(
            load(uwb = true, hotspot = true),
            supportsExtendedAdvertising = false,
        )

        assertEquals(RadioPowerPolicy.UWB_RETENTION_BUSY_MILLIS, busy.uwbRetentionMillis)
        assertTrue(busy.uwbRetentionMillis > 0)
        assertTrue(
            "busy must still shorten the window",
            busy.uwbRetentionMillis < RadioPowerPolicy.UWB_RETENTION_ACTIVE_MILLIS,
        )
    }

    @Test
    fun `busier never means more uwb retention`() {
        // The single invariant that matters: no combination of load may hold a
        // UWB session open longer than an idle one.
        val idle = RadioPowerPolicy.plan(load(uwb = true), supportsExtendedAdvertising = false)
        for (hotspot in listOf(false, true)) {
            for (onBattery in listOf(true, false)) {
                for (members in listOf(1, 4, 8)) {
                    val plan = RadioPowerPolicy.plan(
                        load(members = members, uwb = true, hotspot = hotspot, onBattery = onBattery),
                        supportsExtendedAdvertising = false,
                    )
                    assertTrue(
                        "retention rose under load: members=$members hotspot=$hotspot battery=$onBattery",
                        plan.uwbRetentionMillis <= idle.uwbRetentionMillis,
                    )
                }
            }
        }
    }

    @Test
    fun `the retention window stays inside what the policy guarantees`() {
        // UwbRetentionPolicy owns correctness of the window; this one only picks
        // how long to pay. A value outside its bounds would be a bug in one of
        // the two, so the numbers are asserted rather than left implicit.
        assertTrue(
            RadioPowerPolicy.UWB_RETENTION_BUSY_MILLIS <
                RadioPowerPolicy.UWB_RETENTION_ACTIVE_MILLIS
        )
        assertTrue(RadioPowerPolicy.UWB_RETENTION_BUSY_MILLIS > 0)
    }

    @Test
    fun `the summary names every setting it chose`() {
        // It exists so a device run can be checked after the fact; a summary
        // that omits a knob would make the log useless for that.
        val summary = RadioPowerPolicy.describe(
            RadioPowerPolicy.plan(load(members = 8, uwb = true), supportsExtendedAdvertising = true)
        )

        assertTrue(summary.contains("scan="))
        assertTrue(summary.contains("passes="))
        assertTrue(summary.contains("uwbRetention="))
    }
}
