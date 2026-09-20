package com.fkurevija.pingring.coordination

import com.fkurevija.pingring.coordination.model.CreateEventRequest
import com.fkurevija.pingring.coordination.model.Event
import com.fkurevija.pingring.coordination.model.EventResponse
import com.fkurevija.pingring.coordination.model.JoinEventRequest
import com.fkurevija.pingring.coordination.model.Member
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe, in-memory coordination store for events and their membership.
 *
 * All mutating and reading operations are serialized through a single [Mutex] so that the
 * "one active event per peer id" invariant can be enforced atomically, and so that expiry
 * cleanup never races with a concurrent join/leave/end call. Given the expected scale of a
 * self-hosted coordination service (small groups, short-lived events) a single coarse-grained
 * lock keeps the implementation simple and correct without needing per-event locking.
 */
class EventCoordinationService(private val clock: () -> Long = System::currentTimeMillis) {

    private data class EventRecord(
        val event: Event,
        val members: MutableMap<String, Member> = linkedMapOf()
    )

    private val mutex = Mutex()
    private val events = mutableMapOf<String, EventRecord>()
    private val activeEventByPeer = mutableMapOf<String, String>()

    suspend fun createEvent(request: CreateEventRequest): EventResponse = mutex.withLock {
        val now = clock()
        purgeExpiredLocked(now)

        val id = request.id.trim()
        val name = request.name.trim()
        val hostPeerId = request.hostPeerId.trim()
        val hostName = request.hostName.trim()
        if (id.isEmpty()) throw ValidationException("id must not be blank")
        if (name.isEmpty()) throw ValidationException("name must not be blank")
        if (hostPeerId.isEmpty()) throw ValidationException("hostPeerId must not be blank")
        if (hostName.isEmpty()) throw ValidationException("hostName must not be blank")
        if (request.ttlSeconds <= 0) throw ValidationException("ttlSeconds must be positive")

        activeEventByPeer[hostPeerId]?.let { existingEventId ->
            throw PeerAlreadyActiveException(
                "Peer '$hostPeerId' is already active in event '$existingEventId'"
            )
        }

        if (events.containsKey(id)) throw ValidationException("event '$id' already exists")
        val event = Event(
            id = id,
            name = name,
            description = request.description.trim(),
            expiryTimestamp = now + request.ttlSeconds * 1000,
            hostPeerId = hostPeerId,
            trackHost = request.trackHost
        )
        val record = EventRecord(event)
        record.members[hostPeerId] = Member(hostPeerId, hostName, now, isHost = true)
        events[id] = record
        activeEventByPeer[hostPeerId] = id

        record.toResponse()
    }

    suspend fun getEvent(eventId: String): EventResponse = mutex.withLock {
        purgeExpiredLocked(clock())
        requireRecord(eventId).toResponse()
    }

    suspend fun listMembers(eventId: String): List<Member> = mutex.withLock {
        purgeExpiredLocked(clock())
        requireRecord(eventId).members.values.toList()
    }

    suspend fun joinEvent(eventId: String, request: JoinEventRequest): EventResponse = mutex.withLock {
        val now = clock()
        purgeExpiredLocked(now)
        val record = requireRecord(eventId)

        val peerId = request.peerId.trim()
        val name = request.name.trim()
        if (peerId.isEmpty()) throw ValidationException("peerId must not be blank")
        if (name.isEmpty()) throw ValidationException("name must not be blank")

        val existingEventId = activeEventByPeer[peerId]
        if (existingEventId != null && existingEventId != eventId) {
            throw PeerAlreadyActiveException(
                "Peer '$peerId' is already active in event '$existingEventId'"
            )
        }

        val isHost = peerId == record.event.hostPeerId
        record.members[peerId] = Member(peerId, name, now, isHost = isHost)
        activeEventByPeer[peerId] = eventId

        record.toResponse()
    }

    suspend fun leaveEvent(eventId: String, peerId: String) = mutex.withLock {
        purgeExpiredLocked(clock())
        val record = requireRecord(eventId)
        val trimmedPeerId = peerId.trim()

        record.members.remove(trimmedPeerId)
            ?: throw MemberNotFoundException("Peer '$trimmedPeerId' is not a member of event '$eventId'")
        if (activeEventByPeer[trimmedPeerId] == eventId) {
            activeEventByPeer.remove(trimmedPeerId)
        }
        Unit
    }

    suspend fun endEvent(eventId: String, hostPeerId: String) = mutex.withLock {
        purgeExpiredLocked(clock())
        val record = requireRecord(eventId)
        val trimmedHostPeerId = hostPeerId.trim()

        if (record.event.hostPeerId != trimmedHostPeerId) {
            throw ForbiddenHostActionException(
                "Peer '$trimmedHostPeerId' is not the host of event '$eventId'"
            )
        }

        events.remove(eventId)
        record.members.keys.forEach { peerId ->
            if (activeEventByPeer[peerId] == eventId) {
                activeEventByPeer.remove(peerId)
            }
        }
        Unit
    }

    /** Removes all events whose expiry timestamp has passed. Safe to call periodically. */
    suspend fun purgeExpired() = mutex.withLock {
        purgeExpiredLocked(clock())
    }

    /** Snapshot of how many events are currently tracked, mainly useful for tests/health checks. */
    suspend fun activeEventCount(): Int = mutex.withLock {
        purgeExpiredLocked(clock())
        events.size
    }

    private fun requireRecord(eventId: String): EventRecord =
        events[eventId] ?: throw EventNotFoundException("Event '$eventId' not found")

    private fun purgeExpiredLocked(now: Long) {
        val expiredIds = events.values.filter { it.event.expiryTimestamp <= now }.map { it.event.id }
        for (id in expiredIds) {
            val record = events.remove(id) ?: continue
            record.members.keys.forEach { peerId ->
                if (activeEventByPeer[peerId] == id) {
                    activeEventByPeer.remove(peerId)
                }
            }
        }
    }

    private fun EventRecord.toResponse() = EventResponse(event = event, members = members.values.toList())
}
