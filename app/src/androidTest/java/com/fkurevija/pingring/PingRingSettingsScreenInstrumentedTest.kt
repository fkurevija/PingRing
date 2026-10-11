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
import com.fkurevija.pingring.data.session.Event
import com.fkurevija.pingring.ui.screens.PingRingSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        onEventChanged: (Event) -> Unit = {},
        onOpenAppSettings: () -> Unit = {},
    ) {
        // The screen holds no state of its own: every switch reads straight from
        // the `event` prop and reports changes upward. So the test has to model
        // the round trip the app performs, handing back an updated event.
        //
        // Composing with a fixed event instead - which is what the staged
        // Save/Cancel model allowed - would snap each switch straight back on the
        // next recomposition and stop testing the behaviour at all. That a test
        // had to change this way is itself evidence of what the removal bought.
        var current by mutableStateOf(event)
        composeRule.setContent {
            MaterialTheme {
                PingRingSettingsScreen(
                    event = current,
                    uwbHardwareSupported = false,
                    isHost = isHost,
                    onBack = {},
                    onOpenAppSettings = onOpenAppSettings,
                    onEventChanged = { changed ->
                        current = changed
                        onEventChanged(changed)
                    },
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
    fun turningOnPreventNewUsersClosesTheRingImmediately() {
        val event = Event.create("4C1A9B")
        var changed: Event? = null
        // Named rather than passed as a trailing lambda: a trailing lambda binds
        // to the *last* parameter, which here is the app-settings callback, not
        // the change handler.
        showSettings(event, isHost = true, onEventChanged = { changed = it })

        composeRule.onNodeWithContentDescription(context.getString(R.string.prevent_new_users_joining))
            .performScrollTo().performClick().assertIsOn()

        composeRule.runOnIdle {
            // No Save press. The switch itself is the commit, which is what App
            // Settings already did and what this screen now does too.
            // The switch reads "prevent", the stored field still reads "accepts".
            // If a later edit flips the field instead of the switch, this is the
            // assertion that catches the ring being left permanently open.
            assertEquals(false, changed?.acceptsNewMembers)
            assertEquals(event.id, changed?.id)
        }
    }

    @Test
    fun thereIsNoSaveButtonAndNothingToCancel() {
        showSettings(Event.create("4C1A9B"), isHost = true)

        // The reason this change was made. App Settings commits as you touch each
        // control; this screen had Cancel and Save, so pressing back after a
        // change looked like cancelling it and silently discarded the change.
        // Asserted absent: a settings bar creeping back is exactly the sort of
        // thing that returns with the next layout change.
        composeRule.onNodeWithText(context.getString(R.string.save_changes))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.cancel))
            .assertDoesNotExist()
    }

    @Test
    fun preventNewUsersAlreadyOnIsShownAsOnAndCanBeTurnedBackOff() {
        val event = Event.create("4C1A9B").copy(acceptsNewMembers = false)
        var changed: Event? = null
        showSettings(event, isHost = true, onEventChanged = { changed = it })

        composeRule.onNodeWithContentDescription(context.getString(R.string.prevent_new_users_joining))
            .performScrollTo().assertIsOn().performClick().assertIsOff()

        composeRule.runOnIdle { assertEquals(true, changed?.acceptsNewMembers) }
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
        showSettings(Event.create("4C1A9B"), isHost = true, onEventChanged = {}, onOpenAppSettings = { opened++ })

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
        var changed: Event? = null
        showSettings(Event.create("4C1A9B"), isHost = true, onEventChanged = { changed = it })
        composeRule.onNodeWithText(context.getString(R.string.uwb_no_hardware))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        // The row asks for the permission it is missing; it does not turn ranging
        // on as a side effect. Previously this was checked by pressing Save after
        // the click, which was an accident of the old flow - the Save button was
        // never what made the row safe to tap.
        composeRule.runOnIdle { assertNull(changed) }
    }

    @Test
    fun theRemainingSwitchExposesItsNameStateAndMinimumTouchTarget() {
        showSettings(Event.create("4C1A9B"), isHost = true) {}
        composeRule.onNodeWithContentDescription(context.getString(R.string.prevent_new_users_joining))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(context.getString(R.string.uwb))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
