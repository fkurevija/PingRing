package com.fkurevija.pingring

import com.fkurevija.pingring.data.BleIdentityCrypto
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleIdentityCryptoTest {
    @Test
    fun `public key round trips and verifies a signature`() {
        val keyPair = BleIdentityCrypto.generateKeyPair()
        val publicKey = BleIdentityCrypto.decodePublicKey(
            BleIdentityCrypto.encodePublicKey(keyPair.public)
        )
        val message = "signed BLE identity".toByteArray()
        val signature = BleIdentityCrypto.sign(keyPair.private, message)

        assertNotNull(publicKey)
        assertTrue(BleIdentityCrypto.verify(publicKey!!, message, signature))
    }

    @Test
    fun `signature rejects a modified message and another member key`() {
        val signer = BleIdentityCrypto.generateKeyPair()
        val otherMember = BleIdentityCrypto.generateKeyPair()
        val message = "member and event identity".toByteArray()
        val signature = BleIdentityCrypto.sign(signer.private, message)

        assertFalse(BleIdentityCrypto.verify(signer.public, message + byteArrayOf(1), signature))
        assertFalse(BleIdentityCrypto.verify(otherMember.public, message, signature))
    }

    @Test
    fun `malformed and non P-256 public keys are rejected`() {
        assertNull(BleIdentityCrypto.decodePublicKey("not-a-key"))
        val otherCurve = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp384r1"))
            generateKeyPair()
        }
        assertNull(BleIdentityCrypto.decodePublicKey(BleIdentityCrypto.encodePublicKey(otherCurve.public)))
    }
}