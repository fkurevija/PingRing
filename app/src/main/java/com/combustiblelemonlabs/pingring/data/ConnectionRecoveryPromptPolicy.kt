package com.combustiblelemonlabs.pingring.data

internal object ConnectionRecoveryPromptPolicy {
    const val SNOOZE_MILLIS = 120_000L

    fun dueAtMillis(
        hostConnectionLostAtMillis: Long?,
        configuredDelayMillis: Long,
        snoozedUntilMillis: Long,
    ): Long? {
        hostConnectionLostAtMillis ?: return null
        return maxOf(hostConnectionLostAtMillis + configuredDelayMillis, snoozedUntilMillis)
    }

    fun shouldPrompt(
        hostConnectionLostAtMillis: Long?,
        configuredDelayMillis: Long,
        snoozedUntilMillis: Long,
        nowMillis: Long,
        isGuest: Boolean,
        isRadarTopDestination: Boolean,
        activityResumed: Boolean,
        settingEnabled: Boolean,
        silencedForRing: Boolean,
    ): Boolean {
        val dueAt = dueAtMillis(hostConnectionLostAtMillis, configuredDelayMillis, snoozedUntilMillis)
            ?: return false
        return isGuest && isRadarTopDestination && activityResumed && settingEnabled &&
            !silencedForRing && nowMillis >= dueAt
    }
}