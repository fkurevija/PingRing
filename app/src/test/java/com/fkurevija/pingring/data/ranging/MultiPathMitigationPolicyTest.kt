package com.fkurevija.pingring.data.ranging
import com.fkurevija.pingring.data.ranging.MultiPathMitigationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPathMitigationPolicyTest {

    @Test
    fun normalMovementIsAccepted() {
        val corrupted = MultiPathMitigationPolicy.isMultiPathCorrupted(
            currentDistanceMeters = 3.2f,
            historicalAverageMeters = 3.0f,
            rssiDbm = -65,
            angleDegreesDelta = 5f,
        )
        assertFalse(corrupted)
    }

    @Test
    fun suddenLargeJumpWithStrongRssiIsFlaggedAsMultiPath() {
        val corrupted = MultiPathMitigationPolicy.isMultiPathCorrupted(
            currentDistanceMeters = 8.5f,
            historicalAverageMeters = 3.0f,
            rssiDbm = -55,
            angleDegreesDelta = 10f,
        )
        assertTrue(corrupted)
    }
}
