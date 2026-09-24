/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AskInteractionEndpointTest.kt  2026-09-24 23:09:21 Changed by gwy
 */

package gradum.server

import gradum.skill.AskResult
import gradum.skill.PendingQuestions
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Tests for `POST /events/respond`, the answer back-channel of ask_interaction. */
class AskInteractionEndpointTest {

  @Test
  fun `respond with unknown requestId returns 404 not_found`() = testApplication {
    application { appWith(pending = PendingQuestions()) }

    val response: HttpResponse = client.post("/events/respond") {
      contentType(ContentType.Application.Json)
      setBody("""{"sessionId":"s","requestId":"never-asked","choice":"once"}""")
    }

    assertEquals(HttpStatusCode.NotFound, response.status)
    assertContains(response.bodyAsText(), "not_found")
  }

  @Test
  fun `respond missing sessionId or requestId returns 400`() = testApplication {
    application { appWith(pending = PendingQuestions()) }

    val missing = client.post("/events/respond") {
      contentType(ContentType.Application.Json)
      setBody("""{"sessionId":"s"}""")
    }
    assertEquals(HttpStatusCode.BadRequest, missing.status)

    val missingBoth = client.post("/events/respond") {
      contentType(ContentType.Application.Json)
      setBody("""{}""")
    }
    assertEquals(HttpStatusCode.BadRequest, missingBoth.status)
  }

  @Test
  fun `respond with no actionable field returns 400`() = testApplication {
    application { appWith(pending = PendingQuestions()) }

    val response = client.post("/events/respond") {
      contentType(ContentType.Application.Json)
      setBody("""{"sessionId":"s","requestId":"r"}""")
    }

    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `respond resolves a parked ask and unblocks it, then a duplicate response is a no-op 404`() {
    val pending = PendingQuestions()
    val holder = arrayOfNulls<AskResult>(1)
    val blocker = Thread { holder[0] = pending.await("sess", "req-1") }
    blocker.start()
    awaitPending(pending, "sess", "req-1")

    testApplication {
      application { appWith(pending = pending) }

      val first = client.post("/events/respond") {
        contentType(ContentType.Application.Json)
        setBody("""{"sessionId":"sess","requestId":"req-1","choice":"always"}""")
      }
      assertEquals(HttpStatusCode.OK, first.status)
      assertContains(first.bodyAsText(), "delivered")

      // Duplicate delivery for the same requestId is an idempotent no-op.
      val duplicate = client.post("/events/respond") {
        contentType(ContentType.Application.Json)
        setBody("""{"sessionId":"sess","requestId":"req-1","text":"again"}""")
      }
      assertEquals(HttpStatusCode.NotFound, duplicate.status)
    }

    blocker.join(2000)
    assertEquals("always", assertIs<AskResult.Case>(holder[0]).id)
  }

  @Test
  fun `respond with cancelled resolves the ask as dismissed`() {
    val pending = PendingQuestions()
    val holder = arrayOfNulls<AskResult>(1)
    val blocker = Thread { holder[0] = pending.await("sess", "req-2") }
    blocker.start()
    awaitPending(pending, "sess", "req-2")

    testApplication {
      application { appWith(pending = pending) }

      val response = client.post("/events/respond") {
        contentType(ContentType.Application.Json)
        setBody("""{"sessionId":"sess","requestId":"req-2","cancelled":true}""")
      }
      assertEquals(HttpStatusCode.OK, response.status)
    }

    blocker.join(2000)
    assertIs<AskResult.Cancelled>(holder[0])
  }

  private fun Application.appWith(pending: PendingQuestions) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    registerAllRoutes(serverConfiguration = ServerConfiguration(), pendingQuestions = pending)
  }

  private fun awaitPending(pending: PendingQuestions, sessionId: String, requestId: String) {
    val deadline: Long = System.currentTimeMillis() + 2000
    while (System.currentTimeMillis() < deadline) {
      if (pending.isPending(sessionId, requestId)) return
      Thread.sleep(10)
    }
    error("ask never parked")
  }
}
