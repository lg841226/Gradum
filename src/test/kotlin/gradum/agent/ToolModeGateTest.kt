/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolModeGateTest.kt  2026-06-30 23:35:47 Changed by gwy
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
 * Pins the read-only / single-step tool surface to a real disk: when the
 * LLM emits an edit_file / save_file / to_do / finish_to_do_item call
 * under a mode that excludes it, the agent must return TOOL_NOT_PERMITTED
 * AND the file on disk must be unchanged. The first condition is easy to
 * check via emitted events; the second is the actual security guarantee.
 *
 * Why this test exists: the schema whitelist in SkillRegistry.getSchemas
 * hides forbidden skills from the LLM's tool list, but the model still
 * knows about edit_file from training data and from the system prompt
 * (which documents the tool surface in WRITE mode). The mode gate in
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
            callIdentifier = "call_1",
            functionName = "edit_file",
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
            ((errorMap["allowedModes"] as List<*>).contains("WRITE")),
            "Error should list the actually-allowed modes",
        )

        // The whole point of the gate: the file on disk must be byte-for-byte
        // identical to the original. If this assertion ever fails, we have
        // a real-world security bug, not just a test problem.
        assertEquals(originalContent, Files.readString(targetFile), "File must not be modified in READ_ONLY mode")
    }

    @Test
    fun `read-only mode rejects save_file call and does not touch the file`() {
        val targetFile: Path = tempProjectRoot.resolve("new-victim.txt")
        assertFalse(Files.exists(targetFile), "Test precondition: file must not exist before save_file")

        val toolCall = ToolCallEntry(
            callIdentifier = "call_1",
            functionName = "save_file",
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
            callIdentifier = "call_1",
            functionName = "to_do",
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
    fun `single-step mode rejects to_do call`() {
        // to_do / finish_to_do_item are the task-planning pair withheld in
        // SINGLE_STEP. Different from READ_ONLY (where write tools are
        // blocked) but the gate is the same mechanism.
        val toolCall = ToolCallEntry(
            callIdentifier = "call_1",
            functionName = "to_do",
            functionArguments = mapOf(
                "tasks" to kotlinx.serialization.json.JsonArray(
                    listOf(JsonPrimitive("step 1"), JsonPrimitive("step 2")),
                ),
            ),
        )
        val events = runAgentWithToolCall(toolCall, ToolMode.SINGLE_STEP)

        val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
            ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

        @Suppress("UNCHECKED_CAST")
        val errorMap = (toolCallEvent.second["result"] as Map<String, Any>)["error"] as Map<String, Any>
        assertEquals(ErrorCode.TOOL_NOT_PERMITTED.name, errorMap["code"])
        assertEquals("SINGLE_STEP", errorMap["toolMode"])
    }

    @Test
    fun `write mode allows edit_file to actually run`() {
        // Negative case: WRITE is the unrestricted mode. The gate must
        // NOT trip here — edit_file should pass through to the skill.
        val targetFile: Path = tempProjectRoot.resolve("legit.txt")
        Files.writeString(targetFile, "hello world\n")

        val toolCall = ToolCallEntry(
            callIdentifier = "call_1",
            functionName = "edit_file",
            functionArguments = mapOf(
                "path" to JsonPrimitive("legit.txt"),
                "edits" to kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonObject(
                            mapOf(
                                "search" to JsonPrimitive("hello world"),
                                "replace" to JsonPrimitive("goodbye world"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val events = runAgentWithToolCall(toolCall, ToolMode.WRITE)

        val toolCallEvent = events.firstOrNull { it.first == "tool_call" }
            ?: error("Expected a tool_call event, got: ${events.map { it.first }}")

        @Suppress("UNCHECKED_CAST")
        val result = toolCallEvent.second["result"] as Map<String, Any>
        // The error field is null/missing on success; the file content
        // should reflect the edit. We do NOT assert on the exact result
        // map shape (linesAdded etc. are skill-level concerns tested
        // elsewhere) — we only assert that the gate let the call through.
        assertNotEquals(
            (result["error"] as? Map<*, *>)?.get("code"),
            ErrorCode.TOOL_NOT_PERMITTED.name,
            "WRITE mode must NOT reject edit_file — only the mode gate should not fire, " +
                "result was: $result"
        )
        assertEquals(
            "goodbye world\n",
            Files.readString(targetFile),
            "edit_file should have applied the edit in WRITE mode"
        )
    }

    @Test
    fun `read-only mode still allows read_file`() {
        // Negative case for the read-only side: read_file / explore_project /
        // run_cmd are the three tools that should still work. Pin one of them
        // to make sure the gate is not over-broad.
        val targetFile: Path = tempProjectRoot.resolve("observable.txt")
        Files.writeString(targetFile, "inspect me\n")

        val toolCall = ToolCallEntry(
            callIdentifier = "call_1",
            functionName = "read_file",
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
                provider = Provider.OLLAMA,
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
