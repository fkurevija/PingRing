package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.ui.screens.PingRingSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The settings for a live PingRing.
 *
 * Most of what is asserted here is negative. The screen lost five controls in one
 * change - discoverable, approve-each-join, track-host, share-GPS and chat
 * notifications - and a screen that quietly regrows a row is how it got that way
 * in the first place. So the removed things are asserted to stay gone, and the
 * two new affordances are asserted to be where they were put.
 */
class PingRingSettingsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun showSettings(
        event: Event,
        isHost: Boolean,
        // Defaulted so a test that only asserts what is on screen can pass a
        // trailing lambda for the last parameter without having to name a
        // save handler it never triggers.
        onSave: (Event) -> Unit = {},
        onOpenAppSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PingRingSettingsScreen(
                    event = event,
                    uwbHardwareSupported = false,
                    isHost = isHost,
                    onBack = {},
                    onOpenAppSettings = onOpenAppSettings,
                    onSave = onSave,
                    onEndEvent = {},
                    onLeaveEvent = {},
                )
            }
        }
    }

    /**
     * Rows this screen used to carry, which now configure behaviour that no
     * longer exists. Matched as text because that is what a user reads; a
     * control whose label drifted is still a control appearing in the wrong
     * place.
     */
    private val removedControls = listOf(
        "Discoverable",
        "Approve Each Join",
        "Track Host Only",
        "Share GPS location",
        "Chat Notifications",
        "Max Capacity",
        "High Bandwidth (Wi-Fi)",
        "Danger Zone",
    )

    @Test
    fun noneOfTheRemovedControlsComeBack() {
        showSettings(Event.create("4C1A9B"), isHost = true) {}
        removedControls.forEach { label ->
            composeRule.onNodeWithText(label, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun theScreenHasNoTitleDuplicatingWhereYouCameFrom() {
        showSettings(Event.create("4C1A9B"), isHost = true) {}

        // The radar above already says which PingRing this is. A second copy at
        // the top of every settings screen is what made the area feel like
        // navigation rather than configuration.
        composeRule.onNodeWithText(context.getString(R.string.pingring_settings)).assertDoesNotExist()
    }

    @Test
    fun preventNewUsersStartsOffForAnOpenRing() {
        showSettings(Event.create("4C1A9B"), isHost = true) {}

        // Inverted on screen: a freshly created ring accepts members, so the
        // lockout has to read as off.
        composeRule.onNodeWithContentDescription(context.getString(R.string.prevent_new_users_joining))
            .performScrollTo()
            .assertIsOff()
    }

    @Test
    fun turningOnPreventNewUsersClosesTheRingAndSaves() {
        val event = Event.create("4C1A9B")
        var saved: Event? = null
        // Named rather than passed as a trailing lambda: a trailing lambda binds
        // to the *last* parameter, which here is the app-settings callback, not
        // the save handler.
        showSettings(event, isHost = true, onSave = { saved = it })

        composeRule.onNodeWithContentDescription(context.getString(R.string.prevent_new_users_joining))
            .performScrollTo().performClick().assertIsOn()
        composeRule.onNodeWithText(context.getString(R.string.save_changes)).performClick()

        composeRule.runOnIdle {
            // The switch reads "prevent", the stored field still reads "accepts".
            // If a later edit flips the field instead of the switch, this is the
            // assertion that catches the ring being left permanently open.
            assertEquals(false, saved?.acceptsNewMembers)
            assertEquals(event.id, saved?.id)
        }
    }

    @Test
    fun preventNewUsersAlreadyOnIsShownAsOnAndCanBeTurnedBackOff() {
        val event = Event.create("4C1A9B").copy(acceptsNewMembers = false)
        var saved: Event? = null
        showSettings(event, isHost = true, onSave = { saved = it })

        composeRule.onNodeWithContentDescription(context.getString(R.string.prevent_new_users_joining))
            .performScrollTo().assertIsOn().performClick().assertIsOff()
        composeRule.onNodeWithText(context.getString(R.string.save_changes)).performClick()

        composeRule.runOnIdle { assertEquals(true, saved?.acceptsNewMembers) }
    }

    @Test
    fun endingIsTheFirstThingOnTheScreenAndSaysWhatItDoes() {
        showSettings(Event.create("4C1A9B"), isHost = true) {}

        composeRule.onNodeWithText(context.getString(R.string.end_pingring))
            .performScrollTo().assertIsDisplayed()
        // The consequence is written under the button, not above it under a
        // heading, so it has to be there too.
        composeRule.onNodeWithText(context.getString(R.string.end_pingring_description)).assertIsDisplayed()
    }

    @Test
    fun aGuestSeesLeaveRatherThanEnd() {
        showSettings(Event.create("4C1A9B"), isHost = false) {}

        composeRule.onNodeWithText(context.getString(R.string.leave_pingring))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.end_pingring)).assertDoesNotExist()
    }

    @Test
    fun thereIsAWayThroughToTheAppSettings() {
        var opened = 0
        showSettings(Event.create("4C1A9B"), isHost = true, onSave = {}, onOpenAppSettings = { opened++ })

        // Exactly one, the button. This used to index [1] past a heading that
        // repeated the button's own label, so the count is what catches that
        // heading coming back.
        composeRule.onAllNodesWithText(context.getString(R.string.app_settings))
            .assertCountEquals(1)
        composeRule.onNodeWithText(context.getString(R.string.app_settings)).performClick()
        composeRule.waitForIdle()

        assertEquals(1, opened)
    }

    @Test
    fun unsupportedUwbRowRemainsActionableWithoutEnablingRanging() {
        var saved: Event? = null
        showSettings(Event.create("4C1A9B"), isHost = true, onSave = { saved = it })
        composeRule.onNodeWithText(context.getString(R.string.uwb_no_hardware))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.save_changes)).performClick()
        composeRule.runOnIdle { assertEquals(false, saved?.useUwb) }
    }

    @Test
    fun theRemainingSwitchesExposeNamesStateAndMinimumTouchTargets() {
        showSettings(Event.create("4C1A9B"), isHost = true) {}
        composeRule.onNodeWithContentDescription(context.getString(R.string.prevent_new_users_joining))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription(context.getString(R.string.uwb))
            .performScrollTo()
            .assertIsOff()
            .assertIsNotEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}
