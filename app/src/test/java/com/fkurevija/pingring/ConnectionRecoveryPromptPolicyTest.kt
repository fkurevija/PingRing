package com.fkurevija.pingring

import com.fkurevija.pingring.data.ConnectionRecoveryPromptPolicy
import com.fkurevija.pingring.data.DEFAULT_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS
import com.fkurevija.pingring.data.MAX_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS
import com.fkurevija.pingring.data.MIN_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS
import com.fkurevija.pingring.data.normalizeConnectionRecoveryPromptDelayMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPromptPolicyTest {
    private fun shouldPrompt(
        nowMillis: Long,
        configuredDelayMillis: Long = DEFAULT_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS,
        hostConnectionLostAtMillis: Long? = 10_000L,
        snoozedUntilMillis: Long = 0L,
        isGuest: Boolean = true,
        isRadarTopDestination: Boolean = true,
        activityResumed: Boolean = true,
        settingEnabled: Boolean = true,
        silencedForRing: Boolean = false,
    ) = ConnectionRecoveryPromptPolicy.shouldPrompt(
        hostConnectionLostAtMillis = hostConnectionLostAtMillis,
        configuredDelayMillis = configuredDelayMillis,
        snoozedUntilMillis = snoozedUntilMillis,
        nowMillis = nowMillis,
        isGuest = isGuest,
        isRadarTopDestination = isRadarTopDestination,
        activityResumed = activityResumed,
        settingEnabled = settingEnabled,
        silencedForRing = silencedForRing,
    )

    @Test
    fun promptWaitsForConfiguredDisconnectDelay() {
        assertFalse(shouldPrompt(nowMillis = 129_999L))
        assertTrue(shouldPrompt(nowMillis = 130_000L))
        assertTrue(shouldPrompt(nowMillis = 70_000L, configuredDelayMillis = MIN_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS))
        assertFalse(shouldPrompt(nowMillis = 200_000L, configuredDelayMillis = MAX_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS))
    }

    @Test
    fun configuredDelayIsClampedToTheSupportedRange() {
        assertEquals(MIN_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS, normalizeConnectionRecoveryPromptDelayMillis(0L))
        assertEquals(180_000L, normalizeConnectionRecoveryPromptDelayMillis(180_000L))
        assertEquals(MAX_CONNECTION_RECOVERY_PROMPT_DELAY_MILLIS, normalizeConnectionRecoveryPromptDelayMillis(Long.MAX_VALUE))
    }

    @Test
    fun snoozeMovesTheDeadlineWithoutChangingTheDisconnectTime() {
        assertEquals(
            250_000L,
            ConnectionRecoveryPromptPolicy.dueAtMillis(
                hostConnectionLostAtMillis = 10_000L,
                configuredDelayMillis = 120_000L,
                snoozedUntilMillis = 250_000L,
            ),
        )
        assertFalse(shouldPrompt(nowMillis = 249_999L, snoozedUntilMillis = 250_000L))
        assertTrue(shouldPrompt(nowMillis = 250_000L, snoozedUntilMillis = 250_000L))
    }

    @Test
    fun promptRequiresGuestForegroundRadarAndEnabledSetting() {
        assertFalse(shouldPrompt(nowMillis = 200_000L, isGuest = false))
        assertFalse(shouldPrompt(nowMillis = 200_000L, isRadarTopDestination = false))
        assertFalse(shouldPrompt(nowMillis = 200_000L, activityResumed = false))
        assertFalse(shouldPrompt(nowMillis = 200_000L, settingEnabled = false))
        assertFalse(shouldPrompt(nowMillis = 200_000L, silencedForRing = true))
        assertFalse(shouldPrompt(nowMillis = 200_000L, hostConnectionLostAtMillis = null))
    }
}