package com.fkurevija.pingring

import com.fkurevija.pingring.data.ConnectivityStatus
import com.fkurevija.pingring.data.LocalRadioAdvicePolicy
import com.fkurevija.pingring.data.LocalRadioAdvicePolicy.Advice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which local-radio problems are worth telling the member about.
 *
 * Two failures pull in opposite directions here and both matter: saying too
 * little leaves a member on a degraded radio with no idea why, and saying too
 * much turns one switch that needs flipping into what looks like a broken app.
 */
class LocalRadioAdvicePolicyTest {

    private fun advice(
        bluetooth: Boolean,
        wifi: Boolean,
        bleIdentity: Boolean = true,
    ) = LocalRadioAdvicePolicy.adviceFor(
        ConnectivityStatus(
            isBluetoothEnabled = bluetooth,
            isWifiEnabled = wifi,
            supportsBleIdentity = bleIdentity,
        )
    )

    @Test
    fun `a device with both radios on and working hardware is told nothing`() {
        // The common case, and the one that must stay silent: a card that appears
        // on a healthy phone is noise that trains people to ignore cards.
        assertEquals(emptyList<Advice>(), advice(bluetooth = true, wifi = true))
    }

    @Test
    fun `wifi off on its own says wifi is off`() {
        assertEquals(listOf(Advice.WIFI_OFF), advice(bluetooth = true, wifi = false))
    }

    @Test
    fun `bluetooth off is the only thing said, and it hides the wifi notice`() {
        // The precedence rule. Bluetooth is what discovery and connections run
        // over, so it is the blocking problem; a Wi-Fi card underneath it tells
        // the member nothing they can act on until the first one is handled.
        assertEquals(
            listOf(Advice.BLUETOOTH_OFF),
            advice(bluetooth = false, wifi = false, bleIdentity = false),
        )
    }

    @Test
    fun `the bluetooth notice comes first when it is shown at all`() {
        // Ordering is the whole point of returning a list. A member who cannot
        // use the app should read the reason they cannot use it above any
        // secondary note.
        val all = advice(bluetooth = false, wifi = true, bleIdentity = false)
        assertEquals(listOf(Advice.BLUETOOTH_OFF), all)
        assertTrue(all.first() == Advice.BLUETOOTH_OFF)
    }

    @Test
    fun `unsupported ble identity is reported but never blocks anything`() {
        // Not a fault the member can fix, so it must not suppress the Wi-Fi note
        // that they *can* act on.
        assertEquals(
            listOf(Advice.WIFI_OFF, Advice.BLE_IDENTITY_UNAVAILABLE),
            advice(bluetooth = true, wifi = false, bleIdentity = false),
        )
        assertEquals(
            listOf(Advice.BLE_IDENTITY_UNAVAILABLE),
            advice(bluetooth = true, wifi = true, bleIdentity = false),
        )
    }

    /**
     * The sweep: every combination of the three inputs, asserting the two
     * invariants that hold for all of them.
     */
    @Test
    fun `no combination ever reports something that is not wrong`() {
        for (bluetooth in listOf(true, false)) {
            for (wifi in listOf(true, false)) {
                for (bleIdentity in listOf(true, false)) {
                    val shown = advice(bluetooth, wifi, bleIdentity)
                    val label = "bt=$bluetooth wifi=$wifi ble=$bleIdentity"

                    if (bluetooth) {
                        // Never claim Bluetooth is off when it is on.
                        assertTrue("$label reported Bluetooth off", Advice.BLUETOOTH_OFF !in shown)
                    }
                    if (wifi) {
                        assertTrue("$label reported Wi-Fi off", Advice.WIFI_OFF !in shown)
                    }
                    if (bleIdentity) {
                        assertTrue(
                            "$label reported BLE identity unavailable",
                            Advice.BLE_IDENTITY_UNAVAILABLE !in shown,
                        )
                    }
                    // Bluetooth off dominates, so it is the only thing that shows.
                    if (!bluetooth) {
                        assertEquals("$label should show only the blocker", listOf(Advice.BLUETOOTH_OFF), shown)
                    }
                    // Never the same notice twice.
                    assertEquals("$label repeated a notice", shown.distinct().size, shown.size)
                }
            }
        }
    }
}