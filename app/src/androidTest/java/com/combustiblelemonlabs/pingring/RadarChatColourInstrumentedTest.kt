package com.combustiblelemonlabs.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.combustiblelemonlabs.pingring.data.chat.ChatBubblePalette
import com.combustiblelemonlabs.pingring.data.session.MAX_PARTICIPANTS
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.combustiblelemonlabs.pingring.data.chat.BeaconVibrationPattern
import com.combustiblelemonlabs.pingring.data.session.ChatMessage
import com.combustiblelemonlabs.pingring.data.radar.LastKnownPeerDirection
import com.combustiblelemonlabs.pingring.data.PendingJoinRequest
import com.combustiblelemonlabs.pingring.data.session.SessionState
import com.combustiblelemonlabs.pingring.ui.screens.RadarScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository

/**
 * The screen a live PingRing actually runs on, driven on a real device.
 *
 * This is the largest class in the app (644 lines) and none of it can be reached
 * from the JVM: a `@Composable` body does not execute until Compose composes it.
 * These tests cover it with `discoveryRepository = null`, which the screen
 * supports - every `?.` on it is deliberate, because the screen has to render
 * before discovery has produced anything. That makes the whole layout testable
 * with no radio, no BLE and no Play Services.
 *
 * The assertions are about what a user can see and do: the role-dependent
 * labels, and that leaving always asks first. Pixel geometry is deliberately not
 * asserted - the ring is drawn on a Canvas, and an angle or colour check would
 * break on any Compose upgrade without catching a real defect.
 */

class RadarChatColourInstrumentedTest : RadarScreenTestSupport() {
    @Test
    fun anEmptyChatSaysSoRatherThanShowingNothing() {
        showRadar()

        composeRule.onNodeWithText("No messages yet. Say hello!").assertIsDisplayed()
    }

    /**
     * A member who changes colour must still see every message they ever sent.
     *
     * The colour arithmetic itself is covered exhaustively by ChatBubbleColorPolicyTest;
     * what this pins down is the wiring - that the screen resolves "who am I" and
     * feeds the current colour through for *every* bubble, including the ones sent
     * before the change. Asserted on presence and attribution rather than on
     * pixels: this suite asserts on semantics throughout and has no pixel-capture
     * idiom, so a colour assertion here would be testing the harness.
     */
    @Test
    fun changingMyColourKeepsEveryMessageIAlreadySentInTheTranscript() {
        val oldColour = 0xFFE53935
        val newColour = 0xFF1E88E5
        showRadar(
            localPeerId = "me",
            chatBubbleColorArgb = newColour,
            sessionState = SessionState(
                chatMessages = listOf(
                    chatMessage("me", "first", colorArgb = oldColour),
                    chatMessage("me", "second", colorArgb = oldColour),
                )
            ),
        )

        // Both messages were sent in the old colour and must both survive the
        // change. A member who loses their own history when they recolour has
        // been made to look like a ring with two "me"s in it.
        composeRule.onNodeWithText("first").assertIsDisplayed()
        composeRule.onNodeWithText("second").assertIsDisplayed()
        composeRule.onNodeWithText("No messages yet. Say hello!").assertDoesNotExist()
    }

    @Test
    fun aMessageFromBeforeColoursExistedStillRendersInTheTranscript() {
        // Legacy messages carry no colour at all. They must keep rendering the way
        // they always have, rather than being dropped or crashing the bubble path
        // when the policy resolves null.
        showRadar(
            localPeerId = "me",
            sessionState = SessionState(
                chatMessages = listOf(
                    chatMessage("ana", "old message", colorArgb = null),
                )
            ),
        )

        composeRule.onNodeWithText("old message").assertIsDisplayed()
    }

    @Test
    fun aMixedTranscriptOfLegacyAndColouredMessagesStillRenders() {
        // The normal state after an upgrade: some messages predate colours, the
        // rest do not. This is the case where the sender's colour is learned from
        // a later message and applied backwards, so it is worth having on screen.
        showRadar(
            localPeerId = "me",
            sessionState = SessionState(
                chatMessages = listOf(
                    chatMessage("ana", "legacy one", colorArgb = null),
                    chatMessage("ana", "coloured one", colorArgb = 0xFFE53935),
                )
            ),
        )

        composeRule.onNodeWithText("legacy one").assertIsDisplayed()
        composeRule.onNodeWithText("coloured one").assertIsDisplayed()
    }

    @Test
    fun theSendButtonIsDisabledUntilSomethingIsTyped() {
        showRadar()

        // An always-enabled send button either drops an empty message or posts
        // one on every stray tap.
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun chatSettingsAndBeaconsShareOneRowWithChatSettingsOnTheLeft() {
        // The position is the point. Chat settings used to live on the PingRing
        // settings screen - two destinations and a chat's length away from the
        // chat they change - and it then sat stacked above the beacon button with
        // each filling the width, spending a second row of a short screen on a
        // short control.
        //
        // Asserted from measured bounds rather than from both labels merely
        // existing, because "both are on screen" is exactly what the old stacked
        // layout also satisfied. The order matters too: chat settings on the left.
        showRadar()
        composeRule.settle()

        val chatBounds = composeRule.onNodeWithText("Chat Settings").getUnclippedBoundsInRoot()
        val beaconBounds = composeRule.onNodeWithText(context.getString(R.string.beacons)).getUnclippedBoundsInRoot()

        assertTrue(
            "Chat Settings should sit left of Beacons, was " +
                "chat=${chatBounds.left} beacon=${beaconBounds.left}",
            chatBounds.left < beaconBounds.left,
        )
        // Same row rather than stacked: the two vertical spans have to overlap.
        assertTrue(
            "Chat Settings and Beacons should share one row, was " +
                "chat=${chatBounds.top}-${chatBounds.bottom} " +
                "beacon=${beaconBounds.top}-${beaconBounds.bottom}",
            chatBounds.top < beaconBounds.bottom && beaconBounds.top < chatBounds.bottom,
        )
    }

    @Test
    fun chatSettingsOffersTheTenPresetsAndSelectsTheirActualColour() {
        var selected: Long? = null
        showRadar(
            sessionState = SessionState(userName = "Ana"),
            chatBubbleColorArgb = ChatBubblePalette.presets.last(),
            onChatBubbleColorChange = { selected = it },
        )
        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        ChatBubblePalette.presets.forEach { colorArgb ->
            val label = context.getString(
                R.string.set_chat_bubble_colour_to,
                String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
            )
            composeRule.onNodeWithContentDescription(label)
                .performScrollTo()
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

        val chosenColor = ChatBubblePalette.presets.first()
        val chosenLabel = context.getString(
            R.string.set_chat_bubble_colour_to,
            String.format(java.util.Locale.ROOT, "#%06X", chosenColor and 0xFFFFFFL),
        )
        composeRule.onNodeWithContentDescription(chosenLabel).performClick()
        composeRule.settle()
        composeRule.onNodeWithText(context.getString(R.string.save_changes)).performClick()
        composeRule.settle()
        composeRule.runOnIdle { assertEquals(chosenColor, selected) }
    }

    @Test
    fun theChatColourAlreadyChosenIsTheOneMarkedAsSelected() {
        // The other half of picking a colour: the swatch for the colour already in
        // use has to read as chosen, or a member cannot tell where they are on the
        // dial before touching it. Asserted from the stored value rather than
        // after a tap because this screen runs on a paused frame clock, where a
        // click does not by itself drive the recomposition that flips the
        // swatch - the assertion then reads the tree as it was before the tap.
        val chosen = ChatBubblePalette.presets[4]
        showRadar(chatBubbleColorArgb = chosen)

        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        fun labelFor(colorArgb: Long) = context.getString(
            R.string.set_chat_bubble_colour_to,
            String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
        )
        composeRule.onNodeWithContentDescription(labelFor(chosen))
            .assertIsDisplayed()
            .assertIsSelected()
        ChatBubblePalette.presets.filter { it != chosen }.forEach { other ->
            composeRule.onNodeWithContentDescription(labelFor(other))
                .performScrollTo()
                .assertIsNotSelected()
        }
    }

    @Test
    fun aColourSomebodyElseHoldsIsOfferedAsTakenRatherThanSelectable() {
        // The visible half of uniqueness. Two members rendering the same colour is
        // what makes a busy transcript unreadable, so a swatch somebody else holds
        // must not be pickable - and must say whose it is, or the member is looking
        // at a disabled control with no explanation.
        //
        // The holder is named by its peer id, because the repository that maps ids to
        // display names is absent here and the id is the honest fallback. What is
        // under test is that the swatch is disabled and says who has it, not what
        // the name resolution does.
        val taken = ChatBubblePalette.presets[3]
        showRadar(
            chatBubbleColorArgb = ChatBubblePalette.presets[0],
            chatColourHolders = mapOf(taken to "peer-other"),
            localPeerId = "peer-me",
        )

        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.chat_colour_taken_by,
                String.format(java.util.Locale.ROOT, "#%06X", taken and 0xFFFFFFL),
                "peer-other",
            )
        )
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun aColourYouHoldIsNotOfferedAsTakenByYou() {
        // The regression the last one could hide: comparing against any holder
        // rather than any holder *other than us* would grey out our own colour and
        // leave a member unable to see or keep what they picked.
        val mine = ChatBubblePalette.presets[2]
        showRadar(
            chatBubbleColorArgb = mine,
            chatColourHolders = mapOf(mine to "peer-me"),
            localPeerId = "peer-me",
        )

        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        fun labelFor(colorArgb: Long) = context.getString(
            R.string.set_chat_bubble_colour_to,
            String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
        )
        composeRule.onNodeWithContentDescription(labelFor(mine))
            .assertIsDisplayed()
            .assertIsSelected()
            .assertIsEnabled()
    }

    @Test
    fun everyColourIsPickableWhenNobodyElseHoldsOne() {
        // The inverse, and the case the suite would otherwise never see: a ring of
        // one. If taken-ness were computed wrongly every swatch would be disabled
        // and a lone member could not choose at all.
        showRadar(chatColourHolders = emptyMap(), localPeerId = "peer-me")

        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        ChatBubblePalette.presets.forEach { colorArgb ->
            composeRule.onNodeWithContentDescription(
                context.getString(
                    R.string.set_chat_bubble_colour_to,
                    String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                )
            )
                .performScrollTo()
                .assertIsEnabled()
        }
    }

    @Test
    fun aRefusedPickSaysSoRatherThanFailingSilently() {
        // The host can refuse, and a member who tapped and saw nothing happen has
        // no other way to learn why. The message is the only feedback there is.
        val refused = ChatBubblePalette.presets[6]
        showRadar(refusedChatColourArgb = refused)

        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        composeRule.onNodeWithText(context.getString(R.string.chat_colour_refused))
            .assertIsDisplayed()
    }

    @Test
    fun chatSettingsDoesNotOfferACustomColourOrTheThemeColour() {
        // A member picks a colour so their messages can be found in a busy
        // transcript. Two phones rendering the same message differently defeats
        // that, so neither escape hatch is available.
        showRadar()
        composeRule.onNodeWithText("Chat Settings").performClick()
        composeRule.settle()

        composeRule.onNodeWithText(context.getString(R.string.custom)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.use_theme_colour)).assertDoesNotExist()
    }

}
