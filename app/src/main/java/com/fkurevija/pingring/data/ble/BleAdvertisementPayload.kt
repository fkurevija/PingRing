package com.fkurevija.pingring.data.ble

import com.fkurevija.pingring.data.session.InviteSecret

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.radar.FocusPolicy
import com.fkurevija.pingring.data.ranging.BleRangePolicy
import com.fkurevija.pingring.data.session.Event
import com.fkurevija.pingring.data.session.SessionManager

/**
 * The bytes PingRing puts in the service-data field of its BLE advertisement,
 * and the flags packed into one of them.
 *
 * This is the one thing on the discovery path that has to agree between two
 * devices with no negotiation at all: a host advertises, a guest scans, and
 * there is no acknowledgement either way. A layout change that is not applied to
 * both sides presents as "this device never sees anybody", with nothing on either
 * phone saying why.
 *
 * It lives apart from [DiscoveryRepository] for the same reason the other policy
 * objects do - so the encoding can be round-tripped in a test without a radio.
 * The repository builds it on the way out and reads it on the way in, and
 * [encode] and [decode] are the two halves of one format.
 *
 * Layout, big-endian (the platform `ByteBuffer` default), version 3:
 *
 * ```
 * offset 0   1 byte   format version, see [VERSION]
 * offset 1   4 bytes  event hash, the event id's String.hashCode()
 * offset 5   1 byte   flags, see the FLAG_* constants
 * offset 6   8 bytes  the advertiser's peer id, UTF-8, NUL-padded
 * offset 14  6 bytes  the event code, UTF-8, NUL-padded
 * offset 20  8 bytes  when the advertiser joined the event, epoch millis
 * ```
 *
 * The join time is what lets "first to join" be answered from the advert
 * rather than guessed from discovery order. It costs eight bytes and it is
 * worth them: without it the host's default tracked member is whoever it
 * happened to hear first, so a member who joins late and stands nearest can be
 * picked over the person who has been here since the start. See
 * [FocusPolicy.resolveFocus].
 *
 * Eight bytes is a full epoch-millis Long rather than something smaller on
 * purpose. A 4-byte value would have to be relative - seconds since some shared
 * epoch nobody agrees on - and there is no such epoch here: peers only ever
 * meet over BLE, with no server to hand out a common start time and no
 * handshake wide enough to agree one. Truncating to 32 bits would work only by
 * comparing timestamps taken on different clocks, which is a bug waiting for
 * anyone whose phone is minutes off. The bytes are available: 8 of the 11
 * legacy headroom, leaving 3.
 *
 * This is the only layout. Version 1 - no version byte, 13 bytes, no event
 * code - was removed for the same reason, and version 2 never shipped outside
 * test devices, so there is nothing to keep reading. A [VERSION] other than
 * this one is ignored like any other advert this build does not understand.
 *
 * The version byte is therefore not about the past. It is what makes the next
 * layout change safe: a newer build's advert is skipped rather than decoded
 * into a plausible-looking event that does not exist. Without it, adding a
 * field would mean every older build read the new leading byte as part of the
 * fields it already knows.
 *
 * The peer-id and event-code fields are padded rather than length-prefixed
 * because both are generated at a fixed length ([SessionManager] makes exactly
 * [PEER_ID_BYTES] characters of each), which leaves every byte accounted for and
 * keeps the total at exactly [TOTAL_BYTES] - the number that has to stay under
 * the 31-byte legacy advertising limit (see [BleRangePolicy]). The padding is
 * whatever [ByteBuffer.allocate] leaves, i.e. NUL, so [decode] strips NULs
 * rather than relying on `trim`, which does not treat them as whitespace.
 *
 * The event code is what lets a guest that was *told* the ring find its host:
 * without it the scanner has a hash it cannot reverse and no way to join. The
 * display name is derived from the code rather than sent, because [Event]
 * already names every event "PingRing <code>" - one less field, and no chance of
 * two copies disagreeing.
 */
object BleAdvertisementPayload {

    /**
     * Current wire format, and the only one.
     *
     * Read as "is this advert mine, and do I understand it" rather than as a
     * record of what came before. Anything else is ignored, including the v1
     * layout that [decode] used to understand: see the format notes above for
     * why that path was dropped rather than kept.
     */
    const val VERSION = 4

    /** V4 legacy fixture size; the active V5 advertiser uses extended service data. */
    const val TOTAL_BYTES = 28

    /** Truncated HMAC-SHA256 tag: 88 bits within the established 28-byte payload. */
    const val AUTH_TAG_BYTES = 11
    const val JOINED_AT_BYTES = 8

    /** The host owns the event; members follow it. */
    const val FLAG_HOST = 1

    /** The host is closing the event; every member leaves. */
    const val FLAG_EVENT_CLOSED = 1 shl 2

    /** UWB ranging is available in this event. */
    const val FLAG_USE_UWB = 1 shl 3

    /** Wi-Fi Direct is available in this event. */
    const val FLAG_USE_WIFI = 1 shl 4

    /** The event has reached its member limit; a guest should not try to join. */
    const val FLAG_RING_FULL = 1 shl 5

    /** New members are still being admitted. */
    const val FLAG_ACCEPTS_NEW_MEMBERS = 1 shl 6

    /**
     * Every flag this build defines.
     *
     * Listed rather than derived by reflection so that [encode]'s callers, the
     * bit-overlap test and the range check all read from one place. A flag added
     * here and nowhere else is caught by the overlap test; a bit assembled at a
     * call site and never declared is caught by [assertFlagsFitTheByte].
     *
     * Bit 1 (track-host) and bit 7 (discoverable) were dropped from this list
     * rather than renumbered, so the bits that remain keep their wire values.
     * Both described host policy that has since been removed: choosing who to
     * track is now the local user's own call, and sessions are no longer
     * advertised as findable, because joining is by code or by scanning the
     * host's own QR code. Renumbering would silently reassign live bits to
     * different meanings for anything still holding an old advertisement.
     */
    val ALL_FLAGS = listOf(
        FLAG_HOST, FLAG_EVENT_CLOSED, FLAG_USE_UWB,
        FLAG_USE_WIFI, FLAG_RING_FULL, FLAG_ACCEPTS_NEW_MEMBERS,
    )

    /**
     * Fails if any flag has moved outside the single byte that carries them.
     *
     * The byte is written as `flags.toByte()`, which keeps the low eight bits and
     * throws the rest away. A ninth flag would therefore not fail: it would be
     * silently dropped on the way out, and the peer on the other side would read
     * the advert perfectly happily while never learning the new fact. That is the
     * worst shape of bug - a feature that appears to work and quietly does
     * nothing - and it costs nothing to rule out here.
     *
     * Called from [encode], so the failure lands in the build's own test run
     * rather than on somebody's phone at an event.
     */
    internal fun assertFlagsFitTheByte(flags: Int) {
        // Checked on the value being written, not on the constant list: encode
        // is handed a combination assembled at the call site, so a bit nobody
        // declared - the ninth flag, added by hand in one place - is exactly what
        // would vanish. Inspecting ALL_FLAGS instead would pass while the very
        // failure being guarded against went straight through.
        require(flags in 0..0xFF) {
            "advert flags must fit in one unsigned byte (0..255), but $flags does not; " +
                "the byte would be truncated and peers would never see those bits"
        }
    }

    private const val PEER_ID_BYTES = 4
    private const val EVENT_CODE_FIELD_BYTES = 3
    private const val PREFIX_BYTES = TOTAL_BYTES - AUTH_TAG_BYTES

    /** The event code's field width, which [SessionManager] must match exactly. */
    const val EVENT_CODE_BYTES = 6

    /** The byte a fixed-width field is padded with, as a Char for `trim`. */
    private const val NUL = '\u0000'

    /** What a decoded advertisement said, once the bytes are unpacked. */
    data class Decoded(
        val flags: Int,
        val peerId: String,
        /**
         * The event code, or null when the field came back empty.
         *
         * Null no longer means "a v1 advert": every advert this build reads is
         * v2 and carries the field. It means the bytes were there and held
         * nothing, which is what [DiscoveryRepository.buildServicePayload]
         * produces when there is no event to name - the field is zero-filled
         * rather than omitted, so absence cannot be told from an all-NUL read
         * except by being empty.
         *
         * Null is not the same as empty: it means "this advert cannot be joined
         * by browsing", and a caller has to fall back to asking for the code
         * rather than offering a join that cannot work.
         */
        val eventCode: String? = null,
        /**
         * When the advertiser joined, in epoch millis, or 0 when it did not say.
         *
         * 0 rather than null on purpose: the field is a fixed-width Long in a
         * fixed-width slot, so there is no such thing as an absent value here -
         * there is only a zero. A zero is also safe to treat as "unknown" rather
         * than as epoch 1970, which is why [FocusPolicy] sorts it last instead of
         * treating it as the earliest possible arrival.
         */
        val joinedAtMillis: Long = 0L,
    ) {
        fun has(flag: Int): Boolean = flags and flag != 0

        val isHost: Boolean get() = has(FLAG_HOST)
        val eventClosed: Boolean get() = has(FLAG_EVENT_CLOSED)
        val usesUwb: Boolean get() = has(FLAG_USE_UWB)
        val usesWifi: Boolean get() = has(FLAG_USE_WIFI)
        val isFull: Boolean get() = has(FLAG_RING_FULL)
        val acceptsNewMembers: Boolean get() = has(FLAG_ACCEPTS_NEW_MEMBERS)

        /**
         * The name to show for a discovered session.
         *
         * Derived rather than transmitted. [com.fkurevija.pingring.data.session.Event]
         * already names every event "PingRing <code>", so sending the code is
         * enough to render the name on the far side - which is what keeps the
         * advert inside the legacy size limit, and keeps two copies of a name
         * from being able to disagree.
         */
        val displayName: String? get() = eventCode?.let { "PingRing $it" }
    }

    /**
     * The unauthenticated locator fields of a legacy advert.
     *
     * The event code alone, with no policy flag: the code is what lets a guest
     * that was told the ring find its host, and nothing else in an advert from a
     * phone that is not yet a member is worth acting on.
     */
    data class DiscoveryHint(val eventCode: String?)

    /**
     * Packs the advertisement.
     *
     * The peer id and event code are truncated or space-padded to exactly their
     * field width rather than rejected, because both are generated at a fixed
     * length and a mismatch would mean the two ends disagree about the identity
     * scheme itself - which is worth surfacing, not something a peer can fix by
     * trying again.
     *
     * A code longer than its field is truncated, which is a silent failure worth
     * naming: a guest adopting a truncated code would fail to join with nothing
     * to say why. [SessionManager] generates exactly [EVENT_CODE_BYTES]
     * characters and a test asserts it, so this cannot happen quietly.
     */
    fun encode(
        flags: Int,
        peerIdBytes: ByteArray,
        eventCode: String? = null,
        joinedAtMillis: Long = 0L,
        inviteSecret: String,
    ): ByteArray {
        // Checked before the buffer is built: `flags.toByte()` on the line below
        // is exactly the silent truncation this prevents.
        assertFlagsFitTheByte(flags)
        require(peerIdBytes.size == 8) { "peer id must be exactly 8 bytes" }
        val peerId = String(peerIdBytes, StandardCharsets.UTF_8)
            .trimEnd(NUL, ' ')
        require(peerId.length == 8 && peerId.all(::isHexDigit)) {
            "peer id must be exactly 8 hexadecimal characters"
        }
        val codeBytes = eventCodeBytes(eventCode)
        val prefix = ByteBuffer.allocate(PREFIX_BYTES)
            .put(VERSION.toByte())
            .put(flags.toByte())
            .put(hexBytes(peerId))
            .put(codeBytes)
            .putLong(joinedAtMillis)
            .array()
        val authTag = authenticationTag(inviteSecret, prefix)
        return prefix + authTag
    }

    /**
     * The event code as a fixed-width, NUL-padded field.
     *
     * Zero-filled first so a short code is padded, then the code is copied into
     * the front and truncated at the field width: an over-long code would
     * otherwise push every later field out of the payload.
     */
    private fun eventCodeBytes(eventCode: String?): ByteArray {
        if (eventCode == null) return ByteArray(EVENT_CODE_FIELD_BYTES)
        require(eventCode.length == EVENT_CODE_BYTES && eventCode.all(::isHexDigit)) {
            "event code must be exactly 6 hexadecimal characters"
        }
        return hexBytes(eventCode)
    }

    /**
     * Unpacks an advertisement, or null if it is not one of ours.
     *
     * Two rejections, both returning null rather than a best guess. A short
     * buffer is the signature of a *different* app's PingRing-shaped service
     * UUID, or of a truncated read, and must be ignored rather than parsed:
     * reading past the end would either throw or, worse, decode neighbouring
     * fields as a peer id and put a phantom member on the radar. A buffer this
     * build does not understand - a later version, or the dropped v1 - fails
     * the version check for the same reason.
     */
    fun decode(serviceData: ByteArray, inviteSecret: String): Decoded? {
        if (serviceData.size < TOTAL_BYTES) return null
        if (serviceData[0].toInt() != VERSION) return null

        val prefix = serviceData.copyOfRange(0, PREFIX_BYTES)
        val storedTag = serviceData.copyOfRange(PREFIX_BYTES, TOTAL_BYTES)
        val expectedTag = runCatching { authenticationTag(inviteSecret, prefix) }.getOrNull()
            ?: return null
        if (!MessageDigest.isEqual(storedTag, expectedTag)) return null

        val payload = ByteBuffer.wrap(prefix)
        payload.get() // the version byte, already checked above
        val flags = payload.get().toInt()
        val peerId = bytesToHex(ByteArray(PEER_ID_BYTES).also(payload::get)).lowercase()
        val code = bytesToHex(ByteArray(EVENT_CODE_FIELD_BYTES).also(payload::get))
            .takeIf { it != "000000" }
        val joinedAtMillis = payload.long
        return Decoded(flags, peerId, code, joinedAtMillis)
    }

    fun decodeDiscoveryHint(serviceData: ByteArray): DiscoveryHint? {
        if (serviceData.size < TOTAL_BYTES || serviceData[0].toInt() != VERSION) return null
        val payload = ByteBuffer.wrap(serviceData)
        payload.position(1 + 1 + PEER_ID_BYTES)
        val code = bytesToHex(ByteArray(EVENT_CODE_FIELD_BYTES).also(payload::get))
            .takeIf { it != "000000" }
        return DiscoveryHint(code)
    }

    /**
     * Reads a fixed-width, NUL-padded field.
     *
     * Exactly the field width, never `remaining()`: a newer build that adds a
     * trailing field must not have those bytes folded into this one. `trim` does
     * not consider NUL whitespace, so they are stripped explicitly, and spaces
     * with them - a field carrying either is equally unusable as a routing key.
     */
    private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char.lowercaseChar() in 'a'..'f'

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    private fun authenticationTag(secret: String, prefix: ByteArray): ByteArray {
        val key = InviteSecret.decode(secret) ?: throw IllegalArgumentException("invalid invite secret")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(prefix).copyOf(AUTH_TAG_BYTES)
    }
}
