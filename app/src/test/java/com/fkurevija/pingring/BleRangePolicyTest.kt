package com.fkurevija.pingring

import com.fkurevija.pingring.data.BleAdvertisementPayload
import com.fkurevija.pingring.data.BleRangePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of [BleRangePolicy] that are decided by arithmetic rather than by a
 * radio.
 *
 * What is deliberately *not* here: the capability probes
 * ([BleRangePolicy.supportsExtendedAdvertising], [BleRangePolicy.isCodedPhyUsable])
 * and the settings builders. Those need a real `BluetoothAdapter` and real
 * `ScanSettings`/`AdvertiseSettings` objects, which a JVM unit test cannot supply.
 * Their getters (`matchMode`, `numOfMatches`, `isLegacy`, `advertiseMode`) are
 * also `@hide` in the public SDK, so they could not be read back even with a
 * framework simulation in place. The arithmetic below is what the app's range
 * behaviour actually rests on, and it is fully checkable here.
 */
class BleRangePolicyTest {

    @Test
    fun `the service data fits in a legacy advertisement`() {
        // Growing the payload past the legacy limit makes the advert silently
        // untransmittable, which presents as a total range failure with no error
        // anywhere. This is a real budget, not a comment: 31 bytes is what legacy
        // advertising can carry and 20 is what PingRing now sends - the event
        // code and its version byte are what the extra seven bought.
        assertTrue(BleRangePolicy.fitsInLegacyAdvertising())
        assertEquals(31, BleRangePolicy.MAX_LEGACY_SERVICE_DATA_BYTES)
        assertEquals(20, BleRangePolicy.SERVICE_DATA_BYTES)
        assertEquals(
            BleAdvertisementPayload.TOTAL_BYTES,
            BleRangePolicy.SERVICE_DATA_BYTES
        )
    }

    @Test
    fun `the service data has room to grow before it breaks`() {
        // Not padding for its own sake: a future field that consumes the headroom
        // should fail a test rather than silently break range on device.
        assertEquals(
            BleRangePolicy.MAX_LEGACY_SERVICE_DATA_BYTES - BleRangePolicy.SERVICE_DATA_BYTES,
            BleRangePolicy.LEGACY_HEADROOM_BYTES
        )
        assertTrue(
            "exhausting the legacy budget needs a decision, not a build",
            BleRangePolicy.LEGACY_HEADROOM_BYTES > 0
        )
    }

    @Test
    fun `one scan pass is used when extended advertising is unavailable`() {
        // Two passes contend for one adapter and cost real power, so the second is
        // only justified when it would actually hear something new.
        assertEquals(1, BleRangePolicy.scanPassCount(supportsExtended = false))
    }

    @Test
    fun `a second scan pass is used when extended advertising is available`() {
        assertEquals(2, BleRangePolicy.scanPassCount(supportsExtended = true))
    }

    @Test
    fun `the pass count never exceeds two`() {
        // More than two passes makes throughput worse rather than better, since
        // every request queues behind the others on the same adapter.
        assertTrue(BleRangePolicy.scanPassCount(true) <= 2)
        assertTrue(BleRangePolicy.scanPassCount(false) <= 2)
    }

    @Test
    fun `the pass count never drops below one`() {
        // A pass count of zero would mean the peer is never scanned for at all,
        // which presents as a device that joins nothing and reports nothing.
        assertTrue(BleRangePolicy.scanPassCount(true) >= 1)
        assertTrue(BleRangePolicy.scanPassCount(false) >= 1)
    }

    @Test
    fun `the pass count is monotonic in extended advertising support`() {
        // Gaining a capability can only ever add work, never remove it.
        assertTrue(
            BleRangePolicy.scanPassCount(supportsExtended = true) >
                BleRangePolicy.scanPassCount(supportsExtended = false)
        )
    }
}