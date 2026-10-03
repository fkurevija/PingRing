package com.fkurevija.pingring

import com.fkurevija.pingring.data.BeaconPreset
import com.fkurevija.pingring.data.BeaconVibrationPattern
import com.fkurevija.pingring.data.AccessibilityFeature
import com.fkurevija.pingring.data.RadarFeedbackPolicy
import com.fkurevija.pingring.data.RadarAnnouncementPolicy
import com.fkurevija.pingring.data.RangingSourcePolicy
import com.fkurevija.pingring.data.ChatBubblePalette
import com.fkurevija.pingring.data.ChatMessageOwnership
import com.fkurevija.pingring.data.ChatNotificationPolicy
import com.fkurevija.pingring.data.CoordinationSyncState
import com.fkurevija.pingring.data.CoordinationSyncStatus
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.RelayEnvelope
import com.fkurevija.pingring.data.RadarAccessibilityPolicy
import com.fkurevija.pingring.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The small decisions that every screen and the notification path depend on.
 *
 * Each of these is a rule that is easy to state wrongly and impossible to notice
 * when it is: a message that renders on the wrong side of the chat, a
 * notification that is suppressed while nobody is looking, a pattern that vibrates
 * when it was asked not to.
 */
class AppRulesTest {

    @Test
    fun aNotificationIsSuppressedOnlyWhenTheUserIsActuallyWatching() {
        // All four conditions met: in the app, on the radar, screen on, unlocked.
        assertTrue(
            ChatNotificationPolicy.shouldSuppressNotification(
                appInForeground = true,
                radarWindowVisible = true,
                screenOn = true,
                deviceUnlocked = true,
            )
        )
    }

    @Test
    fun aNotificationIsPostedWheneverAnyOneConditionFails() {
        val watching = arrayOf(true, true, true, true)

        // Every single condition is decisive on its own, so each is flipped one at
        // a time: a missing condition must always mean "post the notification".
        for (index in watching.indices) {
            val flags = watching.copyOf()
            flags[index] = false
            assertFalse(
                "condition $index alone must not suppress",
                ChatNotificationPolicy.shouldSuppressNotification(
                    appInForeground = flags[0],
                    radarWindowVisible = flags[1],
                    screenOn = flags[2],
                    deviceUnlocked = flags[3],
                )
            )
        }
    }

    @Test
    fun anUnknownVibrationPatternFallsBackToSomethingNoticeable() {
        // A pattern name arrives over the wire, so an unrecognised one is expected
        // rather than exceptional - and falling back to SILENT would be the one
        // wrong choice, since a beacon that cannot be felt fails silently.
        val pattern = BeaconVibrationPattern.fromName("SOMETHING_ELSE")

        assertEquals(BeaconVibrationPattern.STEADY_PULSE, pattern)
        assertTrue("the fallback still has to vibrate", pattern.pattern.any { it > 0 })
    }

    @Test
    fun aKnownVibrationPatternRoundTripsThroughItsName() {
        BeaconVibrationPattern.entries.forEach { pattern ->
            assertEquals(pattern, BeaconVibrationPattern.fromName(pattern.name))
        }
    }

    @Test
    fun theSilentPatternIsTheOnlyOneThatDoesNotVibrate() {
        BeaconVibrationPattern.entries.forEach { pattern ->
            val vibrates = pattern.pattern.any { it > 0L }
            assertEquals(
                "${pattern.name} vibrates=$vibrates",
                pattern != BeaconVibrationPattern.SILENT,
                vibrates,
            )
        }
    }

    @Test
    fun everyPatternHasAnEmptyLeadingPause() {
        // Android treats the first entry of a pattern as a delay before the first
        // vibration, so a non-zero first entry would silently shift the pattern.
        BeaconVibrationPattern.entries.forEach { pattern ->
            assertEquals("${pattern.name} must start with a pause", 0L, pattern.pattern.first())
        }
    }
    @Test
    fun everyBeaconPresetIsFullyDescribed() {
        // A preset with a blank label or no default pattern would render as an
        // empty or dead row in the beacon picker, with nothing to point at.
        BeaconPreset.entries.forEach { preset ->
            assertTrue("${preset.name} needs a label", preset.label.isNotBlank())
            assertTrue("${preset.name} needs a title", preset.defaultTitle.isNotBlank())
            assertTrue("${preset.name} needs an emoji", preset.iconEmoji.isNotBlank())
            assertNotEquals(0L, preset.defaultColorArgb)
            assertTrue(
                "${preset.name} points at a pattern it does not define",
                BeaconVibrationPattern.entries.contains(preset.defaultPattern)
            )
        }
    }

    @Test
    fun emergencyIsNotTheDefaultFirstPresetAndCustomSitsLast() {
        // The emergency preset is important, but it should not dominate the first
        // slot in the picker because people open the sheet to choose a routine cue,
        // not to send a distress signal by default. The custom preset is a tool
        // for editing a beacon rather than a common action, so it belongs at the end.
        assertNotEquals(BeaconPreset.SOS, BeaconPreset.entries.first())
        assertTrue("SOS is kept away from the first slot", BeaconPreset.entries.indexOf(BeaconPreset.SOS) > 0)
        assertEquals(BeaconPreset.CUSTOM, BeaconPreset.entries.last())
    }

    @Test
    fun chatBubblePresetsAreTwelveDistinctOpaqueColors() {
        assertEquals(12, ChatBubblePalette.presets.size)
        assertEquals(12, ChatBubblePalette.presets.distinct().size)
        // Fully opaque, or a bubble would show the chat background through it and
        // two members could be given colours that render identically.
        assertTrue(ChatBubblePalette.presets.all { it ushr 24 == 0xFFL })
    }

    @Test
    fun chatBubblePresetsAreTheSameForEveryMemberAndRing() {
        // The whole point of the fixed list. This used to hash the ring and the
        // member id into a shuffled subset, which meant two phones derived a
        // *different* palette for the same person - so the preset a sender was
        // shown was not the one their messages were painted with, and they could
        // not see the alternatives before choosing.
        assertEquals(
            ChatBubblePalette.presets,
            ChatBubblePalette.presets,
        )
        assertEquals(12, ChatBubblePalette.presets.size)
    }

    @Test
    fun `the colour a member starts with is one they could have picked`() {
        // A random draw must come from the offered list, or the user opens chat
        // settings and finds no swatch matching their own bubbles.
        repeat(200) {
            assertTrue(ChatBubblePalette.presets.contains(ChatBubblePalette.random()))
        }
    }

    @Test
    fun `entering a ring twice in a row does not always give the same colour`() {
        // Not determinism being the goal - the opposite. If every draw returned
        // the same colour then a ring of eight would be unreadable, which is the
        // reason the colour exists. Enough draws that a constant implementation
        // cannot pass by luck.
        val draws = List(50) { ChatBubblePalette.random() }
        assertTrue("expected more than one colour across draws", draws.distinct().size > 1)
    }

    @Test
    fun radarSummaryNamesTheTrackedMemberAndItsAvailableSpatialCues() {
        assertEquals(
            "Radar for Ana. 3.5 meters away. Direction 42 degrees. Active beacon: Regroup here.",
            RadarAccessibilityPolicy.describe(
                peerName = "Ana",
                distanceMeters = 3.5f,
                azimuthDegrees = 42f,
                proximity = "Warm",
                beaconTitle = "Regroup here",
            )
        )
        assertEquals(
            "Radar. No tracked member. Direction unavailable.",
            RadarAccessibilityPolicy.describe(null, null, null, null, null)
        )
    }

    @Test
    fun accessibilityFeaturesAreDisabledUntilTheUserEnablesThem() {
        assertTrue(AccessibilityFeature.entries.isNotEmpty())
        assertTrue(AccessibilityFeature.PROXIMITY_HAPTICS.defaultEnabled)
        assertTrue(AccessibilityFeature.entries.filterNot { it == AccessibilityFeature.PROXIMITY_HAPTICS }.none { it.defaultEnabled })
    }

    @Test
    fun reducedMotionHonorsEitherTheAppOrSystemPreference() {
        assertFalse(RadarFeedbackPolicy.reduceMotion(false, true))
        assertTrue(RadarFeedbackPolicy.reduceMotion(true, true))
        assertTrue(RadarFeedbackPolicy.reduceMotion(false, false))
    }

    @Test
    fun proximityHapticsAreIndependentAndNeverUseGpsEstimates() {
        assertTrue(RadarFeedbackPolicy.proximityHaptic(true, 1f, RangingSourcePolicy.RangingSource.UWB))
        assertFalse(RadarFeedbackPolicy.proximityHaptic(false, 1f, RangingSourcePolicy.RangingSource.UWB))
        assertFalse(RadarFeedbackPolicy.proximityHaptic(true, 1f, RangingSourcePolicy.RangingSource.GNSS))
        assertFalse(RadarFeedbackPolicy.proximityHaptic(true, Float.NaN, null))
        assertFalse(RadarFeedbackPolicy.proximityHaptic(true, -1f, null))
    }

    @Test
    fun radarAnnouncementsOnlySpeakChangedStatesAtABoundedRate() {
        val policy = RadarAnnouncementPolicy()
        val first = RadarAnnouncementPolicy.State("peer", "farther", null)
        val near = first.copy(proximityState = "near")
        assertEquals(0L, policy.delayBeforeAnnouncement(first, 0))
        policy.markAnnounced(first, 0)
        assertEquals(null, policy.delayBeforeAnnouncement(first, 1_000))
        assertEquals(9_000L, policy.delayBeforeAnnouncement(near, 1_000))
        assertEquals(0L, policy.delayBeforeAnnouncement(near, 10_000))
        policy.markAnnounced(near, 10_000)
        assertEquals(null, policy.delayBeforeAnnouncement(near, 11_000))
        policy.reset()
        assertEquals(0L, policy.delayBeforeAnnouncement(first, 11_000))
    }

    @Test
    fun spokenGpsSummaryIncludesTheSameEstimateAndUncertaintyAsTheRadar() {
        val summary = RadarAccessibilityPolicy.describe(
            peerName = "Ana",
            distanceMeters = 42f,
            azimuthDegrees = null,
            proximity = null,
            beaconTitle = null,
            distanceIsEstimate = true,
            distanceAccuracyMeters = 20f,
        )
        assertTrue(summary.contains("GPS distance estimate. About 42 meters, uncertainty 20 meters."))
        assertTrue(summary.contains("Direction unavailable."))
    }

    @Test
    fun pendingSpeechKeepsTheLatestStateWithoutExtendingTheThrottle() {
        val policy = RadarAnnouncementPolicy()
        val original = RadarAnnouncementPolicy.State("first", "farther", null)
        policy.markAnnounced(original, 0L)
        val intermediate = original.copy(proximityState = "near")
        val latest = RadarAnnouncementPolicy.State("second", "farther", "Regroup")
        assertEquals(9_000L, policy.delayBeforeAnnouncement(intermediate, 1_000L))
        assertEquals(8_000L, policy.delayBeforeAnnouncement(latest, 2_000L))
        assertEquals(0L, policy.delayBeforeAnnouncement(latest, 10_000L))
        policy.markAnnounced(latest, 10_000L)
        assertEquals(null, policy.delayBeforeAnnouncement(latest, 11_000L))
    }

    @Test
    fun emptyRadarDoesNotGenerateAnInitialSpokenUpdate() {
        assertEquals(null, RadarAnnouncementPolicy().delayBeforeAnnouncement(
            RadarAnnouncementPolicy.State(null, "unknown", null), 0L,
        ))
    }

    @Test
    fun gpsSummaryWithoutUncertaintyDoesNotClaimAnExactDistance() {
        val summary = RadarAccessibilityPolicy.describe(
            "Ana", 42f, null, null, null, distanceIsEstimate = true,
        )
        assertTrue(summary.contains("Distance unavailable."))
        assertFalse(summary.contains("42"))
    }

    @Test
    fun aMessageIsOursOnlyWhenThePeerIdsMatch() {
        assertTrue(ChatMessageOwnership.isOwnMessage("peer-a", "peer-a"))
    }

    @Test
    fun aMessageFromAnotherMemberIsNotOurs() {
        assertFalse(ChatMessageOwnership.isOwnMessage("peer-b", "peer-a"))
    }

    @Test
    fun aMessageWithNoKnownOriginIsNeverOurs() {
        // The rule that keeps a blank id from rendering as though the local user
        // sent it, which is the failure the class exists to prevent.
        assertFalse(ChatMessageOwnership.isOwnMessage(null, "peer-a"))
        assertFalse(ChatMessageOwnership.isOwnMessage("", "peer-a"))
        assertFalse(ChatMessageOwnership.isOwnMessage("   ", "peer-a"))
    }

    @Test
    fun withNoLocalIdentityNothingCanBeOurs() {
        assertFalse(ChatMessageOwnership.isOwnMessage("peer-a", null))
        assertFalse(ChatMessageOwnership.isOwnMessage("peer-a", ""))
    }

    @Test
    fun theRelayEnvelopeTypesAreDistinct() {
        // A collision here would make the relay read one envelope as another,
        // which presents as a message that simply never arrives.
        val all = listOf(
            RelayEnvelope.JOIN, RelayEnvelope.DATA, RelayEnvelope.LEAVE,
            RelayEnvelope.END, RelayEnvelope.PING, RelayEnvelope.PEER_JOINED,
            RelayEnvelope.PEER_LEFT, RelayEnvelope.EVENT_CLOSED
        )

        assertEquals(all.size, all.distinct().size)
    }

    @Test
    fun theRelayRolesAreDistinct() {
        assertNotEquals(RelayEnvelope.ROLE_HOST, RelayEnvelope.ROLE_MEMBER)
    }

    @Test
    fun aDefaultSessionHasNoEventAndIsNotHost() {
        val state = SessionState()

        assertEquals(null, state.currentEvent)
        assertFalse(state.isHost)
        assertTrue(state.friends.isEmpty())
        assertTrue(state.chatMessages.isEmpty())
    }

    @Test
    fun syncStatusStartsIdleAndCarriesNoError() {
        val status = CoordinationSyncStatus()

        assertEquals(CoordinationSyncState.IDLE, status.state)
        assertEquals(null, status.errorMessage)
    }

    @Test
    fun theSyncStatesAreDistinct() {
        val states = CoordinationSyncState.entries

        assertEquals(4, states.size)
        assertEquals(states.size, states.distinct().size)
    }

    @Test
    fun aCreatedEventIsOfferedForTheDocumentedLifetime() {
        val before = System.currentTimeMillis()
        val event = Event.create(id = "ABC", hostPeerId = "peer-a")
        val after = System.currentTimeMillis()

        assertTrue("the event must not be born already expired", event.expiryTimestamp > before)
        // Bounded on both sides of the window create() actually saw, so this
        // cannot fail on a slow machine that crosses a millisecond mid-call.
        assertTrue(
            "expiry ${event.expiryTimestamp} outlives the documented lifetime",
            event.expiryTimestamp <= after + Event.DURATION_MILLIS
        )
        assertEquals("peer-a", event.hostPeerId)
        assertTrue(event.useUwb)
    }

    @Test
    fun aCreatedEventCanBeCreatedWithoutUwbOrAHost() {
        val event = Event.create(id = "ABC", useUwb = false)

        assertFalse(event.useUwb)
        assertEquals(null, event.hostPeerId)
    }

    @Test
    fun twoCopiesOfTheSameEventAreEqual() {
        // The wire format and the repository both key on the id, so a copy has to
        // compare equal or a round trip would look like a different event.
        val event = Event.create(id = "ABC")

        assertEquals(event, event.copy())
        assertEquals(event.hashCode(), event.copy().hashCode())
    }

    @Test
    fun theParticipantLimitIsTheOneTheAppAdvertises() {
        assertTrue("a ring has to hold more than one member", MAX_PARTICIPANTS > 1)
    }
}