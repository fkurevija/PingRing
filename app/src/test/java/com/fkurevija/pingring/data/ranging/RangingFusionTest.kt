package com.fkurevija.pingring.data.ranging
import com.fkurevija.pingring.data.ranging.RangingConfidence
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy.GnssCapability
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy.RangingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-checking a UWB reading against BLE signal strength.
 *
 * This is the one fusion that costs nothing extra: the BLE adverts are already
 * arriving for discovery, and they measure the same geometry as the UWB round
 * trip by an independent path. The failure that matters is a check which cries
 * wolf - at a festival a phone in a back pocket is routinely faint - because a
 * warning that is always on gets ignored, and a warning that is always off is
 * worse than not having one.
 */
class RangingConfidenceTest {

    /** A peer the app believes is roughly arm's reach away. */
    private val near = 1.5f

    @Test
    fun `a strong signal at close range is corroborated`() {
        assertEquals(
            RangingConfidence.CLEAR,
            RangingSourcePolicy.confidence(rssiDbm = -55, expectedDistanceMeters = near),
        )
    }

    @Test
    fun `a weak signal at close range means something is in the way`() {
        // The case this exists for: UWB says arm's reach while the link has
        // collapsed. That combination means the round trip went off a
        // reflection, so the distance reads long rather than short.
        assertEquals(
            RangingConfidence.OBSTRUCTED,
            RangingSourcePolicy.confidence(rssiDbm = -92, expectedDistanceMeters = near),
        )
    }

    @Test
    fun `one source alone is never corroboration`() {
        // With nothing to compare against, claiming CLEAR would assert a check
        // that never ran. This is every peer's state until the first BLE advert
        // lands after a UWB fix.
        assertEquals(
            RangingConfidence.UNKNOWN,
            RangingSourcePolicy.confidence(rssiDbm = null, expectedDistanceMeters = near),
        )
        assertEquals(
            RangingConfidence.UNKNOWN,
            RangingSourcePolicy.confidence(rssiDbm = -55, expectedDistanceMeters = null),
        )
    }

    @Test
    fun `a weak signal far away is unremarkable`() {
        // Someone in the next tent is genuinely faint. Only a *near* peer being
        // faint is evidence of something, or every distant member would be
        // permanently flagged.
        assertEquals(
            RangingConfidence.UNKNOWN,
            RangingSourcePolicy.confidence(
                rssiDbm = -92,
                expectedDistanceMeters = RangingSourcePolicy.SHORT_RANGE_METERS + 5f,
            ),
        )
    }

    @Test
    fun `ordinary fading near the threshold does not raise an alarm`() {
        // Just above the bar, at the edge of the close range. This is the
        // back-pocket case and it must stay quiet.
        assertEquals(
            RangingConfidence.CLEAR,
            RangingSourcePolicy.confidence(
                rssiDbm = RangingSourcePolicy.OBSTRUCTION_RSSI_DBM + 1,
                expectedDistanceMeters = RangingSourcePolicy.SHORT_RANGE_METERS,
            ),
        )
    }

    @Test
    fun `the boundary is the last good reading`() {
        // The bar is inclusive: -80 dBm is what an unobstructed link would still
        // produce, so it counts as clear. Only one step past it is evidence.
        assertEquals(
            RangingConfidence.CLEAR,
            RangingSourcePolicy.confidence(
                rssiDbm = RangingSourcePolicy.OBSTRUCTION_RSSI_DBM,
                expectedDistanceMeters = RangingSourcePolicy.SHORT_RANGE_METERS,
            ),
        )
        assertEquals(
            RangingConfidence.OBSTRUCTED,
            RangingSourcePolicy.confidence(
                rssiDbm = RangingSourcePolicy.OBSTRUCTION_RSSI_DBM - 1,
                expectedDistanceMeters = RangingSourcePolicy.SHORT_RANGE_METERS,
            ),
        )
    }

    @Test
    fun `every confidence has a sentence`() {
        for (confidence in RangingConfidence.entries) {
            val sentence = RangingSourcePolicy.describe(confidence)
            assertTrue(
                "confidence $confidence has no explanation",
                sentence.isNotBlank() && sentence.endsWith("."),
            )
        }
    }
}

/**
 * The ranked chain: UWB, then Wi-Fi RTT, then GNSS, then BLE RSSI.
 *
 * Ordering is the whole point, so these pin the order rather than one outcome
 * at a time - a future edit that reshuffles the chain should fail here.
 */
class RangingChainTest {

    private val rttCapable = RangingSourcePolicy.WifiRttCapability(
        rttAvailable = true,
        locationPermissionGranted = true,
        locationServicesEnabled = true,
    )

    private fun gnss(ageMillis: Long = 1_000L, on: Boolean = true) =
        GnssCapability(locationServicesEnabled = on, fixAgeMillis = ageMillis)

    @Test
    fun `the chain runs uwb then rtt then gnss then rssi`() {
        // Each step removes exactly one rung, so a pass at the end means every
        // rung above was preferred while it was available.
        assertEquals(
            RangingSource.UWB,
            RangingSourcePolicy.select(
                uwbActive = true,
                useWifiRtt = true,
                wifiRtt = rttCapable,
                gnss = gnss(),
            ).source,
        )
        assertEquals(
            RangingSource.WIFI_RTT,
            RangingSourcePolicy.select(
                uwbActive = false,
                useWifiRtt = true,
                wifiRtt = rttCapable,
                gnss = gnss(),
            ).source,
        )
        // RTT off leaves GNSS in play: a phone with no RTT radio can still
        // benefit from a satellite fix outdoors.
        assertEquals(
            RangingSource.GNSS,
            RangingSourcePolicy.select(
                uwbActive = false,
                useWifiRtt = false,
                wifiRtt = rttCapable,
                gnss = gnss(),
            ).source,
        )
        assertEquals(
            RangingSource.BLE_RSSI,
            RangingSourcePolicy.select(
                uwbActive = false,
                useWifiRtt = false,
                wifiRtt = rttCapable,
                gnss = gnss(on = false),
            ).source,
        )
    }

    @Test
    fun `a stale fix is worse than no fix`() {
        // Someone walking at 1.4 m/s covers most of a radar ring in fifteen
        // seconds. RSSI at least describes where the peer is now.
        val stale = GnssCapability(
            locationServicesEnabled = true,
            fixAgeMillis = GnssCapability.MAX_FIX_AGE_MILLIS + 1,
        )

        assertEquals(
            RangingSource.BLE_RSSI,
            RangingSourcePolicy.select(
                uwbActive = false,
                useWifiRtt = false,
                wifiRtt = rttCapable,
                gnss = stale,
            ).source,
        )
    }

    @Test
    fun `gnss never beats an available rtt session`() {
        // Coordinate separation is coarser than a fresh direct RTT measurement.
        assertEquals(
            RangingSource.WIFI_RTT,
            RangingSourcePolicy.select(
                uwbActive = false,
                useWifiRtt = true,
                wifiRtt = rttCapable,
                gnss = gnss(ageMillis = 0L),
            ).source,
        )
    }

    @Test
    fun `a refused rtt can use an explicitly enabled usable gnss fix`() {
        // A usable, explicitly enabled GPS source remains available after an RTT refusal.
        val selection = RangingSourcePolicy.select(
            uwbActive = false,
            useWifiRtt = true,
            wifiRtt = rttCapable.copy(locationPermissionGranted = false),
            gnss = gnss(),
        )

        assertEquals(RangingSource.GNSS, selection.source)
        assertEquals(
            RangingSourcePolicy.WifiRttUnavailable.NO_LOCATION_PERMISSION,
            selection.rttRefusedBy,
        )
    }
}