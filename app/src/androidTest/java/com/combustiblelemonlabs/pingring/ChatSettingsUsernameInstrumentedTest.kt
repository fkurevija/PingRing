package com.combustiblelemonlabs.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.chat.ChatBubblePalette
import com.combustiblelemonlabs.pingring.ui.screens.ChatSettingsDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ChatSettingsUsernameInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        readReceiptsEnabled: Boolean = true,
        onSave: (String) -> Unit = {},
        onColorSelected: (Long) -> Unit = {},
        onReadReceiptsChanged: (Boolean) -> Unit = {},
    ) {
        val name = mutableStateOf("Ana")
        composeRule.setContent {
            MaterialTheme {
                ChatSettingsDialog(
                    selectedColorArgb = null,
                    onColorSelected = onColorSelected,
                    onDismiss = {},
                    username = name.value,
                    existingMemberNames = listOf("Bo"),
                    onUsernameChange = {
                        name.value = it
                        onSave(it)
                    },
                    readReceiptsEnabled = readReceiptsEnabled,
                    onReadReceiptsChanged = onReadReceiptsChanged,
                )
            }
        }
    }

    @Test
    fun validEditsAreSanitizedTrimmedAndSaved() {
        var saved: String? = null
        show(onSave = { saved = it })
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
        show(onSave = { saved = it })
        composeRule.onNodeWithText("Username").performTextReplacement("Alex")
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(null, saved)
    }

    @Test
    fun ownUnchangedNameIsAllowedAndDoesNotNeedSaving() {
        show()
        composeRule.onNodeWithText("Ana").assertIsDisplayed()
        composeRule.onNodeWithText("Save Changes").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun readReceiptPreferenceIsAppliedOnlyWhenSaved() {
        var savedSetting: Boolean? = null
        show(onReadReceiptsChanged = { savedSetting = it })

        composeRule.onNode(isToggleable()).performClick()
        assertEquals(null, savedSetting)
        composeRule.onNodeWithText("Save Changes").performClick()

        assertEquals(false, savedSetting)
    }

    @Test
    fun firstBubbleColourIsNotAppliedUntilSaveIsClicked() {
        var savedColor: Long? = null
        show(onColorSelected = { savedColor = it })
        val color = ChatBubblePalette.presets.first()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val swatchDescription = context.getString(
            R.string.set_chat_bubble_colour_to,
            String.format(Locale.ROOT, "#%06X", color and 0xFFFFFFL),
        )

        composeRule.onNodeWithContentDescription(swatchDescription).performClick()
        assertEquals(null, savedColor)
        composeRule.onNodeWithText("Save Changes").assertIsEnabled().performClick()

        assertEquals(color, savedColor)
    }
}
