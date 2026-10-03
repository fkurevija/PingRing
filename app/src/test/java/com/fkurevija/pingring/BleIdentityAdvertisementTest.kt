package com.fkurevija.pingring

import com.fkurevija.pingring.data.BleIdentityAdvertisement
import com.fkurevija.pingring.data.BleIdentityCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleIdentityAdvertisementTest {
    @Test
    fun `signed packet round trips identity and sequence`() {
        val keys = BleIdentityCrypto.generateKeyPair()
        val packet = BleIdentityAdvertisement.encode(
            flags = BleIdentityAdvertisement.FLAG_HOST,
            peerId = "a1b2c3d4",
            eventCode = "e5f6a7",
            joinedAtMillis = 1_750_000_000_000L,
            sequence = 42L,
            privateKey = keys.private,
        )

        val decoded = BleIdentityAdvertisement.decode(packet, keys.public)

        assertEquals(BleIdentityAdvertisement.TOTAL_BYTES, packet.size)
        assertEquals("a1b2c3d4", decoded?.peerId)
        assertEquals("e5f6a7", decoded?.eventCode)
        assertEquals(42L, decoded?.sequence)
    }

    @Test
    fun `tampering and a different member public key are rejected`() {
        val keys = BleIdentityCrypto.generateKeyPair()
        val otherMember = BleIdentityCrypto.generateKeyPair()
        val packet = BleIdentityAdvertisement.encode(
            flags = 0,
            peerId = "a1b2c3d4",
            eventCode = "e5f6a7",
            joinedAtMillis = 1L,
            sequence = 1L,
            privateKey = keys.private,
        )
        val modified = packet.copyOf().also { it[1] = 1 }

        assertNull(BleIdentityAdvertisement.decode(modified, keys.public))
        assertNull(BleIdentityAdvertisement.decode(packet, otherMember.public))
    }

    @Test
    fun `only a first or strictly newer sequence is accepted`() {
        assertTrue(BleIdentityAdvertisement.acceptsSequence(null, 0L))
        assertTrue(BleIdentityAdvertisement.acceptsSequence(7L, 8L))
        assertTrue(!BleIdentityAdvertisement.acceptsSequence(8L, 8L))
        assertTrue(!BleIdentityAdvertisement.acceptsSequence(8L, 7L))
        assertTrue(!BleIdentityAdvertisement.acceptsSequence(null, -1L))
    }

    @Test
    fun `prejoin hint reads only locator flags and is not authenticated presence`() {
        val keys = BleIdentityCrypto.generateKeyPair()
        val packet = BleIdentityAdvertisement.encode(
            flags = BleIdentityAdvertisement.FLAG_HOST,
            peerId = "a1b2c3d4",
            eventCode = "e5f6a7",
            joinedAtMillis = 1L,
            sequence = 1L,
            privateKey = keys.private,
        ).also { it[it.lastIndex] = 1 }

        val hint = BleIdentityAdvertisement.decodeDiscoveryHint(packet)

        assertEquals("a1b2c3d4", hint?.peerId)
        assertEquals("e5f6a7", hint?.eventCode)
        assertTrue(hint?.isHost == true)
        // The hint reads an unauthenticated advert, which is why the signature
        // above is broken: identity is claimed, not proven. Nothing else is read
        // from it, and in particular nothing offers a join.
        assertNull(BleIdentityAdvertisement.decode(packet, keys.public))
    }
}