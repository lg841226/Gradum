/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolModeGateTest.kt  2026-08-16 21:40:14 Changed by gwy
 */

package gradum.agent

import gradum.AgentConfiguration
import gradum.ErrorCode
import gradum.Provider
import gradum.ToolMode
import gradum.client.LLMResponseChunk
import gradum.client.LlmClient
import gradum.client.TokenUsageSnapshot
import gradum.client.ToolCallEntry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Pins the read-only / edit tool surface to a real disk: when the
 * LLM emits an edit_file / save_file / to_do / finish_to_do_item call
 * under a mode that excludes it, the agent must return TOOL_NOT_PERMITTED
 * AND the file on disk must be unchanged. The first condition is easy to
 * check via emitted events; the second is the actual security guarantee.
 *
 * Why this test exists: the schema whitelist in SkillRegistry.getSchemas
 * hides forbidden skills from the LLM's tool list, but the model still
 * knows about edit_file from training data and from the system prompt
 * (which documents the tool surface in AGENT mode). The mode gate in
 * executeSingleTool is the only line of defense against a hallucinated
 * tool call — if that gate ever regresses, these tests will catch it.
 */
class ToolModeGateTest {

  private val tempProjectRoot: Path by lazy {
    Files.createTempDirectory("gradum-mode-gate-")
  }

  @Test
  fun `read-only mode rejects edit_file call and does not touch the file`() {
    val targetFile: Path = tempProjectRoot.resolve("victim.txt")
    val originalContent = "original line one\noriginal line two\n"
    Files.writeString(targetFile, originalContent)

    val toolCall = ToolCallEntry(
      functionName = "edit_file",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "path" to JsonPrimitive("victim.txt"),
        "edits" to JsonPrimitive(
          """[{"search": "original line one", "replace": "HACKED"}]"""
        ),
      ),
    )
    val events = runAgentWithToolCall(toolCall, ToolMode.READ_ONLY)

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val errorMap = (toolCallEvent.second["result"] as Map<String, Any>)["error"] as Map<String, Any>
    assertEquals(
      ErrorCode.TOOL_NOT_PERMITTED.name, errorMap["code"],
      "edit_file in READ_ONLY mode must return TOOL_NOT_PERMITTED",
    )
    assertEquals("READ_ONLY", errorMap["toolMode"])
    assertTrue(
      ((errorMap["allowedModes"] as List<*>).contains("AGENT")),
      "Error should list the actually-allowed modes",
    )

    assertEquals(originalContent, Files.readString(targetFile), "File must not be modified in READ_ONLY mode")
  }

  @Test
  fun `read-only mode rejects save_file call and does not touch the file`() {
    val targetFile: Path = tempProjectRoot.resolve("new-victim.txt")
    assertFalse(Files.exists(targetFile), "Test precondition: file must not exist before save_file")

    val toolCall = ToolCallEntry(
      functionName = "save_file",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "path" to JsonPrimitive("new-victim.txt"),
        "content" to JsonPrimitive("HACKED CONTENT"),
      ),
    )
    val events = runAgentWithToolCall(toolCall, ToolMode.READ_ONLY)

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val errorMap = (toolCallEvent.second["result"] as Map<String, Any>)["error"] as Map<String, Any>
    assertEquals(ErrorCode.TOOL_NOT_PERMITTED.name, errorMap["code"])
    assertFalse(Files.exists(targetFile), "save_file must not create the file in READ_ONLY mode")
  }

  @Test
  fun `read-only mode rejects to_do call`() {
    val toolCall = ToolCallEntry(
      functionName = "to_do",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "tasks" to JsonPrimitive("""["steal data"]"""),
      ),
    )
    val events = runAgentWithToolCall(toolCall, ToolMode.READ_ONLY)

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val errorMap = (toolCallEvent.second["result"] as Map<String, Any>)["error"] as Map<String, Any>
    assertEquals(ErrorCode.TOOL_NOT_PERMITTED.name, errorMap["code"])
  }

  @Test
  fun `edit mode rejects to_do call`() {
    val toolCall = ToolCallEntry(
      functionName = "to_do",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "tasks" to kotlinx.serialization.json.JsonArray(
          listOf(JsonPrimitive("step 1"), JsonPrimitive("step 2")),
        ),
      ),
    )
    val events = runAgentWithToolCall(toolCall, ToolMode.EDIT)

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val errorMap = (toolCallEvent.second["result"] as Map<String, Any>)["error"] as Map<String, Any>
    assertEquals(ErrorCode.TOOL_NOT_PERMITTED.name, errorMap["code"])
    assertEquals("EDIT", errorMap["toolMode"])
  }

  @Test
  fun `agent mode allows edit_file to actually run`() {
    val targetFile: Path = tempProjectRoot.resolve("legit.txt")
    Files.writeString(targetFile, "hello world\n")

    val toolCall = ToolCallEntry(
      functionName = "edit_file",
      callIdentifier = "call_1",
      functionArguments = mapOf(
        "path" to JsonPrimitive("legit.txt"),
        "edits" to kotlinx.serialization.json.JsonArray(
          listOf(
            kotlinx.serialization.json.JsonObject(
              mapOf(
                "oldString" to JsonPrimitive("hello world"),
                "newString" to JsonPrimitive("goodbye world"),
              ),
            ),
          ),
        ),
      ),
    )
    val events = runAgentWithToolCall(toolCall, ToolMode.AGENT)

    val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
      ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

    @Suppress("UNCHECKED_CAST")
    val result = toolCallEvent.second["result"] as Map<String, Any>
    assertNotEquals(
      (result["error"] as? Map<*, *>)?.get("code"),
      ErrorCode.TOOL_NOT_PERMITTED.name,
      "AGENT mode must NOT reject edit_file — only the mode gate should not fire, " +
        "result was: $result"
    )
    assertEquals(
      "goodbye world\n",
      Files.readString(targetFile),
      "edit_file should have applied the edit in AGENT mode"
    )
  }

  @Test
  fun `read-only mode still allows read_file`() {
    val targetFile: Path = tempProjectRoot.resolve("observable.txt")
    Files.writeString(targetFile, "inspect me\n")

    val toolCall = ToolCallEntry(
      functionName = "read_file",
      callIdentifier = "call_1",
      functionArguments = mapOf("path" to JsonPrimitive("observable.txt")),
    )
    val events = runAgentWithToolCall(toolCall, ToolMode.READ_ONLY)

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

  /**
   * Shared driver: build an Agent with a mocked LLM that emits exactly
   * one tool call followed by a no-op text chunk (so the agent loop
   * terminates), and return every event the agent emitted.
   */
  private fun runAgentWithToolCall(
    toolCall: ToolCallEntry,
    toolMode: ToolMode
  ): List<Pair<String, Map<String, Any>>> {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    val mockClient: LlmClient = mockk {
      every { sendChat(any(), any()) } returns flowOf(
        LLMResponseChunk.ToolCallBatch(listOf(toolCall)),
        LLMResponseChunk.TextContent(""),
      )
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      configuration = AgentConfiguration(
        provider = Provider.OPENAI,
        toolMode = toolMode,
        projectRoot = tempProjectRoot.toString(),
      ),
      emitEvent = { type, data -> events.add(type to data) },
      llmClient = mockClient,
    )
    agent.executeTask("test prompt")
    return events
  }
}
