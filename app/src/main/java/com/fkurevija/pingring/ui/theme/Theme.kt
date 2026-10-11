package com.fkurevija.pingring.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.fkurevija.pingring.data.settings.ThemeMode
import com.fkurevija.pingring.data.session.Event

// Every role Material draws with is named. Leaving any of these unset does not
// fall back to the platform default quietly - it produces a hole in the scheme
// that shows up as one unexplained colour somewhere nobody designed.
//
// Internal rather than private so the unit tests can assert against the schemes
// MaterialTheme actually receives, rather than against the colour constants and
// hoping the wiring in between matches.
internal val DarkColorScheme = darkColorScheme(
    primary = PingRingPrimaryDark,
    onPrimary = PingRingOnPrimaryDark,
    primaryContainer = PingRingPrimaryContainerDark,
    onPrimaryContainer = PingRingOnPrimaryContainerDark,
    secondary = PingRingSecondaryDark,
    secondaryContainer = PingRingSecondaryContainerDark,
    onSecondaryContainer = PingRingOnSecondaryContainerDark,
    tertiary = PingRingTertiaryDark,
    tertiaryContainer = PingRingTertiaryContainerDark,
    onTertiaryContainer = PingRingOnTertiaryContainerDark,
    background = PingRingBackgroundDark,
    surface = PingRingSurfaceDark,
    surfaceVariant = PingRingSurfaceVariantDark,
    onSurface = PingRingOnSurfaceDark,
    onSurfaceVariant = PingRingOnSurfaceVariantDark
)

internal val LightColorScheme = lightColorScheme(
    primary = PingRingPrimaryLight,
    onPrimary = PingRingOnPrimaryLight,
    primaryContainer = PingRingPrimaryContainerLight,
    onPrimaryContainer = PingRingOnPrimaryContainerLight,
    secondary = PingRingSecondaryLight,
    secondaryContainer = PingRingSecondaryContainerLight,
    onSecondaryContainer = PingRingOnSecondaryContainerLight,
    tertiary = PingRingTertiaryLight,
    tertiaryContainer = PingRingTertiaryContainerLight,
    onTertiaryContainer = PingRingOnTertiaryContainerLight,
    background = PingRingBackgroundLight,
    surface = PingRingSurfaceLight,
    surfaceVariant = PingRingSurfaceVariantLight,
    onSurface = PingRingOnSurfaceLight,
    onSurfaceVariant = PingRingOnSurfaceVariantLight
)

/**
 * PingRing's theme. Light or dark is chosen by [themeMode]; the *colour* is not
 * negotiable, because it is the brand.
 *
 * There is intentionally no dynamic-colour option and no path to add one
 * without it being a deliberate act. Material You would derive the palette from
 * the user's wallpaper, which means the app looks different on every device -
 * two phones at the same event can be visibly different apps, and the colour
 * that identifies PingRing is gone. If the brand ever needs to yield to the
 * wallpaper, that is a product decision to be made once and written down, not a
 * default that silently applies to anyone on Android 12+.
 */
@Composable
fun PingRingTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}