package com.fkurevija.pingring

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.ui.screens.HostSetupScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The screen a host configures a new PingRing on.
 *
 * The one thing worth asserting here is the route to the full settings: this
 * screen carries a subset of the event switches, and the two new ones -
 * Discoverable and Approve Each Join - exist only on PingRing Settings. A host
 * who configures here has to be able to reach them, or they are invisible.
 */
class HostSetupScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun show(onOpenSettings: () -> Unit = {}) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The screen asks the repository only whether this device can range, so
        // a repository is needed but no radio is ever touched. The production
        // transport is used rather than the debug one, which would need a relay
        // host to connect to.
        val repository = DiscoveryRepository(
            context = context,
            sessionManager = com.fkurevija.pingring.data.SessionManager(),
            nearbyConnectionsManager = com.fkurevija.pingring.data.NearbyConnectionsManager(context)
        )
        composeRule.setContent {
            MaterialTheme {
                HostSetupScreen(
                    eventCode = "4C1A9B",
                    savedUsername = "Alex",
                    discoveryRepository = repository,
                    onEndEvent = {},
                    onSaveUsername = {},
                    onEventChange = { _, _, _, _, _ -> },
                    onView = {},
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }

    @Test
    fun theFullSettingsAreReachableFromTheHostScreen() {
        var opened = 0
        show(onOpenSettings = { opened++ })

        composeRule.onNodeWithText("All Settings").assertIsDisplayed()
        composeRule.onNodeWithText("All Settings").performClick()
        composeRule.waitForIdle()

        assertEquals(1, opened)
    }

    @Test
    fun theEventCodeIsShownSoTheHostCanShareIt() {
        show()

        // The code is the only way somebody can join without the session being
        // discoverable, so it has to be readable here.
        composeRule.onNodeWithText("4C1A9B").assertIsDisplayed()
    }
}
