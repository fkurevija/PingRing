package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fkurevija.pingring.data.DiscoveredSession
import com.fkurevija.pingring.ui.screens.PingRingJoinScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The join screen's list of sessions found nearby.
 *
 * Only discoverable hosts ever reach this list, and that filtering happens in
 * the decoder and the repository - what is testable here is that whatever
 * arrives is presented honestly, particularly a session that is visible but
 * cannot be joined.
 */
class JoinScreenNearbySessionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        discoveredSessions: List<DiscoveredSession> = emptyList(),
        onJoinEvent: (String, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            MaterialTheme {
                PingRingJoinScreen(
                    savedUsername = "Alex",
                    existingMembers = emptyList(),
                    isConnecting = false,
                    discoveredSessions = discoveredSessions,
                    onBack = {},
                    onJoinEvent = onJoinEvent
                )
            }
        }
    }

    @Test
    fun aNearbySessionIsListedByName() {
        show(discoveredSessions = listOf(DiscoveredSession("4C1A9B", "PingRing 4C1A9B", joinable = true)))

        // The name, not the bare code: a list of six-character strings is a list
        // of things to read aloud rather than recognise.
        composeRule.onNodeWithText("PingRing 4C1A9B").assertIsDisplayed()
    }

    @Test
    fun severalNearbySessionsAreAllListed() {
        show(
            discoveredSessions = listOf(
                DiscoveredSession("4C1A9B", "PingRing 4C1A9B", joinable = true),
                DiscoveredSession("FF00AA", "PingRing FF00AA", joinable = true)
            )
        )

        composeRule.onNodeWithText("PingRing 4C1A9B").assertIsDisplayed()
        composeRule.onNodeWithText("PingRing FF00AA").assertIsDisplayed()
    }

    @Test
    fun tappingASessionJoinsItByItsCode() {
        var joined: String? = null
        show(
            discoveredSessions = listOf(DiscoveredSession("4C1A9B", "PingRing 4C1A9B", joinable = true)),
            onJoinEvent = { code, _ -> joined = code }
        )

        composeRule.onNodeWithText("PingRing 4C1A9B").performClick()
        composeRule.waitForIdle()

        assertEquals("4C1A9B", joined)
    }

    @Test
    fun aSessionThatCannotBeJoinedSaysSoRatherThanFailingSilently() {
        show(discoveredSessions = listOf(DiscoveredSession("4C1A9B", "PingRing 4C1A9B", joinable = false)))

        // Visible but not joinable, with the reason on screen. A row that looked
        // joinable and then did nothing is the worse version of this.
        composeRule.onNodeWithText("PingRing 4C1A9B").assertIsDisplayed()
        composeRule.onNodeWithText("Full or not accepting members").assertIsDisplayed()
    }

    @Test
    fun nothingIsListedWhenNoSessionsAreNearby() {
        // The code entry is the fallback and must not be displaced by an empty
        // "Nearby PingRings" heading.
        show(discoveredSessions = emptyList())

        composeRule.onNodeWithText("Nearby PingRings").assertDoesNotExist()
        composeRule.onNodeWithText("Enter PingRing Code").assertIsDisplayed()
    }
}
