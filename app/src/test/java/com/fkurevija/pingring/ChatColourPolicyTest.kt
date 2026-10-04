package com.fkurevija.pingring

import com.fkurevija.pingring.data.ChatBubblePalette
import com.fkurevija.pingring.data.ChatColourPolicy
import com.fkurevija.pingring.data.ChatColourPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat bubble colour means one person, so holding one is a decision somebody
 * has to make.
 *
 * Grouped by the failure each case prevents. The destructive ones are the
 * best-effort shortcuts: allowing a colour that is already held, or resolving a
 * tie differently on two devices, each leaves two members rendering identically
 * while both believe the colour is theirs.
 */
class ChatColourPolicyTest {

    private val teal = ChatBubblePalette.presets[0]
    private val pink = ChatBubblePalette.presets[1]
    private val blue = ChatBubblePalette.presets[4]

    private fun claim(
        color: Long,
        peerId: String,
        holders: Map<Long, String> = emptyMap(),
        localPeerId: String = "host",
    ) = ChatColourPolicy.evaluateClaim(color, peerId, holders, localPeerId)

    @Test
    fun `an unheld colour is granted to whoever asks`() {
        assertEquals(Decision.GRANTED, claim(teal, "ana"))
        assertEquals(Decision.GRANTED, claim(teal, "ana", holders = mapOf(pink to "bo")))
    }

    @Test
    fun `a colour somebody else holds is refused`() {
        // The whole point. Granting it would leave two members rendering the same
        // colour and both certain the colour was theirs.
        assertEquals(
            Decision.REFUSED,
            claim(teal, "ana", holders = mapOf(teal to "bo")),
        )
    }

    @Test
    fun `re-claiming a colour you already hold is not a conflict`() {
        // Re-opening Chat Settings and confirming the current choice must not be
        // refused, or the setting could never be looked at without changing it.
        assertEquals(
            Decision.GRANTED,
            claim(teal, "bo", holders = mapOf(teal to "bo")),
        )
    }

    @Test
    fun `the host is granted its own pick without asking`() {
        // Otherwise the host would be waiting for permission from itself, which is
        // a deadlock rather than a rule.
        assertEquals(Decision.GRANTED, claim(teal, "host", localPeerId = "host"))
    }

    @Test
    fun `two members can never both be granted the same colour`() {
        // The invariant, swept over every ordering and several colours rather than
        // asserted once. A later change that admits a second grant fails here
        // whatever shape it takes.
        val colours = listOf(teal, pink, blue)
        val peers = listOf("ana", "bo", "cy")
        for (first in peers) {
            for (second in peers) {
                if (first == second) continue
                for (color in colours) {
                    val decision = claim(color, second, holders = mapOf(color to first))
                    assertFalse(
                        "$color was granted to $second while $first already held it",
                        decision == Decision.GRANTED,
                    )
                }
            }
        }
    }
@Test
    fun `the earliest of two simultaneous claims wins and the other is refused`() {
        // Both guests asked for the same colour in the same instant, so neither can
        // be told "somebody already had it" - the host has to choose, and both have
        // to reach the same conclusion about who.
        val competing = listOf("ana" to 1_000L, "bo" to 1_000L)

        assertTrue(
            "the earlier claim should win",
            ChatColourPolicy.shouldGrantAmongCompetingClaims(teal, "ana", 1_000L, competing),
        )
        assertFalse(
            "the later claim should lose",
            ChatColourPolicy.shouldGrantAmongCompetingClaims(teal, "bo", 1_000L, competing),
        )
    }

    @Test
    fun `claims stamped identically are still separated deterministically`() {
        // Two claims with the same timestamp must produce exactly one winner, and
        // it has to be the same one on every device - otherwise one guest is told
        // it won by the host and lost by the other guest.
        val competing = listOf("ana" to 5L, "bo" to 5L)

        val winners = listOf("ana", "bo").count { peerId ->
            ChatColourPolicy.shouldGrantAmongCompetingClaims(teal, peerId, 5L, competing)
        }

        assertEquals("exactly one of two tied claims must win", 1, winners)
    }

    @Test
    fun `a colour a member holds stays available to them`() {
        // Their colour is theirs. Greying it out would leave them unable to see
        // which colour is currently theirs, and unable to confirm it.
        val available = ChatColourPolicy.availableColors(
            localPeerId = "ana",
            currentHolders = mapOf(teal to "ana", pink to "bo"),
            presets = ChatBubblePalette.presets,
        )

        assertTrue("your own colour must remain selectable", teal in available)
        assertFalse("another member's colour must not be", pink in available)
    }

    @Test
    fun `available colours keep palette order and every colour is free to start`() {
        val available = ChatColourPolicy.availableColors(
            localPeerId = "ana",
            currentHolders = emptyMap(),
            presets = ChatBubblePalette.presets,
        )

        assertEquals(ChatBubblePalette.presets, available)
    }

    @Test
    fun `a taken colour reports who holds it, and a free one reports nobody`() {
        assertEquals("bo", ChatColourPolicy.holderOf(pink, mapOf(pink to "bo")))
        assertNull(ChatColourPolicy.holderOf(teal, mapOf(pink to "bo")))
    }

    @Test
    fun `more members than free colours refuses rather than sharing`() {
        // Twelve presets, eight members, so saturation is reachable. What matters
        // is that held colours are refused to a newcomer rather than shared - and
        // that the colours nobody holds stay available.
        val holders = ChatBubblePalette.presets.take(7).mapIndexed { index, color ->
            color to "peer-$index"
        }.toMap()

        val refused = holders.keys.count { color ->
            ChatColourPolicy.evaluateClaim(color, "newcomer", holders, "host") == Decision.REFUSED
        }

        assertEquals("every held colour must be refused to a newcomer", 7, refused)
        assertEquals(
            "the colours nobody holds must still be available",
            5,
            ChatColourPolicy.availableColors("newcomer", holders, ChatBubblePalette.presets).size,
        )
    }
}