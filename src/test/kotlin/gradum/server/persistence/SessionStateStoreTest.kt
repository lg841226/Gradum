package gradum.server.persistence

import gradum.agent.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SessionStateStoreTest {

  @Test
  fun `round-trips the rich history without losing UI fields`() {
    val project: Path = Files.createTempDirectory("gradum_state_")
    val messages: List<MessageRecord> = listOf(
      systemMessage("you are gradum"),
      userMessage { id = "m1"; text = "hello" },
      assistantMessage {
        content = "hi"
        modelName = "qwen3"
        thinking = "weighing the options"
        tokenUsage(promptTokens = 3, completionTokens = 9, totalTokens = 12)
      }
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
    val messages: List<MessageRecord> = listOf(
      UserMessageRecord(
        ts = 0L,
        content = PartsContent(
          parts = listOf(
            TextPart(text = "look"),
            ImagePart(data = "BASE64PAYLOAD", mime = "image/png", filename = "a.png")
          )
        ),
        attachments = listOf(AttachmentMeta(name = "a.png", mime = "image/png"))
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
