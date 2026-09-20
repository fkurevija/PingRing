package com.fkurevija.pingring.coordination

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesTest {

    private suspend fun ApplicationTestBuilder.createEventViaApi(hostPeerId: String = "host-1"): String {
        val body = """{"id":"event-$hostPeerId","name":"Picnic","description":"Park meetup","ttlSeconds":3600,"hostPeerId":"$hostPeerId","hostName":"Host"}"""
        val response = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val json = Json.parseToJsonElement(response.bodyAsText())
        return json.jsonObject["event"]!!.jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `health check responds ok`() = testApplication {
        application { coordinationModule() }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `create then get event round trips`() = testApplication {
        application { coordinationModule() }

        val eventId = createEventViaApi()

        val getResponse = client.get("/api/events/$eventId")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertTrue(getResponse.bodyAsText().contains("Picnic"))
    }

    @Test
    fun `join unknown event returns 404`() = testApplication {
        application { coordinationModule() }

        val response = client.post("/api/events/does-not-exist/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"peerId":"peer-2","name":"Alice"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("event_not_found"))
    }

    @Test
    fun `joining the same peer id into a second event conflicts`() = testApplication {
        application { coordinationModule() }

        val eventA = createEventViaApi()
        val eventB = createEventViaApi(hostPeerId = "host-2")

        client.post("/api/events/$eventA/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"peerId":"peer-2","name":"Alice"}""")
        }

        val conflict = client.post("/api/events/$eventB/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"peerId":"peer-2","name":"Alice"}""")
        }

        assertEquals(HttpStatusCode.Conflict, conflict.status)
    }

    @Test
    fun `leaving an event frees the peer id for other events`() = testApplication {
        application { coordinationModule() }

        val eventA = createEventViaApi()
        val eventB = createEventViaApi(hostPeerId = "host-2")

        client.post("/api/events/$eventA/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"peerId":"peer-2","name":"Alice"}""")
        }
        val leaveResponse = client.delete("/api/events/$eventA/members/peer-2") {
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.NoContent, leaveResponse.status)

        val joinResponse = client.post("/api/events/$eventB/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"peerId":"peer-2","name":"Alice"}""")
        }
        assertEquals(HttpStatusCode.OK, joinResponse.status)
    }

    @Test
    fun `ending an event as a non host is forbidden`() = testApplication {
        application { coordinationModule() }

        val eventId = createEventViaApi()

        val response = client.post("/api/events/$eventId/end") {
            contentType(ContentType.Application.Json)
            setBody("""{"hostPeerId":"someone-else"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `ending an event as the host clears membership`() = testApplication {
        application { coordinationModule() }

        val eventId = createEventViaApi()

        val endResponse = client.post("/api/events/$eventId/end") {
            contentType(ContentType.Application.Json)
            setBody("""{"hostPeerId":"host-1"}""")
        }
        assertEquals(HttpStatusCode.NoContent, endResponse.status)

        val getResponse = client.get("/api/events/$eventId")
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }
}
