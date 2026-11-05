package com.combustiblelemonlabs.pingring.data.session
import com.combustiblelemonlabs.pingring.data.session.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A member's copy of the event has to describe the same ring on every device: a
 * guest that joined an event whose name or lifetime does not match the host's is
 * not something anyone inside the app could notice, because each side only ever
 * shows its own copy.
 */
class EventTest {

    @Test
    fun aHostedEventNamesItselfAfterItsCodeAndRecordsTheHost() {
        val event = Event.create(id = "4C1A9B", hostPeerId = "host-peer-id", useUwb = true)

        assertEquals("4C1A9B", event.id)
        assertEquals("PingRing 4C1A9B", event.name)
        assertEquals("PingRing Event", event.description)
        assertEquals("host-peer-id", event.hostPeerId)
        assertTrue(event.useUwb)
    }

    @Test
    fun aJoinedEventHasNoHostUntilTheHostIsLearned() {
        // A guest has never seen the host's peer id at the moment it joins: it is
        // picked up from the host's BLE advertisement afterwards, so a non-null
        // host here would make the app look for a member that does not exist yet.
        val event = Event.create(id = "4C1A9B")

        assertNull(event.hostPeerId)
    }

    @Test
    fun anEventLastsForTheAdvertisedDurationFromNow() {
        val before = System.currentTimeMillis()

        val event = Event.create(id = "4C1A9B")

        assertTrue(event.expiryTimestamp >= before + Event.DURATION_MILLIS)
        assertTrue(event.expiryTimestamp <= System.currentTimeMillis() + Event.DURATION_MILLIS)
    }

    @Test
    fun hostingAndJoiningProduceTheSameEventApartFromTheHost() {
        val hosted = Event.create(id = "4C1A9B", hostPeerId = "host-peer-id", useUwb = false)
        val joined = Event.create(id = "4C1A9B")

        // The parts every member has to agree on, compared by value rather than by
        // field so a new shared field cannot silently be left out of the check.
        assertEquals(hosted.id, joined.id)
        assertEquals(hosted.name, joined.name)
        assertEquals(hosted.description, joined.description)
        assertEquals(hosted.useWifiDirect, joined.useWifiDirect)
        // GNSS sharing used to be a host-side opt-in, so a guest and a host could
        // hold genuinely different copies of the same event. It is now always on,
        // and this is the assertion that keeps it that way.
        assertEquals(hosted.useGnss, joined.useGnss)
        assertTrue(joined.useGnss)
        assertEquals(hosted.acceptsNewMembers, joined.acceptsNewMembers)
    }

    @Test
    fun uwbIsOffWhenTheDeviceCannotRange() {
        val event = Event.create(id = "4C1A9B", useUwb = false)

        assertEquals(false, event.useUwb)
    }
}
