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

    assertContains(body, "\"status\":\"healthy\"")
    assertContains(body, "\"version\":\"${Version.GRADUM_VERSION}\"")
    assertContains(body, "\"uptimeSeconds\"")
    assertContains(body, "\"timestamp\"")
  }

  @Test
  fun `GET skills returns 200 with non-empty skills array`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/skills")

    assertEquals(
      HttpStatusCode.OK,
      response.status
    )
    val body: String = response.bodyAsText()

    assertContains(body, "\"skills\"")
    assertTrue(body.contains("read_file"), "skills should include read_file")
    assertTrue(body.contains("write_file"), "skills should include write_file")
    assertFalse(body.contains("save_file"), "save_file was merged into write_file")
    assertFalse(body.contains("edit_file"), "edit_file was renamed to write_file")
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

    val denied: HttpResponse = client.get("/skills")
    assertEquals(HttpStatusCode.Unauthorized, denied.status)

    val allowed: HttpResponse = client.get("/skills") {
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

    // A valid query token: the full page plus the auth cookie.
    val allowed: HttpResponse = client.get("/skills/editor?token=secret-token")
    assertEquals(HttpStatusCode.OK, allowed.status)
    assertFalse(allowed.bodyAsText().contains("data-reader"))
    val setCookie: String = assertNotNull(allowed.headers[HttpHeaders.SetCookie])
    assertContains(setCookie, "gradum_token=secret-token")

    // A browser already holding the cookie gets the full page too.
    val withCookie: HttpResponse = client.get("/skills/editor") {
      header(HttpHeaders.Cookie, "gradum_token=secret-token")
    }
    assertEquals(HttpStatusCode.OK, withCookie.status)
    assertFalse(withCookie.bodyAsText().contains("data-reader"))
  }

  @Test
  fun `reader mode exposes editor assets and source reads but no writes`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val asset: HttpResponse = client.get("/skills/editor/main.js")
    assertEquals(HttpStatusCode.OK, asset.status)

    val sources: HttpResponse = client.get("/skills/sources")
    assertEquals(HttpStatusCode.OK, sources.status)

    val source: HttpResponse = client.get("/skills/source?name=whatever")
    assertNotEquals(HttpStatusCode.Unauthorized, source.status)

    val deploy: HttpResponse = client.post("/skills/deploy")
    assertEquals(HttpStatusCode.Unauthorized, deploy.status)

    val skills: HttpResponse = client.get("/skills")
    assertEquals(HttpStatusCode.Unauthorized, skills.status)
    val models: HttpResponse = client.get("/models")
    assertEquals(HttpStatusCode.Unauthorized, models.status)
  }

  @Test
  fun `lock query forces the reader view even for a valid cookie`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val full: HttpResponse = client.get("/skills/editor") {
      header(HttpHeaders.Cookie, "gradum_token=secret-token")
    }
    assertEquals(HttpStatusCode.OK, full.status)
    assertFalse(full.bodyAsText().contains("data-reader"))

    val forced: HttpResponse = client.get("/skills/editor?lock=1") {
      header(HttpHeaders.Cookie, "gradum_token=secret-token")
    }
    assertEquals(HttpStatusCode.OK, forced.status)
    assertContains(forced.bodyAsText(), "data-reader lang=")
    assertNull(forced.headers[HttpHeaders.SetCookie])
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
    assertContains(setCookie, "gradum_token=secret-token")
    assertContains(setCookie, "HttpOnly")

    val page: HttpResponse = client.get("/skills/editor") {
      header(HttpHeaders.Cookie, "gradum_token=secret-token")
    }
    assertEquals(HttpStatusCode.OK, page.status)
    assertFalse(page.bodyAsText().contains("data-reader"))

    val deploy: HttpResponse = client.post("/skills/build") {
      header(HttpHeaders.Cookie, "gradum_token=secret-token")
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
