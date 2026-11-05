package com.combustiblelemonlabs.pingring.data

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal object BleIdentityCrypto {
    private const val KEY_ALGORITHM = "EC"
    private const val CURVE_NAME = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance(KEY_ALGORITHM).run {
        initialize(ECGenParameterSpec(CURVE_NAME))
        generateKeyPair()
    }

    fun encodePublicKey(publicKey: PublicKey): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.encoded)

    fun decodePublicKey(encoded: String): PublicKey? {
        if (encoded.length !in 100..180) return null
        val keyBytes = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
        if (keyBytes.size !in 72..135) return null
        val key = runCatching {
            KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(keyBytes))
        }.getOrNull() ?: return null
        val ecKey = key as? java.security.interfaces.ECPublicKey ?: return null
        return key.takeIf { ecKey.params.curve.field.fieldSize == 256 }
    }

    fun sign(privateKey: java.security.PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(message)
            sign()
        }

    fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(message)
                verify(signature)
            }
        }.getOrDefault(false)
}