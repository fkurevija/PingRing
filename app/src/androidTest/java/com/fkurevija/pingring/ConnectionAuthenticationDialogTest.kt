package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fkurevija.pingring.data.ConnectionAuthenticationRequest
import com.fkurevija.pingring.ui.screens.ConnectionAuthenticationDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectionAuthenticationDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sasDigitsAreShownAndRequireExplicitConfirmation() {
        var confirmed = false
        composeRule.setContent {
            MaterialTheme {
                ConnectionAuthenticationDialog(
                    request = ConnectionAuthenticationRequest("endpoint", "Alex", "123 456", true),
                    onConfirm = { confirmed = true },
                    onReject = {},
                )
            }
        }

        // The digits are interpolated into one sentence rather than standing
        // alone, so this has to match a substring: an exact match on "123 456"
        // finds nothing and reports the code as missing when it is on screen.
        composeRule.onNodeWithText("123 456", substring = true).assertIsDisplayed()
        assertEquals(false, confirmed)
        composeRule.onNodeWithText("Codes match").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun rejectingSasDoesNotConfirmTheConnection() {
        var rejected = false
        composeRule.setContent {
            MaterialTheme {
                ConnectionAuthenticationDialog(
                    request = ConnectionAuthenticationRequest("endpoint", "Alex", "654 321", false),
                    onConfirm = {},
                    onReject = { rejected = true },
                )
            }
        }

        composeRule.onNodeWithText("Reject").performClick()
        assertEquals(true, rejected)
    }
}