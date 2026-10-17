package gradum.server.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStateStoreTest {

  @Test
  fun `round-trips the rich history without losing UI fields`() {
    val project: Path = Files.createTempDirectory("gradum_state_")
    val messages: List<Map<String, Any>> = listOf(
      mapOf("role" to "system", "content" to "you are gradum"),
      mapOf("id" to "m1", "role" to "user", "content" to "hello"),
      mapOf(
        "id" to "m2",
        "role" to "assistant",
        "content" to "hi",
        "thinking" to "weighing the options",
        "tokenUsage" to mapOf("promptTokens" to 3, "completionTokens" to 9, "totalTokens" to 12)
      )
    )

    assertTrue(SessionStateStore.write(project, "s-1", "qwen3", messages))

    val state: JsonObject = SessionStateStore.read(project, "s-1")!!
    assertEquals(1, state["version"]!!.jsonPrimitive.int)
    assertEquals("s-1", state["sessionId"]!!.jsonPrimitive.content)
    assertEquals("qwen3", state["modelName"]!!.jsonPrimitive.content)

    val storedMessages = state["messages"]!!.jsonArray
    assertEquals(2, storedMessages.size, "the system prompt must not be persisted")
    assertEquals("user", storedMessages[0].jsonObject["role"]!!.jsonPrimitive.content)
    val assistant: JsonObject = storedMessages[1].jsonObject
    assertEquals("weighing the options", assistant["thinking"]!!.jsonPrimitive.content)
    assertEquals(
      12,
      assistant["tokenUsage"]!!.jsonObject["totalTokens"]!!.jsonPrimitive.int
    )
  }

  @Test
  fun `strips base64 image payloads but keeps their metadata`() {
    val project: Path = Files.createTempDirectory("gradum_state_redact_")
    val messages: List<Map<String, Any>> = listOf(
      mapOf(
        "role" to "user",
        "content" to listOf(
          mapOf("type" to "text", "text" to "look"),
          mapOf("type" to "image", "data" to "BASE64PAYLOAD", "mime" to "image/png", "filename" to "a.png")
        ),
        "attachments" to listOf(mapOf("name" to "a.png", "mime" to "image/png"))
      )
    )

    assertTrue(SessionStateStore.write(project, "s-img", "qwen3", messages))

    val stored = SessionStateStore.read(project, "s-img")!!
    val content = stored["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
    val imagePart: JsonObject = content[1].jsonObject
    assertFalse(imagePart.containsKey("data"))
    assertEquals("image/png", imagePart["mime"]!!.jsonPrimitive.content)
    assertEquals("a.png", imagePart["filename"]!!.jsonPrimitive.content)
    assertEquals("look", content[0].jsonObject["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun `rejects a session id that escapes the sessions directory`() {
    val project: Path = Files.createTempDirectory("gradum_state_traversal_")

    assertFalse(SessionStateStore.write(project, "../escape", "qwen3", emptyList()))
    assertNull(SessionStateStore.read(project, "../escape"))
    assertTrue(Files.notExists(project.resolve(".gradum").resolve("sessions").resolve("..").resolve("evil")))
  }

  @Test
  fun `read of an unknown session is null`() {
    val project: Path = Files.createTempDirectory("gradum_state_missing_")

    assertNull(SessionStateStore.read(project, "never-written"))
  }
}
