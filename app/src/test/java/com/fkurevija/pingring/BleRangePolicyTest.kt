package com.fkurevija.pingring

import com.fkurevija.pingring.data.BleRangePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BLE reach configuration.
 *
 * These assert only the decisions that are pure functions. The
 * [android.bluetooth.le.ScanSettings] and [android.bluetooth.le.AdvertiseSettings]
 * objects the policy builds cannot be constructed in a JVM unit test - the
 * android.jar stubs throw on every builder call - so what they actually contain
 * has to be checked on a device, or from the logcat line BleRangePolicy emits
 * on every scan start.
 */
class BleRangePolicyTest {

    @Test
    fun aSecondPassIsOnlyUsedWhenExtendedAdvertisingIsAvailable() {
        assertEquals(1, BleRangePolicy.scanPassCount(supportsExtended = false))
        assertEquals(2, BleRangePolicy.scanPassCount(supportsExtended = true))
    }

    @Test
    fun neverMoreThanTwoScanPasses() {
        // Beyond two the requests contend for the same adapter and make
        // throughput worse, not better.
        assertTrue(BleRangePolicy.scanPassCount(true) <= 2)
        assertTrue(BleRangePolicy.scanPassCount(false) <= 2)
    }

    @Test
    fun theAdvertPayloadStillFitsInALegacyPacket() {
        // Growing the service data past 31 bytes makes the advert silently
        // untransmittable, which presents as a total range failure with no
        // error anywhere - so this bound is asserted rather than assumed.
        assertTrue(
            "service data is ${BleRangePolicy.SERVICE_DATA_BYTES} bytes, limit is " +
                "${BleRangePolicy.MAX_LEGACY_SERVICE_DATA_BYTES}",
            BleRangePolicy.fitsInLegacyAdvertising()
        )
    }

    @Test
    fun theServicePayloadMatchesWhatTheRepositoryActuallySends() {
        // 4 bytes event hash + 1 byte flags + 8 bytes peer id.
        assertEquals(13, BleRangePolicy.SERVICE_DATA_BYTES)
    }
}
