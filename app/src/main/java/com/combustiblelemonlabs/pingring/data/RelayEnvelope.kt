package com.combustiblelemonlabs.pingring.data

import com.squareup.moshi.JsonClass
import com.combustiblelemonlabs.pingring.data.session.Event

/**
 * Line-delimited JSON envelope used by [LanDebugConnectionsManager] to talk to
 * the debug relay server (tools/lan_debug_relay.py). The relay groups
 * connected clients by [eventId] and fans messages out based on
 * [targetPeerId]: null means "broadcast to everyone else in the group",
 * non-null means "deliver only to that peerId".
 *
 * Envelopes this device sends: [JOIN] (with [role]), [DATA], [LEAVE], [END]
 * and [PING]. Envelopes the relay sends back: [PEER_JOINED], [PEER_LEFT],
 * [EVENT_CLOSED] and forwarded [DATA]. [role] is only meaningful on a join:
 * a host tells the relay it owns the event, and the relay closes the event for
 * everyone else when that host ends it or fails to come back after a drop.
 */
@JsonClass(generateAdapter = true)
data class RelayEnvelope(
    val type: String,
    val eventId: String? = null,
    val senderPeerId: String? = null,
    val targetPeerId: String? = null, // null = broadcast
    val payload: String? = null, // JSON-encoded DTO, only present when type == "data"
    val role: String? = null, // "host" or "member", only present when type == "join"
    val messageId: String? = null, // Unique identifier for the message
    val originPeerId: String? = null, // ID of the peer that originated the message
    val hopCount: Int = 0, // Number of hops the message has taken
    val originSignature: String? = null,
) {
    companion object {
        const val JOIN = "join"
        const val DATA = "data"
        const val LEAVE = "leave"
        const val END = "end"
        const val PING = "ping"

        const val PEER_JOINED = "peer-joined"
        const val PEER_LEFT = "peer-left"
        const val EVENT_CLOSED = "event-closed"

        const val ROLE_HOST = "host"
        const val ROLE_MEMBER = "member"
    }
}
