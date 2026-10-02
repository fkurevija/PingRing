package com.fkurevija.pingring

import com.fkurevija.pingring.data.ConnectionRecoveryPromptPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPromptPolicyTest {
    private fun shouldPrompt(
        nowMillis: Long,
        hostConnectionLostAtMillis: Long? = 10_000L,
        snoozedUntilMillis: Long = 0L,
        isGuest: Boolean = true,
        isRadarTopDestination: Boolean = true,
        activityResumed: Boolean = true,
        settingEnabled: Boolean = true,
        silencedForRing: Boolean = false,
    ) = ConnectionRecoveryPromptPolicy.shouldPrompt(
        hostConnectionLostAtMillis = hostConnectionLostAtMillis,
        configuredDelayMillis = 120_000L,
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