package com.fkurevija.pingring.data.chat
import com.fkurevija.pingring.data.chat.ChatBubblePalette
import com.fkurevija.pingring.data.session.ChatColourPolicy
import com.fkurevija.pingring.data.session.ChatColourPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fkurevija.pingring.data.session.Event

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

    // Named for what they are, not for what they used to be: the palette changed
    // and these follow the list, so a stale name would quietly make the test read
    // as though it were about a colour it no longer covers.
    private val red = ChatBubblePalette.presets[0]
    private val cyan = ChatBubblePalette.presets[1]
    private val amber = ChatBubblePalette.presets[4]

    private fun claim(
        color: Long,
        peerId: String,
        holders: Map<Long, String> = emptyMap(),
        localPeerId: String = "host",
    ) = ChatColourPolicy.evaluateClaim(color, peerId, holders, localPeerId)

    @Test
    fun `an unheld colour is granted to whoever asks`() {
        assertEquals(Decision.GRANTED, claim(red, "ana"))
        assertEquals(Decision.GRANTED, claim(red, "ana", holders = mapOf(cyan to "bo")))
    }

    @Test
    fun `a colour somebody else holds is refused`() {
        // The whole point. Granting it would leave two members rendering the same
        // colour and both certain the colour was theirs.
        assertEquals(
            Decision.REFUSED,
            claim(red, "ana", holders = mapOf(red to "bo")),
        )
    }

    @Test
    fun `re-claiming a colour you already hold is not a conflict`() {
        // Re-opening Chat Settings and confirming the current choice must not be
        // refused, or the setting could never be looked at without changing it.
        assertEquals(
            Decision.GRANTED,
            claim(red, "bo", holders = mapOf(red to "bo")),
        )
    }

    @Test
    fun `the host is granted its own pick without asking`() {
        // Otherwise the host would be waiting for permission from itself, which is
        // a deadlock rather than a rule.
        assertEquals(Decision.GRANTED, claim(red, "host", localPeerId = "host"))
    }

    @Test
    fun `two members can never both be granted the same colour`() {
        // The invariant, swept over every ordering and several colours rather than
        // asserted once. A later change that admits a second grant fails here
        // whatever shape it takes.
        val colours = listOf(red, cyan, amber)
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
            ChatColourPolicy.shouldGrantAmongCompetingClaims(red, "ana", 1_000L, competing),
        )
        assertFalse(
            "the later claim should lose",
            ChatColourPolicy.shouldGrantAmongCompetingClaims(red, "bo", 1_000L, competing),
        )
    }

    @Test
    fun `claims stamped identically are still separated deterministically`() {
        // Two claims with the same timestamp must produce exactly one winner, and
        // it has to be the same one on every device - otherwise one guest is told
        // it won by the host and lost by the other guest.
        val competing = listOf("ana" to 5L, "bo" to 5L)

        val winners = listOf("ana", "bo").count { peerId ->
            ChatColourPolicy.shouldGrantAmongCompetingClaims(red, peerId, 5L, competing)
        }

        assertEquals("exactly one of two tied claims must win", 1, winners)
    }

@Test
    fun `switching colour hands the old one back`() {
        // The release half. Ana takes red, then changes her mind to cyan: red is
        // nobody's again and Bo must be able to take it. Without this Ana would
        // hold two colours for the rest of the ring and red would stay greyed out
        // for everyone while she no longer rendered in it.
        val afterTeal = ChatColourPolicy.applyGrantedClaim(emptyMap(), red, "ana")
        val afterPink = ChatColourPolicy.applyGrantedClaim(afterTeal, cyan, "ana")

        assertEquals("ana should hold exactly one colour", mapOf(cyan to "ana"), afterPink)
        assertNull(
            "the released colour must be free again",
            ChatColourPolicy.holderOf(red, afterPink),
        )
        assertEquals(
            "the released colour must be claimable by somebody else",
            Decision.GRANTED,
            claim(red, "bo", holders = afterPink),
        )
    }

    @Test
    fun `switching does not disturb anybody else's colour`() {
        // The release is scoped to the member who switched. Losing red must not
        // un-reserve a colour some third member is relying on.
        val holders = ChatColourPolicy.applyGrantedClaim(
            ChatColourPolicy.applyGrantedClaim(emptyMap(), red, "ana"),
            amber,
            "bo",
        )

        val after = ChatColourPolicy.applyGrantedClaim(holders, cyan, "ana")

        assertEquals("bo's colour must survive ana switching", "bo", ChatColourPolicy.holderOf(amber, after))
        assertEquals("ana's new colour is hers", "ana", ChatColourPolicy.holderOf(cyan, after))
        assertNull("ana's old colour is released", ChatColourPolicy.holderOf(red, after))
    }

    @Test
    fun `re-claiming the same colour does not release it`() {
        // Re-opening Chat Settings and confirming is not a switch. Releasing first
        // and re-claiming would work by accident here, but only because the grant
        // is unconditional for a colour you already hold - and if it were ever
        // refused the colour would be lost on a no-op edit.
        val holders = ChatColourPolicy.applyGrantedClaim(emptyMap(), red, "ana")

        val after = ChatColourPolicy.applyGrantedClaim(holders, red, "ana")

        assertEquals(mapOf(red to "ana"), after)
    }

    @Test
    fun `a member who leaves releases their colour`() {
        // Otherwise a departed member holds a colour for the rest of the event, and
        // with eight members and twelve colours four of them are gone for good.
        val holders = ChatColourPolicy.applyGrantedClaim(
            ChatColourPolicy.applyGrantedClaim(emptyMap(), red, "ana"),
            cyan,
            "bo",
        )

        val after = ChatColourPolicy.releaseDepartedMembers(holders, setOf("bo"))

        assertEquals("only the remaining member keeps a colour", mapOf(cyan to "bo"), after)
    }

    @Test
    fun `a released colour becomes available to pick in the UI`() {
        // The end-to-end statement of what the picker offers: the colours nobody
        // holds, plus your own. red was ana's, is now released, and must be back
        // on bo's list.
        val held = ChatColourPolicy.applyGrantedClaim(emptyMap(), red, "ana")
        val anaSwitched = ChatColourPolicy.applyGrantedClaim(held, cyan, "ana")

        val availableToBo = ChatColourPolicy.availableColors(
            localPeerId = "bo",
            currentHolders = anaSwitched,
            presets = ChatBubblePalette.presets,
        )

        assertTrue("the released colour must be offered again", red in availableToBo)
        assertFalse("a colour still held must not be", cyan in availableToBo)
    }

    @Test
    fun `switching and claiming can never produce two holders of one colour`() {
        // The invariant across the transition rather than at a point in time: after
        // any sequence of switches, every colour has at most one holder.
        var holders = emptyMap<Long, String>()
        val colours = listOf(red, cyan, amber)
        // A deliberately repetitive sequence: the same members switching back and
        // forth is the case that would strand a colour if release were missing.
        repeat(6) { round ->
            holders = ChatColourPolicy.applyGrantedClaim(holders, colours[round % 3], "ana")
            holders = ChatColourPolicy.applyGrantedClaim(holders, colours[(round + 1) % 3], "bo")
        }

        assertEquals(
            "each colour must have exactly one holder or none",
            emptySet<Any>(),
            holders.entries.groupBy { it.key }.filterValues { it.size > 1 }.keys,
        )
        assertEquals("neither member may hold two colours", 2, holders.values.toSet().size)
    }

    @Test
    fun `a colour a member holds stays available to them`() {
        // Their colour is theirs. Greying it out would leave them unable to see
        // which colour is currently theirs, and unable to confirm it.
        val available = ChatColourPolicy.availableColors(
            localPeerId = "ana",
            currentHolders = mapOf(red to "ana", cyan to "bo"),
            presets = ChatBubblePalette.presets,
        )

        assertTrue("your own colour must remain selectable", red in available)
        assertFalse("another member's colour must not be", cyan in available)
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
        assertEquals("bo", ChatColourPolicy.holderOf(cyan, mapOf(cyan to "bo")))
        assertNull(ChatColourPolicy.holderOf(red, mapOf(cyan to "bo")))
    }

    @Test
    fun `more members than free colours refuses rather than sharing`() {
        // Held colours are refused to a newcomer rather than shared, while every
        // preset nobody holds stays available.
        val holders = ChatBubblePalette.presets.take(7).mapIndexed { index, color ->
            color to "peer-$index"
        }.toMap()

        val refused = holders.keys.count { color ->
            ChatColourPolicy.evaluateClaim(color, "newcomer", holders, "host") == Decision.REFUSED
        }

        assertEquals("every held colour must be refused to a newcomer", 7, refused)
        assertEquals(
            "the colours nobody holds must still be available",
            ChatBubblePalette.presets.size - holders.size,
            ChatColourPolicy.availableColors("newcomer", holders, ChatBubblePalette.presets).size,
        )
    }
}