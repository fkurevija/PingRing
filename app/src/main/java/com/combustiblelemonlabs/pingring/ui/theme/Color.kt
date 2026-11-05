package com.combustiblelemonlabs.pingring.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * PingRing's brand palette.
 *
 * The primary is a fixed violet, chosen as the app's identity rather than
 * derived from the logo - the logo does not exist yet. Everything else in the
 * scheme is a tonal step from that same hue, so the app reads as one thing
 * rather than a primary colour with unrelated accents bolted on.
 *
 * Dynamic colour (Material You) is deliberately NOT used. It would replace this
 * palette with whatever wallpaper the user happens to have, which means two
 * phones running PingRing look like different apps and the brand disappears on
 * exactly the devices where somebody is most likely to photograph them. Light
 * and dark are still honoured - that is a legibility choice, not an identity
 * one - so the scheme below has both halves tuned.
 */

// --- The brand hue -------------------------------------------------------
// A medium violet: strong enough to read as a colour on a lit screen in a
// crowded venue, not so light that white text on it fails contrast in daylight.
val PingRingBrand = Color(0xFF5C2BD9)

/** Tone 40 - the violet as a button fill on a light background. */
val PingRingPrimaryLight = Color(0xFF5C2BD9)

/** A pale lavender wash behind primary text, and on chips. */
val PingRingPrimaryContainerLight = Color(0xFFE7DEFF)
val PingRingOnPrimaryContainerLight = Color(0xFF21005D)
val PingRingOnPrimaryLight = Color(0xFFFFFFFF)

/**
 * Tone 80 for dark mode. The brand violet itself is too dark to read on a
 * near-black background - it would sit close to the surface and vanish - so
 * dark mode uses a lighter step of the same hue. Same colour family, not the
 * same swatch: this is what lets a hue survive being applied to a dark
 * surface, and it is why the two modes are not one constant.
 */
val PingRingPrimaryDark = Color(0xFFCBBCFF)
val PingRingPrimaryContainerDark = Color(0xFF4300A8)
val PingRingOnPrimaryContainerDark = Color(0xFFE7DEFF)
val PingRingOnPrimaryDark = Color(0xFF2A0066)

// --- Secondary: the same hue, quietened ----------------------------------
// Material wants secondary near primary in hue and far from it in weight, so
// this is the brand violet desaturated rather than a second colour.
val PingRingSecondaryLight = Color(0xFF615B71)
val PingRingSecondaryContainerLight = Color(0xFFE7DFF7)
val PingRingOnSecondaryContainerLight = Color(0xFF1D192B)

val PingRingSecondaryDark = Color(0xFFCBC2DB)
val PingRingSecondaryContainerDark = Color(0xFF49424F)
val PingRingOnSecondaryContainerDark = Color(0xFFE7DFF7)

// --- Tertiary: the warm counterweight ------------------------------------
// Kept deliberately *not* violet. Tertiary carries the approval banner and
// other "needs attention" surfaces, and a third shade of the same hue would
// leave the approval queue looking like ordinary chrome. Warm amber reads as a
// different kind of thing at a glance.
val PingRingTertiaryLight = Color(0xFF7A5900)
val PingRingTertiaryContainerLight = Color(0xFFFFDF9B)
val PingRingOnTertiaryContainerLight = Color(0xFF261A00)

val PingRingTertiaryDark = Color(0xFFF2C048)
val PingRingTertiaryContainerDark = Color(0xFF5C4300)
val PingRingOnTertiaryContainerDark = Color(0xFFFFDF9B)

// --- Neutrals ------------------------------------------------------------
// Very slightly violet-tinted greys. A pure neutral grey beside a saturated
// violet reads as a different material; tinting it keeps the surfaces feeling
// like they belong to the same object.
val PingRingBackgroundLight = Color(0xFFFBF8FF)
val PingRingSurfaceLight = Color(0xFFFFFFFF)
val PingRingSurfaceVariantLight = Color(0xFFE9E1F0)
val PingRingOnSurfaceLight = Color(0xFF1C1B1F)
val PingRingOnSurfaceVariantLight = Color(0xFF4A454F)

val PingRingBackgroundDark = Color(0xFF121016)
val PingRingSurfaceDark = Color(0xFF1A181F)
val PingRingSurfaceVariantDark = Color(0xFF4A454F)
val PingRingOnSurfaceDark = Color(0xFFE6E0E9)
val PingRingOnSurfaceVariantDark = Color(0xFFCAC4D0)