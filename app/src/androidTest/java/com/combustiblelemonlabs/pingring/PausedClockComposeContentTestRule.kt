package com.combustiblelemonlabs.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule

/**
 * A [ComposeContentTestRule] for screens that animate forever.
 *
 * PingRing has two of them: the home screen's pulse and the radar ring's beacon
 * pulse, both driven by `rememberInfiniteTransition`. An infinite transition never
 * settles, and the test rule waits for the clock to go idle before every single
 * assertion, so with an auto-advancing clock *every* test on such a screen dies
 * with `ComposeNotIdleException` before reaching its first assertion.
 *
 * This rule puts the clock in manual mode instead, which is the supported way to
 * compose a screen that never goes idle. Note that the Gradle setting
 * `testOptions.unitTests`/`animationsDisabled` does not help here: that governs
 * the *system* animator scale, not the Compose frame clock, and Compose test
 * animations are driven by the test clock regardless.
 *
 * A consequence is that animations only progress when a test says so, which is
 * what [settle] is for: it advances far enough for an enter transition to finish
 * so the node is on screen at full opacity rather than captured mid-fade.
 */
class PausedClockComposeContentTestRule : ComposeContentTestRule by createComposeRule() {

    /** True once [pauseForEndlessAnimations] has run. */
    private var paused = false

    /**
     * Stops the frame clock auto-advancing.
     *
     * Called automatically before `setContent`, because that is the earliest
     * point the rule can be in place, and the first composition is what starts
     * the animation in the first place. Idempotent.
     */
    fun pauseForEndlessAnimations() {
        if (!paused) {
            mainClock.autoAdvance = false
            paused = true
        }
    }

    /**
     * Runs [content] under [androidx.compose.material3.MaterialTheme] with the
     * clock paused and the first two composition frames already executed, so an
     * assertion that follows sees a laid-out tree rather than an empty one.
     */
    fun setScreen(content: @Composable () -> Unit) {
        pauseForEndlessAnimations()
        setContent { MaterialTheme { content() } }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
    }

    /**
     * Advances the clock far enough for an enter/exit transition to finish, then
     * returns to idle.
     *
     * Without this a dialog opened while the clock is paused is captured at zero
     * alpha, and `assertIsDisplayed` reports it as not displayed even though it
     * is on screen - a failure that reads as "the dialog never opened" rather
     * than "the animation never ran".
     */
    fun settle() {
        mainClock.advanceTimeBy(1_000L)
        waitForIdle()
    }
}