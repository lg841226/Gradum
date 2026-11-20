package gradum.desktop

import gradum.server.ServerAuth
import gradum.server.ServerConfiguration
import gradum.server.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/**
 * Wire behaviour of the editor UI the desktop shell installs onto the
 * engine: page boot states, assets and the pairing flow, for both the
 * /legatus entry point and the legacy /skills/editor alias.
 */
class EditorUiRoutesTest {

  private fun uiConfig(authToken: String = "secret-token"): ServerConfiguration =
    ServerConfiguration(
      authToken = authToken,
      uiRoutes = ::installUiRoutes,
      uiPagePrefixes = UI_PAGE_PREFIXES,
    )

  @Test
  fun `legatus page serves a reader view without a token and the app for a valid one`(): Unit = testApplication {
    application { module(uiConfig()) }

    // No token: the unified workspace page ships, marked for reader mode,
    // with no cookie set.
    val denied: HttpResponse = client.get("/legatus")
    assertEquals(HttpStatusCode.OK, denied.status)
    assertContains(denied.bodyAsText(), "data-reader")
    assertContains(denied.bodyAsText(), "data-default-root")
    assertNull(denied.headers[HttpHeaders.SetCookie])

    // A valid query token: the full app plus a session ticket cookie.
    val allowed: HttpResponse = client.get("/legatus?token=secret-token")
    assertEquals(HttpStatusCode.OK, allowed.status)
    assertFalse(allowed.bodyAsText().contains("data-reader"))
    assertContains(allowed.bodyAsText(), "data-session-remaining")
    val setCookie: String = assertNotNull(allowed.headers[HttpHeaders.SetCookie])
    assertContains(setCookie, "gradum_session=")
    assertFalse(setCookie.contains("secret-token"))

    // A browser already holding a valid ticket gets the full app too.
    val withCookie: HttpResponse = client.get("/legatus") {
      header(HttpHeaders.Cookie, setCookie.substringBefore(";"))
    }
    assertEquals(HttpStatusCode.OK, withCookie.status)
    assertFalse(withCookie.bodyAsText().contains("data-reader"))
  }

  @Test
  fun `legatus serves the unified workspace shell and unknown assets 404`(): Unit = testApplication {
    application { module(uiConfig()) }

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
    application { module(uiConfig(), pairingCode = "ABCDE") }

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
    assertContains(setCookie, "gradum_session=")

    // The cookie from pairing boots the full app without a query token.
    val paired: HttpResponse = client.get("/legatus") {
      header(HttpHeaders.Cookie, setCookie.substringBefore(";"))
    }
    assertFalse(paired.bodyAsText().contains("data-reader"))
  }

  @Test
  fun `write routes stay token-gated next to the open legatus page`(): Unit = testApplication {
    application { module(uiConfig()) }

    val events: HttpResponse = client.post("/events") {
      contentType(ContentType.Application.Json)
      setBody("{}")
    }
    assertEquals(HttpStatusCode.Unauthorized, events.status)

    val sessions: HttpResponse = client.get("/sessions")
    assertEquals(HttpStatusCode.Unauthorized, sessions.status)
  }

  @Test
  fun `editor alias serves a reader page without a token and a full page for a valid one`(): Unit = testApplication {
    application { module(uiConfig()) }

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
    application { module(uiConfig()) }

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
    application { module(uiConfig()) }

    val asset: HttpResponse = client.get("/skills/editor/main.js")
    assertEquals(HttpStatusCode.OK, asset.status)

    val models: HttpResponse = client.get("/models")
    assertEquals(HttpStatusCode.Unauthorized, models.status)
  }

  @Test
  fun `a valid ticket boots the full view with its own deadline`(): Unit = testApplication {
    application { module(uiConfig()) }

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
  fun `pairing accepts the printed code and boots the editor page`(): Unit = testApplication {
    application { module(uiConfig(), pairingCode = "K7X2P") }

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
}
