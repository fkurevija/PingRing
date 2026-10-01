package com.fkurevija.pingring

import com.fkurevija.pingring.ui.screens.PingRingCodeParser
import com.fkurevija.pingring.ui.screens.PingRingInvite
import com.fkurevija.pingring.data.InviteSecret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingRingCodeParserTest {

    @Test
    fun parse_acceptsPlainEventCode() {
        assertEquals("ABC123", PingRingCodeParser.parse("ABC123"))
    }

    @Test
    fun parse_extractsEventCodeFromDeepLinkQuery() {
        assertEquals("AB12CD", PingRingCodeParser.parse("pingring://join?event=AB12CD"))
    }

    @Test
    fun parse_extractsEventCodeFromPath() {
        assertEquals("A1B2C3", PingRingCodeParser.parse("https://pingring.example/join/A1B2C3"))
    }

    @Test
    fun parse_rejectsInvalidAndEmptyValues() {
        assertNull(PingRingCodeParser.parse(""))
        assertNull(PingRingCodeParser.parse("  "))
        assertNull(PingRingCodeParser.parse("pingring://join?event=@@@"))
        assertNull(PingRingCodeParser.parse("EVENT-42"))
        assertNull(PingRingCodeParser.parse("ab"))
    }

    @Test
    fun qrInvitationCarriesSecretSeparatelyFromThePublicCode() {
        val invite = PingRingInvite("ABC123", InviteSecret.generate())

        assertEquals(invite, PingRingCodeParser.parseInvite(invite.toQrValue()))
        assertEquals("ABC123", PingRingCodeParser.parseInvite("ABC123")?.eventCode)
        assertNull(PingRingCodeParser.parseInvite("pingring://join?event=ABC123&secret=weak"))
    }

    @Test
    fun inviteSecretsContain256RandomBits() {
        val first = InviteSecret.generate()
        val second = InviteSecret.generate()

        assertEquals(32, InviteSecret.decode(first)?.size)
        assertNotEquals(first, second)
    }
}
