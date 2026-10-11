package com.fkurevija.pingring

import com.fkurevija.pingring.data.session.InviteSecret
import com.fkurevija.pingring.data.nfc.NfcInviteApdu
import com.fkurevija.pingring.data.nfc.NfcInviteExchange
import com.fkurevija.pingring.ui.screens.PingRingCodeParser
import com.fkurevija.pingring.ui.screens.PingRingInvite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.fkurevija.pingring.data.session.Event

class NfcInviteApduTest {

    @Test
    fun selectedApplicationReturnsTheSameInviteAsTheQrCode() {
        val invite = PingRingInvite("AB12CD", InviteSecret.generate())
        val response = NfcInviteApdu.respond(
            NfcInviteApdu.selectCommand,
            invite.toQrValue(),
        )

        assertEquals(invite, PingRingCodeParser.parseInvite(NfcInviteApdu.decodeResponse(response)))
    }

    @Test
    fun unrecognizedCommandsDoNotReturnAnInvitation() {
        val response = NfcInviteApdu.respond(byteArrayOf(0x00, 0x01), "private invite")

        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), response)
        assertNull(NfcInviteApdu.decodeResponse(response))
    }

    @Test
    fun missingInviteIsNotTreatedAsSuccess() {
        val response = NfcInviteApdu.respond(NfcInviteApdu.selectCommand, null)

        assertArrayEquals(byteArrayOf(0x69, 0x85.toByte()), response)
        assertNull(NfcInviteApdu.decodeResponse(response))
    }

    @Test
    fun malformedResponseOrUtf8IsRejected() {
        assertNull(NfcInviteApdu.decodeResponse(null))
        assertNull(NfcInviteApdu.decodeResponse(byteArrayOf(0x01, 0x02)))
        assertNull(
            NfcInviteApdu.decodeResponse(
                byteArrayOf(0xC3.toByte(), 0x28, 0x90.toByte(), 0x00),
            ),
        )
        assertNull(NfcInviteApdu.decodeResponse(byteArrayOf(0x41, 0x6A, 0x82.toByte())))
    }

    @Test
    fun invalidInviteSecretIsNeverActivatedForHostEmulation() {
        val value = "pingring://join?event=AB12CD&secret=invalid"
        NfcInviteExchange.activate("AB12CD", "invalid")

        assertNull(NfcInviteExchange.currentInvite())
    }

    @Test
    fun hostEmulationOnlyServesAValidatedSecretUntilTheInviteScreenCloses() {
        val secret = InviteSecret.generate()
        val value = PingRingInvite("AB12CD", secret).toQrValue()
        NfcInviteExchange.activate("AB12CD", secret)

        assertEquals(value, NfcInviteExchange.currentInvite())
        NfcInviteExchange.deactivate("another invite")
        assertEquals(value, NfcInviteExchange.currentInvite())

        NfcInviteExchange.deactivate(value)
        assertNull(NfcInviteExchange.currentInvite())
    }
}
