package gradum.utils

import gradum.server.persistence.SessionStateStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextManagerTest {

  @Test
  fun `save context writes a sidecar and a transcript from the same history`() {
    val project: Path = Files.createTempDirectory("gradum_context_")
    val sessionDirectory: Path = project.resolve(".gradum").resolve("sessions").resolve("s-1")
    Files.createDirectories(sessionDirectory)

    val messages: List<Map<String, Any>> = listOf(
      mapOf("role" to "system", "content" to "SECRET SYSTEM PROMPT"),
      mapOf("id" to "m1", "role" to "user", "content" to "hello there"),
      mapOf(
        "id" to "m2",
        "role" to "assistant",
        "content" to "hi",
        "thinking" to "thinking hard",
        "modelName" to "qwen3",
        "tool_calls" to listOf(
          mapOf("id" to "c1", "function" to mapOf("name" to "read_file", "arguments" to "{}"))
        )
      ),
      mapOf("role" to "tool", "tool_call_id" to "c1", "content" to "file body")
    )

    assertTrue(ContextManager(sessionDirectory).saveContext(messages, "qwen3"))

    val state: JsonObject = SessionStateStore.read(project, "s-1")!!
    assertEquals(3, state["messages"]!!.jsonArray.size, "the system prompt must not reach the sidecar")

    val transcript: String = Files.readString(sessionDirectory.resolve("conversation.md"))
    assertTrue(transcript.contains("hello there"), "the user text survives into the transcript")
    assertTrue(transcript.contains("thinking hard"), "the thinking block survives into the transcript")
    assertTrue(transcript.contains("read_file"), "the tool call survives into the transcript")
    assertTrue(transcript.contains("file body"), "the paired tool result survives into the transcript")
    assertFalse(transcript.contains("SECRET SYSTEM PROMPT"), "the system prompt is never rendered")
  }
}
