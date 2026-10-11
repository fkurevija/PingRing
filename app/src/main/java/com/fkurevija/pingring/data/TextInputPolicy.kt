package com.fkurevija.pingring.data

import com.fkurevija.pingring.data.chat.MAX_BEACON_TITLE_LENGTH
import com.fkurevija.pingring.data.chat.MAX_CHAT_LENGTH
import com.fkurevija.pingring.data.chat.MAX_USERNAME_LENGTH

/**
 * What a person is allowed to type, and what is allowed to leave the device.
 *
 * Every free-text field in PingRing funnels through here rather than through its
 * own `onValueChange`, because the interesting cases are not per-field. The same
 * three things matter everywhere:
 *
 *  - **Nothing legitimate is destroyed.** Names and messages are the only place
 *    people put their own language, so emoji, combining marks, right-to-left
 *    scripts and Devanagari conjuncts all have to survive intact. A filter that
 *    "removes special characters" would quietly break every name outside ASCII,
 *    which is the worst thing a sanitizer can do.
 *  - **Nothing invisible survives.** Control characters and bidi overrides have
 *    no legitimate use in a name and are the whole toolkit for making two
 *    different strings render identically, which defeats the uniqueness check
 *    that names depend on.
 *  - **Nothing unbounded survives.** Length is counted in code points rather
 *    than `Char`s, because a `Char` is a UTF-16 unit and every emoji outside the
 *    BMP is two of them. `DiscoveryRepository` used to cap chat with `take(500)`,
 *    which can slice a surrogate pair in half and leave a lone surrogate in a
 *    message that is then serialised and sent to every peer.
 *
 * A note on zero-width joiners, because it looks like an oversight otherwise:
 * U+200D and U+200C are **kept**, while the other invisible formatting
 * characters are dropped. ZWJ builds family and profession emoji and the
 * conjuncts in Indic scripts, and ZWNJ changes the meaning of Persian text, so
 * stripping them would corrupt real names. What is dropped is the set with no
 * rendering job to do - zero-width space, the BOM used as a zero-width filler,
 * soft hyphen, and the bidi embedding and override controls.
 */
internal object TextInputPolicy {

    /**
     * A name has to fit a radar row and a chat bubble without truncating a
     * person mid-word. Derived from the [MAX_USERNAME_LENGTH] already enforced
     * at the transport edge rather than chosen here, so the field and the wire
     * cannot disagree about how long a name may be.
     */
    const val MAX_USERNAME_CODE_POINTS = MAX_USERNAME_LENGTH

    /**
     * Derived from the [MAX_CHAT_LENGTH] already enforced at the transport edge,
     * for the same reason. Enforced in code points as well as there in `Char`s,
     * so the two agree instead of one being twice as generous as the other.
     */
    const val MAX_MESSAGE_CODE_POINTS = MAX_CHAT_LENGTH

    /** Beacon titles are short by design; see [MAX_BEACON_TITLE_LENGTH]. */
    const val MAX_BEACON_TITLE_CODE_POINTS = MAX_BEACON_TITLE_LENGTH

    /** The PingRing code is exactly six hex characters; see [sanitizeEventCode]. */
    const val EVENT_CODE_LENGTH = 6

    private const val EVENT_CODE_RADIX = 16

    /**
     * Inert formatting characters with no rendering job: invisible padding used
     * to push text out of a length check, and the bidi controls that can make one
     * string render as another. Deliberately excludes ZWJ and ZWNJ - see the class
     * comment for why those are load-bearing.
     */
    private val DROPPED_INVISIBLE = setOf(
        '\u200B', // ZERO WIDTH SPACE
        '\uFEFF', // ZERO WIDTH NO-BREAK SPACE, used as invisible filler
        '\u00AD', // SOFT HYPHEN
        '\u200E', '\u200F', // LEFT-TO-RIGHT / RIGHT-TO-LEFT MARK
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E', // EMBEDDING and OVERRIDE
        '\u2066', '\u2067', '\u2068', '\u2069', // ISOLATES
        '\u061C', // ARABIC LETTER MARK
    )

    /** A name, as it will be stored, displayed and compared. */
    fun sanitizeUsername(raw: String): String =
        clean(raw, MAX_USERNAME_CODE_POINTS)

    enum class UsernameError { REQUIRED, TAKEN }

    fun usernameError(raw: String, existingMembers: List<String>): UsernameError? {
        val name = sanitizeUsername(raw).trim()
        return when {
            name.isBlank() -> UsernameError.REQUIRED
            existingMembers.any { sanitizeUsername(it).trim().equals(name, ignoreCase = true) } ->
                UsernameError.TAKEN
            else -> null
        }
    }

    /**
     * A chat message or beacon title.
     *
     * Newlines are collapsed rather than removed so that pasting two lines into a
     * single-line field produces two words instead of one. Everything else is
     * left alone.
     */
    fun sanitizeMessage(raw: String): String =
        clean(raw, MAX_MESSAGE_CODE_POINTS)

    /**
     * A beacon title.
     *
     * Short, because it is announced by the vibrating beacon rather than read:
     * a title long enough to need scrolling is a title nobody will ever read off
     * someone else's phone.
     */
    fun sanitizeBeaconTitle(raw: String): String =
        clean(raw, MAX_BEACON_TITLE_CODE_POINTS)

    /**
     * The six-character code, forced to upper case hex.
     *
     * The keyboard already asks for capitals, but that is only a hint: a pasted
     * code, a hardware keyboard and an accessibility keyboard can all produce
     * lower case or punctuation. Non-hex characters are dropped as they are
     * typed rather than being accepted and then rejected on submit, so the field
     * always holds something that is either right or too short - never something
     * that will be refused at the moment of joining.
     */
    fun sanitizeEventCode(raw: String): String =
        raw.uppercase()
            .filter { it.digitToIntOrNull(EVENT_CODE_RADIX) != null || (it.isLetter() && it in 'A'..'F') }
            .take(EVENT_CODE_LENGTH)

    /**
     * A relay host for the LAN debug transport, or null if it is not one.
     *
     * This is the one field that becomes a socket address, so it is validated
     * rather than cleaned: a host, or a host:port, and nothing else. Rejecting
     * outright beats repairing, because no legitimate address becomes correct by
     * having characters deleted from it - a typo fails to connect either way,
     * and silently editing someone's relay address only makes the failure harder
     * to explain.
     */
    fun sanitizeRelayHost(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 255) return null

        // A bracketed IPv6 literal, optionally with a port. Handled first
        // because a bare IPv6 address is nothing but colons, and the port split
        // below would otherwise read the last one as a port separator.
        if (trimmed.startsWith('[')) {
            val close = trimmed.indexOf(']')
            if (close < 0) return null
            val host = trimmed.substring(0, close + 1)
            val port = trimmed.substring(close + 1).let {
                if (it.isEmpty()) null else it.removePrefix(":")
            }
            if (port != null && !isValidPort(port)) return null
            if (host.any { !(it.isLetterOrDigit() || it == ':' || it == '.' || it == '[' || it == ']') }) {
                return null
            }
            return trimmed
        }

        val separator = trimmed.lastIndexOf(':')
        // A colon that is present but empty behind it is refused rather than
        // treated as "no port": "10.0.2.2:" is a typo, and quietly accepting it
        // would connect somewhere the user did not name.
        val port = if (separator >= 0) trimmed.substring(separator + 1) else null
        if (port != null && !isValidPort(port)) return null
        val host = if (separator >= 0) trimmed.substring(0, separator) else trimmed
        if (host.isEmpty()) return null
        if (host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) {
            return null
        }
        return trimmed
    }

    /** A port, or the empty string meaning the caller supplied none. */
    private fun isValidPort(port: String): Boolean =
        port.toIntOrNull()?.let { it in 1..65_535 } == true

    /**
     * Strips what must not survive, tidies whitespace, and caps by code points.
     *
     * Shared by every free-text field so the rules cannot drift apart the way two
     * hand-written `onValueChange` bodies did.
     */
    private fun clean(raw: String, maxCodePoints: Int): String {
        val builder = StringBuilder(raw.length)
        var lastWasSpace = false
        for (char in raw) {
            when {
                // Control characters carry no meaning in a name or a message.
                // Tab, newline and carriage return become a space rather than
                // vanishing, so "Alex\nSmith" stays two words.
                Character.isISOControl(char) -> {
                    if (!lastWasSpace) {
                        builder.append(' ')
                        lastWasSpace = true
                    }
                }
                char in DROPPED_INVISIBLE -> Unit
                char.isWhitespace() -> {
                    if (!lastWasSpace) {
                        builder.append(' ')
                        lastWasSpace = true
                    }
                }
                else -> {
                    builder.append(char)
                    lastWasSpace = false
                }
            }
        }
        return builder.toString().trim().takeCodePoints(maxCodePoints).trimEnd()
    }

    /**
     * Takes the first [limit] code points without ever splitting a surrogate
     * pair, which is exactly what `take` does to an emoji straddling the
     * boundary.
     */
    private fun String.takeCodePoints(limit: Int): String {
        if (codePointCount(0, length) <= limit) return this
        return substring(0, offsetByCodePoints(0, limit))
    }
}
