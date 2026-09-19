package com.fkurevija.pingring

import com.fkurevija.pingring.ui.screens.EventCodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventCodeParserTest {

    @Test
    fun parse_acceptsPlainEventCode() {
        assertEquals("ABC_123", EventCodeParser.parse("ABC_123"))
    }

    @Test
    fun parse_extractsEventCodeFromDeepLinkQuery() {
        assertEquals("EVENT-42", EventCodeParser.parse("pingring://join?event=EVENT-42"))
    }

    @Test
    fun parse_extractsEventCodeFromPath() {
        assertEquals("ROOM_777", EventCodeParser.parse("https://pingring.example/join/ROOM_777"))
    }

    @Test
    fun parse_rejectsInvalidAndEmptyValues() {
        assertNull(EventCodeParser.parse(""))
        assertNull(EventCodeParser.parse("  "))
        assertNull(EventCodeParser.parse("pingring://join?event=@@@"))
        assertNull(EventCodeParser.parse("ab"))
    }
}
