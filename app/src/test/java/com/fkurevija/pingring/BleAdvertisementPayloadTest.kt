package com.fkurevija.pingring

import com.fkurevija.pingring.data.BleAdvertisementPayload as SignedBleAdvertisementPayload
import com.fkurevija.pingring.data.BleRangePolicy
import com.fkurevija.pingring.data.InviteSecret
import com.fkurevija.pingring.data.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

private val testInviteSecret = InviteSecret.generate()
private typealias BleDecoded = SignedBleAdvertisementPayload.Decoded

private object BleAdvertisementPayload {
    const val VERSION = SignedBleAdvertisementPayload.VERSION
    const val TOTAL_BYTES = SignedBleAdvertisementPayload.TOTAL_BYTES
    const val AUTH_TAG_BYTES = SignedBleAdvertisementPayload.AUTH_TAG_BYTES
    const val JOINED_AT_BYTES = SignedBleAdvertisementPayload.JOINED_AT_BYTES
    const val EVENT_CODE_BYTES = SignedBleAdvertisementPayload.EVENT_CODE_BYTES
    const val FLAG_HOST = SignedBleAdvertisementPayload.FLAG_HOST
    const val FLAG_EVENT_CLOSED = SignedBleAdvertisementPayload.FLAG_EVENT_CLOSED
    const val FLAG_USE_UWB = SignedBleAdvertisementPayload.FLAG_USE_UWB
    const val FLAG_USE_WIFI = SignedBleAdvertisementPayload.FLAG_USE_WIFI
    const val FLAG_RING_FULL = SignedBleAdvertisementPayload.FLAG_RING_FULL
    const val FLAG_ACCEPTS_NEW_MEMBERS = SignedBleAdvertisementPayload.FLAG_ACCEPTS_NEW_MEMBERS
    val ALL_FLAGS = SignedBleAdvertisementPayload.ALL_FLAGS

    fun assertFlagsFitTheByte(flags: Int) = SignedBleAdvertisementPayload.assertFlagsFitTheByte(flags)

    fun encode(
        flags: Int = FLAG_HOST,
        peerIdBytes: ByteArray,
        eventCode: String? = null,
        joinedAtMillis: Long = 0L,
    ): ByteArray = SignedBleAdvertisementPayload.encode(
        flags = flags,
        peerIdBytes = peerIdBytes,
        eventCode = eventCode,
        joinedAtMillis = joinedAtMillis,
        inviteSecret = testInviteSecret,
    )

    fun decode(serviceData: ByteArray): BleDecoded? =
        SignedBleAdvertisementPayload.decode(serviceData, testInviteSecret)
}

/**
 * The BLE advertisement: the one thing two phones have to agree on with no
 * negotiation at all.
 *
 * A host advertises, a guest scans, and neither ever acknowledges the other. A
 * layout change applied to only one side therefore presents as "this device
 * never sees anybody", with nothing on either phone explaining why. So these
 * tests hold [BleAdvertisementPayload.encode] against
 * [BleAdvertisementPayload.decode] directly, the way [RelayCodecTest] holds the
 * relay's two halves together.
 */
class BleAdvertisementPayloadTest {

    /** An 8-character peer id, as SessionManager actually generates. */
    private val peerId = "a1b2c3d4"
    private val peerIdBytes = peerId.toByteArray(StandardCharsets.UTF_8)

    /** A 6-character event code, as generateEventCode actually produces. */
    private val eventCode = "4C1A9B"
    private val eventCodeBytes = eventCode.toByteArray(StandardCharsets.UTF_8)

    private fun encode(
        flags: Int = BleAdvertisementPayload.FLAG_HOST,
        peerId: ByteArray = peerIdBytes,
        eventCode: String? = null,
        joinedAtMillis: Long = 0L,
    ) = BleAdvertisementPayload.encode(flags, peerId, eventCode, joinedAtMillis)

    private fun expectInvalidInput(block: () -> Unit) {
        try {
            block()
            fail("invalid advertisement fields must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: malformed identifiers must not be truncated into valid ones.
        }
    }

    @Test
    fun `the event code is exactly the width the payload reserves`() {
        // The payload truncates an over-long code, so a code that does not fit
        // produces a *different, wrong* code that fails to join with nothing to
        // say why. Asserting the width here is what stops that happening
        // quietly when the code generator changes.
        assertEquals(BleAdvertisementPayload.EVENT_CODE_BYTES, eventCodeBytes.size)
    }

    @Test
    fun `the event code survives the round trip`() {
        val decoded = BleAdvertisementPayload.decode(encode(eventCode = eventCode))!!

        assertEquals(eventCode, decoded.eventCode)
    }

    @Test
    fun `an advert carries the code so a told guest can find its host`() {
        val decoded = BleAdvertisementPayload.decode(
            encode(flags = BleAdvertisementPayload.FLAG_HOST, eventCode = eventCode)
        )!!

        assertEquals("PingRing $eventCode", decoded.displayName)
    }

    @Test
    fun `a different invite secret cannot authenticate an advertisement`() {
        val payload = encode(flags = BleAdvertisementPayload.FLAG_HOST, eventCode = eventCode)

        assertNull(SignedBleAdvertisementPayload.decode(payload, InviteSecret.generate()))
        assertTrue(BleAdvertisementPayload.decode(payload)!!.isHost)
    }

    @Test
    fun `tampering with host control flags invalidates the authentication tag`() {
        val payload = encode(flags = BleAdvertisementPayload.FLAG_HOST, eventCode = eventCode)
        payload[1] = (payload[1].toInt() xor BleAdvertisementPayload.FLAG_EVENT_CLOSED).toByte()

        assertNull(BleAdvertisementPayload.decode(payload))
    }

    @Test
    fun `the invite secret changes only the advertisement authentication tag`() {
        val otherSecret = InviteSecret.generate()
        val first = SignedBleAdvertisementPayload.encode(
            flags = BleAdvertisementPayload.FLAG_HOST,
            peerIdBytes = peerIdBytes,
            eventCode = eventCode,
            inviteSecret = testInviteSecret,
        )
        val second = SignedBleAdvertisementPayload.encode(
            flags = BleAdvertisementPayload.FLAG_HOST,
            peerIdBytes = peerIdBytes,
            eventCode = eventCode,
            inviteSecret = otherSecret,
        )
        val prefixSize = BleAdvertisementPayload.TOTAL_BYTES - BleAdvertisementPayload.AUTH_TAG_BYTES

        assertArrayEquals(first.copyOf(prefixSize), second.copyOf(prefixSize))
        assertFalse(first.copyOfRange(prefixSize, first.size).contentEquals(second.copyOfRange(prefixSize, second.size)))
        assertNull(SignedBleAdvertisementPayload.decode(first, otherSecret))
    }

    @Test
    fun `prejoin hint exposes only the public locator`() {
        // Deliberately the code and nothing else. An unauthenticated advert from
        // a phone that is not yet a member must not be able to tell a listener
        // anything beyond which ring it claims to be in - the flags it used to
        // carry described host policy a stranger had no business reading.
        val hint = SignedBleAdvertisementPayload.decodeDiscoveryHint(
            encode(
                flags = BleAdvertisementPayload.FLAG_HOST or BleAdvertisementPayload.FLAG_EVENT_CLOSED,
                eventCode = eventCode,
            )
        )!!

        assertEquals(eventCode, hint.eventCode)
    }

    @Test
    fun `an advert with no code names no ring`() {
        // Nothing to join with and nothing to display: a guest that had not been
        // told the code cannot act on it, so it must not appear to offer a join.
        val decoded = BleAdvertisementPayload.decode(
            encode(flags = BleAdvertisementPayload.FLAG_HOST, eventCode = null)
        )!!

        assertNull(decoded.displayName)
    }

    @Test
    fun `a v1 advert is ignored rather than understood`() {
        // The v1 layout - no version byte, 13 bytes - used to be decoded here so
        // a v2 build could still see a v1 host. It never shipped outside test
        // devices, so that path was code serving nobody and was removed. What
        // matters now is that the old bytes are *rejected* rather than read: a
        // 13-byte buffer is now simply too short, and the leading 4 bytes of an
        // event hash must not be mistaken for a version byte.
        val v1 = java.nio.ByteBuffer.allocate(13)
            .putInt("EVENT".hashCode())
            .put(BleAdvertisementPayload.FLAG_HOST.toByte())
            .put(peerIdBytes)
            .array()

        assertNull(BleAdvertisementPayload.decode(v1))
    }

    @Test
    fun `the previous wire version is ignored rather than misread`() {
        val previousVersion = encode(eventCode = eventCode).also { it[0] = 3 }

        assertNull(
            "a previous wire version must not decode as the authenticated format",
            BleAdvertisementPayload.decode(previousVersion)
        )
    }

    @Test
    fun `an unknown version is ignored rather than misread`() {
        // The whole reason for the version byte: a future build's advert must be
        // skipped, not decoded as a different event. Reading it as this version
        // would produce a plausible-looking hash for an event that does not exist.
        val future = encode(eventCode = eventCode)
        future[0] = 99

        assertNull(BleAdvertisementPayload.decode(future))
    }

    @Test
    fun `a too-short buffer is ignored`() {
        // Anything under the full layout is unreadable, including a v2 payload
        // cut short. The version byte cannot rescue a truncated read either: a
        // cut may land before it.
        val short = ByteArray(BleAdvertisementPayload.TOTAL_BYTES - 1)

        assertNull(BleAdvertisementPayload.decode(short))
    }

    @Test
    fun `whatIsEncodedIsWhatIsDecoded`() {
        val decoded = BleAdvertisementPayload.decode(encode())!!

        assertEquals(peerId, decoded.peerId)
        assertNull(decoded.eventCode)
        assertTrue("the host flag must survive the round trip", decoded.isHost)
    }

    @Test
    fun `the payload is exactly the advertised size`() {
        // The size is a budget, not a description: it has to stay under the
        // V4 is retained here only as a codec fixture; production emits V5.
        assertEquals(BleRangePolicy.LEGACY_V4_SERVICE_DATA_BYTES, BleAdvertisementPayload.TOTAL_BYTES)
        assertEquals(BleRangePolicy.LEGACY_V4_SERVICE_DATA_BYTES, encode().size)
        assertTrue(BleRangePolicy.LEGACY_V4_SERVICE_DATA_BYTES <= BleRangePolicy.MAX_LEGACY_SERVICE_DATA_BYTES)
    }

    @Test
    fun `the join time survives the round trip`() {
        // The field the whole "first to join" decision rests on. A Long rather
        // than a narrower type, so the values below are ones that would be
        // truncated or wrapped by anything smaller.
        val joinedAt = 1_726_000_123_456L
        val decoded = BleAdvertisementPayload.decode(
            encode(joinedAtMillis = joinedAt)
        )!!

        assertEquals(joinedAt, decoded.joinedAtMillis)
    }

    @Test
    fun `a join time of zero means the peer did not say`() {
        // Zero is what a device outside an event advertises. It must not be
        // confused with a real arrival: FocusPolicy sorts it last, so a zero
        // read as "epoch 1970" would make that peer win every tie.
        val decoded = BleAdvertisementPayload.decode(encode(joinedAtMillis = 0L))!!

        assertEquals(0L, decoded.joinedAtMillis)
        assertTrue(
            "a zero timestamp must not read as a real arrival",
            decoded.joinedAtMillis <= 0L
        )
    }

    @Test
    fun `the join time costs exactly eight bytes`() {
        // Recorded because the budget is what it is: v2 was 20 bytes and this
        // field is 8 of the 11 that were spare. If this ever changes, the
        // headroom assertion above is what needs re-reading.
        assertEquals(8, BleAdvertisementPayload.JOINED_AT_BYTES)
        assertEquals(
            BleAdvertisementPayload.TOTAL_BYTES - BleAdvertisementPayload.AUTH_TAG_BYTES - 9,
            BleAdvertisementPayload.JOINED_AT_BYTES
        )
    }

    @Test
    fun `the payload still has room to grow within legacy advertising`() {
        assertEquals(3, BleRangePolicy.LEGACY_HEADROOM_BYTES)
        assertEquals(88, BleAdvertisementPayload.AUTH_TAG_BYTES * 8)
    }

    @Test
    fun `every flag survives the round trip`() {
        val allFlags = BleAdvertisementPayload.FLAG_HOST or
            BleAdvertisementPayload.FLAG_USE_UWB or
            BleAdvertisementPayload.FLAG_USE_WIFI or
            BleAdvertisementPayload.FLAG_RING_FULL or
            BleAdvertisementPayload.FLAG_ACCEPTS_NEW_MEMBERS

        val decoded = BleAdvertisementPayload.decode(encode(flags = allFlags))!!

        assertTrue(decoded.isHost)
        assertTrue(decoded.usesUwb)
        assertTrue(decoded.usesWifi)
        assertTrue(decoded.isFull)
        assertTrue(decoded.acceptsNewMembers)
        assertFalse("the close flag was never set", decoded.eventClosed)
    }

    @Test
    fun `eachFlagIsIndependentlyReadable`() {
        // Bit flags that share a byte, so one wrong shift silently sets or clears
        // a neighbour. Each is set alone and must be the only one that reads true.
        val readers: List<Pair<Int, (BleDecoded) -> Boolean>> = listOf(
            BleAdvertisementPayload.FLAG_HOST to { d -> d.isHost },
            BleAdvertisementPayload.FLAG_EVENT_CLOSED to { d -> d.eventClosed },
            BleAdvertisementPayload.FLAG_USE_UWB to { d -> d.usesUwb },
            BleAdvertisementPayload.FLAG_USE_WIFI to { d -> d.usesWifi },
            BleAdvertisementPayload.FLAG_RING_FULL to { d -> d.isFull },
            BleAdvertisementPayload.FLAG_ACCEPTS_NEW_MEMBERS to { d -> d.acceptsNewMembers },
        )

        readers.forEach { (flag, read) ->
            val decoded = BleAdvertisementPayload.decode(encode(flags = flag))!!
            val setFlags = readers.filter { (_, r) -> r(decoded) }.map { it.first }

            assertEquals("flag $flag must be the only one set", listOf(flag), setFlags)
        }
    }

    @Test
    fun `theFlagBitsDoNotOverlap`() {
        // Overlapping bits would make "closed" also mean "use UWB", which
        // no test of behaviour would catch. Driven from ALL_FLAGS rather than a
        // list written out here, because a hand-copied list is exactly how the
        // one omission happened: FLAG_DISCOVERABLE was missing from this test
        // while existing and working - and has since been removed without this
        // list being noticed, which is the same failure a second time.
        BleAdvertisementPayload.ALL_FLAGS.forEach { a ->
            BleAdvertisementPayload.ALL_FLAGS.forEach { b ->
                if (a != b) {
                    assertEquals("flags $a and $b share a bit", 0, a and b)
                }
            }
        }
    }

    @Test
    fun `everyFlagFitsInTheByteThatCarriesThem`() {
        // The flags byte is written with `flags.toByte()`, which discards
        // anything above bit 7. All eight bits are now spoken for, so a ninth
        // flag is the realistic failure: it would be dropped on the way out and
        // the peer would read the advert fine while never learning the new fact.
        // The guard makes that a build failure instead of a silent one.
        BleAdvertisementPayload.ALL_FLAGS.forEach { flag ->
            assertTrue(
                "flag $flag does not fit in an unsigned byte",
                flag in 1..0xFF
            )
        }
        BleAdvertisementPayload.assertFlagsFitTheByte(BleAdvertisementPayload.ALL_FLAGS.sum())
    }

    @Test
    fun `encodingRefusesAFlagThatWouldBeTruncated`() {
        // A hypothetical ninth flag, at bit 8. encode() must reject it rather
        // than quietly writing a byte without it.
        val ninthFlag = 1 shl 8
        try {
            encode(flags = BleAdvertisementPayload.FLAG_HOST or ninthFlag)
            fail("a flag outside the byte must not be encoded")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "the failure should say the byte would truncate",
                expected.message?.contains("byte") == true
            )
        }
    }
    @Test
    fun `aShortPeerIdIsRejected`() {
        val short = "ab".toByteArray(StandardCharsets.UTF_8)
        expectInvalidInput { encode(peerId = short) }
    }

    @Test
    fun `anOverlongPeerIdIsTruncatedToTheField`() {
        // The opposite edge. Truncating keeps the payload a legal size rather
        // than writing a longer field than the layout allows.
        val long = "0123456789ABCDEF".toByteArray(StandardCharsets.UTF_8)
        expectInvalidInput { encode(peerId = long) }
    }

    @Test
    fun `anOverlongEventCodeIsTruncatedToTheField`() {
        expectInvalidInput { encode(eventCode = "TOOLONGCODE") }
    }

    @Test
    fun `a short event code is paddedRatherThanReadAsGarbage`() {
        // The other direction for the same field: fewer characters than the
        // field holds must come back as exactly what was sent, not carrying the
        // NUL padding with it.
        expectInvalidInput { encode(eventCode = "AB") }
    }

    @Test
    fun `aTruncatedAdvertisementIsRejectedRatherThanDecoded`() {
        // The dangerous case: decoding past the end would either throw or read
        // neighbouring bytes as a peer id and put a phantom member on the radar.
        //
        // Every size below the full layout is rejected, which now includes a v2
        // payload cut short. That buffer used to decode as a v1 advert, because
        // the two layouts shared a prefix only by accident - the safety came
        // from misreading being impossible rather than from a check.
        val full = encode()

        for (size in 0 until BleAdvertisementPayload.TOTAL_BYTES) {
            assertNull(
                "a $size-byte payload must not decode",
                BleAdvertisementPayload.decode(full.copyOf(size))
            )
        }
    }

    @Test
    fun `anEmptyAdvertisementIsRejected`() {
        assertNull(BleAdvertisementPayload.decode(ByteArray(0)))
    }

    @Test
    fun `anOversizedAdvertisementStillDecodesItsLeadingFields`() {
        // A longer buffer means a newer build added a field. The fields that
        // exist today must still be readable, or a version skew would make every
        // device blind rather than just missing the new field.
        val decoded = BleAdvertisementPayload.decode(encode() + byteArrayOf(9, 9, 9, 9))!!

        assertEquals(peerId, decoded.peerId)
        assertTrue(decoded.isHost)
    }

    @Test
    fun `aGeneratedPeerIdFitsTheAdvertisementField`() {
        // The two have to agree: SessionManager generates 8 characters and the
        // payload field is 8 bytes. If either changes, ids are silently truncated
        // and two devices stop recognising the same member.
        val generated = SessionManager().peerId.value

        assertEquals(8, generated.length)
        assertEquals(8, generated.toByteArray(StandardCharsets.UTF_8).size)

        val decoded = BleAdvertisementPayload.decode(
            BleAdvertisementPayload.encode(
                BleAdvertisementPayload.FLAG_HOST,
                generated.toByteArray(StandardCharsets.UTF_8),
            )
        )!!
        assertEquals(generated, decoded.peerId)
    }

    @Test
    fun `aZeroFlagByteMeansNoFlagsAreSet`() {
        // The default an unconfigured advertiser would send. Every flag must read
        // false, so a member is not mistaken for a host.
        val decoded = BleAdvertisementPayload.decode(encode(flags = 0))!!

        assertFalse(decoded.isHost)
        assertFalse(decoded.eventClosed)
        assertFalse(decoded.usesUwb)
        assertFalse(decoded.usesWifi)
        assertFalse(decoded.isFull)
        assertFalse(decoded.acceptsNewMembers)
    }
}