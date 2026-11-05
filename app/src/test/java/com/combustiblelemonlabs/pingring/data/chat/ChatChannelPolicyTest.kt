package com.combustiblelemonlabs.pingring.data.chat
import com.combustiblelemonlabs.pingring.data.chat.ChatChannelPolicy
import com.combustiblelemonlabs.pingring.data.chat.contentEqualsOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the chat notification channel has to be thrown away and rebuilt.
 *
 * Deleting a channel is destructive - it takes the user's own renaming and
 * importance choices with it - so it may only happen when the live channel
 * genuinely disagrees with what the settings ask for. Getting that wrong in
 * either direction is invisible on screen: recreate too eagerly and every
 * message silently discards the user's customisation; too strictly and the
 * sound toggle in settings does nothing at all.
 */
class ChatChannelPolicyTest {

    private val pattern = longArrayOf(0, 120, 90, 120)
    private val defaultUri = "content://media/internal/audio/media/7"

    private fun desired(sound: String? = defaultUri, vibration: LongArray? = pattern) =
        ChatChannelPolicy.DesiredConfiguration(sound, vibration)

    private fun live(sound: String? = defaultUri, vibration: LongArray? = pattern) =
        ChatChannelPolicy.LiveConfiguration(sound, vibration)

    @Test
    fun `a channel that has never been created only needs creating`() {
        assertTrue(ChatChannelPolicy.needsCreation(live = null))
        assertFalse(
            "a missing channel is created, not replaced",
            ChatChannelPolicy.needsRecreation(live = null, desired = desired())
        )
    }

    @Test
    fun `a matching channel is left alone`() {
        // The case that matters most: recreating here would discard the user's
        // customisation on every single incoming message.
        assertFalse(ChatChannelPolicy.needsRecreation(live(), desired()))
    }

    @Test
    fun `a channel read twice is still considered unchanged`() {
        // NotificationChannel hands back a fresh LongArray on every read, so an
        // identity comparison would see a difference every time and rebuild the
        // channel forever.
        val first = live(vibration = pattern.copyOf())
        val second = live(vibration = pattern.copyOf())

        assertFalse(ChatChannelPolicy.needsRecreation(second, desired()))
        assertEquals("two reads of one channel must compare equal", first, second)
    }

    @Test
    fun `turning sound off replaces the channel`() {
        assertTrue(
            ChatChannelPolicy.needsRecreation(
                live = live(sound = defaultUri),
                desired = desired(sound = null),
            )
        )
    }

    @Test
    fun `turning sound on replaces the channel`() {
        assertTrue(
            ChatChannelPolicy.needsRecreation(
                live = live(sound = null),
                desired = desired(sound = defaultUri),
            )
        )
    }

    @Test
    fun `turning vibration off replaces the channel`() {
        assertTrue(
            ChatChannelPolicy.needsRecreation(
                live = live(vibration = pattern.copyOf()),
                desired = desired(vibration = null),
            )
        )
    }

    @Test
    fun `turning vibration on replaces the channel`() {
        assertTrue(
            ChatChannelPolicy.needsRecreation(
                live = live(vibration = null),
                desired = desired(vibration = pattern),
            )
        )
    }

    @Test
    fun `changing the pattern itself replaces the channel`() {
        assertTrue(
            "a different pattern is a different channel",
            ChatChannelPolicy.needsRecreation(
                live = live(vibration = longArrayOf(0, 500, 500)),
                desired = desired(vibration = pattern),
            )
        )
    }
    @Test
    fun `a channel with neither sound nor vibration matches a silent setting`() {
        // Both absent must compare equal, which contentEquals on a non-null
        // receiver cannot express.
        assertFalse(
            ChatChannelPolicy.needsRecreation(
                live = live(sound = null, vibration = null),
                desired = desired(sound = null, vibration = null),
            )
        )
    }

    @Test
    fun `soundEnabledDecidesTheUriAndNothingElse`() {
        assertEquals(
            defaultUri,
            ChatChannelPolicy.soundFor(soundEnabled = true, defaultNotificationUri = defaultUri)
        )
        assertNull(ChatChannelPolicy.soundFor(soundEnabled = false, defaultNotificationUri = defaultUri))
    }

    @Test
    fun `vibrationEnabledDecidesThePatternAndNothingElse`() {
        assertEquals(
            pattern.toList(),
            ChatChannelPolicy.vibrationPatternFor(vibrationEnabled = true, pattern = pattern)!!.toList()
        )
        assertNull(ChatChannelPolicy.vibrationPatternFor(vibrationEnabled = false, pattern = pattern))
    }

    @Test
    fun `theInstalledPatternIsACopySoTheSharedConstantCannotBeMutated`() {
        // The pattern is a single shared LongArray on the notifier. Handing it
        // out directly would let a caller mutate it for every message afterwards.
        val installed = ChatChannelPolicy.vibrationPatternFor(vibrationEnabled = true, pattern = pattern)!!
        installed[1] = 9999

        assertEquals(120L, pattern[1])
    }

    @Test
    fun `absentPatternsOnBothSidesAreEqual`() {
        assertTrue(contentEqualsOrNull(null, null))
    }

    @Test
    fun `anAbsentPatternIsNotEqualToAPresentOne`() {
        assertFalse(contentEqualsOrNull(null, pattern))
        assertFalse(contentEqualsOrNull(pattern, null))
    }

    @Test
    fun `presentPatternsAreComparedByContent`() {
        assertTrue(
            contentEqualsOrNull(pattern.copyOf(), pattern.copyOf())
        )
        assertFalse(
            contentEqualsOrNull(pattern, longArrayOf(0, 120, 90, 121))
        )
    }

    @Test
    fun `configurationsWithDifferentPatternsAreNotEqual`() {
        // Guards the hand-written equals on the two holders, which exist only
        // because LongArray compares by identity.
        assertNotEquals(
            ChatChannelPolicy.LiveConfiguration(defaultUri, pattern),
            ChatChannelPolicy.LiveConfiguration(defaultUri, longArrayOf(1, 2, 3))
        )
        assertEquals(
            ChatChannelPolicy.DesiredConfiguration(defaultUri, pattern).hashCode(),
            ChatChannelPolicy.DesiredConfiguration(defaultUri, pattern.copyOf()).hashCode()
        )
    }

    @Test
    fun `aConfigurationIsNotEqualToAnUnrelatedType`() {
        assertFalse(ChatChannelPolicy.LiveConfiguration(null, null).equals("not a configuration"))
        assertFalse(ChatChannelPolicy.DesiredConfiguration(null, null).equals(null))
    }
}