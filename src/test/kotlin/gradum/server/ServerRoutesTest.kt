package gradum.server

import gradum.Version
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ServerRoutesTest {

  @Test
  fun `GET health returns 200 with status and version`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/health")

    assertEquals(
      HttpStatusCode.OK,
      response.status
    )
    val body: String = response.bodyAsText()

    assertTrue(
      body.contains("\"status\":\"healthy\"") || body.contains("\"status\":\"degraded\""),
      "health status should be healthy or degraded: $body"
    )
    assertContains(body, "\"version\":\"${Version.GRADUM_VERSION}\"")
    assertContains(body, "\"uptimeSeconds\"")
    assertContains(body, "\"timestamp\"")
  }

  @Test
  fun `GET models returns 200 with models array`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/models")

    assertEquals(
      HttpStatusCode.OK,
      response.status
    )
    val body: String = response.bodyAsText()

    assertContains(body, "\"models\"")
  }

  @Test
  fun `auth-enabled rejects a missing token and accepts the bearer token`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val denied: HttpResponse = client.get("/models")
    assertEquals(HttpStatusCode.Unauthorized, denied.status)

    val allowed: HttpResponse = client.get("/models") {
      header(HttpHeaders.Authorization, "Bearer secret-token")
    }
    assertEquals(HttpStatusCode.OK, allowed.status)
  }

  @Test
  fun `health is exempt from auth`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val response: HttpResponse = client.get("/health")

    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `non-loopback Host header is forbidden`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/health") {
      header(HttpHeaders.Host, "evil.com")
    }

    assertEquals(HttpStatusCode.Forbidden, response.status)
  }

  @Test
  fun `editor page serves a reader page without a token and a full page for a valid one`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    // No token: the page ships, marked for reader mode, with no cookie set.
    val denied: HttpResponse = client.get("/skills/editor")
    assertEquals(HttpStatusCode.OK, denied.status)
    assertContains(denied.bodyAsText(), "data-reader")
    assertNull(denied.headers[HttpHeaders.SetCookie])

    // A token that misses: the same page, marked invalid so the unlock dialog
    // can report the failed try.
    val wrong: HttpResponse = client.get("/skills/editor?token=wrong")
    assertEquals(HttpStatusCode.OK, wrong.status)
    assertContains(wrong.bodyAsText(), "data-reader=\"invalid\"")

    // A valid query token: the full page plus a session ticket cookie.
    val allowed: HttpResponse = client.get("/skills/editor?token=secret-token")
    assertEquals(HttpStatusCode.OK, allowed.status)
    assertFalse(allowed.bodyAsText().contains("data-reader"))
    assertContains(allowed.bodyAsText(), "data-session-remaining")
    assertContains(allowed.bodyAsText(), "data-session-scope=\"local\"")
    val setCookie: String = assertNotNull(allowed.headers[HttpHeaders.SetCookie])
    assertContains(setCookie, "gradum_session=")
    assertFalse(setCookie.contains("secret-token"))
    assertContains(setCookie, "Max-Age=")

    // A browser already holding a valid ticket gets the full page too.
    val ticket: String = setCookie.substringBefore(";")
    val withCookie: HttpResponse = client.get("/skills/editor") {
      header(HttpHeaders.Cookie, ticket)
    }
    assertEquals(HttpStatusCode.OK, withCookie.status)
    assertFalse(withCookie.bodyAsText().contains("data-reader"))
  }

  @Test
  fun `an expired session ticket boots the reader view and blocks data routes`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val expired: String = ServerAuth.sessionTicket(
      secret = "secret-token",
      expiresEpochSeconds = System.currentTimeMillis() / 1000L - 1L,
    )

    val page: HttpResponse = client.get("/skills/editor") {
      header(HttpHeaders.Cookie, "gradum_session=$expired")
    }
    assertContains(page.bodyAsText(), "data-reader")

    val models: HttpResponse = client.get("/models") {
      header(HttpHeaders.Cookie, "gradum_session=$expired")
    }
    assertEquals(HttpStatusCode.Unauthorized, models.status)
  }

  @Test
  fun `reader mode exposes editor assets but data routes demand the token`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val asset: HttpResponse = client.get("/skills/editor/main.js")
    assertEquals(HttpStatusCode.OK, asset.status)

    val models: HttpResponse = client.get("/models")
    assertEquals(HttpStatusCode.Unauthorized, models.status)
  }

  @Test
  fun `a valid query token is not shadowed by a stale cookie`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val response: HttpResponse = client.get("/models") {
      header(
        HttpHeaders.Cookie,
        "gradum_session=${ServerAuth.sessionTicket("other-secret", Long.MAX_VALUE / 2)}",
      )
      header(HttpHeaders.Authorization, "Bearer secret-token")
    }
    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `a valid ticket boots the full view with its own deadline`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val ticket: String = "gradum_session=" + ServerAuth.sessionTicket(
      secret = "secret-token",
      expiresEpochSeconds = System.currentTimeMillis() / 1000L + 600L,
    )

    val full: HttpResponse = client.get("/skills/editor") {
      header(HttpHeaders.Cookie, ticket)
    }
    assertEquals(HttpStatusCode.OK, full.status)
    assertFalse(full.bodyAsText().contains("data-reader"))
    assertContains(full.bodyAsText(), "data-session-remaining")
  }

  @Test
  fun `pairing refuses a wrong code and accepts the printed one without any token`(): Unit = testApplication {
    application {
      module(ServerConfiguration(authToken = "secret-token"), pairingCode = "K7X2P")
    }

    val wrong: HttpResponse = client.post("/skills/pair") {
      contentType(ContentType.Application.Json)
      setBody("""{"code":"ZZZZZ"}""")
    }
    assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    assertNull(wrong.headers[HttpHeaders.SetCookie])

    val allowed: HttpResponse = client.post("/skills/pair") {
      contentType(ContentType.Application.Json)
      setBody("""{"code":"k7x2p"}""")
    }
    assertEquals(HttpStatusCode.OK, allowed.status)
    val setCookie: String = assertNotNull(allowed.headers[HttpHeaders.SetCookie])
    assertContains(setCookie, "gradum_session=")
    assertContains(setCookie, "HttpOnly")

    val cookie: String = setCookie.substringBefore(";")
    val page: HttpResponse = client.get("/skills/editor") {
      header(HttpHeaders.Cookie, cookie)
    }
    assertEquals(HttpStatusCode.OK, page.status)
    assertFalse(page.bodyAsText().contains("data-reader"))

    val deploy: HttpResponse = client.post("/skills/build") {
      header(HttpHeaders.Cookie, cookie)
    }
    assertNotEquals(HttpStatusCode.Unauthorized, deploy.status)
  }

  @Test
  fun `pairing stays out of reach of a script after three wrong codes`(): Unit = testApplication {
    application {
      module(ServerConfiguration(authToken = "secret-token"), pairingCode = "K7X2P")
    }

    repeat(times = 3) {
      client.post("/skills/pair") {
        contentType(ContentType.Application.Json)
        setBody("""{"code":"ZZZZZ"}""")
      }
    }

    val throttled: HttpResponse = client.post("/skills/pair") {
      contentType(ContentType.Application.Json)
      setBody("""{"code":"k7x2p"}""")
    }
    assertEquals(HttpStatusCode.Unauthorized, throttled.status)
  }
}
