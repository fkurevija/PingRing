package com.combustiblelemonlabs.pingring

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import com.combustiblelemonlabs.pingring.ui.screens.PingRingCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.session.InviteSecret

/**
 * The invitation block, on its own.
 *
 * Host Setup and the radar's QR dialog both render this, so testing it here
 * rather than through either screen means the copy behaviour is covered once for
 * both surfaces - and it stays covered when a third one appears.
 */
class PingRingCodeBlockInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(eventCode: String = "4C1A9B") {
        composeRule.setContent {
            MaterialTheme {
                PingRingCodeBlock(eventCode = eventCode, inviteSecret = null)
            }
        }
    }

    private fun clipboardText(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }

    @Test
    fun longPressingTheCodePutsItOnTheClipboard() {
        // A code that gets read aloud is a code that gets mistyped, and pasting
        // it into a chat is the other thing a host does with it. Until this
        // existed that meant transcribing six characters by hand, one of which
        // is easy to mishear as another.
        show()

        composeRule.onNodeWithText("4C1A9B").performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals("4C1A9B", clipboardText())
    }

    @Test
    fun theCodeOffersACopyActionForAnyoneWhoCannotLongPress() {
        // A long press is a gesture with no keyboard or screen reader
        // equivalent. Without a named accessibility action the clipboard is
        // unreachable for exactly the people least able to transcribe six
        // characters correctly, so this is asserted rather than assumed.
        show()

        // Read off the raw semantics config rather than through a typed property,
        // because the public accessor for custom actions is not exposed in this
        // Compose version and has moved between releases. The key name has not.
        val entries = composeRule.onNodeWithText("4C1A9B")
            .fetchSemanticsNode()
            .config
            .map { it.key.name to it.value }
        val actions = entries
            .filter { (key, _) -> key.endsWith("CustomActions") }
            .flatMap { (_, value) -> (value as? List<*>) ?: emptyList() }

        assertTrue(
            "the code offers no custom action, only: ${entries.map { it.first }}",
            actions.any { (it as? CustomAccessibilityAction)?.label == "Copy PingRing Code" },
        )

        // And the action has to reach the clipboard, not merely exist.
        val copy = actions
            .mapNotNull { it as? CustomAccessibilityAction }
            .first { it.label == "Copy PingRing Code" }
        // runOnIdle because the action shows a Toast, and a Toast needs a Looper.
        // TalkBack activates semantics on the main thread; calling this straight
        // from the test thread does not.
        composeRule.runOnIdle { copy.action() }
        composeRule.waitForIdle()

        assertEquals("4C1A9B", clipboardText())
    }

    @Test
    fun theCodeIsShownWithItsLabelAndTheCapacityBeneathIt() {
        show()

        // The label and the value are one unit, and capacity is stated in the
        // same place on both surfaces rather than only on the one the host saw
        // before launching.
        composeRule.onNodeWithText("PingRing Code").assertExists()
        composeRule.onNodeWithText("4C1A9B").assertExists()
        composeRule.onNodeWithText("Max Capacity").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.max_capacity_description))
            .assertExists()
    }
}
