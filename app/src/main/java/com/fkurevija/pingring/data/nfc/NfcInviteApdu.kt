package com.fkurevija.pingring.data.nfc

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Small ISO-DEP application protocol for transferring the same bearer invite
 * encoded in PingRing's QR code.
 */
internal object NfcInviteApdu {
    private val aid = byteArrayOf(
        0xF0.toByte(), 0x4B, 0x55, 0x52, 0x45, 0x50, 0x49, 0x4E, 0x47,
    )

    val selectCommand: ByteArray = byteArrayOf(
        0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte(),
    ) + aid + byteArrayOf(0x00)

    private val successStatus = byteArrayOf(0x90.toByte(), 0x00)
    private val fileNotFoundStatus = byteArrayOf(0x6A, 0x82.toByte())
    private val conditionsNotSatisfiedStatus = byteArrayOf(0x69, 0x85.toByte())
    private val wrongLengthStatus = byteArrayOf(0x67, 0x00)
    private const val MAX_RESPONSE_DATA_BYTES = 240

    fun respond(command: ByteArray?, inviteValue: String?): ByteArray {
        if (command == null || !command.contentEquals(selectCommand)) {
            return fileNotFoundStatus
        }
        val invite = inviteValue ?: return conditionsNotSatisfiedStatus
        val payload = invite.toByteArray(StandardCharsets.UTF_8)
        if (payload.isEmpty() || payload.size > MAX_RESPONSE_DATA_BYTES) {
            return wrongLengthStatus
        }
        return payload + successStatus
    }

    fun decodeResponse(response: ByteArray?): String? {
        if (response == null || response.size <= successStatus.size) return null
        val statusOffset = response.size - successStatus.size
        if (!response.copyOfRange(statusOffset, response.size).contentEquals(successStatus)) return null
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(response, 0, statusOffset))
                .toString()
                .takeIf(String::isNotBlank)
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
