package com.combustiblelemonlabs.pingring

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.combustiblelemonlabs.pingring.ui.screens.AccessibilitySettingsScreen
import com.combustiblelemonlabs.pingring.ui.screens.rememberBeaconPulse
import com.combustiblelemonlabs.pingring.ui.screens.rememberHomePulse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class AccessibilitySettingsScreenInstrumentedTest {
    @get:Rule val composeRule = PausedClockComposeContentTestRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun feedbackSwitchesHaveIndependentDefaultsAndCallbacks() {
        var reduceMotion by mutableStateOf(false)
        var haptics by mutableStateOf(true)
        var spokenUpdates by mutableStateOf(false)
        var summary by mutableStateOf(false)
        composeRule.setScreen {
            AccessibilitySettingsScreen(
                radarScreenReaderSummaryEnabled = summary,
                onRadarScreenReaderSummaryChange = { summary = it },
                onBack = {},
                reduceMotionEnabled = reduceMotion,
                onReduceMotionChange = { reduceMotion = it },
                proximityHapticsEnabled = haptics,
                onProximityHapticsChange = { haptics = it },
                spokenRadarUpdatesEnabled = spokenUpdates,
                onSpokenRadarUpdatesChange = { spokenUpdates = it },
            )
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.reduce_motion))
            .performScrollTo().assertIsOff().performClick()
        composeRule.settle()
        composeRule.onNodeWithContentDescription(context.getString(R.string.proximity_haptics))
            .performScrollTo().assertIsOn().performClick()
        composeRule.settle()
        composeRule.onNodeWithContentDescription(context.getString(R.string.spoken_radar_updates))
            .performScrollTo().assertIsOff().performClick()
        composeRule.settle()
        composeRule.onNodeWithContentDescription(context.getString(R.string.radar_screen_reader_summary))
            .performScrollTo().assertIsOff()
        composeRule.runOnIdle {
            assertEquals(true, reduceMotion)
            assertEquals(false, haptics)
            assertEquals(true, spokenUpdates)
            assertEquals(false, summary)
        }
    }

    @Test
    fun reducedMotionKeepsBothDecorativePulsesStatic() {
        var beaconPulse: Pair<Float, Float>? = null
        var homePulse: Pair<Float, Float>? = null
        composeRule.setScreen {
            val beacon = rememberBeaconPulse(reduceMotion = true, active = true)
            val home = rememberHomePulse(reduceMotion = true)
            SideEffect { beaconPulse = beacon; homePulse = home }
        }
        composeRule.mainClock.advanceTimeBy(3_000L)
        composeRule.runOnIdle {
            assertEquals(0.9f to 0.35f, beaconPulse)
            assertEquals(0.9f to 0.25f, homePulse)
        }
    }

    @Test
    fun activeBeaconAnimatesUnlessMotionIsReduced() {
        var pulse: Pair<Float, Float>? = null
        var reduceMotion by mutableStateOf(false)
        composeRule.setScreen {
            val values = rememberBeaconPulse(reduceMotion, active = true)
            SideEffect { pulse = values }
        }
        val initial = composeRule.runOnIdle { pulse }
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.runOnIdle {
            assertNotEquals(initial, pulse)
            reduceMotion = true
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(3_000L)
        composeRule.runOnIdle { assertEquals(0.9f to 0.35f, pulse) }
    }
}