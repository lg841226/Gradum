package gradum.agent

import gradum.utils.ContextManager
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Round-trip guarantees of the record storage format: save -> load must
 * preserve exactly what the DSL let in, encrypt user/assistant rows, and
 * compact tool results into stubs.
 */
class MessageRecordRoundtripTest {

  @TempDir
  lateinit var tempDir: Path

  private fun newManager(): ContextManager =
    ContextManager(outputDirectory = tempDir.resolve(".gradum/sessions/roundtrip-test"))

  private fun storedRows(): List<String> {
    val contextFile: Path = tempDir.resolve(".gradum/sessions/roundtrip-test/context.json")
    assertTrue(contextFile.toFile().exists(), "context.json must be written")
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(contextFile.toFile().readText())
    return parsed.jsonObject["messages"]!!.jsonArray.map { it.jsonObject.toString() }
  }

  @Test
  fun `plain text rows survive save and load`() {
    val manager = newManager()
    val history = listOf(
      systemMessage(content = "you are a helper"),
      userMessage { text = "hello world" },
      assistantMessage {
        content = "hi there"
        modelName = "test-model"
      }
    )

    assertTrue(manager.saveContext(messages = history, modelName = "test-model"))
    val loaded: List<MessageRecord> = manager.loadContext()

    assertEquals(2, loaded.size, "system prompt must not persist")
    val user: UserMessageRecord = loaded[0] as UserMessageRecord
    assertEquals("hello world", user.userText())
    assertNull(user.displayText)
    val assistant: AssistantMessageRecord = loaded[1] as AssistantMessageRecord
    assertEquals("hi there", assistant.content)
    assertEquals("test-model", assistant.modelName)
  }

  @Test
  fun `user and assistant rows are encrypted at rest`() {
    val manager = newManager()
    manager.saveContext(
      messages = listOf(
        userMessage { text = "secret prompt text" },
        assistantMessage { content = "secret reply text"; modelName = "test-model" }
      ),
      modelName = "test-model"
    )

    val rawFile: String = tempDir
      .resolve(".gradum/sessions/roundtrip-test/context.json").toFile().readText()
    assertFalse(rawFile.contains("secret prompt text"), "user text must not appear in plaintext")
    assertFalse(rawFile.contains("secret reply text"), "assistant text must not appear in plaintext")
    assertTrue(rawFile.contains("\"cipher\""), "rows must be stored as encrypted envelopes")
  }

  @Test
  fun `blank displayText is not persisted as an authoritative empty bubble`() {
    val manager = newManager()
    manager.saveContext(
      messages = listOf(userMessage { text = "plain words"; displayText = "" }),
      modelName = "test-model"
    )
    val loaded: List<MessageRecord> = manager.loadContext()
    assertNull((loaded[0] as UserMessageRecord).displayText)
  }

  @Test
  fun `image parts survive the round trip`() {
    val manager = newManager()
    manager.saveContext(
      messages = listOf(
        userMessage {
          text = "look at this"
          image(data = "QUJD", mime = "image/png", filename = "shot.png")
        }
      ),
      modelName = "test-model"
    )

    val loaded: List<MessageRecord> = manager.loadContext()
    val user: UserMessageRecord = loaded[0] as UserMessageRecord
    val parts: PartsContent = user.content as PartsContent
    assertEquals(2, parts.parts.size)
    val image: ImagePart = parts.parts[1] as ImagePart
    assertEquals("QUJD", image.data)
    assertEquals("image/png", image.mime)
    assertEquals("shot.png", image.filename)
    assertEquals(1, user.attachments.size)
    assertEquals("shot.png", user.attachments[0].name)
  }

  @Test
  fun `tool results are compacted to stubs`() {
    val manager = newManager()
    manager.saveContext(
      messages = listOf(
        assistantMessage {
          content = ""
          modelName = "test-model"
          toolCall(name = "glob", argumentsJson = """{"pattern":"*.kt"}""", callId = "call-1")
        },
        toolResult {
          alias = "glob"
          content = "very long raw file listing that must never be stored"
          callId = "call-1"
          success = true
        }
      ),
      modelName = "test-model"
    )

    val loaded: List<MessageRecord> = manager.loadContext()
    val stub: ToolMessageRecord = loaded[1] as ToolMessageRecord
    assertTrue(stub.content.contains("Find"), "stub must keep the display label")
    assertTrue(stub.content.contains("pattern=*.kt"), "stub must keep the key argument")
    assertFalse(stub.content.contains("raw file listing"), "raw output must not be stored")
  }
}
