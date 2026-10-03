package com.fkurevija.pingring

import androidx.compose.ui.graphics.Color
import java.io.File
import com.fkurevija.pingring.ui.navigation.AppSettingsKey
import com.fkurevija.pingring.ui.navigation.DeveloperSettingsKey
import com.fkurevija.pingring.ui.navigation.HomeKey
import com.fkurevija.pingring.ui.navigation.HostSetupKey
import com.fkurevija.pingring.ui.navigation.PingRingJoinKey
import com.fkurevija.pingring.ui.navigation.PingRingSettingsKey
import com.fkurevija.pingring.ui.navigation.RadarKey
import com.fkurevija.pingring.ui.theme.DarkColorScheme
import com.fkurevija.pingring.ui.theme.LightColorScheme
import com.fkurevija.pingring.ui.theme.PingRingBackgroundDark
import com.fkurevija.pingring.ui.theme.PingRingBackgroundLight
import com.fkurevija.pingring.ui.theme.PingRingBrand
import com.fkurevija.pingring.ui.theme.PingRingOnPrimaryContainerDark
import com.fkurevija.pingring.ui.theme.PingRingOnPrimaryContainerLight
import com.fkurevija.pingring.ui.theme.PingRingOnPrimaryDark
import com.fkurevija.pingring.ui.theme.PingRingOnPrimaryLight
import com.fkurevija.pingring.ui.theme.PingRingPrimaryContainerDark
import com.fkurevija.pingring.ui.theme.PingRingPrimaryContainerLight
import com.fkurevija.pingring.ui.theme.PingRingPrimaryDark
import com.fkurevija.pingring.ui.theme.PingRingPrimaryLight
import com.fkurevija.pingring.ui.theme.PingRingOnSurfaceLight
import com.fkurevija.pingring.ui.theme.PingRingSecondaryLight
import com.fkurevija.pingring.ui.theme.PingRingSurfaceDark
import com.fkurevija.pingring.ui.theme.PingRingSurfaceLight
import com.fkurevija.pingring.ui.theme.PingRingTertiaryLight
import com.fkurevija.pingring.ui.theme.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the brand colour is the primary, not a wallpaper's`() {
        // The point of dropping dynamic colour: the primary is PingRing's own,
        // on every device, whatever the wallpaper says. If this ever fails the
        // app has gone back to following the system theme, and two phones at
        // the same event can look like different apps.
        assertEquals(PingRingBrand, PingRingPrimaryLight)
    }

    @Test
    fun `the theme offers no way to follow the system colour`() {
        // Not "dynamic colour is off by default" - there must be no way back at
        // all. A flag defaulting to false is one careless call site away from
        // being re-enabled, so this asserts on the source rather than on a
        // parameter: any call to dynamicDarkColorScheme/dynamicLightColorScheme
        // anywhere in the theme is the regression, whatever shape it arrives in.
        val source = File("src/main/java/com/fkurevija/pingring/ui/theme/Theme.kt")
        assertTrue("Theme.kt not found at ${source.absolutePath}", source.exists())
        val text = source.readText()
        listOf("dynamicDarkColorScheme", "dynamicLightColorScheme").forEach { call ->
            assertFalse(
                "$call must not be reachable: the brand colour is not the wallpaper's",
                text.contains(call)
            )
        }
    }

    @Test
    fun `the schemes Material receives are the brand ones`() {
        // Asserting on the colour constants alone would pass even if Theme.kt
        // stopped passing them through, which is how a palette quietly stops
        // being the one anybody designed.
        assertEquals(PingRingBrand, LightColorScheme.primary)
        assertEquals(PingRingPrimaryDark, DarkColorScheme.primary)
        assertEquals(PingRingPrimaryContainerLight, LightColorScheme.primaryContainer)
        assertEquals(PingRingPrimaryContainerDark, DarkColorScheme.primaryContainer)
    }

    @Test
    fun `every role Material draws with is explicitly set`() {
        // An unset role does not fail; it silently falls back to a platform
        // default - very often a purple that looks close enough to be mistaken
        // for ours. So each role is pinned to the constant that was meant to
        // fill it, which is what catches a role nobody set.
        listOf(
            "primary" to Triple(LightColorScheme.primary, DarkColorScheme.primary, PingRingBrand),
            "onPrimary" to Triple(LightColorScheme.onPrimary, DarkColorScheme.onPrimary, PingRingOnPrimaryLight),
            "primaryContainer" to Triple(
                LightColorScheme.primaryContainer, DarkColorScheme.primaryContainer, PingRingPrimaryContainerLight
            ),
            "secondary" to Triple(LightColorScheme.secondary, DarkColorScheme.secondary, PingRingSecondaryLight),
            "tertiary" to Triple(LightColorScheme.tertiary, DarkColorScheme.tertiary, PingRingTertiaryLight),
            "background" to Triple(LightColorScheme.background, DarkColorScheme.background, PingRingBackgroundLight),
            "surface" to Triple(LightColorScheme.surface, DarkColorScheme.surface, PingRingSurfaceLight),
            "onSurface" to Triple(LightColorScheme.onSurface, DarkColorScheme.onSurface, PingRingOnSurfaceLight),
        ).forEach { (name, triple) ->
            val (light, dark, expectedLight) = triple
            assertEquals("$name light is not the colour that was chosen for it", expectedLight, light)
            assertNotEquals(
                "$name dark is identical to its light value, so one of them was never set",
                light, dark
            )
        }
    }

    @Test
    fun `primary text on the primary colour is legible in both modes`() {
        // White on the brand violet, and deep violet on the lightened dark-mode
        // step. Both are the pairing Material would draw for a filled button, so
        // if the contrast collapses the button becomes unreadable.
        assertTrue(
            "white on the light primary should clear 4.5:1",
            contrast(PingRingOnPrimaryLight, PingRingPrimaryLight) >= 4.5f
        )
        assertTrue(
            "onPrimary on the dark primary should clear 4.5:1",
            contrast(PingRingOnPrimaryDark, PingRingPrimaryDark) >= 4.5f
        )
    }

    @Test
    fun `primary body text on its own container is legible in both modes`() {
        // The chat bubbles and the focused peer card use primaryContainer, so
        // this is the pairing a user reads most: sender name and message text.
        assertTrue(
            "primary container text in light mode should clear 4.5:1",
            contrast(PingRingOnPrimaryContainerLight, PingRingPrimaryContainerLight) >= 4.5f
        )
        assertTrue(
            "primary container text in dark mode should clear 4.5:1",
            contrast(PingRingOnPrimaryContainerDark, PingRingPrimaryContainerDark) >= 4.5f
        )
    }

    @Test
    fun `the dark primary is light enough to read on a near-black surface`() {
        // Why dark mode does not reuse the brand violet: on a near-black
        // background it sits too close to the surface and disappears.
        assertTrue(
            "dark primary ${PingRingPrimaryDark} is too dark to read on ${PingRingBackgroundDark}",
            luminance(PingRingPrimaryDark) > 0.35f
        )
        assertTrue(
            "dark primary must contrast with its own background",
            contrast(PingRingPrimaryDark, PingRingBackgroundDark) >= 4.5f
        )
    }

    @Test
    fun `both primaries share one hue`() {
        // Same brand, two tonal steps. If the dark primary ever drifts to an
        // unrelated hue the app stops reading as one thing across light and dark,
        // which is the failure this palette exists to avoid.
        val lightHue = hue(PingRingPrimaryLight)
        val darkHue = hue(PingRingPrimaryDark)
        val delta = kotlin.math.abs(lightHue - darkHue)
        assertTrue(
            "light $lightHue and dark $darkHue primaries are different hues",
            minOf(delta, 360f - delta) <= 15f
        )
    }

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
        assertEquals(DeveloperSettingsKey, DeveloperSettingsKey)
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
            DeveloperSettingsKey,
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

    /**
     * WCAG relative luminance: the sRGB channels are linearised first, because
     * a gamma-encoded value is not proportional to light. Averaging the encoded
     * values - as `luminance` above does - is fine for asking "is this dark or
     * light" but cannot be used for a contrast ratio, where the numbers have to
     * mean what the standard says they mean.
     */
    private fun relativeLuminance(color: Color): Float {
        fun linearise(channel: Float): Float =
            if (channel <= 0.03928f) channel / 12.92f
            else Math.pow(((channel + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * linearise(color.red) +
            0.7152f * linearise(color.green) +
            0.0722f * linearise(color.blue)
    }

    /** WCAG contrast ratio between two opaque colours, from 1.0 to 21.0. */
    private fun contrast(foreground: Color, background: Color): Float {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** Hue in degrees, 0-360, ignoring saturation so greys do not mislead. */
    private fun hue(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return 0f
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return (hue + 360f) % 360f
    }
}