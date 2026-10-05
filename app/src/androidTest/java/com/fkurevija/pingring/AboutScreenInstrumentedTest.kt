package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fkurevija.pingring.data.DiagnosticsFacts
import com.fkurevija.pingring.ui.screens.AboutScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The About screen.
 *
 * Two things are worth asserting on screen. That the facts a bug report depends
 * on are actually visible, and that the privacy claim printed at the bottom of
 * the screen is true of what is printed above it.
 */
@RunWith(AndroidJUnit4::class)
class AboutScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val facts = DiagnosticsFacts(
        versionName = "1.4",
        versionCode = 7,
        isDebugBuild = false,
        applicationId = "com.fkurevija.pingring",
        deviceManufacturer = "Google",
        deviceModel = "Pixel 10",
        androidRelease = "17",
        apiLevel = 37,
        buildId = "BUILD.TEST",
        uwbSupported = true,
        signedBleIdentitySupported = false,
        bluetoothEnabled = true,
        wifiEnabled = false,
        hasVibrator = true,
    )

    private fun show(given: DiagnosticsFacts = facts) {
        composeRule.setContent {
            MaterialTheme {
                AboutScreen(facts = given, onBack = {})
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theBuildVersionAndCodeAreBothVisible() {
        show()

        // Name and code together: a triager cannot tell a released 1.4 from a
        // locally-built one given only the name.
        composeRule.onNodeWithText("1.4 (7)").assertIsDisplayed()
        composeRule.onNodeWithText("Release").assertIsDisplayed()
    }

    @Test
    fun theDeviceAndAndroidVersionAreVisible() {
        show()

        composeRule.onNodeWithText("Google Pixel 10").assertIsDisplayed()
        composeRule.onNodeWithText("17 (API 37)").assertIsDisplayed()
        composeRule.onNodeWithText("BUILD.TEST").assertIsDisplayed()
    }

    @Test
    fun capabilitiesReportTheFalseOnesNotJustTheTrueOnes() {
        // The interesting case in a bug report is a device where something is
        // *absent*, so the missing capabilities have to be readable just as
        // clearly as the present ones.
        //
        // Counted rather than matched one-by-one, because the values repeat:
        // five capability rows, of which UWB, Bluetooth and the vibrator are
        // present and signed BLE identity and Wi-Fi are not. Matching "Yes" alone
        // would also pass if the wrong number of rows rendered, which is the
        // thing most likely to break.
        show()

        composeRule.onAllNodesWithText("Yes").assertCountEquals(3)
        composeRule.onAllNodesWithText("No").assertCountEquals(2)
        composeRule.onNodeWithText("Signed BLE identity").assertIsDisplayed()
        composeRule.onNodeWithText("UWB hardware").assertIsDisplayed()
    }

    @Test
    fun aDebugBuildSaysSoRatherThanClaimingToBeRelease() {
        show(facts.copy(isDebugBuild = true))

        composeRule.onNodeWithText("Debug").assertIsDisplayed()
    }

    @Test
    fun thereIsAWayToCopyTheWholeReportInOneTap() {
        // The reason the rows exist. Reading eleven values off a screen and
        // typing them into an issue is how reports arrive with "1.O" in them.
        show()

        composeRule.onNodeWithText("Copy report for bug report").assertIsDisplayed()
    }

    @Test
    fun thePrivacyClaimIsAboutTheScreenAndNotAboutTheRestOfTheApp() {
        show()

        // Stated on the screen, so a member reading it knows what they are being
        // asked to share before they copy rather than after.
        composeRule.onNodeWithText(
            "This screen shows only your build, your device and your capabilities. " +
                "No username, event code, location or member details."
        ).assertIsDisplayed()
    }

    @Test
    fun nothingOnScreenIsTheUsersOwnIdentifyingDetails() {
        // The invariant, asserted on the rendered tree. The facts type has no
        // field a name or an event code could occupy, so this can only pass by
        // construction - but a future edit that threads a value in from outside
        // would be caught here.
        show()

        listOf(context.getString(R.string.app_name) + " · ", "@", "peer-", "4C1A9B")
            .forEach { fragment ->
                composeRule.onAllNodes(hasText(fragment, substring = true))
                    .assertCountEquals(0)
            }
    }

    @Test
    fun thereIsAWayToReportAProblemFromHere() {
        // The button moved here from App Settings, and this is the assertion
        // that says so. What is being protected is the path, not the pixel: a
        // member on this screen must be able to file a report without first
        // finding a second screen and copying anything by hand.
        var reported = 0
        composeRule.setContent {
            MaterialTheme {
                AboutScreen(facts = facts, onBack = {}, onReportAProblem = { reported++ })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Report a Problem").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(1, reported)
    }

    @Test
    fun reportingIsOfferedBelowTheFactsRatherThanAboveThem() {
        // Order matters: both actions carry the same eleven rows, so a member
        // should read them before choosing. Asserted by position, because both
        // labels are on screen at once and their mere presence proves nothing
        // about which is meant to be pressed.
        show()

        val copyY = composeRule.onNodeWithText("Copy report for bug report")
            .fetchSemanticsNode().boundsInRoot.top
        val reportY = composeRule.onNodeWithText("Report a Problem")
            .fetchSemanticsNode().boundsInRoot.top

        assertTrue("report button should sit below the facts", reportY > copyY)
    }

    @Test
    fun backIsReachable() {
        var backed = 0
        composeRule.setContent {
            MaterialTheme {
                AboutScreen(facts = facts, onBack = { backed++ })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        assertEquals(1, backed)
    }
}