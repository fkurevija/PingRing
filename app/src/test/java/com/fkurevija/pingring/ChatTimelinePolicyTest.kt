package com.fkurevija.pingring

import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.data.ChatMessage
import com.fkurevija.pingring.data.ChatEntryType
import com.fkurevija.pingring.data.ChatTimelineItem
import com.fkurevija.pingring.data.ChatTimelinePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How messages and beacons are ordered together in one transcript.
 *
 * The behaviour that matters: a running beacon sits at the near end of the chat
 * because it is current state, and once it ends it moves back to where it was
 * raised. Nothing about how either renders depends on any of this.
 */
class ChatTimelinePolicyTest {

    private fun msg(id: String, at: Long) = ChatMessage(
        id = id,
        senderPeerId = "peer-a",
        senderName = "Ana",
        messageText = "hello",
        timestamp = at
    )

    private fun beacon(id: String, at: Long, active: Boolean = false) = BeaconInfo(
        id = id,
        senderPeerId = "peer-a",
        senderName = "Ana",
        title = "Regroup",
        colorArgb = 0xFF00E5FF,
        vibrationPattern = "RAPID_ALERT",
        isActive = active,
        timestamp = at
    )

    private fun systemEntry(
        id: String,
        at: Long,
        type: ChatEntryType,
        subjectName: String? = null,
        subjectPeerId: String? = null,
    ) = ChatMessage(
        id = id,
        senderPeerId = "host",
        senderName = "Host",
        messageText = "",
        timestamp = at,
        entryType = type,
        subjectName = subjectName,
        subjectPeerId = subjectPeerId,
    )

    private fun keys(items: List<ChatTimelineItem>) = items.map { it.key }

    @Test
    fun anActiveBeaconSitsAtTheNearEnd() {
        val items = ChatTimelinePolicy.build(
            messages = listOf(msg("m1", 1_000L), msg("m2", 2_000L)),
            activeBeacons = listOf(beacon("b1", 3_000L, active = true)),
            endedBeacons = emptyList(),
        )

        // Nearest the end last, because the transcript is reversed for display.
        assertEquals(listOf("m:m1", "m:m2", "b:b1"), keys(items))
    }

    @Test
    fun anEndedBeaconMovesBackToWhereItWasRaised() {
        // The whole point: it was said between m1 and m2, so that is where it
        // belongs once it has stopped, however long it ran.
        val items = ChatTimelinePolicy.build(
            messages = listOf(msg("m1", 1_000L), msg("m2", 4_000L)),
            activeBeacons = emptyList(),
            endedBeacons = listOf(beacon("b1", 2_000L)),
        )

        assertEquals(listOf("m:m1", "b:b1", "m:m2"), keys(items))
    }

    @Test
    fun aBeaconKeepsItsPlaceWhenItEnds() {
        // Same beacon, before and after. The key is what makes the move a
        // relocation rather than a delete and a fresh insert somewhere else.
        val whileRunning = ChatTimelinePolicy.build(
            messages = listOf(msg("m1", 1_000L), msg("m2", 4_000L)),
            activeBeacons = listOf(beacon("b1", 2_000L, active = true)),
            endedBeacons = emptyList(),
        )
        val afterEnding = ChatTimelinePolicy.build(
            messages = listOf(msg("m1", 1_000L), msg("m2", 4_000L)),
            activeBeacons = emptyList(),
            endedBeacons = listOf(beacon("b1", 2_000L)),
        )

        assertEquals(
            "the beacon must keep its key across the transition",
            whileRunning.map { it.key }.single { it.startsWith("b:") },
            afterEnding.map { it.key }.single { it.startsWith("b:") }
        )
        assertEquals(listOf("m:m1", "b:b1", "m:m2"), keys(afterEnding))
    }

    @Test
    fun runningBeaconsStayPinnedEvenWhenHistoryIsLonger() {
        // Pinned means pinned: however much has been said, a running beacon is
        // the thing happening now and belongs at the near end.
        val items = ChatTimelinePolicy.build(
            messages = (1..10).map { msg("m$it", it * 1_000L) },
            activeBeacons = listOf(beacon("b1", 500L, active = true)),
            endedBeacons = emptyList(),
        )

        assertEquals("b:b1", items.last().key)
    }

    @Test
    fun endedBeaconsAndMessagesAreInterleavedByTime() {
        val items = ChatTimelinePolicy.build(
            messages = listOf(msg("m1", 3_000L)),
            activeBeacons = emptyList(),
            endedBeacons = listOf(
                beacon("b1", 1_000L),
                beacon("b2", 4_000L),
                beacon("b3", 2_000L)
            ),
        )

        assertEquals(listOf("b:b1", "b:b3", "m:m1", "b:b2"), keys(items))
    }

    @Test
    fun anEmptyTranscriptIsEmpty() {
        val items = ChatTimelinePolicy.build(
            messages = emptyList(),
            activeBeacons = emptyList(),
            endedBeacons = emptyList(),
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun messagesOutOfOrderAreSortedIntoOrder() {
        // The host can replay a transcript in any order; the display order must
        // not depend on the order they arrived in.
        val items = ChatTimelinePolicy.build(
            messages = listOf(msg("m3", 3_000L), msg("m1", 1_000L), msg("m2", 2_000L)),
            activeBeacons = emptyList(),
            endedBeacons = emptyList(),
        )

        assertEquals(listOf("m:m1", "m:m2", "m:m3"), keys(items))
    }

    @Test
    fun sharedSystemNoticesAreOrderedWithMessages() {
        val created = systemEntry("created", 500L, ChatEntryType.PINGRING_CREATED)
        val joined = systemEntry(
            "joined",
            2_500L,
            ChatEntryType.MEMBER_JOINED,
            subjectName = "Ana",
            subjectPeerId = "peer-ana",
        )
        val items = ChatTimelinePolicy.build(
            messages = listOf(msg("m2", 3_000L), joined, msg("m1", 1_000L), created),
            activeBeacons = emptyList(),
            endedBeacons = emptyList(),
        )

        assertEquals(
            listOf("sys:created", "m:m1", "sys:joined", "m:m2"),
            keys(items),
        )
        assertEquals(created, (items[0] as ChatTimelineItem.SystemNotice).message)
        assertEquals(joined, (items[2] as ChatTimelineItem.SystemNotice).message)
    }

    @Test
    fun anActiveBeaconIsNeverOrderedIntoTheHistory() {
        // A beacon running *now* carries the timestamp of when it was raised,
        // which is usually old. Ordering that by time would drag a live beacon
        // into the middle of the transcript the moment it was set, which is the
        // pinning this policy exists to prevent.
        val items = ChatTimelinePolicy.build(
            messages = (1..5).map { msg("m$it", it * 1_000L) },
            activeBeacons = listOf(beacon("b1", 1L, active = true)),
            endedBeacons = emptyList(),
        )

        assertEquals("b:b1", items.last().key)
    }
}
