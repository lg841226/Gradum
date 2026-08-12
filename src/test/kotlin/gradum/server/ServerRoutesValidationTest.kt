/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerRoutesValidationTest.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ServerRoutesValidationTest {

    @Test
    fun `POST events without projectRoot rejects with 400`(): Unit = testApplication {
        application { module(ServerConfiguration()) }

        val response: HttpResponse = client.post("/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hello world"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "projectRoot is required")
    }

    @Test
    fun `POST events with a non existing projectRoot rejects with 400`(): Unit = testApplication {
        application { module(ServerConfiguration()) }

        val response: HttpResponse = client.post("/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hello world","projectRoot":"/definitely/not/here"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "not an existing directory")
    }

    @Test
    fun `POST stop with an unknown session responds with 404`(): Unit = testApplication {
        application { module(ServerConfiguration()) }

        val response: HttpResponse = client.post("/stop") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"does-not-exist"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "not_found")
    }
}
