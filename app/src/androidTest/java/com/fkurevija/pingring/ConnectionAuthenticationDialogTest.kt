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
    fun digitsAreShownAndRequireExplicitConfirmation() {
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

        composeRule.onNodeWithText("123 456", substring = true).assertIsDisplayed()
        assertEquals(false, confirmed)
        composeRule.onNodeWithText("Codes match").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun rejectingDoesNotConfirmTheConnection() {
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
