package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.session.BeaconInfo
import com.combustiblelemonlabs.pingring.data.ranging.RangingConfidence
import com.combustiblelemonlabs.pingring.data.settings.ThemeMode
import com.combustiblelemonlabs.pingring.ui.permissions.UwbAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Small value types whose rules are easy to get wrong and invisible when they are.
 *
 * Each of these has a field or a flag that quietly changes behaviour, so the
 * tests are about the *combination* rather than any one field in isolation.
 */
class ValueTypeRulesTest {

    // --- UwbAvailability: ranging needs hardware *and* permission, and asking
    // --- for the permission on hardware that cannot use it leaves it
    // --- permanently ungrantable.

    @Test
    fun `uwb is usable only with both hardware and permission`() {
        fun availability(hardware: Boolean, granted: Boolean) =
            UwbAvailability(hardware, granted) { }

        assertTrue(availability(hardware = true, granted = true).isUsable)
        assertFalse("no hardware, no ranging", availability(hardware = false, granted = true).isUsable)
        assertFalse("no permission, no ranging", availability(hardware = true, granted = false).isUsable)
        assertFalse("neither, certainly not", availability(hardware = false, granted = false).isUsable)
    }

    @Test
    fun `the request callback is always present`() {
        // Every call site invokes it when a UWB-gated control is tapped while
        // disabled, so a null or missing callback would crash on tap rather than
        // prompting.
        val availability = UwbAvailability(hardwareSupported = false, permissionGranted = false) { }

        assertNotNull(availability.onRequestAccess)
        // Safe to call even on hardware that cannot range: it is expected to
        // explain itself rather than throw.
        availability.onRequestAccess()
    }

    @Test
    fun `uwb availability compares by state and ignores its callback`() {
        // The class was a data class holding a lambda, so `==` compared the
        // callback by identity and two instances describing the same state were
        // unequal. That made `==` useless for deciding whether to recompose.
        // It is now a plain class whose equals covers the two state fields only.
        val first = UwbAvailability(hardwareSupported = true, permissionGranted = true) { }
        val second = UwbAvailability(hardwareSupported = true, permissionGranted = true) { }

        assertEquals("a fresh callback must not affect equality", first, second)
        assertEquals(
            "equal instances must hash alike",
            first.hashCode(),
            second.hashCode()
        )
    }

    @Test
    fun `uwb availability differs when either state field differs`() {
        val usable = UwbAvailability(hardwareSupported = true, permissionGranted = true) { }
        val noHardware = UwbAvailability(hardwareSupported = false, permissionGranted = true) { }
        val noPermission = UwbAvailability(hardwareSupported = true, permissionGranted = false) { }

        assertNotEquals(usable, noHardware)
        assertNotEquals(usable, noPermission)
        assertNotEquals(noHardware, noPermission)
    }

    @Test
    fun `uwb availability does not compare equal to an unrelated type`() {
        val availability = UwbAvailability(hardwareSupported = true, permissionGranted = true) { }

        assertFalse(availability.equals("not an availability"))
        assertFalse(availability.equals(null))
    }

    @Test
    fun `uwb availability describes itself without the callback`() {
        // toString is used in Compose tooling and log output; a lambda's
        // default rendering would put an object hash in the middle of it.
        val availability = UwbAvailability(hardwareSupported = true, permissionGranted = false) { }

        val text = availability.toString()

        assertTrue("got '$text'", text.contains("hardwareSupported=true"))
        assertTrue("got '$text'", text.contains("permissionGranted=false"))
    }

    // --- ThemeMode: a settings value that round-trips through storage by name.

    @Test
    fun `every theme mode has a distinct name`() {
        val names = ThemeMode.entries.map { it.name }

        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `a theme mode round-trips through its name`() {
        // The value is persisted as a string, so an enum constant whose name
        // cannot be looked back up would silently reset a user's preference.
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.valueOf(mode.name))
        }
    }

    @Test
    fun `the three theme modes are exactly the ones the app offers`() {
        // Adding one without a matching `when` in the theme switch would crash
        // the settings screen, so the set is pinned deliberately.
        assertEquals(
            listOf("SYSTEM", "LIGHT", "DARK"),
            ThemeMode.entries.map { it.name }
        )
    }

    // --- BeaconInfo: what the app holds for an active beacon.

    @Test
    fun `an active beacon carries everything a peer needs to render it`() {
        val beacon = BeaconInfo(
            id = "b1",
            senderPeerId = "peer-a",
            senderName = "Ana",
            title = "Emergency!",
            colorArgb = 0xFFFF1744,
            vibrationPattern = "SOS_URGENT",
            isActive = true,
            timestamp = 1_700_000_000_000L
        )

        assertEquals("b1", beacon.id)
        assertEquals("Ana", beacon.senderName)
        assertTrue("an emergency beacon must be active", beacon.isActive)
        assertNotEquals("an emergency beacon needs a colour", 0L, beacon.colorArgb)
    }

    @Test
    fun `two beacons compare equal only when every field matches`() {
        val base = BeaconInfo(
            id = "b1", senderPeerId = "peer-a", senderName = "Ana",
            title = "Emergency!", colorArgb = 0xFFFF1744,
            vibrationPattern = "SOS_URGENT", isActive = true, timestamp = 1L
        )

        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        // The fields that decide what a peer sees: a beacon that compares equal
        // while its title or colour differs would render as a stale one.
        assertNotEquals(base, base.copy(title = "Caution"))
        assertNotEquals(base, base.copy(colorArgb = 0xFF00E676))
        assertNotEquals(base, base.copy(isActive = false))
    }

    // --- RangingConfidence: confidence levels for distance readings.

    @Test
    fun `every ranging confidence has a distinct name and round-trips`() {
        RangingConfidence.entries.forEach { confidence ->
            assertEquals(confidence, RangingConfidence.valueOf(confidence.name))
        }
    }
}