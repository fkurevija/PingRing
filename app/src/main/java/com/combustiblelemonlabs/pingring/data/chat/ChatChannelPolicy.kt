package com.combustiblelemonlabs.pingring.data.chat
/**
 * Content comparison for an optional [LongArray], where "both absent" is equal.
 *
 * Distinct from [LongArray.contentEquals], which is only defined on non-null
 * receivers and so cannot express "neither side has a pattern" - which is a real
 * state for a notification channel with vibration turned off, and the one that
 * must not be mistaken for a difference.
 *
 * Top-level rather than a member so [ChatChannelPolicy]'s configuration holders
 * and any caller can share one definition instead of each re-deriving it.
 */
fun contentEqualsOrNull(a: LongArray?, b: LongArray?): Boolean = when {
    a == null && b == null -> true
    a == null || b == null -> false
    else -> a.contentEquals(b)
}

/**
 * Whether the chat notification channel has to be deleted and recreated.
 *
 * Android fixes a notification channel's sound and vibration when the channel is
 * created and gives the app no way to change them afterwards, so the only way
 * to honour an in-app setting toggle is to delete the channel and make a new
 * one. That is destructive - the user may have renamed the channel, or changed
 * its importance in system settings, and both go with it - so it must happen
 * only when the configuration genuinely disagrees.
 *
 * Two failure modes bracket this decision:
 *
 *  - Comparing too loosely, and a channel that already matches is deleted on
 *    every incoming message, silently discarding the user's customisation each
 *    time and re-posting a fresh notification.
 *  - Comparing too strictly, and toggling sound in settings does nothing,
 *    because the stale channel is left in place and the platform keeps using it.
 *
 * The comparison has to be by *content*, not identity: `NotificationChannel`
 * hands back a fresh `LongArray` on every read, so `==` on the pattern would
 * never match and the channel would be recreated forever.
 *
 * Kept free of Android types so the rule can be asserted directly, which a test
 * on [ChatNotifier] could not do without a live notification manager.
 */
object ChatChannelPolicy {

    /**
     * The live channel's configuration, as read back from the platform.
     *
     * [sound] and [vibrationPattern] are nullable exactly as the platform reports
     * them: null means "this channel has no sound" or "no pattern", which is a
     * real state and not the same as "unknown".
     */
    data class LiveConfiguration(
        val sound: String?,
        val vibrationPattern: LongArray?,
    ) {
        // LongArray has no structural equals, so a data class would compare the
        // pattern by identity and two reads of the same channel would differ.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LiveConfiguration) return false
            return sound == other.sound && contentEqualsOrNull(vibrationPattern, other.vibrationPattern)
        }

        override fun hashCode(): Int = 31 * (sound?.hashCode() ?: 0) + (vibrationPattern?.contentHashCode() ?: 0)
    }

    /** What the app wants the channel to be, given the current settings. */
    data class DesiredConfiguration(
        val sound: String?,
        val vibrationPattern: LongArray?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DesiredConfiguration) return false
            return sound == other.sound && contentEqualsOrNull(vibrationPattern, other.vibrationPattern)
        }

        override fun hashCode(): Int = 31 * (sound?.hashCode() ?: 0) + (vibrationPattern?.contentHashCode() ?: 0)
    }

    /**
     * True when [live] disagrees with [desired] and the channel must be replaced.
     *
     * A null [live] means the channel does not exist yet, which is a creation
     * rather than a replacement - the caller does the same thing either way, but
     * the distinction is what makes this testable.
     *
     * The two configurations are compared field by field rather than with
     * `equals`, because they are deliberately different types: [LiveConfiguration]
     * is what the platform reported and [DesiredConfiguration] is what the
     * settings ask for, and neither should be assignable to the other.
     */
    fun needsRecreation(live: LiveConfiguration?, desired: DesiredConfiguration): Boolean {
        if (live == null) return false
        return live.sound != desired.sound ||
            !contentEqualsOrNull(live.vibrationPattern, desired.vibrationPattern)
    }

    /** True when the channel has never been created and only needs creating. */
    fun needsCreation(live: LiveConfiguration?): Boolean = live == null

    /**
     * The vibration pattern to install for a given setting.
     *
     * Null means "no vibration at all", which is a distinct state from "the
     * platform default" - leaving the pattern unset when the user asked for no
     * vibration would let the channel keep whatever it had.
     */
    fun vibrationPatternFor(vibrationEnabled: Boolean, pattern: LongArray): LongArray? =
        if (vibrationEnabled) pattern.copyOf() else null

    /**
     * The sound to install for a given setting.
     *
     * The default notification URI is passed in rather than read from
     * `Settings.System` here, so this stays a pure function; null means silent.
     */
    fun soundFor(soundEnabled: Boolean, defaultNotificationUri: String): String? =
        if (soundEnabled) defaultNotificationUri else null
}