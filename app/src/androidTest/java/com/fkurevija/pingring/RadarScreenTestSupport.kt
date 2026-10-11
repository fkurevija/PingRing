package com.fkurevija.pingring

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
import com.fkurevija.pingring.data.chat.ChatBubblePalette
import com.fkurevija.pingring.data.session.MAX_PARTICIPANTS
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fkurevija.pingring.data.chat.BeaconVibrationPattern
import com.fkurevija.pingring.data.session.ChatMessage
import com.fkurevija.pingring.data.radar.LastKnownPeerDirection
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.session.SessionState
import com.fkurevija.pingring.ui.screens.RadarScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.fkurevija.pingring.data.DiscoveryRepository

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

abstract class RadarScreenTestSupport {

    @get:Rule
    val composeRule = PausedClockComposeContentTestRule()

    protected val eventId = "PING42"

    /** Real resources, so the assertions below can resolve the strings they match on. */
    protected val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A sent message, with the colour it carried at the time it was sent. */
    protected fun chatMessage(
        from: String,
        text: String,
        colorArgb: Long?,
    ) = ChatMessage(
        id = "$from-$text",
        senderPeerId = from,
        senderName = from,
        messageText = text,
        timestamp = 0L,
        colorArgb = colorArgb,
    )

    /**
     * Renders the screen with no discovery repository, as it looks before peers arrive.
     *
     * [PausedClockComposeContentTestRule] handles the clock: the radar ring pulses
     * forever, so the frame clock has to be manual or no assertion on this screen
     * would ever be reached.
     */
    protected fun showRadar(
        isHost: Boolean = false,
        coordinationError: String? = null,
        sessionState: SessionState = SessionState(),
        chatBubbleColorArgb: Long? = null,
        onChatBubbleColorChange: (Long) -> Unit = {},
        /**
         * The taken-colour state, which the screen would otherwise read from a
         * repository these tests do not have. Without it every test sees a ring of
         * one and cannot exercise the only interesting case.
         */
        chatColourHolders: Map<Long, String>? = null,
        refusedChatColourArgb: Long? = null,
        /**
         * Who "us" is, for the chat-colour picker.
         *
         * Without it the screen cannot tell the member's own colour from somebody
         * else's and greys out the wrong one, so both cases under test have to say
         * who the local member is.
         */
        localPeerId: String? = null,
        radarScreenReaderSummaryEnabled: Boolean = false,
        connectionRecoveryPromptEnabled: Boolean = true,
        connectionRecoveryPromptDelayMillis: Long = 120_000L,
        hostConnectionLostAtMillis: Long? = null,
        lastKnownPeerDirections: List<LastKnownPeerDirection> = emptyList(),
        isTopDestination: Boolean = true,
        pendingJoinRequests: List<PendingJoinRequest> = emptyList(),
        pendingJoinRequestsProvider: () -> List<PendingJoinRequest> = { pendingJoinRequests },
        onApproveJoinRequest: (String) -> Unit = {},
        onDenyJoinRequest: (String) -> Unit = {},
        onEventSettings: () -> Unit = {},
        onLeaveEvent: () -> Unit = {},
        onEndEvent: () -> Unit = {},
    ) {
        composeRule.setScreen {
            // Held in Compose state and fed back, mirroring MainActivity: the
            // selection is persisted and the settings flow reports the new value,
            // so the swatch re-renders as chosen. A callback that only recorded
            // the value would leave the dialog showing no colour as selected,
            // which is exactly the failure this wiring exists to prevent.
            var selectedColor by remember { mutableStateOf(chatBubbleColorArgb) }
            RadarScreen(
                eventId = eventId,
                isHost = isHost,
                coordinationError = coordinationError,
                discoveryRepository = null,
                sessionState = sessionState,
                chatBubbleColorArgb = selectedColor,
                chatColourHoldersOverride = chatColourHolders,
                refusedChatColourArgb = refusedChatColourArgb,
                localPeerIdOverride = localPeerId,
                onChatBubbleColorChange = {
                    selectedColor = it
                    onChatBubbleColorChange(it)
                },
                radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
                connectionRecoveryPromptEnabled = connectionRecoveryPromptEnabled,
                connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
                hostConnectionLostAtMillis = hostConnectionLostAtMillis,
                lastKnownPeerDirections = lastKnownPeerDirections,
                isTopDestination = isTopDestination,
                pendingJoinRequests = pendingJoinRequestsProvider(),
                onApproveJoinRequest = onApproveJoinRequest,
                onDenyJoinRequest = onDenyJoinRequest,
                onEventSettings = onEventSettings,
                onLeaveEvent = onLeaveEvent,
                onEndEvent = onEndEvent,
            )
        }
    }

}
