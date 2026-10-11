package com.fkurevija.pingring.data.ranging
import com.fkurevija.pingring.data.ranging.AdvancedSensorFusionFilter
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSensorFusionFilterTest {

    @Test
    fun initialMeasurementSeedsDistance() {
        val filter = AdvancedSensorFusionFilter()
        val result = filter.update(5.0f, 1000L, RangingSourcePolicy.RangingSource.BLE_RSSI)
        assertEquals(5.0f, result, 0.001f)
        assertEquals(5.0f, filter.distanceMeters, 0.001f)
    }

    @Test
    fun highPrecisionUwbMeasurementWeightIsTrustedMore() {
        val filter = AdvancedSensorFusionFilter(initialDistanceMeters = 5.0f)
        filter.update(5.0f, 1000L, RangingSourcePolicy.RangingSource.UWB)

        // Sudden noisy measurement at 10m
        val filtered = filter.update(10.0f, 2000L, RangingSourcePolicy.RangingSource.UWB)

        // With UWB (R = 0.05), the filter responds significantly faster toward 10.0
        assertTrue(filtered > 7.0f)
    }

    @Test
    fun noisyBleMeasurementIsSmoothedMoreHeavily() {
        val filter = AdvancedSensorFusionFilter(initialDistanceMeters = 5.0f)
        filter.update(5.0f, 1000L, RangingSourcePolicy.RangingSource.BLE_RSSI)

        // Sudden noisy measurement at 10m
        val filtered = filter.update(10.0f, 2000L, RangingSourcePolicy.RangingSource.BLE_RSSI)

        // With BLE (R = 4.0), the filter suppresses noisy jumps more heavily
        assertTrue(filtered < 7.0f)
    }

    @Test
    fun invalidAndNegativeDistancesAreIgnored() {
        val filter = AdvancedSensorFusionFilter()
        filter.update(5.0f, 1000L)
        val result = filter.update(-3.0f, 2000L)
        assertEquals(5.0f, result, 0.001f)
    }

    @Test
    fun resetRestoresInitialState() {
        val filter = AdvancedSensorFusionFilter()
        filter.update(5.0f, 1000L)
        filter.update(8.0f, 2000L)
        filter.reset(2.0f)
        assertEquals(2.0f, filter.distanceMeters, 0.001f)
        assertEquals(0f, filter.velocityMps, 0.001f)
    }
}
