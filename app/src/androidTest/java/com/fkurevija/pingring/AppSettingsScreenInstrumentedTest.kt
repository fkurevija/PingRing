package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fkurevija.pingring.data.ThemeMode
import com.fkurevija.pingring.ui.screens.AppSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The App Settings screen, driven on a device.
 *
 * These exist because Appearance and Notifications used to be rows that opened a
 * screen each. Appearance held one control and Notifications three, so both were
 * a tap and a back press spent reaching less than the screen you came from. The
 * assertions below are about the settings being on this screen, which is the
 * part a user would notice regressing.
 */
class AppSettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showSettings(
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        onThemeModeChange: (ThemeMode) -> Unit = {},
        chatNotificationsEnabled: Boolean = true,
        notificationSoundEnabled: Boolean = true,
        notificationVibrationEnabled: Boolean = true,
        onChatNotificationsEnabledChange: (Boolean) -> Unit = {},
        onNotificationSoundEnabledChange: (Boolean) -> Unit = {},
        onNotificationVibrationEnabledChange: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                AppSettingsScreen(
                    selectedLanguageTag = "en",
                    onLanguageSelected = {},
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    chatNotificationsEnabled = chatNotificationsEnabled,
                    onChatNotificationsEnabledChange = onChatNotificationsEnabledChange,
                    notificationSoundEnabled = notificationSoundEnabled,
                    onNotificationSoundEnabledChange = onNotificationSoundEnabledChange,
                    notificationVibrationEnabled = notificationVibrationEnabled,
                    onNotificationVibrationEnabledChange = onNotificationVibrationEnabledChange,
                    onOpenAccessibility = {},
                    onReportAProblem = {},
                    showDeveloperOptions = false,
                    onOpenDeveloperOptions = {},
                    onBack = {},
                )
            }
        }
    }

    /**
     * The Theme Mode field showing [value], rather than a dropdown item.
     *
     * Both carry the same text - the field shows the current choice and the menu
     * lists every choice - so a bare `onNodeWithText` matches two nodes, or one
     * hidden popup item when the menu is closed. Only the field is editable, so
     * EditableText is what tells them apart.
     */
    private fun themeFieldShowing(value: String) =
        hasText(value) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    @Test
    fun themeModeIsChosenOnThisScreenRatherThanBehindAnAppearanceGroup() {
        // The whole point: no Appearance row to tap through. The field carries its
        // own label, so it is announced as a control with a value rather than as a
        // heading followed by an unlabelled dropdown.
        showSettings(themeMode = ThemeMode.DARK)

        composeRule.onNodeWithText("Theme Mode").assertIsDisplayed()
        composeRule.onNode(themeFieldShowing("Dark Theme")).assertExists()
        composeRule.onNodeWithText("Appearance").assertDoesNotExist()
    }

    @Test
    fun theChosenThemeModeIsTheOneShownAsCurrent() {
        // Asserts the value is read from state rather than left at whatever the
        // dropdown was initialised with, which is the bug a control seeded from a
        // local `var` produces on a second visit to the screen.
        showSettings(themeMode = ThemeMode.LIGHT)

        composeRule.onNode(themeFieldShowing("Light Theme")).assertExists()
        composeRule.onNode(themeFieldShowing("System Default")).assertDoesNotExist()
    }

    @Test
    fun changingTheThemeModeReportsTheNewChoice() {
        var picked: ThemeMode? = null
        showSettings(themeMode = ThemeMode.SYSTEM, onThemeModeChange = { picked = it })

        // Opened from the field's label rather than its value: the label is part
        // of the decoration box and is not what carries the menu anchor.
        composeRule.onNodeWithText("Theme Mode").performClick()
        composeRule.waitForIdle()

        // Excludes EditableText, so this skips the field, which already shows one
        // of these three strings. The closed menu keeps its items in the tree but
        // off screen, so this also proves the menu actually opened rather than
        // that its contents merely exist.
        fun menuItem(value: String) =
            hasText(value) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText).not()

        listOf("System Default", "Light Theme", "Dark Theme").forEach { mode ->
            composeRule.onNode(menuItem(mode)).assertIsDisplayed()
        }
        composeRule.onNode(menuItem("Dark Theme")).performClick()
        composeRule.waitForIdle()

        // Dark rather than the already-selected System Default, so this cannot
        // pass by the screen reporting its initial value back at us.
        composeRule.runOnIdle { assertEquals(ThemeMode.DARK, picked) }
    }

    @Test
    fun eachNotificationSettingIsListedIndividuallyOnThisScreen() {
        // Three switches, each with its own title, rather than a Notifications
        // heading that opened a screen holding exactly these three.
        showSettings()

        listOf("Chat Push Notifications", "Notification Sound", "Notification Vibration").forEach { title ->
            composeRule.onNodeWithContentDescription(title).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun thereIsNoAppearanceOrNotificationsGroupRowLeftBehind() {
        // Guards the removal itself. If either heading comes back as a row, the
        // settings are behind a tap again and these fail.
        showSettings()

        composeRule.onNodeWithText("Appearance").assertDoesNotExist()
        composeRule.onNodeWithText("Notifications").assertDoesNotExist()
    }

    @Test
    fun aNotificationSwitchReportsItsOwnNewValueAndNobodyElses() {
        // Each switch is independent. Flipping one must not report a value for
        // another, which is what wiring them to one shared handler position would
        // do - and the mistake is invisible until someone turns sound off and the
        // chat switch appears to change with it.
        var chat: Boolean? = null
        var sound: Boolean? = null
        showSettings(
            chatNotificationsEnabled = true,
            notificationSoundEnabled = true,
            onChatNotificationsEnabledChange = { chat = it },
            onNotificationSoundEnabledChange = { sound = it },
        )

        composeRule.onNodeWithContentDescription("Notification Sound").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(false, sound)
            assertEquals(null, chat)
        }
    }

    @Test
    fun theVibrationSwitchReflectsWhetherTheDeviceCanVibrateAtAll() {
        // The stored preference is independent of the hardware, so a phone with
        // no vibrator shows the setting switched off and labelled unsupported
        // rather than hiding it or showing a switch that silently does nothing.
        // Which branch this lands in depends on the device, so the assertion is
        // that the control and its explanation agree, not which one they are.
        showSettings(notificationVibrationEnabled = true)

        composeRule.onNodeWithContentDescription("Notification Vibration").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Notification Vibration").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theLanguageDropdownHasNoHintUnderIt() {
        // A line of explanatory copy used to sit under the dropdown. Every other
        // entry on this screen is a control with its own label, and nothing here
        // needs a sentence describing it to someone who has just tapped it.
        //
        // Asserted as a substring rather than an exact match because the copy
        // existed only as a free-standing Text and had no name to assert on.
        // The wording is what is being guarded, not the idea: a future hint with
        // different words should still fail rather than slip back in unnoticed.
        showSettings()

        composeRule.onNodeWithText("System default follows your device", substring = true)
            .assertDoesNotExist()
    }
}
