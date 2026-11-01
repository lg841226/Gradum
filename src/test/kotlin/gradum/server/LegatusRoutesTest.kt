package gradum.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/** Wire behaviour of the Gradum Legatus page and its pairing flow. */
class LegatusRoutesTest {

  @Test
  fun `legatus page serves a reader view without a token and the app for a valid one`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    // No token: the unified workspace page ships, marked for reader mode,
    // with no cookie set.
    val denied: HttpResponse = client.get("/legatus")
    assertEquals(HttpStatusCode.OK, denied.status)
    assertContains(denied.bodyAsText(), "data-reader")
    assertContains(denied.bodyAsText(), "data-default-root")
    assertNull(denied.headers[HttpHeaders.SetCookie])

    // A valid query token: the full app plus the auth cookie.
    val allowed: HttpResponse = client.get("/legatus?token=secret-token")
    assertEquals(HttpStatusCode.OK, allowed.status)
    assertFalse(allowed.bodyAsText().contains("data-reader"))
    val setCookie: String = assertNotNull(allowed.headers[HttpHeaders.SetCookie])
    assertContains(setCookie, "gradum_token=secret-token")

    // A browser already holding the cookie gets the full app too.
    val withCookie: HttpResponse = client.get("/legatus") {
      header(HttpHeaders.Cookie, "gradum_token=secret-token")
    }
    assertEquals(HttpStatusCode.OK, withCookie.status)
    assertFalse(withCookie.bodyAsText().contains("data-reader"))
  }

  @Test
  fun `legatus serves the unified workspace shell and unknown assets 404`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    // The page is the bundled workspace shell, whatever entry point serves it.
    val page: HttpResponse = client.get("/legatus")
    assertContains(page.bodyAsText(), "Gradum Workspace")

    val script: HttpResponse = client.get("/legatus/main.js")
    assertEquals(HttpStatusCode.OK, script.status)
    assertContains(script.contentType().toString(), "javascript")

    val missing: HttpResponse = client.get("/legatus/no/such/asset.js")
    assertEquals(HttpStatusCode.NotFound, missing.status)

    val traversal: HttpResponse = client.get("/legatus/../secret")
    assertEquals(HttpStatusCode.NotFound, traversal.status)
  }

  @Test
  fun `the pairing code trades for the auth cookie`(): Unit = testApplication {
    application {
      module(ServerConfiguration(authToken = "secret-token"), pairingCode = "ABCDE")
    }

    val wrong: HttpResponse = client.post("/skills/pair") {
      contentType(ContentType.Application.Json)
      setBody("""{"code":"ZZZZZ"}""")
    }
    assertEquals(HttpStatusCode.Unauthorized, wrong.status)

    val right: HttpResponse = client.post("/skills/pair") {
      contentType(ContentType.Application.Json)
      setBody("""{"code":"abcde"}""")
    }
    assertEquals(HttpStatusCode.OK, right.status)
    assertTrue(Json.parseToJsonElement(right.bodyAsText()).jsonObject.containsKey("ok"))
    val setCookie: String = assertNotNull(right.headers[HttpHeaders.SetCookie])
    assertContains(setCookie, "gradum_token=secret-token")

    // The cookie from pairing boots the full app without a query token.
    val paired: HttpResponse = client.get("/legatus") {
      header(HttpHeaders.Cookie, setCookie.substringBefore(";"))
    }
    assertFalse(paired.bodyAsText().contains("data-reader"))
  }

  @Test
  fun `write routes stay token-gated next to the open legatus page`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val events: HttpResponse = client.post("/events") {
      contentType(ContentType.Application.Json)
      setBody("{}")
    }
    assertEquals(HttpStatusCode.Unauthorized, events.status)

    val sessions: HttpResponse = client.get("/sessions")
    assertEquals(HttpStatusCode.Unauthorized, sessions.status)
  }
}
