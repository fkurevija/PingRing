package com.fkurevija.pingring

import com.fkurevija.pingring.data.ChatBubbleColorPolicy
import com.fkurevija.pingring.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A bubble's colour identifies a person, so it has to follow the person rather
 * than the moment the message was written.
 */
class ChatBubbleColorPolicyTest {

    private val red = 0xFFE53935
    private val blue = 0xFF1E88E5
    private val teal = 0xFF00897B

    private fun message(
        from: String,
        color: Long? = null,
        id: String = "m",
    ) = ChatMessage(
        id = id,
        senderPeerId = from,
        senderName = from,
        messageText = "hi",
        timestamp = 0L,
        colorArgb = color,
    )

    @Test
    fun `changing my colour repaints every message I have already sent`() {
        // The whole point. Both messages were sent in red; the member has since
        // chosen blue. Rendering the first one red would split the transcript in
        // two and make it look like two people.
        val transcript = listOf(message("me", red, "a"), message("me", red, "b"))
        val latest = ChatBubbleColorPolicy.latestColorsBySender(transcript)

        assertEquals(blue, ChatBubbleColorPolicy.colorFor(transcript[0], "me", blue, latest))
        assertEquals(blue, ChatBubbleColorPolicy.colorFor(transcript[1], "me", blue, latest))
    }

    @Test
    fun `a colour I have chosen applies before I have sent anything in it`() {
        // The member is looking at their swatch right now. The transcript has to
        // catch up to that choice, not the other way round.
        val transcript = listOf(message("me", red, "a"))
        val latest = ChatBubbleColorPolicy.latestColorsBySender(transcript)

        assertEquals(blue, ChatBubbleColorPolicy.colorFor(transcript[0], "me", blue, latest))
    }

    @Test
    fun `somebody else's change recolours their earlier messages too`() {
        // We cannot ask them, but the newest colour they have been seen using is
        // an observation rather than a guess, and a consistent transcript is worth
        // more than a historically accurate one.
        val transcript = listOf(
            message("ana", red, "a"),
            message("bo", teal, "b"),
            message("ana", blue, "c"),
        )
        val latest = ChatBubbleColorPolicy.latestColorsBySender(transcript)

        assertEquals(blue, ChatBubbleColorPolicy.colorFor(transcript[0], "me", null, latest))
        // Their last message already carries the new colour and is unchanged.
        assertEquals(blue, ChatBubbleColorPolicy.colorFor(transcript[2], "me", null, latest))
        // A different sender is unaffected by somebody else's change.
        assertEquals(teal, ChatBubbleColorPolicy.colorFor(transcript[1], "me", null, latest))
    }

    @Test
    fun `saved colour is written to all of that sender's existing history entries`() {
        val pink = 0xFFD81B60
        val transcript = listOf(
            message("ana", red, "a"),
            message("bo", teal, "b"),
            message("ana", blue, "c"),
        )

        val updated = ChatBubbleColorPolicy.updateSenderColor(transcript, "ana", pink)

        assertEquals(listOf(pink, teal, pink), updated.map { it.colorArgb })
        assertEquals(listOf(red, teal, blue), transcript.map { it.colorArgb })
    }

    @Test
    fun `one member changing colour does not repaint another member's messages`() {
        val transcript = listOf(message("ana", red, "a"), message("bo", blue, "b"))
        val latest = ChatBubbleColorPolicy.latestColorsBySender(transcript)

        assertEquals(red, ChatBubbleColorPolicy.colorFor(transcript[0], "me", blue, latest))
        assertEquals(blue, ChatBubbleColorPolicy.colorFor(transcript[1], "me", blue, latest))
    }

    @Test
    fun `a message from before colours existed keeps the themed style`() {
        // Not a member who failed to pick a colour - a message from an older
        // build. It has to keep rendering the way it always did.
        val legacy = message("ana", color = null)
        val latest = ChatBubbleColorPolicy.latestColorsBySender(listOf(legacy))

        assertNull(ChatBubbleColorPolicy.colorFor(legacy, "me", null, latest))
    }
    @Test
    fun `a legacy sender repaints once we learn what colour they are`() {
        // Mixed history is the normal case after an upgrade: the first messages
        // predate colours, the rest do not.
        //
        // The legacy bubbles DO get repainted here, and that is deliberate. A
        // themed bubble directly above a red bubble from the same person is the
        // exact split this feature exists to remove - the reader sees two
        // treatments and reasonably concludes there are two people. Consistency
        // is worth more here than historical accuracy, and the colour is not
        // invented: it is the one this person is demonstrably using.
        val transcript = listOf(
            message("ana", null, "a"),
            message("ana", red, "b"),
        )
        val latest = ChatBubbleColorPolicy.latestColorsBySender(transcript)

        assertEquals(red, ChatBubbleColorPolicy.colorFor(transcript[0], "me", null, latest))
        assertEquals(red, ChatBubbleColorPolicy.colorFor(transcript[1], "me", null, latest))
    }

    @Test
    fun `with nobody observed a sender falls back to the colour on the message`() {
        val lonely = message("ana", red)

        assertEquals(red, ChatBubbleColorPolicy.colorFor(lonely, "me", null, emptyMap()))
    }

    @Test
    fun `an unknown local identity does not repaint somebody else's messages as mine`() {
        // While the peer id is still resolving, treating a remote sender as ours
        // would paint their transcript in our colour. Falling back to observation
        // is the safe answer: slightly stale, but not wrong about who spoke.
        val transcript = listOf(message("ana", red))
        val latest = ChatBubbleColorPolicy.latestColorsBySender(transcript)

        assertEquals(red, ChatBubbleColorPolicy.colorFor(transcript[0], null, blue, latest))
        assertEquals(red, ChatBubbleColorPolicy.colorFor(transcript[0], "", blue, latest))
    }

    @Test
    fun `the latest colour per sender is the last one they actually used`() {
        val transcript = listOf(
            message("ana", red, "a"),
            message("bo", teal, "b"),
            message("ana", blue, "c"),
            message("ana", null, "d"), // legacy, must not erase what came before
        )

        val latest = ChatBubbleColorPolicy.latestColorsBySender(transcript)

        assertEquals(mapOf("ana" to blue, "bo" to teal), latest)
    }

    @Test
    fun `an empty transcript has no senders and no colours`() {
        assertEquals(
            emptyMap<String, Long>(),
            ChatBubbleColorPolicy.latestColorsBySender(emptyList()),
        )
    }

    @Test
    fun `repainting is stable no matter where in the transcript a message sits`() {
        // Every one of our messages must agree, which is the invariant a reader
        // actually checks when they scan the history looking for a colour change
        // that is not there.
        val transcript = (1..25).map { message("me", red, "m$it") }
        val latest = ChatBubbleColorPolicy.latestColorsBySender(transcript)

        val rendered = transcript.map {
            ChatBubbleColorPolicy.colorFor(it, "me", blue, latest)
        }

        assertEquals(25, rendered.size)
        assertEquals(setOf(blue), rendered.toSet())
    }
}