package com.fkurevija.pingring.coordination.model

import kotlinx.serialization.Serializable

/**
 * A coordination event ("Ping-Ring") that a host creates and peers join.
 */
@Serializable
data class Event(
    val id: String,
    val name: String,
    val description: String,
    val expiryTimestamp: Long,
    val hostPeerId: String,
    val trackHost: Boolean = false
)

/**
 * A device peer participating in an [Event].
 */
@Serializable
data class Member(
    val peerId: String,
    val name: String,
    val joinedAt: Long,
    val isHost: Boolean = false
)

@Serializable
data class CreateEventRequest(
    val id: String,
    val name: String,
    val description: String = "",
    val ttlSeconds: Long,
    val hostPeerId: String,
    val hostName: String,
    val trackHost: Boolean = false
)

@Serializable
data class JoinEventRequest(
    val peerId: String,
    val name: String
)

@Serializable
data class EndEventRequest(
    val hostPeerId: String
)

@Serializable
data class EventResponse(
    val event: Event,
    val members: List<Member>
)

@Serializable
data class MembersResponse(
    val members: List<Member>
)

@Serializable
data class ApiError(
    val error: String,
    val message: String
)

@Serializable
data class UwbControllerParameters(
    val hostPeerId: String,
    val controllerAddress: String,
    val channel: Int,
    val preambleIndex: Int,
    val sessionId: Int,
    val staticStsKey: String
)

@Serializable
data class UwbPeerAddress(
    val peerId: String,
    val address: String
)

@Serializable
data class UwbExchangeResponse(
    val controller: UwbControllerParameters?,
    val peers: List<UwbPeerAddress>
)
