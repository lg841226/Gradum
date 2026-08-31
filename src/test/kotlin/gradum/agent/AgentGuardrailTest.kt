/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AgentGuardrailTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.agent

import gradum.AgentConfiguration
import gradum.client.LLMResponseChunk
import gradum.client.LlmClient
import gradum.client.TokenUsageSnapshot
import gradum.client.ToolCallEntry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentGuardrailTest {

  private val testRedLineKeywords: List<String> = listOf(
    "red-flag",
    "TOP-SECRET",
    "confidential",
  )

  @Test
  fun `no red line hit emits no guardrail events`() {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    val mockClient: LlmClient = mockk {
      every {
        sendChat(
          messageHistory = any(),
          toolDefinitions = any()
        )
      } returns flowOf(value = LLMResponseChunk.TextContent("normal response without keywords"))
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      llmClient = mockClient,
      configuration = AgentConfiguration(),
      redLineKeywords = testRedLineKeywords,
    ) { type, data -> events.add(type to data) }

    agent.executeTask(userInput = "hello")

    val eventTypes = events.map { it.first }
    assertTrue(
      "mission_revoked" !in eventTypes,
      "Should not revoke when no red line keyword hits"
    )
  }

  @Test
  fun `red line hit below threshold emits guardrail warning but not revocation`() {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    val mockClient: LlmClient = mockk {
      every {
        sendChat(
          messageHistory = any(),
          toolDefinitions = any()
        )
      } returns flowOf(value = LLMResponseChunk.TextContent("this contains red-flag in the text"))
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      llmClient = mockClient,
      configuration = AgentConfiguration(),
      redLineKeywords = testRedLineKeywords,
    ) { type, data -> events.add(type to data) }

    agent.executeTask(userInput = "test")

    val eventTypes = events.map { it.first }
    assertContains(
      iterable = eventTypes,
      element = "guardrail",
      message = "Should emit guardrail warning on keyword hit"
    )
    assertTrue(
      "mission_revoked" !in eventTypes,
      "Should not revoke when hits are below threshold"
    )
  }

  @Test
  fun `red line hit reaches threshold triggers mission_revoked`() {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    val mockClient: LlmClient = mockk {
      every {
        sendChat(
          messageHistory = any(),
          toolDefinitions = any()
        )
      } returns flowOf(value = LLMResponseChunk.TextContent("this mentions TOP-SECRET in the text"))
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      llmClient = mockClient,
      redLineKeywords = testRedLineKeywords,
      configuration = AgentConfiguration(maxRedLineHits = 1)
    ) { type, data -> events.add(type to data) }

    agent.executeTask(userInput = "test")

    val eventTypes = events.map { it.first }
    assertContains(
      iterable = eventTypes,
      element = "guardrail",
      message = "Should emit guardrail warning"
    )
    assertContains(
      iterable = eventTypes,
      element = "mission_revoked",
      message = "Should emit mission_revoked when threshold is reached"
    )

    val revokedEvent = events.first { it.first == "mission_revoked" }

    @Suppress("UNCHECKED_CAST")
    val details = revokedEvent.second["details"] as? Map<String, Any>
    assertEquals(
      "red_line_violation",
      revokedEvent.second["reason"],
      "Reason should be red_line_violation"
    )
    assertEquals(
      1,
      details?.get("hitCount"),
      "hitCount should be 1"
    )

    val sessionEndEvent = events.first { it.first == "session_end" }
    assertEquals(
      true,
      sessionEndEvent.second["aborted"],
      "session_end should be marked aborted"
    )
  }

  @Test
  fun `case insensitive keyword matching works`() {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    val mockClient: LlmClient = mockk {
      every {
        sendChat(messageHistory = any(), toolDefinitions = any())
      } returns flowOf(value = LLMResponseChunk.TextContent("UPPERCASE RED-FLAG in text"))
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      llmClient = mockClient,
      configuration = AgentConfiguration(maxRedLineHits = 1),
      redLineKeywords = testRedLineKeywords,
    ) { type, data -> events.add(type to data) }

    agent.executeTask(userInput = "test")

    val eventTypes = events.map { it.first }
    assertContains(
      eventTypes,
      "mission_revoked",
      "Should match case-insensitively"
    )
  }

  @Test
  fun `multiple distinct keywords accumulate hits`() {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()

    val mockClient: LlmClient = mockk {
      every {
        sendChat(
          messageHistory = any(),
          toolDefinitions = any()
        )
      } returns flowOf(value = LLMResponseChunk.TextContent("red-flag and confidential together"))
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      llmClient = mockClient,
      configuration = AgentConfiguration(maxRedLineHits = 2),
      redLineKeywords = testRedLineKeywords,
    ) { type, data -> events.add(type to data) }

    agent.executeTask(userInput = "test")

    val eventTypes = events.map { it.first }
    assertContains(
      iterable = eventTypes,
      element = "guardrail",
      message = "Should emit guardrail on first hit"
    )
    assertTrue(
      "mission_revoked" !in eventTypes,
      "One hit with maxRedLineHits=2 should not revoke"
    )
  }

  @Test
  fun `same tool call repeated reaches threshold triggers tool_runaway`() {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    val toolCall = ToolCallEntry(
      functionName = "read_file",
      callIdentifier = "",
      functionArguments = mapOf("path" to JsonPrimitive(value = "Agent.kt")),
    )
    var sendChatCallCount = 0
    val mockClient: LlmClient = mockk {
      every {
        sendChat(
          messageHistory = any(),
          toolDefinitions = any()
        )
      } answers {
        sendChatCallCount++
        if (sendChatCallCount <= 3) {
          flowOf(
            LLMResponseChunk.TextContent("doing it again"),
            LLMResponseChunk.ToolCallBatch(toolCalls = listOf(toolCall)),
          )
        } else {
          flowOf(value = LLMResponseChunk.TextContent("done"))
        }
      }
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      llmClient = mockClient,
      configuration = AgentConfiguration(maxRepeatedToolCalls = 3),
    ) { type, data -> events.add(type to data) }

    agent.executeTask(userInput = "test")

    val eventTypes = events.map { it.first }
    assertContains(
      eventTypes,
      "mission_revoked",
      "Should emit mission_revoked when same tool call repeats"
    )
    val revokedEvent = events.first { it.first == "mission_revoked" }
    assertEquals(
      "tool_runaway",
      revokedEvent.second["reason"],
      "Reason should be tool_runaway"
    )
    @Suppress("UNCHECKED_CAST")
    val details = revokedEvent.second["details"] as? Map<String, Any>
    assertEquals(
      3,
      details?.get("repeatedCount"),
      "repeatedCount should be 3"
    )

    val sessionEndEvent = events.first { it.first == "session_end" }
    assertEquals(
      true,
      sessionEndEvent.second["aborted"],
      "session_end should be marked aborted"
    )
  }

  @Test
  fun `different tool call resets runaway counter`() {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    val pathArg1: Map<String, JsonPrimitive> = mapOf("path" to JsonPrimitive(value = "file1.txt"))
    val pathArg2: Map<String, JsonPrimitive> = mapOf("path" to JsonPrimitive(value = "file2.txt"))
    val toolCalls: Map<Int, ToolCallEntry> = mapOf(
      1 to ToolCallEntry(functionName = "read_file", callIdentifier = "", functionArguments = pathArg1),
      2 to ToolCallEntry(functionName = "read_file", callIdentifier = "", functionArguments = pathArg2),
      3 to ToolCallEntry(functionName = "read_file", callIdentifier = "", functionArguments = pathArg1)
    )
    var sendChatCallCount = 0
    val mockClient: LlmClient = mockk {
      every {
        sendChat(
          messageHistory = any(),
          toolDefinitions = any()
        )
      } answers {
        sendChatCallCount++
        if (sendChatCallCount <= 3) {
          flowOf(
            LLMResponseChunk.TextContent("step $sendChatCallCount"),
            LLMResponseChunk.ToolCallBatch(
              toolCalls = listOf(toolCalls.getValue(key = sendChatCallCount))
            )
          )
        } else flowOf(value = LLMResponseChunk.TextContent("done"))
      }
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      llmClient = mockClient,
      configuration = AgentConfiguration(maxRepeatedToolCalls = 3),
    ) { type, data -> events.add(type to data) }

    agent.executeTask(userInput = "test")

    val eventTypes = events.map { it.first }
    assertTrue(
      "mission_revoked" !in eventTypes,
      "Should not revoke when tool calls differ"
    )
  }
}
