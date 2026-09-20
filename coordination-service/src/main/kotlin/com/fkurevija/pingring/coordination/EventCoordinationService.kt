package com.fkurevija.pingring.coordination

import com.fkurevija.pingring.coordination.model.CreateEventRequest
import com.fkurevija.pingring.coordination.model.Event
import com.fkurevija.pingring.coordination.model.EventResponse
import com.fkurevija.pingring.coordination.model.JoinEventRequest
import com.fkurevija.pingring.coordination.model.Member
import com.fkurevija.pingring.coordination.model.UwbControllerParameters
import com.fkurevija.pingring.coordination.model.UwbExchangeResponse
import com.fkurevija.pingring.coordination.model.UwbPeerAddress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Thread-safe, in-memory coordination store for events and their membership.
 *
 * All mutating and reading operations are serialized through a single [Mutex] so that the
 * "one active event per peer id" invariant can be enforced atomically, and so that expiry
 * cleanup never races with a concurrent join/leave/end call. Given the expected scale of a
 * self-hosted coordination service (small groups, short-lived events) a single coarse-grained
 * lock keeps the implementation simple and correct without needing per-event locking.
 */
class EventCoordinationService(
    private val clock: () -> Long = System::currentTimeMillis,
    private val storageFile: Path? = null
) {

    private data class EventRecord(
        val event: Event,
        val members: MutableMap<String, Member> = linkedMapOf(),
        var uwbController: UwbControllerParameters? = null,
        val uwbAddresses: MutableMap<String, String> = mutableMapOf()
    )

    private val mutex = Mutex()
    private val events = mutableMapOf<String, EventRecord>()
    private val activeEventByPeer = mutableMapOf<String, String>()

    init {
        restore()
        purgeExpiredLocked(clock())
        persist()
    }

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
        persist()

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
        persist()

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
        persist()
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
            persist()
        }
        Unit
    }

    suspend fun publishUwbController(eventId: String, parameters: UwbControllerParameters) = mutex.withLock {
        purgeExpiredLocked(clock())
        val record = requireRecord(eventId)
        if (record.event.hostPeerId != parameters.hostPeerId) {
            throw ForbiddenHostActionException("Only the host can publish UWB controller parameters")
        }
        record.uwbController = parameters
        persist()
    }

    suspend fun publishUwbPeerAddress(eventId: String, address: UwbPeerAddress) = mutex.withLock {
        purgeExpiredLocked(clock())
        val record = requireRecord(eventId)
        if (address.peerId !in record.members) {
            throw MemberNotFoundException("Peer '${address.peerId}' is not a member of event '$eventId'")
        }
        record.uwbAddresses[address.peerId] = address.address
        persist()
    }

    suspend fun getUwbExchange(eventId: String): UwbExchangeResponse = mutex.withLock {
        purgeExpiredLocked(clock())
        val record = requireRecord(eventId)
        UwbExchangeResponse(
            controller = record.uwbController,
            peers = record.uwbAddresses.map { (peerId, address) -> UwbPeerAddress(peerId, address) }
        )
    }

    /** Removes all events whose expiry timestamp has passed. Safe to call periodically. */
    suspend fun purgeExpired() = mutex.withLock {
        purgeExpiredLocked(clock())
        persist()
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

    private fun restore() {
        val file = storageFile ?: return
        if (!Files.exists(file)) return
        runCatching {
            json.decodeFromString<PersistedState>(Files.readString(file)).events.forEach { stored ->
                val record = EventRecord(
                    stored.event,
                    stored.members.associateByTo(linkedMapOf()) { it.peerId },
                    stored.uwbController,
                    stored.uwbPeers.associate { it.peerId to it.address }.toMutableMap()
                )
                events[stored.event.id] = record
                record.members.keys.forEach { peerId -> activeEventByPeer[peerId] = stored.event.id }
            }
        }
    }

    private fun persist() {
        val file = storageFile ?: return
        runCatching {
            file.parent?.let(Files::createDirectories)
            val snapshot = PersistedState(events.values.map { record ->
                PersistedEvent(
                    record.event,
                    record.members.values.toList(),
                    record.uwbController,
                    record.uwbAddresses.map { (peerId, address) -> UwbPeerAddress(peerId, address) }
                )
            })
            Files.writeString(file, json.encodeToString(snapshot), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }
    }

    @Serializable
    private data class PersistedState(val events: List<PersistedEvent> = emptyList())

    @Serializable
    private data class PersistedEvent(
        val event: Event,
        val members: List<Member>,
        val uwbController: UwbControllerParameters? = null,
        val uwbPeers: List<UwbPeerAddress> = emptyList()
    )

    private companion object {
        val json = Json { encodeDefaults = true }
    }

    private fun EventRecord.toResponse() = EventResponse(event = event, members = members.values.toList())
}
