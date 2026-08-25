/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * LLMClientTest.kt  2026-08-25 14:34:39 Changed by gwy
 */

package gradum.client

import gradum.AgentConfiguration
import gradum.Provider
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-level tests for [OpenAICompatibleClient] / [OllamaClient] against
 * a mocked HTTP engine. Covers SSE chunk parsing (content, reasoning,
 * tool-call accumulation, token usage), the `[DONE]` stream terminator,
 * HTTP error folding, and transient-error handling.
 */
class LLMClientTest {

  private fun config(
    baseUrl: String = "https://api.example.com/v1",
    apiKey: String? = "test-key",
    thinking: Boolean = false,
  ): AgentConfiguration = AgentConfiguration(
    apiKey = apiKey,
    baseUrl = baseUrl,
    modelName = "test-model",
    provider = Provider.OPENAI,
    enableThinking = thinking
  )

  private fun sseChunks(lines: List<String>): MockEngine =
    MockEngine { _ ->
      respond(
        content = ByteReadChannel(text = lines.joinToString(separator = "\n") + "\n"),
        status = HttpStatusCode.OK,
        headers = headersOf(name = HttpHeaders.ContentType, value = "text/event-stream"),
      )
    }

  @Test
  fun `openai sse text and reasoning deltas are emitted in order`() = runBlocking {
    val engine: MockEngine = sseChunks(
      lines = listOf(
        """data: {"choices":[{"delta":{"content":"Hello "}}]}""",
        """data: {"choices":[{"delta":{"reasoning_content":"thinking..."}}]}""",
        """data: {"choices":[{"delta":{"content":"world"}}]}""",
        "data: [DONE]",
      )
    )
    val client = OpenAICompatibleClient(
      config(baseUrl = "https://api.deepseek.com/v1"),
      HttpClient(engine),
    )

    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "hi"))).toList()

    assertEquals(
      expected = listOf(
        LLMResponseChunk.TextContent("Hello "),
        LLMResponseChunk.ReasoningContent("thinking..."),
        LLMResponseChunk.TextContent("world"),
      ),
      chunks,
    )
  }

  @Test
  fun `openai sse preserves pure-whitespace deltas that separate markdown`() = runBlocking {
    // Paragraph breaks, blank lines inside code blocks and trailing
    // spaces (hard line break) arrive as standalone whitespace chunks in
    // real streams. They must NOT be dropped or Markdown collapses.
    val engine: MockEngine = sseChunks(
      lines = listOf(
        """data: {"choices":[{"delta":{"content":"## Title"}}]}""",
        """data: {"choices":[{"delta":{"content":"\n\n"}}]}""",
        """data: {"choices":[{"delta":{"content":"line one  "}}]}""",
        """data: {"choices":[{"delta":{"content":"\n"}}]}""",
        """data: {"choices":[{"delta":{"content":"line two"}}]}""",
        """data: {"choices":[{"delta":{"content":"\n```\ncode\n```\n"}}]}""",
        "data: [DONE]",
      )
    )
    val client = OpenAICompatibleClient(
      config(baseUrl = "https://api.deepseek.com/v1"),
      HttpClient(engine),
    )

    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "hi"))).toList()

    assertEquals(
      expected = listOf(
        LLMResponseChunk.TextContent("## Title"),
        LLMResponseChunk.TextContent("\n\n"),
        LLMResponseChunk.TextContent("line one  "),
        LLMResponseChunk.TextContent("\n"),
        LLMResponseChunk.TextContent("line two"),
        LLMResponseChunk.TextContent("\n```\ncode\n```\n"),
      ),
      chunks,
    )
  }

  @Test
  fun `openai sse folds multiple tool-call deltas into completed calls`() = runBlocking {
    val engine: MockEngine = sseChunks(
      lines = listOf(
        """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":\""}}]}}]}""",
        """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"src/A.kt\"}"}}]}}]}""",
        """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"read_file","arguments":"{\"path\":\"B.kt\"}"}}]}}]}""",
        "data: [DONE]",
      )
    )
    val client = OpenAICompatibleClient(config(), HttpClient(engine))

    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "x"))).toList()

    val toolBatches = chunks.filterIsInstance<LLMResponseChunk.ToolCallBatch>()
    assertEquals(
      1,
      toolBatches.size
    )
    val calls = toolBatches.single().toolCalls
    assertEquals(
      2,
      calls.size
    )
    assertEquals(
      "call_1",
      calls[0].callIdentifier
    )
    assertEquals(
      "read_file",
      calls[0].functionName
    )
    assertEquals(
      mapOf("path" to JsonPrimitive(value = "src/A.kt")),
      calls[0].functionArguments
    )
    assertEquals(
      "call_2",
      calls[1].callIdentifier
    )
    assertEquals(
      mapOf("path" to JsonPrimitive(value = "B.kt")),
      calls[1].functionArguments
    )
  }

  @Test
  fun `openai index-less tool-call chunks do not collapse into one call`() = runBlocking {
    // Some providers (DeepSeek, proxies) omit `index` and emit each tool
    // call as a single complete chunk. optInt("index") would map both to 0
    // and merge them into one corrupted entry — each must stay distinct.
    val engine: MockEngine = sseChunks(
      listOf(
        """data: {"choices":[{"delta":{"tool_calls":[{"id":"call_a","function":{"name":"read_file","arguments":"{\"path\":\"A.kt\"}"}}]}}]}""",
        """data: {"choices":[{"delta":{"tool_calls":[{"id":"call_b","function":{"name":"run_cmd","arguments":"{\"command\":\"ls\"}"}}]}}]}""",
        "data: [DONE]",
      )
    )
    val client = OpenAICompatibleClient(config(), HttpClient(engine))

    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "x"))).toList()

    val toolBatches = chunks.filterIsInstance<LLMResponseChunk.ToolCallBatch>()
    assertEquals(
      1,
      toolBatches.size
    )
    val calls = toolBatches.single().toolCalls
    assertEquals(
      2,
      calls.size
    )
    assertEquals(
      "call_a",
      calls[0].callIdentifier
    )
    assertEquals(
      "read_file",
      calls[0].functionName
    )
    assertEquals(
      mapOf("path" to JsonPrimitive("A.kt")),
      calls[0].functionArguments
    )
    assertEquals(
      "call_b",
      calls[1].callIdentifier
    )
    assertEquals(
      "run_cmd",
      calls[1].functionName
    )
    assertEquals(
      mapOf("command" to JsonPrimitive("ls")),
      calls[1].functionArguments
    )
  }

  @Test
  fun `openai usage deltas accumulate across chunks`() = runBlocking {
    val engine: MockEngine = sseChunks(
      listOf(
        """data: {"choices":[{"delta":{"content":"a"}}],"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
        """data: {"choices":[{"delta":{"content":"b"}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}""",
        "data: [DONE]",
      )
    )
    val client = OpenAICompatibleClient(config(), HttpClient(engine))

    client.sendChat(listOf(mapOf("role" to "user", "content" to "x"))).toList()

    assertEquals(
      15,
      client.tokenUsage.promptTokens
    )
    assertEquals(
      5,
      client.tokenUsage.completionTokens
    )
    assertEquals(
      20,
      client.tokenUsage.totalTokens
    )
  }

  @Test
  fun `openai stream missing done emits interrupted error`() = runBlocking {
    val engine: MockEngine = sseChunks(
      lines = listOf("""data: {"choices":[{"delta":{"content":"partial"}}]}""")
    )
    val client = OpenAICompatibleClient(config(), HttpClient(engine))
    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "x"))).toList()

    assertTrue(chunks.contains(LLMResponseChunk.TextContent("partial")))
    assertTrue(
      chunks.any { it is LLMResponseChunk.ErrorMessage },
      "Missing [DONE] must surface an interruption error",
    )
  }

  @Test
  fun `openai http 4xx error surfaces as error message without retry`() = runBlocking {
    var requestCount = 0
    val engine = MockEngine { _ ->
      requestCount++
      respondError(status = HttpStatusCode.Unauthorized, "bad key")
    }
    val client = OpenAICompatibleClient(config(), HttpClient(engine))

    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "x"))).toList()

    assertTrue(chunks.any { it is LLMResponseChunk.ErrorMessage })
    assertEquals(
      1,
      requestCount,
      "4xx is not transient — must not retry"
    )
  }

  @Test
  fun `openai 4xx error body message is surfaced instead of the generic interrupt text`() = runBlocking {
    val engine = MockEngine { _ ->
      respond(
        content = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""",
        status = HttpStatusCode.Unauthorized,
      )
    }
    val client = OpenAICompatibleClient(config(), HttpClient(engine))

    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "x"))).toList()

    val error = chunks.filterIsInstance<LLMResponseChunk.ErrorMessage>()
    assertEquals(
      expected = 1,
      actual = error.size
    )
    assertEquals(
      expected = "Incorrect API key provided",
      actual = error.single().description
    )
  }

  @Test
  fun `openai 4xx with unparseable body falls back to the status line`() = runBlocking {
    val engine = MockEngine { _ ->
      respond(
        content = "no json here",
        status = HttpStatusCode.BadGateway,
      )
    }
    val client = OpenAICompatibleClient(config(), HttpClient(engine))

    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "x"))).toList()

    val error = chunks.filterIsInstance<LLMResponseChunk.ErrorMessage>()
    assertEquals(
      expected = 1,
      actual = error.size
    )
    assertEquals(
      expected = "HTTP 502 Bad Gateway",
      actual = error.single().description
    )
  }

  @Test
  fun `openai non-transient server error surfaces without retry`() = runBlocking {
    var requestCount = 0
    val engine = MockEngine { _ ->
      requestCount++
      respondError(status = HttpStatusCode.InternalServerError, "boom")
    }
    val client = OpenAICompatibleClient(config(), HttpClient(engine))
    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "x"))).toList()

    assertTrue(actual = chunks.any { it is LLMResponseChunk.ErrorMessage })
    assertEquals(
      expected = 1,
      actual = requestCount
    )
  }

  @Test
  fun `ollama streams content and tool calls from native protocol`() = runBlocking {
    val engine = MockEngine { _ ->
      respond(
        content = ByteReadChannel(
          text = listOf(
            """{"message":{"content":"Answer","thinking":"hmm"},"prompt_eval_count":4,"eval_count":2}""",
            """{"message":{"tool_calls":[{"function":{"name":"read_file","arguments":{"path":"a.kt"}},"id":"t1"}]}}""",
          ).joinToString(separator = "\n") + "\n"
        ),
        status = HttpStatusCode.OK,
      )
    }
    val client = OllamaClient(
      config(baseUrl = "http://localhost:11434", apiKey = null),
      HttpClient(engine),
    )

    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "hi"))).toList()

    assertTrue(chunks.contains(LLMResponseChunk.ReasoningContent(text = "hmm")))
    assertTrue(chunks.contains(LLMResponseChunk.TextContent("Answer")))
    val toolBatches = chunks.filterIsInstance<LLMResponseChunk.ToolCallBatch>()
    assertEquals(
      1,
      toolBatches.size
    )
    val call = toolBatches.single().toolCalls.single()
    assertEquals(
      "read_file",
      call.functionName
    )
    assertEquals(
      mapOf("path" to JsonPrimitive("a.kt")),
      call.functionArguments
    )
    assertEquals(
      4,
      client.tokenUsage.promptTokens
    )
    assertEquals(
      2,
      client.tokenUsage.completionTokens
    )
  }

  @Test
  fun `ollama non-transient failure surfaces error without retry`() = runBlocking {
    var requestCount = 0
    val engine = MockEngine { _ ->
      requestCount++
      respondError(status = HttpStatusCode.BadRequest, content = "nope")
    }
    val client = OllamaClient(
      configuration = config(baseUrl = "http://localhost:11434", apiKey = null),
      HttpClient(engine),
    )

    val chunks = client.sendChat(messageHistory = listOf(mapOf("role" to "user", "content" to "hi"))).toList()

    assertTrue(actual = chunks.any { it is LLMResponseChunk.ErrorMessage })
    assertEquals(
      expected = 1,
      actual = requestCount
    )
  }
}
