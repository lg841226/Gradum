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

    assertEquals(
      HttpStatusCode.BadRequest,
      response.status
    )
    assertContains(response.bodyAsText(), "projectRoot is required")
  }

  @Test
  fun `POST events with a non existing projectRoot rejects with 400`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.post("/events") {
      contentType(ContentType.Application.Json)
      setBody("""{"message":"hello world","projectRoot":"/definitely/not/here"}""")
    }

    assertEquals(
      HttpStatusCode.BadRequest,
      response.status
    )
    assertContains(charSequence = response.bodyAsText(), other = "not an existing directory")
  }

  @Test
  fun `POST stop with an unknown run responds with 404`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.post("/stop") {
      contentType(ContentType.Application.Json)
      setBody("""{"runId":"does-not-exist"}""")
    }

    assertEquals(
      HttpStatusCode.NotFound,
      response.status
    )
    assertContains(charSequence = response.bodyAsText(), other = "not_found")
  }

  @Test
  fun `POST sessions merge with fewer than two sessions rejects with 400`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.post("/sessions/merge") {
      contentType(ContentType.Application.Json)
      setBody("""{"projectRoot":"/tmp","sessionIds":["only-one"],"baseTitle":"Merged"}""")
    }

    assertEquals(
      HttpStatusCode.BadRequest,
      response.status
    )
    assertContains(charSequence = response.bodyAsText(), other = "at least two sessions")
  }

  @Test
  fun `POST sessions merge without baseTitle rejects with 400`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.post("/sessions/merge") {
      contentType(ContentType.Application.Json)
      setBody("""{"projectRoot":"/tmp","sessionIds":["alpha","beta"],"baseTitle":"  "}""")
    }

    assertEquals(
      HttpStatusCode.BadRequest,
      response.status
    )
    assertContains(charSequence = response.bodyAsText(), other = "baseTitle is required")
  }
}
