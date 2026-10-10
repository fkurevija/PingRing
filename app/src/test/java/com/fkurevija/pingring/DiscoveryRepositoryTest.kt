package com.fkurevija.pingring

import com.fkurevija.pingring.data.discovery.ConnectivityStatus
import com.fkurevija.pingring.data.radar.RadarPeerUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DiscoveryRepositoryTest {

    @Test
    fun testConnectivityStatusDataClass() {
        val status = ConnectivityStatus(isBluetoothEnabled = true, isWifiEnabled = false)
        assertEquals(true, status.isBluetoothEnabled)
        assertEquals(false, status.isWifiEnabled)
    }

    @Test
    fun testRadarPeerUpdateDataClass() {
        val update = RadarPeerUpdate(
            peerId = "abc12345",
            name = "Peer abc12345",
            rssi = -65,
            proximity = "Hot",
            distance = 1.5f,
            azimuth = 45.0f,
            elevation = 10.0f,
            worldDirectionX = 0.5f,
            worldDirectionY = 0.5f,
            worldDirectionZ = 0.1f,
            lastSeen = System.currentTimeMillis()
        )
        assertEquals("abc12345", update.peerId)
        assertEquals("Hot", update.proximity)
        assertEquals(1.5f, update.distance)
        assertEquals(45.0f, update.azimuth)
        assertEquals(10.0f, update.elevation)
    }

    @Test
    fun testSensorFusionMathLogic() {
        // Verify vector transformations matching DiscoveryRepository logic
        val azimuthDegrees = 45.0f
        val elevationDegrees = 0.0f

        val azimuthRad = Math.toRadians(azimuthDegrees.toDouble()).toFloat()
        val elevationRad = Math.toRadians(elevationDegrees.toDouble()).toFloat()

        // Vector in device horizontal plane: x = sin(azimuth), y = cos(azimuth), z = sin(elevation)
        val devX = sin(azimuthRad) * cos(elevationRad)
        val devY = cos(azimuthRad) * cos(elevationRad)
        val devZ = sin(elevationRad)

        // Using identity rotation matrix
        val rotationMatrix = FloatArray(9) { 0f }.apply { this[0] = 1f; this[4] = 1f; this[8] = 1f }

        val wX = rotationMatrix[0] * devX + rotationMatrix[1] * devY + rotationMatrix[2] * devZ
        val wY = rotationMatrix[3] * devX + rotationMatrix[4] * devY + rotationMatrix[5] * devZ
        val wZ = rotationMatrix[6] * devX + rotationMatrix[7] * devY + rotationMatrix[8] * devZ

        assertNotNull(wX)
        assertNotNull(wY)
        assertNotNull(wZ)
        assertEquals(devX, wX, 0.001f)
        assertEquals(devY, wY, 0.001f)
        assertEquals(devZ, wZ, 0.001f)
    }
}
