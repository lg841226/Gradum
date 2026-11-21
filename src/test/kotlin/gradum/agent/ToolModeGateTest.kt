package gradum.agent

import gradum.AgentConfiguration
import gradum.ErrorCode
import gradum.Provider
import gradum.client.LLMResponseChunk
import gradum.client.LlmClient
import gradum.client.TokenUsageSnapshot
import gradum.client.ToolCallEntry
import gradum.mode.ModeRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Pins the read-only / edit tool surface to a real disk: when the
 * LLM emits a write_file / to_do / finish_to_do_item call
 * under a mode that excludes it, the agent must return TOOL_NOT_PERMITTED
 * AND the file on disk must be unchanged. The first condition is easy to
 * check via emitted events; the second is the actual security guarantee.
 *
 * Why this test exists: the schema whitelist in SkillRegistry.getSchemas
 * hides forbidden skills from the LLM's tool list, but the model still
 * knows about write_file from training data and from the system prompt
 * (which documents the tool surface in AGENT mode). The mode gate in
 * executeSingleTool is the only line of defense against a hallucinated
 * tool call: if that gate ever regresses, these tests will catch it.
 */
class ToolModeGateTest {

  private val tempProjectRoot: Path by lazy {
    Files.createTempDirectory("gradum-mode-gate-")
  }

  private var previousConfigDir: String? = null

  @BeforeTest
  fun isolateConfigDir() {
    previousConfigDir = System.getProperty("gradum.server.configDir")
    System.setProperty(
      "gradum.server.configDir",
      Files.createTempDirectory("gradum-mode-gate-config-").toAbsolutePath().toString()
    )
    ModeRegistry.reload()
  }

  @AfterTest
  fun restoreConfigDir() {
    if (previousConfigDir == null) System.clearProperty("gradum.server.configDir")
    else System.setProperty("gradum.server.configDir", previousConfigDir!!)
    ModeRegistry.reload()
  }

  @Test
  fun `read-only mode rejects write_file call and does not touch the file`() {
    val targetFile: Path = tempProjectRoot.resolve("victim.txt")
    val originalContent = "original line one\noriginal line two\n"
    Files.writeString(targetFile, originalContent)

    val toolCall = ToolCallEntry(
      functionName = "write_file",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "path" to JsonPrimitive(value = "victim.txt"),
        "edits" to JsonPrimitive(
          value = """[{"search": "original line one", "replace": "HACKED"}]"""
        ),
      ),
    )
    val events = runAgentWithToolCall(toolCall, "read_only")

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val errorMap = (toolCallEvent.second["result"] as Map<String, Any>)["error"] as Map<String, Any>
    assertEquals(
      ErrorCode.TOOL_NOT_PERMITTED.name,
      errorMap["code"],
      "write_file in READ_ONLY mode must return TOOL_NOT_PERMITTED",
    )
    assertEquals(
      "read_only",
      errorMap["toolMode"]
    )
    assertTrue(
      (errorMap["message"] as String).contains("read_only"),
      "Error message should name the mode that rejected the tool",
    )

    assertEquals(
      originalContent,
      Files.readString(targetFile),
      "File must not be modified in READ_ONLY mode"
    )
  }

  @Test
  fun `read-only mode rejects write_file create call and does not touch the file`() {
    val targetFile: Path = tempProjectRoot.resolve("new-victim.txt")
    assertFalse(
      Files.exists(targetFile),
      "Test precondition: file must not exist before write_file create"
    )

    val toolCall = ToolCallEntry(
      functionName = "write_file",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "path" to JsonPrimitive(value = "new-victim.txt"),
        "newString" to JsonPrimitive(value = "HACKED CONTENT"),
      ),
    )

    val events = runAgentWithToolCall(toolCall, "read_only")

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val errorMap = (toolCallEvent.second["result"] as Map<String, Any>)["error"] as Map<String, Any>
    assertEquals(
      ErrorCode.TOOL_NOT_PERMITTED.name,
      errorMap["code"]
    )
    assertFalse(
      Files.exists(targetFile),
      "write_file must not create the file in READ_ONLY mode"
    )
  }

  @Test
  fun `read-only mode rejects to_do call`() {
    val toolCall = ToolCallEntry(
      functionName = "to_do",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "tasks" to JsonPrimitive(value = """["steal data"]"""),
      ),
    )
    val events = runAgentWithToolCall(toolCall, "read_only")

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val errorMap = (toolCallEvent.second["result"] as Map<String, Any>)["error"] as Map<String, Any>
    assertEquals(
      ErrorCode.TOOL_NOT_PERMITTED.name,
      errorMap["code"]
    )
  }

  @Test
  fun `edit mode rejects to_do call`() {
    val toolCall = ToolCallEntry(
      functionName = "to_do",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "tasks" to JsonArray(
          content = listOf(JsonPrimitive(value = "step 1"), JsonPrimitive(value = "step 2")),
        ),
      ),
    )
    val events = runAgentWithToolCall(toolCall, "edit")

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val errorMap = (toolCallEvent.second["result"] as Map<String, Any>)["error"] as Map<String, Any>
    assertEquals(
      ErrorCode.TOOL_NOT_PERMITTED.name,
      errorMap["code"]
    )
    assertEquals(
      "edit",
      errorMap["toolMode"]
    )
  }

  @Test
  fun `agent mode allows write_file to actually run`() {
    val targetFile: Path = tempProjectRoot.resolve("legit.txt")
    Files.writeString(targetFile, "hello world\n")

    val toolCall = ToolCallEntry(
      functionName = "write_file",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "path" to JsonPrimitive(value = "legit.txt"),
        "edits" to JsonArray(
          content = listOf(
            JsonObject(
              content = mapOf(
                "oldString" to JsonPrimitive(value = "hello world"),
                "newString" to JsonPrimitive(value = "goodbye world"),
              ),
            ),
          ),
        ),
      ),
    )
    val events = runAgentWithToolCall(toolCall, "agent")

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val result = toolCallEvent.second["result"] as Map<String, Any>
    assertNotEquals(
      (result["error"] as? Map<*, *>)?.get("code"),
      ErrorCode.TOOL_NOT_PERMITTED.name,
      "AGENT mode must NOT reject write_file: only the mode gate should not fire, " +
        "result was: $result"
    )
    assertEquals(
      "goodbye world\n",
      Files.readString(targetFile),
      "write_file should have applied the edit in AGENT mode"
    )
  }

  @Test
  fun `read-only mode still allows read_file`() {
    val targetFile: Path = tempProjectRoot.resolve("observable.txt")
    Files.writeString(targetFile, "inspect me\n")

    val toolCall = ToolCallEntry(
      callIdentifier = "call_1",
      functionName = "read_file",
      functionArguments = mapOf("path" to JsonPrimitive(value = "observable.txt")),
    )
    val events = runAgentWithToolCall(toolCall, "read_only")

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val result = toolCallEvent.second["result"] as Map<String, Any>
    assertNotEquals(
      (result["error"] as? Map<*, *>)?.get("code"),
      ErrorCode.TOOL_NOT_PERMITTED.name,
      "read_file should be allowed in READ_ONLY mode"
    )
  }

  @Test
  fun `YOLO mode bypasses the tool-mode gate entirely`() {
    val toolCall = ToolCallEntry(
      functionName = "to_do",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "tasks" to JsonArray(
          content = listOf(JsonPrimitive(value = "step 1")),
        ),
      ),
    )
    val events = runAgentWithToolCall(toolCall, "yolo")

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val result = toolCallEvent.second["result"] as Map<String, Any>
    assertNotEquals(
      (result["error"] as? Map<*, *>)?.get("code"),
      ErrorCode.TOOL_NOT_PERMITTED.name,
      "YOLO must NOT reject to_do: the mode gate must be bypassed, result was: $result"
    )
  }

  private fun runAgentWithToolCall(toolCall: ToolCallEntry, toolMode: String): List<Pair<String, Map<String, Any>>> {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    val mockClient: LlmClient = mockk {
      every {
        sendChat(
          messageHistory = any(),
          toolDefinitions = any()
        )
      } returns flowOf(
        LLMResponseChunk.ToolCallBatch(toolCalls = listOf(toolCall)),
        LLMResponseChunk.TextContent(""),
      )
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      llmClient = mockClient,
      configuration = AgentConfiguration(
        provider = Provider.OPENAI,
        toolMode = toolMode,
        projectRoot = tempProjectRoot.toString()
      ),
    ) { type, data -> events.add(type to data) }
    agent.executeTask(userInput = "test prompt")
    return events
  }
}
