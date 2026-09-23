package com.fkurevija.pingring

import com.fkurevija.pingring.ui.screens.PingRingCodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingRingCodeParserTest {

    @Test
    fun parse_acceptsPlainEventCode() {
        assertEquals("ABC_123", PingRingCodeParser.parse("ABC_123"))
    }

    @Test
    fun parse_extractsEventCodeFromDeepLinkQuery() {
        assertEquals("EVENT-42", PingRingCodeParser.parse("pingring://join?event=EVENT-42"))
    }

    @Test
    fun parse_extractsEventCodeFromPath() {
        assertEquals("ROOM_777", PingRingCodeParser.parse("https://pingring.example/join/ROOM_777"))
    }

    @Test
    fun parse_rejectsInvalidAndEmptyValues() {
        assertNull(PingRingCodeParser.parse(""))
        assertNull(PingRingCodeParser.parse("  "))
        assertNull(PingRingCodeParser.parse("pingring://join?event=@@@"))
        assertNull(PingRingCodeParser.parse("ab"))
    }
}
