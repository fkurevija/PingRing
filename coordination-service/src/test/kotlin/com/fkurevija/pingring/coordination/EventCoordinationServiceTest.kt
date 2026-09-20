package com.fkurevija.pingring.coordination

import com.fkurevija.pingring.coordination.model.CreateEventRequest
import com.fkurevija.pingring.coordination.model.JoinEventRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventCoordinationServiceTest {

    private var nextEventNumber = 0

    private fun newRequest(hostPeerId: String = "host-1", ttlSeconds: Long = 3600) = CreateEventRequest(
        id = "event-${nextEventNumber++}",
        name = "Picnic",
        description = "Park meetup",
        ttlSeconds = ttlSeconds,
        hostPeerId = hostPeerId,
        hostName = "Host"
    )

    @Test
    fun `create event registers host as a member`() = runTest {
        val service = EventCoordinationService()

        val response = service.createEvent(newRequest())

        assertEquals("Picnic", response.event.name)
        assertEquals(1, response.members.size)
        assertEquals("host-1", response.members.single().peerId)
        assertTrue(response.members.single().isHost)
    }

    @Test
    fun `create event rejects blank name`() = runTest {
        val service = EventCoordinationService()

        assertFailsWith<ValidationException> {
            service.createEvent(newRequest().copy(name = "  "))
        }
    }

    @Test
    fun `create event rejects non positive ttl`() = runTest {
        val service = EventCoordinationService()

        assertFailsWith<ValidationException> {
            service.createEvent(newRequest(ttlSeconds = 0))
        }
    }

    @Test
    fun `join event adds member and returns updated roster`() = runTest {
        val service = EventCoordinationService()
        val created = service.createEvent(newRequest())

        val response = service.joinEvent(created.event.id, JoinEventRequest("peer-2", "Alice"))

        assertEquals(2, response.members.size)
        assertTrue(response.members.any { it.peerId == "peer-2" && it.name == "Alice" })
    }

    @Test
    fun `join event fails for unknown event id`() = runTest {
        val service = EventCoordinationService()

        assertFailsWith<EventNotFoundException> {
            service.joinEvent("missing", JoinEventRequest("peer-2", "Alice"))
        }
    }

    @Test
    fun `join event enforces one active event per peer id`() = runTest {
        val service = EventCoordinationService()
        val eventA = service.createEvent(newRequest(hostPeerId = "host-a"))
        val eventB = service.createEvent(newRequest(hostPeerId = "host-b"))
        service.joinEvent(eventA.event.id, JoinEventRequest("peer-2", "Alice"))

        assertFailsWith<PeerAlreadyActiveException> {
            service.joinEvent(eventB.event.id, JoinEventRequest("peer-2", "Alice"))
        }
    }

    @Test
    fun `join event is idempotent for the same event`() = runTest {
        val service = EventCoordinationService()
        val created = service.createEvent(newRequest())
        service.joinEvent(created.event.id, JoinEventRequest("peer-2", "Alice"))

        val response = service.joinEvent(created.event.id, JoinEventRequest("peer-2", "Alice Renamed"))

        assertEquals(2, response.members.size)
        assertTrue(response.members.any { it.peerId == "peer-2" && it.name == "Alice Renamed" })
    }

    @Test
    fun `leave event removes member and frees peer id for other events`() = runTest {
        val service = EventCoordinationService()
        val eventA = service.createEvent(newRequest(hostPeerId = "host-a"))
        val eventB = service.createEvent(newRequest(hostPeerId = "host-b"))
        service.joinEvent(eventA.event.id, JoinEventRequest("peer-2", "Alice"))

        service.leaveEvent(eventA.event.id, "peer-2")
        val response = service.joinEvent(eventB.event.id, JoinEventRequest("peer-2", "Alice"))

        assertTrue(response.members.any { it.peerId == "peer-2" })
    }

    @Test
    fun `leave event fails for a peer that never joined`() = runTest {
        val service = EventCoordinationService()
        val created = service.createEvent(newRequest())

        assertFailsWith<MemberNotFoundException> {
            service.leaveEvent(created.event.id, "peer-2")
        }
    }

    @Test
    fun `end event is host only`() = runTest {
        val service = EventCoordinationService()
        val created = service.createEvent(newRequest())
        service.joinEvent(created.event.id, JoinEventRequest("peer-2", "Alice"))

        assertFailsWith<ForbiddenHostActionException> {
            service.endEvent(created.event.id, "peer-2")
        }
    }

    @Test
    fun `end event by host clears event and membership`() = runTest {
        val service = EventCoordinationService()
        val created = service.createEvent(newRequest())
        service.joinEvent(created.event.id, JoinEventRequest("peer-2", "Alice"))

        service.endEvent(created.event.id, "host-1")

        assertFailsWith<EventNotFoundException> { service.getEvent(created.event.id) }
        // Ended event frees up both peer ids to join/host new events.
        val recreated = service.createEvent(newRequest(hostPeerId = "host-1"))
        assertEquals("host-1", recreated.event.hostPeerId)
    }

    @Test
    fun `expired events are purged and no longer joinable`() = runTest {
        var now = 1_000_000L
        val service = EventCoordinationService(clock = { now })
        val created = service.createEvent(newRequest(ttlSeconds = 1))

        now += 2_000 // advance past expiry

        assertFailsWith<EventNotFoundException> { service.getEvent(created.event.id) }
        assertFailsWith<EventNotFoundException> {
            service.joinEvent(created.event.id, JoinEventRequest("peer-2", "Alice"))
        }
        assertEquals(0, service.activeEventCount())
    }

    @Test
    fun `expiry frees the host peer id for a new event`() = runTest {
        var now = 1_000_000L
        val service = EventCoordinationService(clock = { now })
        service.createEvent(newRequest(hostPeerId = "host-1", ttlSeconds = 1))

        now += 2_000

        val recreated = service.createEvent(newRequest(hostPeerId = "host-1", ttlSeconds = 3600))
        assertEquals("host-1", recreated.event.hostPeerId)
    }
}
