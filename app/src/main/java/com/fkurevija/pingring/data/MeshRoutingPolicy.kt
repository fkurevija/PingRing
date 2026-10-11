package com.fkurevija.pingring.data

import com.fkurevija.pingring.data.session.MAX_PARTICIPANTS

import java.util.LinkedHashMap
import java.security.MessageDigest

/** Bounds forwarding and duplicate retention for the in-memory peer mesh. */
internal object MeshRoutingPolicy {
    const val MAX_HOPS = MAX_PARTICIPANTS - 1
    const val SEEN_MESSAGE_CAPACITY = 4_096
    const val PENDING_MESSAGE_CAPACITY = 512
    const val PENDING_MESSAGE_BYTES = 1_048_576

    private val FORWARDABLE_PAYLOAD_TYPES = setOf(
        RelayCodec.CHAT,
        RelayCodec.CHAT_HISTORY,
        RelayCodec.CHAT_RECEIPT,
        RelayCodec.BEACON,
        RelayCodec.BLE_IDENTITY_KEY,
        RelayCodec.MEMBER_DEPARTURE,
        RelayCodec.MESH_PRESENCE,
        RelayCodec.PEER_RANGING,
        RelayCodec.EVENT_CONTROL,
        RelayCodec.UWB_ADDRESS,
        RelayCodec.UWB_PARAMS,
        RelayCodec.UWB_OFFER,
    )

    fun nextHopCount(currentHopCount: Int): Int? = when {
        currentHopCount < 0 -> null
        currentHopCount >= MAX_HOPS -> null
        else -> currentHopCount + 1
    }

    fun isForwardable(payloadType: String): Boolean = payloadType in FORWARDABLE_PAYLOAD_TYPES
    fun isEphemeral(payloadType: String): Boolean = payloadType == RelayCodec.MESH_PRESENCE ||
        payloadType == RelayCodec.PEER_RANGING

    fun shouldDeliver(targetPeerId: String?, localPeerId: String): Boolean =
        targetPeerId == null || targetPeerId == localPeerId

    fun shouldConnectToDiscoveredPeer(
        localPeerId: String,
        discoveredPeerId: String,
        isHost: Boolean,
        hostPeerId: String?,
        alreadyAdmitted: Boolean,
    ): Boolean {
        if (localPeerId == discoveredPeerId) return false
        if (alreadyAdmitted) return localPeerId < discoveredPeerId
        return !isHost && discoveredPeerId == hostPeerId
    }

    fun messageId(envelope: RelayEnvelope): String = envelope.messageId?.takeIf { it.isNotBlank() }
        ?: MessageDigest.getInstance("SHA-256")
            .digest("${envelope.eventId}|${envelope.type}|${envelope.targetPeerId}|${envelope.payload}".toByteArray())
            .joinToString("") { "%02x".format(it) }
}

internal class MeshDuplicateCache(
    private val capacity: Int = MeshRoutingPolicy.SEEN_MESSAGE_CAPACITY,
) {
    private val entries = LinkedHashMap<String, Unit>(capacity, 0.75f, true)

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun markIfNew(messageId: String): Boolean {
        if (messageId.isBlank()) return false
        if (entries.containsKey(messageId)) return false
        entries[messageId] = Unit
        if (entries.size > capacity) {
            val oldest = entries.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        return true
    }

    @Synchronized
    fun clear() = entries.clear()
}

internal class MeshPendingMessageQueue(
    private val capacity: Int = MeshRoutingPolicy.PENDING_MESSAGE_CAPACITY,
    private val maxBytes: Int = MeshRoutingPolicy.PENDING_MESSAGE_BYTES,
) {
    private data class Entry(val envelope: RelayEnvelope, val bytes: Int)

    private val entries = LinkedHashMap<String, Entry>()
    private var totalBytes = 0

    init {
        require(capacity > 0)
        require(maxBytes > 0)
    }

    @Synchronized
    fun enqueue(envelope: RelayEnvelope): Boolean {
        val messageId = MeshRoutingPolicy.messageId(envelope)
        val bytes = RelayCodec.encodeEnvelope(envelope).toByteArray().size
        if (bytes > maxBytes) return false
        entries.remove(messageId)?.let { totalBytes -= it.bytes }
        while (entries.size >= capacity || totalBytes + bytes > maxBytes) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) return false
            totalBytes -= oldest.next().value.bytes
            oldest.remove()
        }
        entries[messageId] = Entry(envelope, bytes)
        totalBytes += bytes
        return true
    }

    @Synchronized
    fun snapshot(): List<RelayEnvelope> = entries.values.map { it.envelope }

    @Synchronized
    fun remove(messageId: String) {
        entries.remove(messageId)?.let { totalBytes -= it.bytes }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalBytes = 0
    }
}