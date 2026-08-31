/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SessionDeleteEndpointTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Tests for `POST /session/delete`: it must remove the whole session
 * directory (transcript + server `context.json`) and reject any sessionId
 * that resolves outside `.gradum/sessions/` (path-traversal guard).
 */
class SessionDeleteEndpointTest {

  private var tempRoot: Path? = null

  @AfterTest
  fun cleanup() {
    tempRoot?.toFile()?.deleteRecursively()
  }

  private fun projectRoot(): String {
    val root: Path = Files.createTempDirectory("gradum-delete-test")
    tempRoot = root
    // Simulate a previously saved session directory (like the plugin wrote it).
    val sessionDir: Path = root.resolve(".gradum").resolve("sessions").resolve("20260812-131500-a1b2")
    Files.createDirectories(sessionDir)
    Files.writeString(sessionDir.resolve("conversation.md"), "# placeholder")
    Files.writeString(sessionDir.resolve("context.json"), "{}")
    return root.toAbsolutePath().normalize().toString()
  }

  @Test
  fun `delete removes the whole session directory`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val project: String = projectRoot()

    val response: HttpResponse = client.post("/session/delete") {
      contentType(ContentType.Application.Json)
      setBody("""{"projectRoot":"$project","sessionId":"20260812-131500-a1b2"}""")
    }

    assertEquals(
      HttpStatusCode.OK,
      response.status
    )
    assertContains(charSequence = response.bodyAsText(), other = "\"deleted\"")
    val sessionDir: Path = Path.of(project).resolve(".gradum")
      .resolve("sessions").resolve("20260812-131500-a1b2")
    assertEquals(
      false,
      Files.exists(sessionDir)
    )
  }

  @Test
  fun `delete of an unknown session responds 404`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val project: String = projectRoot()

    val response: HttpResponse = client.post("/session/delete") {
      contentType(ContentType.Application.Json)
      setBody("""{"projectRoot":"$project","sessionId":"never-existed"}""")
    }

    assertEquals(
      HttpStatusCode.NotFound,
      response.status
    )
    assertContains(charSequence = response.bodyAsText(), other = "not_found")
  }

  @Test
  fun `delete rejects path traversal outside sessions root`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val project: String = projectRoot()

    val response: HttpResponse = client.post("/session/delete") {
      contentType(ContentType.Application.Json)
      setBody("""{"projectRoot":"$project","sessionId":"../.."}""")
    }

    assertEquals(
      HttpStatusCode.BadRequest,
      response.status
    )
    assertContains(charSequence = response.bodyAsText(), other = "invalid sessionId")
  }

  @Test
  fun `delete rejects blank projectRoot and sessionId`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val noRoot: HttpResponse = client.post("/session/delete") {
      contentType(ContentType.Application.Json)
      setBody("""{"projectRoot":"","sessionId":"abc"}""")
    }
    assertEquals(
      HttpStatusCode.BadRequest,
      noRoot.status
    )
    assertContains(charSequence = noRoot.bodyAsText(), other = "projectRoot is required")

    val noSession: HttpResponse = client.post("/session/delete") {
      contentType(ContentType.Application.Json)
      setBody("""{"projectRoot":"/tmp","sessionId":"  "}""")
    }
    assertEquals(
      HttpStatusCode.BadRequest,
      noSession.status
    )
    assertContains(charSequence = noSession.bodyAsText(), other = "sessionId is required")
  }
}
