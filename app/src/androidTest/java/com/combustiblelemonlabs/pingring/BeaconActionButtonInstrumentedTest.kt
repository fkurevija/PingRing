package com.combustiblelemonlabs.pingring

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.combustiblelemonlabs.pingring.data.session.BeaconInfo
import com.combustiblelemonlabs.pingring.ui.screens.BeaconActionButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BeaconActionButtonInstrumentedTest {
    @get:Rule
    val composeRule = PausedClockComposeContentTestRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val beacon = BeaconInfo(
        id = "own-beacon",
        senderPeerId = "local",
        senderName = "Ana",
        title = "Regroup",
        colorArgb = 0xFF00FF00,
        vibrationPattern = "SOS_URGENT",
        isActive = true,
        timestamp = 1L,
    )

    @Test
    fun beaconsOpensTheSheetWhenNoLocalBeaconIsActive() {
        var opened = 0
        var stopped = 0
        composeRule.setScreen {
            BeaconActionButton(null, onOpen = { opened++ }, onStop = { stopped++ })
        }

        composeRule.onNodeWithText(context.getString(R.string.beacons)).performClick()

        assertEquals(1, opened)
        assertEquals(0, stopped)
        composeRule.onNodeWithText(context.getString(R.string.stop)).assertDoesNotExist()
    }

    @Test
    fun stopEndsTheLocalBeaconDirectlyAndReturnsToBeacons() {
        val current = mutableStateOf<BeaconInfo?>(beacon)
        var opened = 0
        var stopped = 0
        composeRule.setScreen {
            BeaconActionButton(
                current.value,
                onOpen = { opened++ },
                onStop = { stopped++; current.value = null },
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.stop)).performClick()
        composeRule.settle()

        assertEquals(1, stopped)
        assertEquals(0, opened)
        composeRule.onNodeWithText(context.getString(R.string.stop)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.beacons)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun endingTheBeaconWithoutAButtonTapRestoresBeacons() {
        val current = mutableStateOf<BeaconInfo?>(beacon)
        composeRule.setScreen {
            BeaconActionButton(current.value, onOpen = {}, onStop = {})
        }
        composeRule.onNodeWithText(context.getString(R.string.stop)).assertIsDisplayed()

        composeRule.runOnIdle { current.value = beacon.copy(isActive = false) }
        composeRule.settle()

        composeRule.onNodeWithText(context.getString(R.string.beacons)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.stop)).assertDoesNotExist()
    }
}
