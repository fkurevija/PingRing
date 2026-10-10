package com.fkurevija.pingring.data.ble

import com.fkurevija.pingring.data.ble.BleAdvertisementPayload as SignedBleAdvertisementPayload
import com.fkurevija.pingring.data.InviteSecret
import com.fkurevija.pingring.data.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

private val compatibilityInviteSecret = InviteSecret.generate()
private typealias CompatibilityBleDecoded = SignedBleAdvertisementPayload.Decoded

private object CompatibilityBleAdvertisementPayload {
    const val FLAG_HOST = SignedBleAdvertisementPayload.FLAG_HOST
    const val TOTAL_BYTES = SignedBleAdvertisementPayload.TOTAL_BYTES

    fun encode(
        flags: Int = FLAG_HOST,
        peerIdBytes: ByteArray,
        eventCode: String? = null,
    ): ByteArray = SignedBleAdvertisementPayload.encode(
        flags = flags,
        peerIdBytes = peerIdBytes,
        eventCode = eventCode,
        inviteSecret = compatibilityInviteSecret,
    )

    fun decode(serviceData: ByteArray): CompatibilityBleDecoded? =
        SignedBleAdvertisementPayload.decode(serviceData, compatibilityInviteSecret)
}

class BleAdvertisementPayloadCompatibilityTest {
    private val peerId = "a1b2c3d4"

    private fun encode(flags: Int = CompatibilityBleAdvertisementPayload.FLAG_HOST) =
        CompatibilityBleAdvertisementPayload.encode(
            flags = flags,
            peerIdBytes = peerId.toByteArray(StandardCharsets.UTF_8),
        )

    private fun expectInvalidInput(block: () -> Unit) {
        try {
            block()
            fail("invalid advertisement fields must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected for malformed input.
        }
    }

    @Test
    fun `aShortPeerIdIsRejected`() {
        val short = "ab".toByteArray(StandardCharsets.UTF_8)
        expectInvalidInput {
            CompatibilityBleAdvertisementPayload.encode(peerIdBytes = short)
        }
    }

    @Test
    fun `anOverlongPeerIdIsTruncatedToTheField`() {
        val long = "0123456789ABCDEF".toByteArray(StandardCharsets.UTF_8)
        expectInvalidInput {
            CompatibilityBleAdvertisementPayload.encode(peerIdBytes = long)
        }
    }

    @Test
    fun `anOverlongEventCodeIsTruncatedToTheField`() {
        expectInvalidInput {
            CompatibilityBleAdvertisementPayload.encode(
                peerIdBytes = peerId.toByteArray(StandardCharsets.UTF_8),
                eventCode = "TOOLONGCODE",
            )
        }
    }

    @Test
    fun `a short event code is paddedRatherThanReadAsGarbage`() {
        expectInvalidInput {
            CompatibilityBleAdvertisementPayload.encode(
                peerIdBytes = peerId.toByteArray(StandardCharsets.UTF_8),
                eventCode = "AB",
            )
        }
    }

    @Test
    fun `aTruncatedAdvertisementIsRejectedRatherThanDecoded`() {
        val full = encode()

        for (size in 0 until CompatibilityBleAdvertisementPayload.TOTAL_BYTES) {
            assertNull(
                "a $size-byte payload must not decode",
                CompatibilityBleAdvertisementPayload.decode(full.copyOf(size)),
            )
        }
    }

    @Test
    fun `anEmptyAdvertisementIsRejected`() {
        assertNull(CompatibilityBleAdvertisementPayload.decode(ByteArray(0)))
    }

    @Test
    fun `anOversizedAdvertisementStillDecodesItsLeadingFields`() {
        val decoded = CompatibilityBleAdvertisementPayload.decode(encode() + byteArrayOf(9, 9, 9, 9))!!

        assertEquals(peerId, decoded.peerId)
        assertTrue(decoded.isHost)
    }

    @Test
    fun `aGeneratedPeerIdFitsTheAdvertisementField`() {
        val generated = SessionManager().peerId.value

        assertEquals(8, generated.length)
        assertEquals(8, generated.toByteArray(StandardCharsets.UTF_8).size)

        val decoded = CompatibilityBleAdvertisementPayload.decode(
            CompatibilityBleAdvertisementPayload.encode(
                peerIdBytes = generated.toByteArray(StandardCharsets.UTF_8),
            )
        )!!
        assertEquals(generated, decoded.peerId)
    }

    @Test
    fun `aZeroFlagByteMeansNoFlagsAreSet`() {
        val decoded = CompatibilityBleAdvertisementPayload.decode(encode(flags = 0))!!

        assertFalse(decoded.isHost)
        assertFalse(decoded.eventClosed)
        assertFalse(decoded.usesUwb)
        assertFalse(decoded.usesWifi)
        assertFalse(decoded.isFull)
        assertFalse(decoded.acceptsNewMembers)
    }
}
