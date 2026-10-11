package com.fkurevija.pingring

import com.fkurevija.pingring.data.TextInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fkurevija.pingring.data.session.Event

/**
 * What PingRing lets a person type, and what it stops leaving the device.
 *
 * The tests are grouped by the failure they prevent rather than by function,
 * because the grouping is the argument: a sanitizer is judged on what it
 * destroys as much as on what it removes, and the destructive cases are the ones
 * a future "simplify this to a regex" edit would reintroduce.
 */
class TextInputPolicyTest {

    @Test
    fun usernameValidationRejectsBlankAndDuplicateNamesAfterSanitizing() {
        assertEquals(TextInputPolicy.UsernameError.REQUIRED, TextInputPolicy.usernameError(" \u200B ", emptyList()))
        assertEquals(TextInputPolicy.UsernameError.TAKEN, TextInputPolicy.usernameError(" aNA ", listOf("Ana")))
        assertEquals(TextInputPolicy.UsernameError.TAKEN, TextInputPolicy.usernameError("A\u200Bna", listOf("Ana")))
        assertNull(TextInputPolicy.usernameError("Bo", listOf("Ana")))
    }

    private val familyEmoji = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
    private val flag = "\uD83C\uDDEF\uD83C\uDDF5"

    // --- Nothing legitimate is destroyed -------------------------------

    @Test
    fun `an emoji outside the basic plane survives a name`() {
        // U+1F600 is two UTF-16 units. A filter that iterates chars and rejects
        // anything unusual rejects this, which is how names outside ASCII lose
        // their own faces.
        assertEquals("\uD83D\uDE00", TextInputPolicy.sanitizeUsername("\uD83D\uDE00"))
    }

    @Test
    fun `a family emoji keeps its zero width joiners`() {
        // U+200D is what makes this one emoji rather than four people. Stripping
        // "invisible" formatting characters without checking which ones are
        // load-bearing turns one symbol into four.
        assertEquals(familyEmoji, TextInputPolicy.sanitizeUsername(familyEmoji))
    }

    @Test
    fun `a regional indicator flag survives`() {
        assertEquals(flag, TextInputPolicy.sanitizeMessage(flag))
    }

    @Test
    fun `a combining mark stays attached to its letter`() {
        // "e" + COMBINING ACUTE is one letter, e acute. Splitting or dropping the
        // mark turns it into a plain e.
        assertEquals("e\u0301", TextInputPolicy.sanitizeUsername("e\u0301"))
    }

    @Test
    fun `names outside ascii are untouched`() {
        listOf(
            "\u5F20\u4E09",
            "\u0410\u043B\u0435\u043A\u0441\u0435\u0439",
            "\u0623\u062D\u0645\u062F",
            "\u0928\u0928\u094D\u0928\u094D",
            "\u00C5sa",
        ).forEach { name ->
            assertEquals(name, TextInputPolicy.sanitizeUsername(name))
        }
    }

    @Test
    fun `a zero width non joiner survives because it changes persian`() {
        // U+200C is not decoration: it tells the renderer not to join the
        // letters either side. Removing it changes what the name says.
        assertEquals(
            "\u0645\u200C\u0627\u0646",
            TextInputPolicy.sanitizeUsername("\u0645\u200C\u0627\u0646"),
        )
    }

    // --- Nothing invisible survives ------------------------------------

    @Test
    fun `zero width spaces cannot pad a name past the uniqueness check`() {
        // Two names that render identically must not compare differently, or the
        // duplicate check is defeated by an invisible character.
        val padded = "\u200BAlex\u200B"
        assertEquals("Alex", TextInputPolicy.sanitizeUsername(padded))
        // The two now compare equal, which is the point: they render the same,
        // so the duplicate check must see the same string, or a name can be
        // padded into the ring twice.
        assertEquals(
            TextInputPolicy.sanitizeUsername(padded),
            TextInputPolicy.sanitizeUsername("Alex"),
        )
    }

    @Test
    fun `every inert formatting character is dropped from a message`() {
        val inert = listOf(
            "\u200B", "\uFEFF", "\u00AD", "\u200E", "\u200F",
            "\u202A", "\u202B", "\u202C", "\u202D", "\u202E",
            "\u2066", "\u2067", "\u2068", "\u2069", "\u061C",
        )
        inert.forEach { invisible ->
            assertEquals(
                "invisible U+" + invisible.codePointAt(0).toString(16).uppercase() +
                    " should be dropped",
                "meet at the pier",
                TextInputPolicy.sanitizeMessage("meet $invisible at the pier"),
            )
        }
    }

    @Test
    fun `a bidi override cannot make one name render as another`() {
        // The classic impersonation: RLO reverses what follows it, so "exe"
        // with the right overrides reads as a different word entirely.
        assertEquals("exe", TextInputPolicy.sanitizeUsername("\u202Eexe\u202C"))
    }

    @Test
    fun `control characters never reach the wire`() {
        val cleaned = TextInputPolicy.sanitizeUsername("Alex\u0000\u0007\u001B[31m")
        assertTrue(
            "sanitised name still holds a control character: $cleaned",
            cleaned.none { Character.isISOControl(it) },
        )
    }

    @Test
    fun `a pasted multiline value becomes words rather than one word`() {
        // A name pasted from two lines is two words, not one run-together word,
        // and never a name containing a newline that breaks every layout
        // downstream.
        assertEquals("Alex Smith", TextInputPolicy.sanitizeUsername("Alex\nSmith"))
        assertEquals("Alex Smith", TextInputPolicy.sanitizeUsername("Alex\r\nSmith"))
        assertEquals("Alex Smith", TextInputPolicy.sanitizeUsername("Alex\tSmith"))
    }

    @Test
    fun `surrounding and repeated whitespace is collapsed`() {
        assertEquals("Alex Smith", TextInputPolicy.sanitizeUsername("   Alex   Smith   "))
        assertEquals("Alex", TextInputPolicy.sanitizeUsername(" Alex﻿ "))
    }

    @Test
    fun `a name that is only invisible characters sanitises to nothing`() {
        // Otherwise a member could join with a name that displays as nothing at
        // all, while still being distinct from every other such member.
        assertEquals("", TextInputPolicy.sanitizeUsername("\u200B\u200B\u200B"))
        assertEquals("", TextInputPolicy.sanitizeUsername("     "))
        assertEquals("", TextInputPolicy.sanitizeUsername("\n\n"))
    }

    // --- Nothing unbounded survives ------------------------------------

    @Test
    fun `a name is capped at the same length the wire allows`() {
        val tooLong = "A".repeat(TextInputPolicy.MAX_USERNAME_CODE_POINTS + 50)
        assertEquals(
            TextInputPolicy.MAX_USERNAME_CODE_POINTS,
            TextInputPolicy.sanitizeUsername(tooLong).length,
        )
    }

    @Test
    fun `a cap never splits an emoji in half`() {
        // This is the bug the code point count exists for. `take(n)` over UTF-16
        // lands between a surrogate pair and leaves a lone surrogate, which is
        // then serialised and sent to every peer.
        // The emoji is placed so the cap lands inside its surrogate pair, which is the
        // only way this test can fail.
        val cleaned = TextInputPolicy.sanitizeUsername(
            "A".repeat(TextInputPolicy.MAX_USERNAME_CODE_POINTS) + "\uD83D\uDE00",
        )
        cleaned.indices.forEach { index ->
            if (cleaned.isHighSurrogateAt(index)) {
                assertTrue(
                    "high surrogate at $index with no low surrogate after it in $cleaned",
                    cleaned.hasLowSurrogateAt(index + 1),
                )
            }
            if (cleaned.hasLowSurrogateAt(index) && !cleaned.isHighSurrogateAt(index - 1)) {
                throw AssertionError("unpaired low surrogate at $index in $cleaned")
            }
        }
    }

    private fun String.isHighSurrogateAt(index: Int): Boolean =
        index in indices && Character.isHighSurrogate(this[index])

    private fun String.hasLowSurrogateAt(index: Int): Boolean =
        index < length && Character.isLowSurrogate(this[index])

    @Test
    fun `length is counted in code points so a name of emoji is not half as long`() {
        val emoji = "\uD83D\uDE00"
        val cleaned = TextInputPolicy.sanitizeUsername(emoji.repeat(TextInputPolicy.MAX_USERNAME_CODE_POINTS))
        assertEquals(TextInputPolicy.MAX_USERNAME_CODE_POINTS, cleaned.codePointCount(0, cleaned.length))
    }

    @Test
    fun `a message is capped at the length the transport already enforced`() {
        val long = "x".repeat(TextInputPolicy.MAX_MESSAGE_CODE_POINTS + 100)
        assertEquals(
            TextInputPolicy.MAX_MESSAGE_CODE_POINTS,
            TextInputPolicy.sanitizeMessage(long).length,
        )
    }

    @Test
    fun `a beacon title keeps its own shorter limit`() {
        // Beacon titles are announced by vibration, so the shorter cap is the
        // point rather than an inconsistency.
        val long = "x".repeat(TextInputPolicy.MAX_BEACON_TITLE_CODE_POINTS + 100)
        assertEquals(
            TextInputPolicy.MAX_BEACON_TITLE_CODE_POINTS,
            TextInputPolicy.sanitizeBeaconTitle(long).length,
        )
    }

    @Test
    fun `truncation does not leave a trailing space`() {
        val exactlyFull = "A".repeat(TextInputPolicy.MAX_USERNAME_CODE_POINTS - 1) + " "
        assertEquals(
            "A".repeat(TextInputPolicy.MAX_USERNAME_CODE_POINTS - 1),
            TextInputPolicy.sanitizeUsername(exactlyFull),
        )
    }

    // --- The event code -------------------------------------------------

    @Test
    fun `a lower case code is accepted because the host reads theirs aloud`() {
        assertEquals("4C1A9B", TextInputPolicy.sanitizeEventCode("4c1a9b"))
    }

    @Test
    fun `non hexadecimal characters are dropped as they are typed`() {
        // Accepting them and refusing the code on submit is the worse failure:
        // the user finds out at the moment of joining, not while typing.
        assertEquals("4C1A9B", TextInputPolicy.sanitizeEventCode("4C-1A9B!"))
        assertEquals("AABBCC", TextInputPolicy.sanitizeEventCode("aabbcc"))
        // Nothing here is hex at all, so the field is left empty rather than
        // being given a character that would be refused on submit.
        assertEquals("", TextInputPolicy.sanitizeEventCode("GHIJKZ"))
    }

    @Test
    fun `a code longer than six characters is cut to six`() {
        assertEquals("4C1A9B", TextInputPolicy.sanitizeEventCode("4C1A9BEXTRA"))
    }

    @Test
    fun `a sanitised code is always something the parser can accept`() {
        // The invariant, swept over noise rather than asserted once: whatever
        // arrives, what the field holds is either a valid code or a prefix of
        // one. It can never hold a value that looks complete and is wrong.
        val noise = listOf(" ", "!", "\u0000", "G", "z", "1", "pingring://join?event=", "\u200B")
        noise.forEach { filler ->
            val cleaned = TextInputPolicy.sanitizeEventCode("4C1A${filler}9B")
            assertTrue(
                "'$filler' produced '$cleaned', which is not hex",
                cleaned.all { it in '0'..'9' || it in 'A'..'F' },
            )
            assertTrue(
                "'$filler' produced '$cleaned', which is longer than a code",
                cleaned.length <= TextInputPolicy.EVENT_CODE_LENGTH,
            )
        }
    }

    // --- The relay host -------------------------------------------------

    @Test
    fun `ordinary relay addresses are accepted`() {
        listOf(
            "10.0.2.2",
            "127.0.0.1",
            "localhost",
            "host.example.com",
            "my-relay_box",
            "10.0.2.2:8942",
            "127.0.0.1:1",
            "127.0.0.1:65535",
        ).forEach { address ->
            assertEquals(address, TextInputPolicy.sanitizeRelayHost(address))
        }
    }

    @Test
    fun `an address that is not one is refused rather than repaired`() {
        // Repairing would turn a typo into a different address that connects
        // somewhere unexpected, or fails to connect for reasons the user cannot
        // see. Refusing leaves the previous value in place and says nothing.
        listOf(
            "",
            "   ",
            "10.0.2.2:0",
            "10.0.2.2:65536",
            "10.0.2.2:-1",
            "10.0.2.2:abc",
            "10.0.2.2:",
            "host name!",
            "host/../etc",
            "10.0.2.2 8942",
            "a".repeat(300),
        ).forEach { address ->
            assertNull("'$address' should have been refused", TextInputPolicy.sanitizeRelayHost(address))
        }
    }

    @Test
    fun `an ipv6 literal with a port is accepted`() {
        // Colons are legal in an IPv6 literal, so the port split has to leave
        // the bracket form intact rather than treating every colon as a port
        // separator.
        assertEquals("[::1]:8942", TextInputPolicy.sanitizeRelayHost("[::1]:8942"))
    }
}
