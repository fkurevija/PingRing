package com.fkurevija.pingring

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.fkurevija.pingring.ui.screens.ConnectionSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectionSettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun connectionSwitchesHaveIndependentCallbacks() {
        var nearbyDigit by mutableStateOf(false)
        var recoveryPrompt by mutableStateOf(true)
        var recoveryDelay by mutableLongStateOf(120_000L)
        var joinWait by mutableLongStateOf(60_000L)

        composeRule.setContent {
            MaterialTheme {
                ConnectionSettingsScreen(
                    nearbyDigitComparisonEnabled = nearbyDigit,
                    onNearbyDigitComparisonEnabledChange = { nearbyDigit = it },
                    connectionRecoveryPromptEnabled = recoveryPrompt,
                    connectionRecoveryPromptDelayMillis = recoveryDelay,
                    onConnectionRecoveryPromptEnabledChange = { recoveryPrompt = it },
                    onConnectionRecoveryPromptDelayChange = { recoveryDelay = it },
                    joinWaitTimeoutMillis = joinWait,
                    onJoinWaitTimeoutChange = { joinWait = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.nearby_digit_comparison_title))
            .performScrollTo().assertIsOff().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(context.getString(R.string.connection_recovery_title))
            .performScrollTo().assertIsOn().performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(true, nearbyDigit)
            assertEquals(false, recoveryPrompt)
        }
    }
}
