package com.fkurevija.pingring.data.ranging
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy.RangingSource
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy.WifiRttCapability
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy.WifiRttUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which ranging source a device falls back to.
 *
 * Two failure modes pull in opposite directions and both matter: picking a
 * source the device cannot actually produce gives a reading that never arrives,
 * while picking a coarser one than it could gives a worse answer than the user
 * was promised. Most of these assert the *order* of the fallback rather than a
 * single outcome.
 */
class RangingSourcePolicyTest {

    private fun capability(
        rtt: Boolean = true,
        permission: Boolean = true,
        locationOn: Boolean = true,
        apiLevel: Int = RangingSourcePolicy.MIN_API_LEVEL,
    ) = WifiRttCapability(
        rttAvailable = rtt,
        locationPermissionGranted = permission,
        locationServicesEnabled = locationOn,
        apiLevel = apiLevel,
    )

    @Test
    fun `an active uwb fix wins over everything`() {
        // The user is already paying for a UWB session. Dropping to a coarser
        // source while it is available would throw that away for nothing.
        val selection = RangingSourcePolicy.select(
            uwbActive = true,
            useWifiRtt = true,
            wifiRtt = capability(),
        )

        assertEquals(RangingSource.UWB, selection.source)
        assertNull("uwb needs no explanation", selection.rttRefusedBy)
    }

    @Test
    fun `wifi rtt is used when it is asked for and available`() {
        val selection = RangingSourcePolicy.select(
            uwbActive = false,
            useWifiRtt = true,
            wifiRtt = capability(),
        )

        assertEquals(RangingSource.WIFI_RTT, selection.source)
        assertFalse("Wi-Fi RTT measures distance, not a bearing", selection.hasBearing)
    }

    @Test
    fun `rssi is the fallback and carries no bearing`() {
        val selection = RangingSourcePolicy.select(
            uwbActive = false,
            useWifiRtt = false,
            wifiRtt = capability(),
        )

        assertEquals(RangingSource.BLE_RSSI, selection.source)
        assertFalse(
            "signal strength cannot give a bearing, and pretending otherwise " +
                "would draw a needle pointing nowhere",
            selection.hasBearing,
        )
    }

    @Test
    fun `coordinate separation is an estimate without a directional needle`() {
        val selection = RangingSourcePolicy.select(
            uwbActive = false,
            useWifiRtt = false,
            wifiRtt = capability(),
            gnss = RangingSourcePolicy.GnssCapability(true, 0L),
        )
        assertEquals(RangingSource.GNSS, selection.source)
        assertFalse(selection.hasBearing)
    }

    @Test
    fun `ranging is never used unless asked for`() {
        // It costs a Wi-Fi session and needs location services on. That is a
        // real trade and it is the user's to make.
        val selection = RangingSourcePolicy.select(
            uwbActive = false,
            useWifiRtt = false,
            wifiRtt = capability(),
        )

        assertEquals(RangingSource.BLE_RSSI, selection.source)
    }

    @Test
    fun `each blocker is named so the user knows what to do`() {
        // The ordering matters: hardware is not actionable, so it must not be
        // reported for a device where granting permission would achieve nothing.
        val cases = listOf(
            Triple(capability(rtt = false), WifiRttUnavailable.NO_HARDWARE, "no radio"),
            Triple(
                capability(permission = false),
                WifiRttUnavailable.NO_LOCATION_PERMISSION,
                "permission",
            ),
            Triple(
                capability(locationOn = false),
                WifiRttUnavailable.LOCATION_SERVICES_OFF,
                "location off",
            ),
        )

        for ((caps, expected, label) in cases) {
            val selection = RangingSourcePolicy.select(
                uwbActive = false,
                useWifiRtt = true,
                wifiRtt = caps,
            )

            assertEquals(
                "wrong refusal reported for $label",
                expected,
                selection.rttRefusedBy,
            )
            assertEquals(
                "must still fall back rather than fail for $label",
                RangingSource.BLE_RSSI,
                selection.source,
            )
        }
    }

    @Test
    fun `no hardware outranks a missing permission`() {
        // Both are true on a phone with no RTT radio that has never been asked
        // for location. Telling that user to grant a permission would be advice
        // that cannot possibly help.
        val selection = RangingSourcePolicy.select(
            uwbActive = false,
            useWifiRtt = true,
            wifiRtt = capability(rtt = false, permission = false),
        )

        assertEquals(WifiRttUnavailable.NO_HARDWARE, selection.rttRefusedBy)
    }

    @Test
    fun `an unusable source is never selected however it is asked for`() {
        // Sweeps every combination, so a future edit that reorders the checks
        // cannot quietly start returning WIFI_RTT without all three conditions.
        for (rtt in listOf(true, false)) {
            for (permission in listOf(true, false)) {
                for (locationOn in listOf(true, false)) {
                    val caps = capability(rtt, permission, locationOn)
                    val selection = RangingSourcePolicy.select(
                        uwbActive = false,
                        useWifiRtt = true,
                        wifiRtt = caps,
                    )

                    if (selection.source == RangingSource.WIFI_RTT) {
                        assertTrue(
                            "rtt selected without full capability: $caps",
                            caps.isUsable,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `usability needs all three, not just hardware`() {
        // The case that is easy to get wrong: a phone with an RTT radio, the
        // permission granted, and location switched off device-wide returns no
        // results at all.
        assertFalse(capability(locationOn = false).isUsable)
        assertFalse(capability(permission = false).isUsable)
        assertFalse(capability(rtt = false).isUsable)
        assertTrue(capability().isUsable)
    }

    @Test
    fun `a refusal is only reported when rtt was actually asked for`() {
        // Switching it off is not a refusal, so the UI must not put up an
        // explanation for something the user chose.
        val selection = RangingSourcePolicy.select(
            uwbActive = false,
            useWifiRtt = false,
            wifiRtt = capability(rtt = false),
        )

        assertNull(selection.rttRefusedBy)
    }

    @Test
    fun `an os too old to correlate results is refused`() {
        // The case that was silently wrong: every capability says yes, but the
        // OS has no way to attach a result to a peer, so a session would open,
        // produce numbers, and none of them could be attributed to anybody.
        val selection = RangingSourcePolicy.select(
            uwbActive = false,
            useWifiRtt = true,
            wifiRtt = capability(apiLevel = RangingSourcePolicy.MIN_API_LEVEL - 1),
        )

        assertEquals(RangingSource.BLE_RSSI, selection.source)
        assertEquals(
            WifiRttUnavailable.UNSUPPORTED_OS_VERSION,
            selection.rttRefusedBy,
        )
    }

    @Test
    fun `the os gate is part of usability not just of the refusal`() {
        assertFalse(capability(apiLevel = RangingSourcePolicy.MIN_API_LEVEL - 1).isUsable)
        assertTrue(capability(apiLevel = RangingSourcePolicy.MIN_API_LEVEL).isUsable)
    }

    @Test
    fun `an actionable blocker outranks the os version`() {
        // A phone that is too old *and* has never granted the permission should
        // still be told to grant it. An upgrade is a much bigger ask than a tap,
        // so naming the tap first costs nothing and the upgrade can be named
        // once it is the only thing left.
        assertEquals(
            WifiRttUnavailable.NO_LOCATION_PERMISSION,
            RangingSourcePolicy.select(
                uwbActive = false,
                useWifiRtt = true,
                wifiRtt = capability(
                    permission = false,
                    apiLevel = RangingSourcePolicy.MIN_API_LEVEL - 1,
                ),
            ).rttRefusedBy,
        )
        assertEquals(
            WifiRttUnavailable.NO_HARDWARE,
            RangingSourcePolicy.select(
                uwbActive = false,
                useWifiRtt = true,
                wifiRtt = capability(
                    rtt = false,
                    apiLevel = RangingSourcePolicy.MIN_API_LEVEL - 1,
                ),
            ).rttRefusedBy,
        )
    }

    @Test
    fun `no combination of capabilities selects rtt on too old an os`() {
        // Sweeps the OS level as well, so adding apiLevel to the gate cannot be
        // quietly undone by a later edit to the ordering above it.
        for (rtt in listOf(true, false)) {
            for (permission in listOf(true, false)) {
                for (locationOn in listOf(true, false)) {
                    for (api in listOf(0, RangingSourcePolicy.MIN_API_LEVEL - 1)) {
                        val caps = capability(rtt, permission, locationOn, api)
                        val selection = RangingSourcePolicy.select(
                            uwbActive = false,
                            useWifiRtt = true,
                            wifiRtt = caps,
                        )

                        if (selection.source == RangingSource.WIFI_RTT) {
                            assertTrue(
                                "rtt selected on an unsupported os: $caps",
                                caps.isUsable,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `every refusal has a sentence`() {
        for (refusal in WifiRttUnavailable.entries) {
            val sentence = RangingSourcePolicy.describe(refusal)
            assertTrue(
                "refusal $refusal has no explanation",
                sentence.isNotBlank() && sentence.endsWith("."),
            )
        }
    }
}
