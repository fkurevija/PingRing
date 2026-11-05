package com.combustiblelemonlabs.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.combustiblelemonlabs.pingring.data.session.MAX_PARTICIPANTS
import com.combustiblelemonlabs.pingring.ui.screens.PingRingJoinScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository

/**
 * The join screen, driven on a device.
 *
 * This screen was the one place in the app with no UI test at all, which is a bad
 * gap to leave: it is one of the two journeys the home screen offers, so if its
 * fields stopped accepting input or its Connect button stopped reporting the code
 * the host read out, nothing else in the suite would have noticed.
 *
 * Reachability is asserted from both ends. The home screen's Join callback is
 * covered in [HomeScreenInstrumentedTest]; what is pinned here is that this
 * screen offers a way back and a way forward, and that it refuses the three
 * things it must refuse before it hands anything back to the caller.
 */
class PingRingJoinScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The two editable fields, in declaration order: Username, then PingRing Code.
     *
     * Matched on [hasSetTextAction] rather than on the field's label, because a
     * label is only a label until Compose merges it into the field's semantics -
     * and when it does, the same text then matches both the field and the label.
     * The screen has exactly two editable nodes and their order is fixed by the
     * layout, so indexing them is unambiguous in a way a text match is not.
     */
    private fun usernameField() = composeRule.onAllNodes(hasSetTextAction())[0]
    private fun codeField() = composeRule.onAllNodes(hasSetTextAction())[1]

    private fun showJoin(
        savedUsername: String = "",
        existingMembers: List<String> = emptyList(),
        onBack: () -> Unit = {},
        onJoinEvent: (String, String, String?) -> Unit = { _, _, _ -> },
    ) {
        composeRule.setContent {
            MaterialTheme {
                PingRingJoinScreen(
                    savedUsername = savedUsername,
                    existingMembers = existingMembers,
                    onBack = onBack,
                    onJoinEvent = onJoinEvent,
                )
            }
        }
    }

@Test
    fun thereIsAWayBackToWhereYouCameFrom() {
        var backs = 0
        showJoin(onBack = { backs++ })

        // Nothing is live on this screen, so back means "never mind". It is
        // asserted because a screen with no back affordance strands a user who
        // opened it by mistake with nothing to press.
        composeRule.onNodeWithContentDescription(context.getString(R.string.back))
            .assertIsDisplayed()
            .performClick()

        assertEquals("back must leave the join screen", 1, backs)
    }

    @Test
    fun aNameAndAValidCodeJoinTheRing() {
        var joined: String? = null
        var name: String? = null
        showJoin(onJoinEvent = { code, username, _ ->
            joined = code
            name = username
        })

        usernameField().performTextInput("Alex")
        codeField().performTextInput("4C1A9B")
        composeRule.onNodeWithText(context.getString(R.string.connect)).performClick()
        composeRule.waitForIdle()

        // The code is handed on normalised to upper case, because the host reads
        // theirs aloud in capitals and a user typing that back must not be told
        // the code is invalid for being lower case.
        assertEquals("4C1A9B", joined)
        assertEquals("Alex", name)
    }

    @Test
    fun aLowercaseCodeIsAcceptedBecauseTheHostReadsTheirsAloud() {
        var joined: String? = null
        showJoin(onJoinEvent = { code, _, _ -> joined = code })

        usernameField().performTextInput("Alex")
        codeField().performTextInput("4c1a9b")
        composeRule.onNodeWithText(context.getString(R.string.connect)).performClick()
        composeRule.waitForIdle()

        assertEquals("4C1A9B", joined)
    }

    @Test
    fun aNameOfOnlySpacesIsRefused() {
        var joins = 0
        showJoin(onJoinEvent = { _, _, _ -> joins++ })

        // Left empty on purpose: the screen trims before the blank check, so "" is the
        // case that would otherwise produce a session named " ".
        usernameField().performTextInput("")
        codeField().performTextInput("4C1A9B")
        composeRule.onNodeWithText(context.getString(R.string.connect)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.join_username_required))
            .assertIsDisplayed()
        assertEquals("a blank name must not reach the join path", 0, joins)
    }

    @Test
    fun aNameAlreadyInTheRingIsRefused() {
        var joins = 0
        showJoin(existingMembers = listOf("Alex"), onJoinEvent = { _, _, _ -> joins++ })

        // Case-insensitive: two members differing only in case would be two
        // different-looking people in the radar with one name.
        usernameField().performTextInput("alex")
        codeField().performTextInput("4C1A9B")
        composeRule.onNodeWithText(context.getString(R.string.connect)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            context.getString(R.string.join_username_taken, "alex")
        ).assertIsDisplayed()
        assertEquals("a duplicate name must not reach the join path", 0, joins)
    }

    @Test
    fun aCodeThatIsNotSixCharactersIsRefused() {
        var joins = 0
        showJoin(onJoinEvent = { _, _, _ -> joins++ })

        usernameField().performTextInput("Alex")
        codeField().performTextInput("NOPE")
        composeRule.onNodeWithText(context.getString(R.string.connect)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.join_code_invalid))
            .assertIsDisplayed()
        assertEquals("a malformed code must not reach the join path", 0, joins)
    }

    @Test
    fun joiningAFullRingIsRefused() {
        var joins = 0
        val nearlyFull = (1 until MAX_PARTICIPANTS).map { "Member$it" }
        showJoin(existingMembers = nearlyFull, onJoinEvent = { _, _, _ -> joins++ })

        // The count is the same one DiscoveryRepository enforces over the radio.
        // The screen refusing it first is what stops a user being told they are
        // in only to be dropped by the host a second later.
        usernameField().performTextInput("Alex")
        codeField().performTextInput("4C1A9B")
        composeRule.onNodeWithText(context.getString(R.string.connect)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            context.getString(R.string.join_pingring_full, MAX_PARTICIPANTS)
        ).assertIsDisplayed()
        assertEquals("a full ring must not be joined from here", 0, joins)
    }

    @Test
    fun theScreenSaysWhatItWantsBeforeAnythingIsTyped() {
        showJoin()

        // The hint under the heading is the only thing telling a first-time user
        // that the code comes from the host. Losing it leaves two empty fields
        // and no idea where the six characters are supposed to come from.
        composeRule.onNodeWithText(context.getString(R.string.enter_pingring_code))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.ask_host_for_code))
            .assertIsDisplayed()
    }

    @Test
    fun nfcJoinOptionIsShownAlongsideQrAndCodeFallbacks() {
        showJoin()

        composeRule.onNodeWithText(context.getString(R.string.nfc_join_button))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.scan_host_qr))
            .assertIsDisplayed()
    }

    @Test
    fun nfcJoinAppearsAboveQrWithOrSeparatorBetween() {
        showJoin()

        val connect = composeRule.onNodeWithText(context.getString(R.string.connect)).getUnclippedBoundsInRoot()
        val firstSeparator = composeRule.onNodeWithTag("code-nfc-or").getUnclippedBoundsInRoot()
        val nfc = composeRule.onNodeWithTag("nfc-join-option").getUnclippedBoundsInRoot()
        val secondSeparator = composeRule.onNodeWithTag("join-methods-or").getUnclippedBoundsInRoot()
        val qr = composeRule.onNodeWithTag("qr-join-option").getUnclippedBoundsInRoot()

        assert(connect.bottom <= firstSeparator.top)
        assert(firstSeparator.bottom <= nfc.top)
        assert(nfc.bottom <= secondSeparator.top)
        assert(secondSeparator.bottom <= qr.top)
    }
}
