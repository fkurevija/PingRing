package com.fkurevija.pingring

import com.fkurevija.pingring.data.BleAdvertisementPayload
import com.fkurevija.pingring.data.BleRangePolicy
import com.fkurevija.pingring.data.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

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
        eventHash: Int = "EVENT".hashCode(),
        flags: Int = BleAdvertisementPayload.FLAG_HOST,
        peerId: ByteArray = peerIdBytes,
        eventCode: String? = null,
    ) = BleAdvertisementPayload.encode(eventHash, flags, peerId, eventCode)

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
    fun `an advert with a code and the flag is discoverable`() {
        val decoded = BleAdvertisementPayload.decode(
            encode(
                flags = BleAdvertisementPayload.FLAG_HOST or BleAdvertisementPayload.FLAG_DISCOVERABLE,
                eventCode = eventCode
            )
        )!!

        assertTrue(decoded.isDiscoverable)
        assertEquals("PingRing $eventCode", decoded.displayName)
    }

    @Test
    fun `a flag without a code is not discoverable`() {
        // The flag alone is not enough: a guest that adopted a session with no
        // code would have nothing to join with, so this must not offer a join
        // that cannot work.
        val decoded = BleAdvertisementPayload.decode(
            encode(flags = BleAdvertisementPayload.FLAG_DISCOVERABLE, eventCode = null)
        )!!

        assertFalse("the flag alone must not make a session discoverable", decoded.isDiscoverable)
        assertNull(decoded.displayName)
    }

    @Test
    fun `a v1 advert is still understood but carries no code`() {
        // A v2 build meeting a v1 host must still see it as a member, and must
        // know the session is not one it can join by browsing.
        val v1 = java.nio.ByteBuffer.allocate(BleAdvertisementPayload.V1_TOTAL_BYTES)
            .putInt("EVENT".hashCode())
            .put(BleAdvertisementPayload.FLAG_HOST.toByte())
            .put(peerIdBytes)
            .array()

        val decoded = BleAdvertisementPayload.decode(v1)!!

        assertEquals("EVENT".hashCode(), decoded.eventHash)
        assertEquals(peerId, decoded.peerId)
        assertTrue(decoded.isHost)
        assertNull("a v1 advert has no code to browse by", decoded.eventCode)
    }

    @Test
    fun `an unknown version is ignored rather than misread`() {
        // The whole reason for the version byte: a future build's advert must be
        // skipped, not decoded as a different event. Reading it as v2 would
        // produce a plausible-looking hash for an event that does not exist.
        val future = encode(eventCode = eventCode)
        future[0] = 99

        assertNull(BleAdvertisementPayload.decode(future))
    }

    @Test
    fun `a too-short buffer is ignored`() {
        val short = ByteArray(BleAdvertisementPayload.V1_TOTAL_BYTES - 1)

        assertNull(BleAdvertisementPayload.decode(short))
    }

    @Test
    fun `whatIsEncodedIsWhatIsDecoded`() {
        val decoded = BleAdvertisementPayload.decode(encode())!!

        assertEquals("EVENT".hashCode(), decoded.eventHash)
        assertEquals(peerId, decoded.peerId)
        assertTrue("the host flag must survive the round trip", decoded.isHost)
    }

    @Test
    fun `the payload is exactly the advertised size`() {
        // The size is a budget, not a description: it has to stay under the
        // 31-byte legacy advertising limit for the advert to be transmittable
        // at all. See BleRangePolicy. The payload and the policy's copy of the
        // number are one budget - if they drift apart, the advert is built at a
        // size nothing is testing against the limit that would reject it.
        assertEquals(BleRangePolicy.SERVICE_DATA_BYTES, BleAdvertisementPayload.TOTAL_BYTES)
        assertEquals(BleRangePolicy.SERVICE_DATA_BYTES, encode().size)
        assertTrue(
            "the payload must fit legacy advertising",
            BleRangePolicy.fitsInLegacyAdvertising()
        )
    }

    @Test
    fun `the payload still has room to grow within legacy advertising`() {
        // Adding a field costs bytes, and this is where the cost is recorded.
        // v2 spent 7 of the 18 bytes v1 left spare (version byte + code); the
        // rest is what a later field can have without dropping legacy-only
        // devices. Exceeding it is a decision, not an accident - it means moving
        // to BLE 5 extended advertising and giving up the devices that cannot.
        assertEquals(11, BleRangePolicy.LEGACY_HEADROOM_BYTES)
        assertTrue(BleRangePolicy.LEGACY_HEADROOM_BYTES > 0)
    }

    @Test
    fun `every flag survives the round trip`() {
        val allFlags = BleAdvertisementPayload.FLAG_HOST or
            BleAdvertisementPayload.FLAG_TRACK_HOST or
            BleAdvertisementPayload.FLAG_USE_UWB or
            BleAdvertisementPayload.FLAG_USE_WIFI or
            BleAdvertisementPayload.FLAG_RING_FULL or
            BleAdvertisementPayload.FLAG_ACCEPTS_NEW_MEMBERS

        val decoded = BleAdvertisementPayload.decode(encode(flags = allFlags))!!

        assertTrue(decoded.isHost)
        assertTrue(decoded.tracksMembers)
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
        val readers: List<Pair<Int, (BleAdvertisementPayload.Decoded) -> Boolean>> = listOf(
            BleAdvertisementPayload.FLAG_HOST to { d -> d.isHost },
            BleAdvertisementPayload.FLAG_TRACK_HOST to { d -> d.tracksMembers },
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
        // Overlapping bits would make "track the host" also mean "use UWB", which
        // no test of behaviour would catch.
        val flags = listOf(
            BleAdvertisementPayload.FLAG_HOST,
            BleAdvertisementPayload.FLAG_TRACK_HOST,
            BleAdvertisementPayload.FLAG_EVENT_CLOSED,
            BleAdvertisementPayload.FLAG_USE_UWB,
            BleAdvertisementPayload.FLAG_USE_WIFI,
            BleAdvertisementPayload.FLAG_RING_FULL,
            BleAdvertisementPayload.FLAG_ACCEPTS_NEW_MEMBERS,
        )

        flags.forEach { a ->
            flags.forEach { b ->
                if (a != b) {
                    assertEquals("flags $a and $b share a bit", 0, a and b)
                }
            }
        }
    }
    @Test
    fun `anEventHashOfZeroStillRoundTrips`() {
        // "".hashCode() is 0, and an all-zero payload is exactly what an
        // uninitialised buffer looks like, so this edge is worth holding down.
        val decoded = BleAdvertisementPayload.decode(encode(eventHash = 0))!!

        assertEquals(0, decoded.eventHash)
    }

    @Test
    fun `aFullRangeEventHashSurvivesTheSignBit`() {
        // String.hashCode() can be negative, and the top bit of an Int is the
        // sign. A payload that came back with its top bit flipped would be
        // compared against a hash that never matched any event.
        val decoded = BleAdvertisementPayload.decode(encode(eventHash = -1))!!

        assertEquals(-1, decoded.eventHash)
    }

    @Test
    fun `aShortPeerIdIsPaddedAndReadBackCleanly`() {
        // The field is a fixed 8 bytes, so a shorter id is space-padded. Reading
        // it back must not leave the padding in the id, or every peer would carry
        // trailing spaces into its label and its routing key.
        val short = "ab".toByteArray(StandardCharsets.UTF_8)

        val decoded = BleAdvertisementPayload.decode(
            BleAdvertisementPayload.encode("E".hashCode(), 0, short)
        )!!

        assertEquals("ab", decoded.peerId)
    }

    @Test
    fun `anOverlongPeerIdIsTruncatedToTheField`() {
        // The opposite edge. Truncating keeps the payload a legal size rather
        // than writing a longer field than the layout allows.
        val long = "0123456789ABCDEF".toByteArray(StandardCharsets.UTF_8)

        val payload = BleAdvertisementPayload.encode("E".hashCode(), 0, long)

        assertEquals(BleAdvertisementPayload.TOTAL_BYTES, payload.size)
        assertEquals(8, BleAdvertisementPayload.decode(payload)!!.peerId.length)
    }

    @Test
    fun `anOverlongEventCodeIsTruncatedToTheField`() {
        // Same edge for the code, and the more dangerous of the two: a truncated
        // code is still a *valid-looking* code that simply belongs to no event,
        // so a guest adopting it fails to join with nothing to say why. The
        // payload stays a legal size either way, which is why this needs saying.
        val long = "TOOLONGCODE"

        val payload = BleAdvertisementPayload.encode("E".hashCode(), 0, peerIdBytes, long)

        assertEquals(BleAdvertisementPayload.TOTAL_BYTES, payload.size)
        assertEquals(
            BleAdvertisementPayload.EVENT_CODE_BYTES,
            BleAdvertisementPayload.decode(payload)!!.eventCode!!.length
        )
    }

    @Test
    fun `a short event code is paddedRatherThanReadAsGarbage`() {
        // The other direction for the same field: fewer characters than the
        // field holds must come back as exactly what was sent, not carrying the
        // NUL padding with it.
        val payload = BleAdvertisementPayload.encode("E".hashCode(), 0, peerIdBytes, "AB")

        assertEquals("AB", BleAdvertisementPayload.decode(payload)!!.eventCode)
    }

    @Test
    fun `aTruncatedAdvertisementIsRejectedRatherThanDecoded`() {
        // The dangerous case: decoding past the end would either throw or read
        // neighbouring bytes as a peer id and put a phantom member on the radar.
        //
        // Only the sub-v1 sizes are rejected outright. A truncated *v2* buffer
        // that is still at least v1's 13 bytes decodes as a v1 advert, which is
        // deliberate: the layouts share a prefix only by accident, so a v2
        // payload cut short would otherwise be silently misread. The version
        // byte cannot be trusted either, because a cut may land before it.
        val full = encode()

        for (size in 0 until BleAdvertisementPayload.V1_TOTAL_BYTES) {
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
                "E".hashCode(),
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
        assertFalse(decoded.tracksMembers)
        assertFalse(decoded.eventClosed)
        assertFalse(decoded.usesUwb)
        assertFalse(decoded.usesWifi)
        assertFalse(decoded.isFull)
        assertFalse(decoded.acceptsNewMembers)
    }
}