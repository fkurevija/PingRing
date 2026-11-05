package com.combustiblelemonlabs.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.combustiblelemonlabs.pingring.ui.screens.EndPingRingDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.session.SessionManager

/**
 * The leave/end confirmation, which is role-aware for a reason worth testing.
 *
 * A guest is offered "Leave" and a host "End": sending a guest down the end
 * path calls [com.combustiblelemonlabs.pingring.data.session.SessionManager.endHostedEvent], which
 * asserts the session is hosted and throws. The wording is the only thing that
 * tells the user which they are about to do, so the difference has to survive.
 */
class EndPingRingDialogInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aHostIsOfferedEndingTheWholeRing() {
        composeRule.setContent {
            MaterialTheme { EndPingRingDialog(isHost = true, onConfirm = {}, onDismiss = {}) }
        }

        composeRule.onNodeWithText("End this PingRing?").assertIsDisplayed()
        composeRule.onNodeWithText("End PingRing").assertIsDisplayed()
    }

    @Test
    fun aGuestIsOfferedOnlyLeaving() {
        composeRule.setContent {
            MaterialTheme { EndPingRingDialog(isHost = false, onConfirm = {}, onDismiss = {}) }
        }

        composeRule.onNodeWithText("Leave this PingRing?").assertIsDisplayed()
        composeRule.onNodeWithText("Leave").assertIsDisplayed()
    }

    @Test
    fun aGuestIsToldTheOtherMembersAreNotAffected() {
        // Ending the ring would drop every other member; the copy has to say that
        // leaving is the narrower action.
        composeRule.setContent {
            MaterialTheme { EndPingRingDialog(isHost = false, onConfirm = {}, onDismiss = {}) }
        }

        composeRule.onNodeWithText(
            "You will stop sharing your position with this PingRing. " +
                "The host and the other members are not affected."
        ).assertIsDisplayed()
    }

    @Test
    fun aHostIsToldEverybodyIsRemoved() {
        composeRule.setContent {
            MaterialTheme { EndPingRingDialog(isHost = true, onConfirm = {}, onDismiss = {}) }
        }

        composeRule.onNodeWithText(
            "This removes every member from the PingRing and returns them to the home page."
        ).assertIsDisplayed()
    }

    @Test
    fun confirmingCallsBackExactlyOnce() {
        var confirmed = 0
        composeRule.setContent {
            MaterialTheme {
                EndPingRingDialog(isHost = true, onConfirm = { confirmed++ }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("End PingRing").performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun cancellingDoesNotConfirm() {
        // The destructive path has to be the one the user opted into.
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            MaterialTheme {
                EndPingRingDialog(
                    isHost = true,
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals("Cancel must not end the ring", 0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun cancellingIsAvailableToBothRoles() {
        // Whatever the role, there has to be a way out that does not act. The
        // two roles are separate tests because `setContent` may only be called
        // once per ComposeTestRule - a loop would throw "has already set
        // content" on the second pass.
        var dismissed = 0
        composeRule.setContent {
            MaterialTheme {
                EndPingRingDialog(
                    isHost = true,
                    onConfirm = {},
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun cancellingIsAvailableToAGuestToo() {
        var dismissed = 0
        composeRule.setContent {
            MaterialTheme {
                EndPingRingDialog(
                    isHost = false,
                    onConfirm = {},
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
    }
}