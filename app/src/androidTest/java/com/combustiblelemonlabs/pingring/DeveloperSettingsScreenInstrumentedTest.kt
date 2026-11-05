package com.combustiblelemonlabs.pingring

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.combustiblelemonlabs.pingring.data.settings.AppearanceSettingsRepository
import com.combustiblelemonlabs.pingring.ui.screens.DeveloperSettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.TextInputPolicy

/**
 * The Developer Options screen, driven on a device.
 *
 * The relay host field is here because of a bug this file would have caught. The
 * field's value was only updated when [com.combustiblelemonlabs.pingring.data.TextInputPolicy.sanitizeRelayHost]
 * accepted it, and that refuses an empty string - so the backspace which emptied
 * the field was thrown away and the controlled value snapped straight back. The
 * last character could never be deleted.
 *
 * The visible consequence was a field holding a stale host that could be appended
 * to but not replaced, and the relay then never connected while the reason stayed
 * invisible. That is what the first test below pins down.
 */
class DeveloperSettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The host each test starts from.
     *
     * The emulator's own default, chosen so the value under test is one the app
     * would have anyway - the point is what happens when the field is edited, not
     * this particular address.
     */
    private val host = "10.0.2.2"

private fun showDeveloperOptions() {
        // The real repository, because the screen depends on the concrete type and
        // a fake would not exercise the DataStore write the bug was really about.
        val settings = AppearanceSettingsRepository(
            ApplicationProvider.getApplicationContext<Context>(),
        )
        // The field shows the *stored* host, and this suite shares a device with the
        // rest of the app's tests - so without setting it first these assertions
        // would be asserting whatever the last run happened to leave behind. That is
        // not hypothetical: the emulator carried a host of "1" from a manual session,
        // and every test here failed on a missing field rather than on the behaviour
        // it meant to check.
        //
        // The setters fire on the repository's own scope, so the write has to be
        // awaited through the flow rather than assumed done when the call returns.
        settings.setLanDebugEnabled(true)
        settings.setLanDebugHost(host)
        runBlocking {
            withTimeout(5_000) {
                settings.lanDebugHost.first { it == host }
                settings.lanDebugEnabled.first { it }
            }
        }
        composeRule.setContent {
            MaterialTheme {
                DeveloperSettingsScreen(
                    appearanceSettings = settings,
                    onBack = {},
                )
            }
        }
    }

    /**
     * The editable relay host field.
     *
     * Matched on EditableText alone, not on its current value: a matcher naming the
     * text stops finding the node the moment the field is edited, which is the whole
     * point of these tests. Telling it apart from the label and the hint is what
     * EditableText is for - all three carry text.
     *
     * No performScrollTo: this screen's content is a plain Column, not a scrollable
     * one, so asking to scroll fails with "no parent layout with a Scroll
     * SemanticsAction" on a node that is already on screen.
     */
    private fun relayHostField() =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    @Test
    fun theRelayHostFieldIsOfferedWhenLanDebugIsOn() {
        // The field only exists once the transport is switched on, so with the
        // switch off there is nothing here to reach at all.
        showDeveloperOptions()

        composeRule.onNodeWithText("Relay host", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRelayHostCanBeClearedAndRetypedRatherThanOnlyAppendedTo() {
        // The regression itself. Clearing the field is exactly what a person does to
        // replace a wrong host, and before the fix the final backspace was refused,
        // so the previous value survived and the next character was appended to it.
        showDeveloperOptions()

        relayHostField().performTextClearance()
        relayHostField().performTextInput("127.0.0.1")

        // A concatenation would read "10.0.2.2127.0.0.1", so this fails both when
        // the clearance did not take and when the new text was appended instead of
        // replacing what was there.
        composeRule.onNodeWithText("127.0.0.1").assertIsDisplayed()
        composeRule.onNodeWithText("$host" + "127.0.0.1").assertDoesNotExist()
    }

    @Test
    fun anInvalidHostIsRefusedRatherThanRepaired() {
        // The screen's stated contract, and the reason the fix was not simply to
        // accept everything: this field becomes a socket address, so a bad entry is
        // rejected instead of tidied. A dropped space would turn "10.0.2.2 8942"
        // into "10.0.2.28942" - a different machine, reached without a word of
        // warning.
        showDeveloperOptions()

        relayHostField().performTextInput("host name!")

        // The invalid entry is dropped, so the field still shows what it had.
        composeRule.onNodeWithText(host).assertIsDisplayed()
        composeRule.onNodeWithText("hostname!").assertDoesNotExist()
    }

    @Test
    fun clearingTheFieldLeavesItBlank() {
        // Empty is allowed as an editing state, so the field really does go blank -
        // this is the half of the fix that the regression test alone would not prove,
        // since a field that silently refused to clear would also fail to concatenate.
        showDeveloperOptions()

        relayHostField().performTextClearance()

        assertEquals(
            "the field should be blank after clearing",
            "",
            editableHost(),
        )
    }

    /**
     * The field's current text, read from the editable node itself.
     *
     * Asserted on EditableText rather than on a visible label so that an empty
     * field can be checked at all: matching on text cannot distinguish "blank" from
     * "absent", and every node on the screen matches an empty string.
     */
    private fun editableHost(): String {
        val node = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
        ).fetchSemanticsNode()
        val editable = node.config.getOrElseNullable(SemanticsProperties.EditableText) { null }
        return editable?.text ?: ""
    }
}
