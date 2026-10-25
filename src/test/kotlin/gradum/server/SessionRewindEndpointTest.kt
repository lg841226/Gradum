package gradum.server

import gradum.agent.MessageRecord
import gradum.agent.assistantMessage
import gradum.agent.userMessage
import gradum.server.persistence.SessionStateStore
import gradum.utils.ContextManager
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonArray
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Tests for `POST /session/rewind` now that the sidecar is the authority: a
 * rewind must refresh `session-state.json` and `conversation.md` from the
 * surviving history. It used to re-render the transcript from
 * `ChatDb.loadContext`, which is empty in file mode, wiping every message.
 */
class SessionRewindEndpointTest {

  private var tempRoot: Path? = null
  private val sessionId: String = "20260812-131500-a1b2"

  @AfterTest
  fun cleanup() {
    tempRoot?.toFile()?.deleteRecursively()
  }

  // Seeds a saved session with two complete turns and returns the project root.
  private fun seedSession(): String {
    val root: Path = Files.createTempDirectory("gradum-rewind-test")
    tempRoot = root
    val sessionDirectory: Path = root.resolve(".gradum").resolve("sessions").resolve(sessionId)
    val messages: List<MessageRecord> = listOf(
      userMessage { id = "m1"; text = "first turn" },
      assistantMessage { content = "first answer"; modelName = "qwen3" },
      userMessage { id = "m2"; text = "second turn" },
      assistantMessage { content = "second answer"; modelName = "qwen3" }
    )
    assertTrue(ContextManager(sessionDirectory).saveContext(messages, "qwen3"))
    return root.toAbsolutePath().normalize().toString()
  }

  @Test
  fun `rewind refreshes the sidecar and transcript from the surviving history`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val project: String = seedSession()

    val response: HttpResponse = client.post("/session/rewind") {
      contentType(ContentType.Application.Json)
      setBody("""{"projectRoot":"$project","sessionId":"$sessionId","messageId":"m2"}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertContains(charSequence = response.bodyAsText(), other = "rewound")

    val state = SessionStateStore.read(Path.of(project), sessionId)!!
    assertEquals(
      2,
      state["messages"]!!.jsonArray.size,
      "the sidecar must keep only the turns before the rewound message"
    )

    val transcript: String = Files.readString(
      Path.of(project).resolve(".gradum").resolve("sessions").resolve(sessionId).resolve("conversation.md")
    )
    assertTrue(transcript.contains("first turn"), "the surviving user turn stays in the transcript")
    assertTrue(transcript.contains("first answer"), "the surviving assistant reply stays in the transcript")
    assertFalse(transcript.contains("second turn"), "the rewound user turn is gone from the transcript")
    assertFalse(transcript.contains("second answer"), "the rewound assistant reply is gone from the transcript")
  }
}
