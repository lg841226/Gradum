package gradum.server

import gradum.server.persistence.SessionStateStore
import gradum.server.persistence.SessionTranscriptWriter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SessionMergeEndpointTest {

  @Test
  fun `POST sessions merge returns the new session id and title`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val projectRoot: Path = Files.createTempDirectory("gradum-merge-endpoint")
    seed(projectRoot, "alpha")
    seed(projectRoot, "beta")
    val escapedRoot: String = projectRoot.toString().replace("\\", "\\\\")

    val response: HttpResponse = client.post("/sessions/merge") {
      contentType(ContentType.Application.Json)
      setBody(
        """{"projectRoot":"$escapedRoot","sessionIds":["alpha","beta"],""" +
          """"baseTitle":"Merged conversation"}"""
      )
    }

    assertEquals(HttpStatusCode.OK, response.status)
    val body: String = response.bodyAsText()
    assertContains(body, "\"status\":\"merged\"")
    assertContains(body, "\"title\":\"Merged conversation 1\"")
  }

  private fun seed(projectRoot: Path, sessionId: String) {
    val messages: List<Map<String, Any>> = listOf(
      mapOf("role" to "user", "content" to "$sessionId q", "id" to "$sessionId-u"),
      mapOf("role" to "assistant", "content" to "$sessionId a", "id" to "$sessionId-a", "modelName" to "test-model")
    )
    SessionStateStore.write(projectRoot, sessionId, "test-model", messages)
    SessionTranscriptWriter.writeTranscript(projectRoot, sessionId, "test-model", messages, title = sessionId)
  }
}
