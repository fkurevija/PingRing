package com.combustiblelemonlabs.pingring.data.ranging
import com.combustiblelemonlabs.pingring.data.ranging.WifiAnchorDirectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.radar.GnssFixDto

class WifiAnchorDirectionPolicyTest {
    private val origin = GnssFixDto(
        latitude = 0.0,
        longitude = 0.0,
        accuracyMeters = 0.5f,
        ageMillis = 0L,
    )

    @Test
    fun `anchor follows the captured compass heading`() {
        val eastAnchor = WifiAnchorDirectionPolicy.createAnchor(
            fix = origin,
            magneticHeadingDegrees = 90f,
            declinationDegrees = 0f,
            distanceMeters = 10f,
        )!!

        assertEquals(0.0, eastAnchor.latitude, 0.000001)
        assertTrue(eastAnchor.longitude > origin.longitude)
    }

    @Test
    fun `bearing changes when the host moves relative to the fixed anchor`() {
        val anchor = WifiAnchorDirectionPolicy.createAnchor(
            fix = origin,
            magneticHeadingDegrees = 0f,
            declinationDegrees = 0f,
            distanceMeters = 10f,
        )!!
        val hostMovedEast = origin.copy(longitude = 0.00009)

        val estimate = WifiAnchorDirectionPolicy.estimate(
            localFix = hostMovedEast,
            anchor = anchor,
            magneticHeadingDegrees = 0f,
            declinationDegrees = 0f,
        )

        assertNotNull(estimate)
        assertEquals(315f, estimate!!.azimuthDegrees, 2f)
    }

    @Test
    fun `bearing is relative to the current phone heading`() {
        val anchor = WifiAnchorDirectionPolicy.createAnchor(
            fix = origin,
            magneticHeadingDegrees = 0f,
            declinationDegrees = 0f,
            distanceMeters = 10f,
        )!!

        val estimate = WifiAnchorDirectionPolicy.estimate(
            localFix = origin.copy(latitude = -0.00009),
            anchor = anchor,
            magneticHeadingDegrees = 90f,
            declinationDegrees = 0f,
        )

        assertNotNull(estimate)
        assertEquals(270f, estimate!!.azimuthDegrees, 2f)
    }

    @Test
    fun `stale fixes or unusable anchors do not produce a bearing`() {
        val anchor = WifiAnchorDirectionPolicy.createAnchor(
            fix = origin,
            magneticHeadingDegrees = 0f,
            declinationDegrees = 0f,
            distanceMeters = 10f,
        )!!

        assertNull(
            WifiAnchorDirectionPolicy.createAnchor(
                fix = origin.copy(ageMillis = 10_001L),
                magneticHeadingDegrees = 0f,
                declinationDegrees = 0f,
                distanceMeters = 10f,
            )
        )
        assertNull(
            WifiAnchorDirectionPolicy.estimate(
                localFix = origin.copy(ageMillis = 10_001L),
                anchor = anchor,
                magneticHeadingDegrees = 0f,
                declinationDegrees = 0f,
            )
        )
    }
}
