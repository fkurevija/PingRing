package com.fkurevija.pingring

import com.fkurevija.pingring.data.PeerDisplayNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Labels for members with no display name.
 *
 * The property that matters most: whatever label comes back must never contain
 * the peer id. That id is an internal routing key derived from BLE identity, and
 * showing it leaks an implementation detail to users while telling them nothing
 * they can act on.
 */
class PeerDisplayNamesTest {

    @Test
    fun `a label never contains the peer id`() {
        val names = PeerDisplayNames()
        val peerId = "a1b2c3d4"
        val label = names.labelFor(peerId)
        assertFalse("label '$label' leaked peer id '$peerId'", label.contains(peerId))
    }

    @Test
    fun `labels are positional and human readable`() {
        val names = PeerDisplayNames()
        assertEquals("Member 1", names.labelFor("a1b2c3d4"))
        assertEquals("Member 2", names.labelFor("z9y8x7w6"))
    }

    @Test
    fun `the same peer keeps the same label`() {
        // Otherwise a member would appear to be renamed every time a BLE
        // advertisement arrived, which reads as a bug to anyone watching.
        val names = PeerDisplayNames()
        val first = names.labelFor("a1b2c3d4")
        assertEquals(first, names.labelFor("a1b2c3d4"))
        assertEquals(first, names.labelFor("a1b2c3d4"))
    }

    @Test
    fun `different peers get different labels`() {
        val names = PeerDisplayNames()
        assertNotEquals(names.labelFor("a1b2c3d4"), names.labelFor("z9y8x7w6"))
    }

    @Test
    fun `a returning peer reuses its number until forgotten`() {
        val names = PeerDisplayNames()
        val first = names.labelFor("a1b2c3d4")
        names.labelFor("z9y8x7w6")
        assertEquals(first, names.labelFor("a1b2c3d4"))
    }

    @Test
    fun `a forgotten peer is renumbered if it returns`() {
        val names = PeerDisplayNames()
        names.labelFor("a1b2c3d4")
        names.forget("a1b2c3d4")
        // A member who leaves and rejoins is a new member, so it should not go on
        // displaying a number that belonged to its previous stint.
        assertEquals("Member 2", names.labelFor("a1b2c3d4"))
    }

    @Test
    fun `clearing restarts numbering and forgets every assignment`() {
        val names = PeerDisplayNames()
        names.labelFor("a1b2c3d4")
        names.labelFor("z9y8x7w6")
        names.clear()
        assertEquals(0, names.count)
        assertEquals("Member 1", names.labelFor("a1b2c3d4"))
    }

    @Test
    fun `a blank peer id still yields a usable label`() {
        // Defensive: the label is positional, so even a degenerate id produces
        // something displayable rather than an empty row.
        val names = PeerDisplayNames()
        assertTrue(names.labelFor("").isNotBlank())
    }
}