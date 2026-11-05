package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.ConnectionMethodPolicy
import com.combustiblelemonlabs.pingring.data.ConnectionMethodPolicy.Capabilities
import com.combustiblelemonlabs.pingring.data.ConnectionMethodPolicy.Method
import com.combustiblelemonlabs.pingring.data.ConnectionMethodPolicy.Preferences
import com.combustiblelemonlabs.pingring.data.ConnectionMethodPolicy.Unavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way of joining a ring a device uses, and what it is told when the
 * thing it asked for is not available.
 *
 * The API level is the interesting one: it was missing entirely before, which
 * meant a device reporting the UWB feature on Android 11 could be offered a
 * switch whose permission does not exist there.
 */
class ConnectionMethodPolicyTest {

    private fun capabilities(
        apiLevel: Int = 34,
        uwbHardware: Boolean = true,
        uwbPermission: Boolean = true,
    ) = Capabilities(
        apiLevel = apiLevel,
        uwbHardwareSupported = uwbHardware,
        uwbPermissionGranted = uwbPermission,
    )

    @Test
    fun `a capable device ranges`() {
        val selection = ConnectionMethodPolicy.chooseMethod(
            capabilities(),
            Preferences(uwbRangingEnabled = true),
        )

        assertTrue(selection.usesUwb)
        assertEquals(listOf(Method.NEARBY, Method.UWB_RANGING), selection.methods)
        assertNull(selection.unranged)
    }

    @Test
    fun `turning ranging off drops it without complaint`() {
        // Asked-for-and-not-wanted is different from asked-for-and-refused.
        // There is nothing to explain when the user simply said no.
        val selection = ConnectionMethodPolicy.chooseMethod(
            capabilities(),
            Preferences(uwbRangingEnabled = false),
        )

        assertFalse(selection.usesUwb)
        assertNull("switching it off is not a refusal", selection.unranged)
    }

    @Test
    fun `nearby is always present`() {
        // Every combination, including a device that cannot range at all. There
        // is no configuration in which a PingRing runs without it.
        for (caps in listOf(
            capabilities(),
            capabilities(uwbHardware = false),
            capabilities(uwbPermission = false),
            capabilities(apiLevel = 29),
        )) {
            for (wanted in listOf(true, false)) {
                val selection = ConnectionMethodPolicy.chooseMethod(
                    caps,
                    Preferences(uwbRangingEnabled = wanted),
                )
                assertTrue(
                    "nearby must always be chosen: caps=$caps wanted=$wanted",
                    Method.NEARBY in selection.methods,
                )
            }
        }
    }

    @Test
    fun `a device with no uwb radio is told so`() {
        val selection = ConnectionMethodPolicy.chooseMethod(
            capabilities(uwbHardware = false),
            Preferences(uwbRangingEnabled = true),
        )

        assertFalse(selection.usesUwb)
        assertEquals(Unavailable.NO_HARDWARE, selection.unranged)
    }

    @Test
    fun `a missing permission is distinguished from missing hardware`() {
        // Different fixes: one needs a prompt, the other is impossible. Saying
        // "no UWB radio" to a device that has one and has simply not been asked
        // yet would be wrong in a way the user cannot correct.
        val selection = ConnectionMethodPolicy.chooseMethod(
            capabilities(uwbPermission = false),
            Preferences(uwbRangingEnabled = true),
        )

        assertEquals(Unavailable.NO_PERMISSION, selection.unranged)
        assertFalse(selection.unranged == Unavailable.NO_HARDWARE)
    }

    @Test
    fun `a device below android 12 is refused on version, not hardware`() {
        // The case that was previously unhandled. UWB_RANGING does not exist
        // below API 31, so the switch could be offered and never be grantable.
        val selection = ConnectionMethodPolicy.chooseMethod(
            capabilities(apiLevel = 29),
            Preferences(uwbRangingEnabled = true),
        )

        assertFalse(selection.usesUwb)
        assertEquals(Unavailable.UNSUPPORTED_OS_VERSION, selection.unranged)
    }

    @Test
    fun `an old device without permission reports the version first`() {
        // Both are true on an API 29 device that has not been granted the
        // permission. The version is the one the user cannot fix by tapping, so
        // it is the one worth saying, and it is checked first.
        val selection = ConnectionMethodPolicy.chooseMethod(
            capabilities(apiLevel = 29, uwbPermission = false),
            Preferences(uwbRangingEnabled = true),
        )

        assertEquals(Unavailable.UNSUPPORTED_OS_VERSION, selection.unranged)
    }

    @Test
    fun `capability is about what is possible, not what was chosen`() {
        // available() answers "can this device range", so it must not change
        // when the preference does: the settings screen offers the option
        // either way and shows the state differently.
        val caps = capabilities()
        assertTrue(Method.UWB_RANGING in caps.available())

        val refused = ConnectionMethodPolicy.chooseMethod(caps, Preferences(uwbRangingEnabled = false))
        assertTrue(
            "turning it off must not remove it from what the device can do",
            Method.UWB_RANGING in caps.available(),
        )
        assertFalse(refused.usesUwb)
    }

    @Test
    fun `an incapable device does not offer ranging`() {
        assertFalse(Method.UWB_RANGING in capabilities(uwbHardware = false).available())
        assertFalse(Method.UWB_RANGING in capabilities(uwbPermission = false).available())
        assertFalse(Method.UWB_RANGING in capabilities(apiLevel = 30).available())
    }

    @Test
    fun `every refusal has a sentence`() {
        // A blank would leave the switch with no explanation, which is the
        // outcome the whole reporting mechanism exists to avoid.
        for (refusal in Unavailable.entries) {
            val sentence = ConnectionMethodPolicy.describe(refusal)
            assertTrue(
                "refusal $refusal has no explanation",
                sentence.isNotBlank() && sentence.endsWith("."),
            )
        }
    }
}
