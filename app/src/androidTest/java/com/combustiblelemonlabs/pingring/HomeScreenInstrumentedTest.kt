package com.combustiblelemonlabs.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.combustiblelemonlabs.pingring.ui.screens.EndPingRingDialog
import com.combustiblelemonlabs.pingring.ui.screens.HomeScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.session.Event

/**
 * The screen a user sees first, driven on a real device.
 *
 * These are the tests that cannot exist on the JVM: a `@Composable` body does
 * not run until Compose composes it, and nothing composes it without a device
 * and a real measure/layout pass. That is why the JVM unit tests can say
 * nothing at all about whether the home screen renders, or whether its buttons
 * are wired to the right callbacks - and those are exactly the failures a user
 * meets first.
 *
 * The assertions are about wiring, not appearance. Pixel-level layout checks
 * would be brittle across Compose and Material versions without catching
 * anything a person would file a bug about.
 */
class HomeScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theHomeScreenOffersBothWaysIntoAPingRing() {
        composeRule.setContent {
            MaterialTheme { HomeScreen(onHost = {}, onJoin = {}, onSettings = {}) }
        }

        composeRule.onNodeWithText("Create a PingRing").assertIsDisplayed()
        composeRule.onNodeWithText("Join a PingRing").assertIsDisplayed()
    }

    @Test
    fun theHomeScreenExplainsWhatEachOptionDoes() {
        composeRule.setContent {
            MaterialTheme { HomeScreen(onHost = {}, onJoin = {}, onSettings = {}) }
        }

        // The subtitles are what distinguish the two cards; losing one leaves two
        // identical-looking buttons and no way for a user to choose.
        composeRule.onNodeWithText("Host a private local mesh for nearby friends")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Scan QR code or enter an event code")
            .assertIsDisplayed()
    }

    @Test
    fun creatingAPingRingCallsTheHostCallback() {
        var hosted = 0
        composeRule.setContent {
            MaterialTheme { HomeScreen(onHost = { hosted++ }, onJoin = {}, onSettings = {}) }
        }

        composeRule.onNodeWithText("Create a PingRing").performClick()

        assertEquals("tapping Create must reach the host path", 1, hosted)
    }

    @Test
    fun joiningAPingRingCallsTheJoinCallback() {
        var joined = 0
        composeRule.setContent {
            MaterialTheme { HomeScreen(onHost = {}, onJoin = { joined++ }, onSettings = {}) }
        }

        composeRule.onNodeWithText("Join a PingRing").performClick()

        assertEquals("tapping Join must reach the join path", 1, joined)
    }

    @Test
    fun tappingSettingsCallsBack() {
        // The route from the one screen every session starts on into App
        // Settings. Nothing else on Home reaches it, so a rewired button here
        // would leave App Settings reachable only from inside a live ring.
        var opened = 0
        composeRule.setContent {
            MaterialTheme { HomeScreen(onHost = {}, onJoin = {}, onSettings = { opened++ }) }
        }

        // No performScrollTo: Home is not a scrolling screen, so the button is
        // already on the viewport. Scrolling it would assert nothing and would
        // fail on any layout where the settings button moves.
        composeRule.onNodeWithText("Settings").assertIsDisplayed().performClick()

        assertEquals("Settings must reach App Settings", 1, opened)
    }

    @Test
    fun theTwoCardsDoNotShareACallback() {
        // The two cards sit next to each other; wiring both to one callback looks
        // perfectly fine on screen and silently breaks one of the two journeys.
        var hosted = 0
        var joined = 0
        composeRule.setContent {
            MaterialTheme { HomeScreen(onHost = { hosted++ }, onJoin = { joined++ }, onSettings = {}) }
        }

        composeRule.onNodeWithText("Join a PingRing").performClick()

        assertEquals(0, hosted)
        assertEquals(1, joined)
    }
}