package com.fkurevija.pingring.coordination

import com.fkurevija.pingring.coordination.model.ApiError
import com.fkurevija.pingring.coordination.model.CreateEventRequest
import com.fkurevija.pingring.coordination.model.EndEventRequest
import com.fkurevija.pingring.coordination.model.JoinEventRequest
import com.fkurevija.pingring.coordination.model.MembersResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Ktor application module wiring content negotiation, error handling, routes and cleanup. */
fun Application.coordinationModule(service: EventCoordinationService = EventCoordinationService()) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<ValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", cause.message ?: "Invalid request"))
        }
        exception<EventNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiError("event_not_found", cause.message ?: "Event not found"))
        }
        exception<MemberNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiError("member_not_found", cause.message ?: "Member not found"))
        }
        exception<PeerAlreadyActiveException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiError("peer_already_active", cause.message ?: "Peer already active"))
        }
        exception<ForbiddenHostActionException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", cause.message ?: "Forbidden"))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", cause.message ?: "Unexpected error"))
        }
    }

    // Periodic sweep for events whose expiry timestamp has passed, in addition to the
    // lazy purge performed on every service call, so idle events are still reclaimed.
    launch {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            service.purgeExpired()
        }
    }

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        route("/api/events") {
            post {
                val request = call.receive<CreateEventRequest>()
                call.respond(HttpStatusCode.Created, service.createEvent(request))
            }

            route("/{eventId}") {
                get {
                    val eventId = call.parameters.getOrFail("eventId")
                    call.respond(HttpStatusCode.OK, service.getEvent(eventId))
                }

                post("/end") {
                    val eventId = call.parameters.getOrFail("eventId")
                    val request = call.receive<EndEventRequest>()
                    service.endEvent(eventId, request.hostPeerId)
                    call.respond(HttpStatusCode.NoContent)
                }

                route("/members") {
                    get {
                        val eventId = call.parameters.getOrFail("eventId")
                        call.respond(HttpStatusCode.OK, MembersResponse(service.listMembers(eventId)))
                    }

                    post {
                        val eventId = call.parameters.getOrFail("eventId")
                        val request = call.receive<JoinEventRequest>()
                        call.respond(HttpStatusCode.OK, service.joinEvent(eventId, request))
                    }

                    delete("/{peerId}") {
                        val eventId = call.parameters.getOrFail("eventId")
                        val peerId = call.parameters.getOrFail("peerId")
                        service.leaveEvent(eventId, peerId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

private fun io.ktor.http.Parameters.getOrFail(name: String): String =
    this[name] ?: throw ValidationException("Missing path parameter '$name'")
