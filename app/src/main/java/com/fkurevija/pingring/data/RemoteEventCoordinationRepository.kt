package com.fkurevija.pingring.data

import android.content.Context
import android.util.Log
import com.fkurevija.pingring.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

private const val TAG = "RemoteCoordination"

@JsonClass(generateAdapter = true)
data class CreateEventBody(
    val id: String,
    val name: String,
    val description: String,
    val ttlSeconds: Long,
    val hostPeerId: String,
    val hostName: String,
    val trackHost: Boolean
)

@JsonClass(generateAdapter = true)
data class JoinEventBody(val peerId: String, val name: String)

@JsonClass(generateAdapter = true)
data class EndEventBody(val hostPeerId: String)

@JsonClass(generateAdapter = true)
data class RemoteEvent(
    val id: String,
    val name: String,
    val description: String,
    val expiryTimestamp: Long,
    val hostPeerId: String,
    val trackHost: Boolean
)

@JsonClass(generateAdapter = true)
data class RemoteMember(
    val peerId: String,
    val name: String,
    val joinedAt: Long,
    val isHost: Boolean
)

@JsonClass(generateAdapter = true)
data class EventResponse(val event: RemoteEvent, val members: List<RemoteMember>)

private interface CoordinationApi {
    @POST("api/events")
    suspend fun create(@Body request: CreateEventBody): EventResponse

    @POST("api/events/{eventId}/members")
    suspend fun join(@Path("eventId") eventId: String, @Body request: JoinEventBody): EventResponse

    @DELETE("api/events/{eventId}/members/{peerId}")
    suspend fun leave(@Path("eventId") eventId: String, @Path("peerId") peerId: String)

    @POST("api/events/{eventId}/end")
    suspend fun end(@Path("eventId") eventId: String, @Body request: EndEventBody)

    @GET("api/events/{eventId}")
    suspend fun event(@Path("eventId") eventId: String): EventResponse
}

/**
 * Mirrors the authoritative coordination service into observable app state. Calls never block the
 * UI; a failed mutation is rolled back and reported through [syncStatus].
 */
class RemoteEventCoordinationRepository(context: Context) : EventCoordinationRepository {
    private val local = InMemoryEventCoordinationRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = Retrofit.Builder()
        .baseUrl(BuildConfig.COORDINATION_URL)
        .addConverterFactory(
            MoshiConverterFactory.create(
                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            )
        )
        .build()
        .create(CoordinationApi::class.java)

    override val sessionState: StateFlow<SessionState> = local.sessionState
    override val syncStatus: StateFlow<CoordinationSyncStatus> = local.syncStatus

    override fun joinEvent(event: Event, peerId: String) {
        local.joinEvent(event, peerId)
        scope.launch {
            runCatching {
                api.join(event.id, JoinEventBody(peerId, "Member"))
            }.onSuccess(::applyServerState)
                .onFailure { rollback("Unable to join Ping-Ring: ${it.message}") }
        }
    }

    override fun hostEvent(event: Event, peerId: String) {
        local.hostEvent(event, peerId)
        scope.launch {
            runCatching {
                api.create(
                    CreateEventBody(
                        id = event.id,
                        name = event.name,
                        description = event.description,
                        ttlSeconds = ((event.expiryTimestamp - System.currentTimeMillis()) / 1000).coerceAtLeast(1),
                        hostPeerId = peerId,
                        hostName = "Host",
                        trackHost = event.trackHost
                    )
                )
            }.onSuccess(::applyServerState)
                .onFailure { rollback("Unable to create Ping-Ring: ${it.message}") }
        }
    }

    override fun updateEvent(event: Event) = local.updateEvent(event)

    override fun leaveEvent(peerId: String) {
        val event = sessionState.value.currentEvent
        local.leaveEvent(peerId)
        if (event != null) {
            scope.launch { runCatching { api.leave(event.id, peerId) } }
        }
    }

    override fun setVisibility(visible: Boolean) = local.setVisibility(visible)
    override fun updateMembers(newMembers: List<Friend>) = local.updateMembers(newMembers)
    override fun upsertMember(member: Friend) = local.upsertMember(member)
    override fun markSyncing() = local.markSyncing()
    override fun markSynced() = local.markSynced()
    override fun markSyncError(message: String) = local.markSyncError(message)

    fun refresh() {
        val event = sessionState.value.currentEvent ?: return
        scope.launch {
            runCatching { api.event(event.id) }
                .onSuccess(::applyServerState)
                .onFailure {
                    if (it is retrofit2.HttpException && it.code() == 404) {
                        local.leaveEvent(event.hostPeerId.orEmpty())
                    } else {
                        Log.w(TAG, "Unable to refresh Ping-Ring state", it)
                    }
                }
        }
    }

    fun endEvent(hostPeerId: String) {
        val event = sessionState.value.currentEvent ?: return
        local.leaveEvent(hostPeerId)
        scope.launch {
            runCatching { api.end(event.id, EndEventBody(hostPeerId)) }
                .onFailure { local.markSyncError("Unable to end Ping-Ring: ${it.message}") }
        }
    }

    private fun applyServerState(response: EventResponse) {
        local.updateEvent(response.event.toEvent())
        local.updateMembers(response.members.map { member ->
            Friend(member.peerId, member.name, member.joinedAt, null)
        })
        local.markSynced()
    }

    private fun rollback(message: String) {
        local.leaveEvent(sessionState.value.currentEvent?.hostPeerId.orEmpty())
        local.markSyncError(message)
    }

    private fun RemoteEvent.toEvent() = Event(
        id = id,
        name = name,
        description = description,
        expiryTimestamp = expiryTimestamp,
        hostPeerId = hostPeerId,
        trackHost = trackHost
    )
}
