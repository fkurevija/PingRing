package com.fkurevija.pingring.data.ranging
import com.fkurevija.pingring.data.BleIdentityAdvertisement
import com.fkurevija.pingring.data.ranging.BleRangePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `signed service data requires extended advertising`() {
        assertEquals(31, BleRangePolicy.MAX_LEGACY_SERVICE_DATA_BYTES)
        assertEquals(98, BleRangePolicy.SERVICE_DATA_BYTES)
        assertEquals(BleIdentityAdvertisement.TOTAL_BYTES, BleRangePolicy.SERVICE_DATA_BYTES)
        assertTrue(BleRangePolicy.fitsInExtendedAdvertising())
        assertFalse(BleRangePolicy.fitsInLegacyAdvertising())
        assertTrue(BleRangePolicy.SERVICE_DATA_BYTES > BleRangePolicy.MAX_LEGACY_SERVICE_DATA_BYTES)
        assertTrue(BleRangePolicy.MIN_EXTENDED_AD_DATA_BYTES > BleRangePolicy.SERVICE_DATA_BYTES)
    }

    @Test
    fun `legacy V4 fixture remains within its historical budget`() {
        assertEquals(28, BleRangePolicy.LEGACY_V4_SERVICE_DATA_BYTES)
        assertEquals(
            BleRangePolicy.MAX_LEGACY_SERVICE_DATA_BYTES - BleRangePolicy.LEGACY_V4_SERVICE_DATA_BYTES,
            BleRangePolicy.LEGACY_HEADROOM_BYTES
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