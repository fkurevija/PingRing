package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fkurevija.pingring.data.settings.ThemeMode
import com.fkurevija.pingring.ui.screens.AppSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppSettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showSettings(
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        onThemeModeChange: (ThemeMode) -> Unit = {},
        onOpenNotifications: () -> Unit = {},
        onOpenConnection: () -> Unit = {},
        onOpenAccessibility: () -> Unit = {},
        showDeveloperOptions: Boolean = false,
        onOpenDeveloperOptions: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                AppSettingsScreen(
                    selectedLanguageTag = "en",
                    onLanguageSelected = {},
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onOpenNotifications = onOpenNotifications,
                    onOpenConnection = onOpenConnection,
                    onOpenAccessibility = onOpenAccessibility,
                    showDeveloperOptions = showDeveloperOptions,
                    onOpenDeveloperOptions = onOpenDeveloperOptions,
                    onOpenAbout = onOpenAbout,
                    onBack = {},
                )
            }
        }
    }

    private fun themeFieldShowing(value: String) =
        hasText(value) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    @Test
    fun themeModeIsChosenOnThisScreenRatherThanBehindAnAppearanceGroup() {
        showSettings(themeMode = ThemeMode.DARK)

        composeRule.onNodeWithText("Theme Mode").assertIsDisplayed()
        composeRule.onNode(themeFieldShowing("Dark Theme")).assertExists()
        composeRule.onNodeWithText("Appearance").assertDoesNotExist()
    }

    @Test
    fun notificationsAndConnectionSubMenusAreReachable() {
        var notificationsOpened = 0
        var connectionOpened = 0
        showSettings(
            onOpenNotifications = { notificationsOpened++ },
            onOpenConnection = { connectionOpened++ }
        )

        composeRule.onNodeWithText("Notifications").performScrollTo().performClick()
        composeRule.onNodeWithText("Connection").performScrollTo().performClick()

        assertEquals(1, notificationsOpened)
        assertEquals(1, connectionOpened)
    }

    @Test
    fun tappingAccessibilityCallsBack() {
        var opened = 0
        showSettings(onOpenAccessibility = { opened++ })

        composeRule.onNodeWithText("Accessibility").performScrollTo().performClick()

        assertEquals("Accessibility must be reachable from App Settings", 1, opened)
    }

    @Test
    fun aboutIsReachableAndIsNotADebugOnlyRow() {
        var opened = 0
        showSettings(showDeveloperOptions = false, onOpenAbout = { opened++ })

        composeRule.onNodeWithText("About").performScrollTo().performClick()

        assertEquals("About must be reachable from App Settings", 1, opened)
    }

    @Test
    fun developerOptionsAreHiddenInABuildThatIsNotDebug() {
        showSettings(showDeveloperOptions = false)

        composeRule.onNodeWithText("Developer Options").assertDoesNotExist()
    }

    @Test
    fun developerOptionsAreOfferedInADebugBuildAndLeadSomewhere() {
        var opened = 0
        showSettings(showDeveloperOptions = true, onOpenDeveloperOptions = { opened++ })

        composeRule.onNodeWithText("Developer Options").performScrollTo().performClick()

        assertEquals("Developer Options must be reachable when it is shown", 1, opened)
    }
}
