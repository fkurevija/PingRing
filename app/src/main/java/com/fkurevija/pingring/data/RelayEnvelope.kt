package com.fkurevija.pingring.data

import com.squareup.moshi.JsonClass

/**
 * Line-delimited JSON envelope used by [LanDebugConnectionsManager] to talk to
 * the debug relay server (tools/lan_debug_relay.py). The relay groups
 * connected clients by [eventId] and fans messages out based on
 * [targetPeerId]: null means "broadcast to everyone else in the group",
 * non-null means "deliver only to that peerId".
 */
@JsonClass(generateAdapter = true)
data class RelayEnvelope(
    val type: String, // "join", "peer-joined", or "data"
    val eventId: String? = null,
    val senderPeerId: String? = null,
    val targetPeerId: String? = null, // null = broadcast
    val payload: String? = null // JSON-encoded DTO, only present when type == "data"
)
