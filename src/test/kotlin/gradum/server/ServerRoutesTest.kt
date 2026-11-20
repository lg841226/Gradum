package gradum.server

import gradum.Version
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
