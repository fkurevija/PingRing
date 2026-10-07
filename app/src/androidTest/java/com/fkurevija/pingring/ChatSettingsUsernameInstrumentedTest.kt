package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.fkurevija.pingring.ui.screens.ChatSettingsDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatSettingsUsernameInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(onSave: (String) -> Unit = {}) {
        val name = mutableStateOf("Ana")
        composeRule.setContent {
            MaterialTheme {
                ChatSettingsDialog(
                    selectedColorArgb = null,
                    onColorSelected = {},
                    onDismiss = {},
                    username = name.value,
                    existingMemberNames = listOf("Bo"),
                    onUsernameChange = {
                        name.value = it
                        onSave(it)
                    },
                )
            }
        }
    }

    @Test
    fun validEditsAreSanitizedTrimmedAndSaved() {
        var saved: String? = null
        show { saved = it }
        composeRule.onNodeWithText("Username").performTextReplacement("  A\u200Blex  ")
        composeRule.onNodeWithText("Save Changes").performClick()
        assertEquals("Alex", saved)
        composeRule.onNodeWithText("Alex").assertIsDisplayed()
        composeRule.onNodeWithText("Save Changes").assertDoesNotExist()
    }

    @Test
    fun blankNamesCannotBeSaved() {
        show()
        composeRule.onNodeWithText("Username").performTextReplacement("   ")
        composeRule.onNodeWithText("Save Changes").assertIsNotEnabled()
    }

    @Test
    fun otherMembersNamesAreRejectedIgnoringCaseAndWhitespace() {
        show()
        composeRule.onNodeWithText("Username").performTextReplacement(" bO ")
        composeRule.onNodeWithText("Save Changes").assertIsNotEnabled()
    }

    @Test
    fun closingDoesNotSaveUnsubmittedChanges() {
        var saved: String? = null
        show { saved = it }
        composeRule.onNodeWithText("Username").performTextReplacement("Alex")
        composeRule.onNodeWithText("Close").performClick()
        assertEquals(null, saved)
    }

    @Test
    fun ownUnchangedNameIsAllowedAndDoesNotNeedSaving() {
        show()
        composeRule.onNodeWithText("Ana").assertIsDisplayed()
        composeRule.onNodeWithText("Save Changes").assertDoesNotExist()
    }
}
