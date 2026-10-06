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
  fun `editor page rejects a missing token and sets a cookie for a valid one`(): Unit = testApplication {
    application { module(ServerConfiguration(authToken = "secret-token")) }

    val denied: HttpResponse = client.get("/skills/editor")
    assertEquals(HttpStatusCode.Unauthorized, denied.status)

    val allowed: HttpResponse = client.get("/skills/editor?token=secret-token")
    assertEquals(HttpStatusCode.OK, allowed.status)
    val setCookie: String = assertNotNull(allowed.headers[HttpHeaders.SetCookie])
    assertContains(setCookie, "gradum_token=secret-token")

    val withCookie: HttpResponse = client.get("/skills/editor") {
      header(HttpHeaders.Cookie, "gradum_token=secret-token")
    }
    assertEquals(HttpStatusCode.OK, withCookie.status)
  }
}
