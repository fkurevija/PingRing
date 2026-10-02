package com.fkurevija.pingring.data

import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.PublicKey

internal object BleIdentityAdvertisement {
    const val VERSION = 5
    const val MAX_SIGNATURE_BYTES = 72
    const val TOTAL_BYTES = 98
    const val FLAG_DISCOVERABLE = 1 shl 7

    private const val PEER_ID_BYTES = 4
    private const val EVENT_CODE_BYTES = 3
    private const val PREFIX_BYTES = 25

    data class Decoded(
        val flags: Int,
        val peerId: String,
        val eventCode: String,
        val joinedAtMillis: Long,
        val sequence: Long,
    ) {
        val isDiscoverable: Boolean get() = flags and FLAG_DISCOVERABLE != 0
    }

    data class DiscoveryHint(val eventCode: String?, val isDiscoverable: Boolean)

    fun acceptsSequence(previousSequence: Long?, candidateSequence: Long): Boolean =
        candidateSequence >= 0 && (previousSequence == null || candidateSequence > previousSequence)

    fun encode(
        flags: Int,
        peerId: String,
        eventCode: String,
        joinedAtMillis: Long,
        sequence: Long,
        privateKey: PrivateKey,
    ): ByteArray {
        require(flags in 0..0xFF)
        require(peerId.length == 8 && peerId.all(::isHexDigit))
        require(eventCode.length == 6 && eventCode.all(::isHexDigit))
        require(sequence >= 0)

        val prefix = ByteBuffer.allocate(PREFIX_BYTES)
            .put(VERSION.toByte())
            .put(flags.toByte())
            .put(hexBytes(peerId))
            .put(hexBytes(eventCode))
            .putLong(joinedAtMillis)
            .putLong(sequence)
            .array()
        val signature = BleIdentityCrypto.sign(privateKey, prefix)
        require(signature.size <= MAX_SIGNATURE_BYTES)
        return ByteBuffer.allocate(TOTAL_BYTES)
            .put(prefix)
            .put(signature.size.toByte())
            .put(signature)
            .put(ByteArray(MAX_SIGNATURE_BYTES - signature.size))
            .array()
    }

    fun readPeerId(serviceData: ByteArray): String? {
        if (serviceData.size != TOTAL_BYTES || serviceData[0].toInt() != VERSION) return null
        return bytesToHex(serviceData.copyOfRange(2, 2 + PEER_ID_BYTES)).lowercase()
    }

    fun decode(serviceData: ByteArray, publicKey: PublicKey): Decoded? {
        if (serviceData.size != TOTAL_BYTES || serviceData[0].toInt() != VERSION) return null
        val prefix = serviceData.copyOfRange(0, PREFIX_BYTES)
        val signatureLength = serviceData[PREFIX_BYTES].toInt() and 0xFF
        if (signatureLength !in 8..MAX_SIGNATURE_BYTES) return null
        val signatureStart = PREFIX_BYTES + 1
        val signatureEnd = signatureStart + signatureLength
        if (serviceData.copyOfRange(signatureEnd, serviceData.size).any { it != 0.toByte() }) return null
        if (!BleIdentityCrypto.verify(publicKey, prefix, serviceData.copyOfRange(signatureStart, signatureEnd))) return null

        val payload = ByteBuffer.wrap(prefix)
        payload.get()
        val flags = payload.get().toInt() and 0xFF
        val peerId = bytesToHex(ByteArray(PEER_ID_BYTES).also(payload::get)).lowercase()
        val eventCode = bytesToHex(ByteArray(EVENT_CODE_BYTES).also(payload::get)).lowercase()
        val joinedAtMillis = payload.long
        val sequence = payload.long
        if (sequence < 0) return null
        return Decoded(flags, peerId, eventCode, joinedAtMillis, sequence)
    }

    fun decodeDiscoveryHint(serviceData: ByteArray): DiscoveryHint? {
        if (serviceData.size != TOTAL_BYTES || serviceData[0].toInt() != VERSION) return null
        val flags = serviceData[1].toInt() and 0xFF
        val eventCode = bytesToHex(serviceData.copyOfRange(2 + PEER_ID_BYTES, 2 + PEER_ID_BYTES + EVENT_CODE_BYTES))
            .lowercase()
        return DiscoveryHint(eventCode, flags and FLAG_DISCOVERABLE != 0)
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char.lowercaseChar() in 'a'..'f'
}