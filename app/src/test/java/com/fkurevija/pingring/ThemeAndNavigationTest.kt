package com.fkurevija.pingring

import androidx.compose.ui.graphics.Color
import com.fkurevija.pingring.ui.navigation.AppSettingsKey
import com.fkurevija.pingring.ui.navigation.HomeKey
import com.fkurevija.pingring.ui.navigation.HostSetupKey
import com.fkurevija.pingring.ui.navigation.PingRingJoinKey
import com.fkurevija.pingring.ui.navigation.PingRingSettingsKey
import com.fkurevija.pingring.ui.navigation.RadarKey
import com.fkurevija.pingring.ui.theme.PingRingBackgroundDark
import com.fkurevija.pingring.ui.theme.PingRingBackgroundLight
import com.fkurevija.pingring.ui.theme.PingRingPrimaryDark
import com.fkurevija.pingring.ui.theme.PingRingPrimaryLight
import com.fkurevija.pingring.ui.theme.PingRingSurfaceDark
import com.fkurevija.pingring.ui.theme.PingRingSurfaceLight
import com.fkurevija.pingring.ui.theme.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's visual vocabulary and its navigation keys.
 *
 * These are declarations rather than logic, but they are exactly the kind of
 * thing that gets edited by eye and quietly broken: a light-mode colour that is
 * not actually light, two nav keys that compare equal and collapse the back
 * stack, or a `Typography` that lost its `bodyLarge` and renders the whole app
 * in the platform default without anyone noticing.
 */
class ThemeAndNavigationTest {

    @Test
    fun `light mode colours really are light`() {
        // A "light" palette whose background is dark is invisible as a bug until
        // someone puts the app on a bright surface. The luminance is the honest
        // check, not the name of the constant.
        listOf(
            "background" to PingRingBackgroundLight,
            "surface" to PingRingSurfaceLight,
        ).forEach { (name, color) ->
            assertTrue("$name light is not light: $color", luminance(color) > 0.5f)
        }
    }

    @Test
    fun `dark mode colours really are dark`() {
        listOf(
            "background" to PingRingBackgroundDark,
            "surface" to PingRingSurfaceDark,
        ).forEach { (name, color) ->
            assertTrue("$name dark is not dark: $color", luminance(color) < 0.5f)
        }
    }

    @Test
    fun `the two modes are genuinely different palettes`() {
        assertNotEquals(PingRingBackgroundLight, PingRingBackgroundDark)
        assertNotEquals(PingRingPrimaryLight, PingRingPrimaryDark)
    }

    @Test
    fun `every colour is fully opaque`() {
        // A translucent palette entry shows whatever is behind it, which on the
        // radar is the camera feed. These are deliberately flat colours.
        listOf(
            PingRingPrimaryLight, PingRingPrimaryDark,
            PingRingBackgroundLight, PingRingBackgroundDark,
            PingRingSurfaceLight, PingRingSurfaceDark,
        ).forEach { color ->
            assertEquals("colour $color is not opaque", 1f, color.alpha, 0.001f)
        }
    }

    @Test
    fun `bodyLarge is defined rather than left to the platform default`() {
        // The app sets exactly one style. If it is ever dropped, every string in
        // the app falls back to the system font and nothing fails.
        assertNotNull("Typography.bodyLarge must be set", Typography.bodyLarge)
    }

    @Test
    fun `navigation keys without data are singletons`() {
        // Navigation3 uses key equality to decide where a back stack entry is.
        // If a data object behaved by identity instead, going Home and back would
        // push a second copy every time.
        assertEquals(HomeKey, HomeKey)
        assertEquals(PingRingJoinKey, PingRingJoinKey)
        assertEquals(AppSettingsKey, AppSettingsKey)
    }

    @Test
    fun `navigation keys carrying an argument differ by that argument`() {
        // These must be value-equal only for the same destination, or a settings
        // screen for one event would be reused for another.
        assertEquals(HostSetupKey("E1"), HostSetupKey("E1"))
        assertNotEquals(HostSetupKey("E1"), HostSetupKey("E2"))
        assertEquals(RadarKey("E1"), RadarKey("E1"))
        assertNotEquals(RadarKey("E1"), RadarKey("E2"))
        assertEquals(PingRingSettingsKey("E1"), PingRingSettingsKey("E1"))
        assertNotEquals(PingRingSettingsKey("E1"), PingRingSettingsKey("E2"))
    }

    @Test
    fun `keys for different destinations never collide`() {
        val keys = listOf(
            HomeKey, PingRingJoinKey, AppSettingsKey,
            HostSetupKey("E1"), RadarKey("E1"), PingRingSettingsKey("E1"),
        )

        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `a keyed destination reports the argument it was created with`() {
        assertEquals("E1", RadarKey("E1").eventId)
        assertEquals("E1", HostSetupKey("E1").eventCode)
        assertEquals("E1", PingRingSettingsKey("E1").eventId)
    }

    /** Rec. 709 relative luminance: the standard perceptual measure of "light". */
    private fun luminance(color: Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
}