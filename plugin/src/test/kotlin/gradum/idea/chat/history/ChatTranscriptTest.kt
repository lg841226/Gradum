/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatTranscriptTest.kt  2026-08-25 14:53:11 Changed by gwy
 */

package gradum.idea.chat.history

import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.TokenUsage
import gradum.idea.chat.model.ToolCallInfo
import gradum.idea.editor.AttachedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [ChatTranscript]: `parse(generate(messages))` must
 * reproduce every event in order (so `fullContent` is identical), all
 * message metadata, token usage, and the user bubble's attachments.
 */
class ChatTranscriptTest {

  private val sessionMeta: SessionMeta = SessionMeta(
    title = "Fix the flaky test",
    modelName = "qwen2.5:7b",
    sessionId = "20260812-131500-a1b2",
    createdAt = 1000L,
    updatedAt = 2000L
  )

  private fun sampleMessages(): List<ChatMessage> {
    val user = ChatMessage(
      role = "user",
      content = "Explain the bug in `renderBlocks` please.\n\nSecond paragraph.",
      timestamp = 1000L,
      attachments = listOf(
        AttachedText(content = "/project/src/Main.kt", preview = "Main.kt")
      )
    )

    val assistant = ChatMessage(
      role = "assistant",
      content = "",
      timestamp = 1500L,
      modelName = "qwen2.5:7b",
      provider = "ollama",
      serverName = "ollama",
      tokenUsage = TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
    )
      .appendEvent(ChatEvent.Thinking(content = "Let me look at the block aggregation logic.\n\nSecond thought line."))
      .appendEvent(
        ChatEvent.ToolCall(
          ToolCallInfo(
            toolName = "read_file",
            alias = "read",
            toolCallId = "tc-1",
            success = true,
            result = "{\"path\":\"/src/Main.kt\",\"content\":\"fun x() {}\"}",
            arguments = mapOf(
              "path" to "/src/Main.kt",
              "maxLines" to 100,
              "strict" to true
            )
          )
        )
      )
      .appendEvent(ChatEvent.Response(content = "The bug is that consecutive responses are coalesced into one render block.\n\nHere's the fix:"))
      .appendEvent(
        ChatEvent.Error(
          message = "Tool execution failed",
          code = "TOOL_FAILED",
          tool = "read_file"
        )
      )
      .appendEvent(ChatEvent.Response(content = "I've corrected the aggregation."))

    return listOf(user, assistant)
  }

  @Test
  fun `generate produces a valid v1 header`() {
    val transcriptMarkdown: String = ChatTranscript.generateTranscript(messages = sampleMessages(), sessionMeta)
    assertTrue(transcriptMarkdown.startsWith(prefix = "<!-- gradum-transcript v1 -->"))
    assertTrue(transcriptMarkdown.contains(other = "<!-- gradum-session id=\"20260812-131500-a1b2\""))
  }

  @Test
  fun `round trip preserves fullContent exactly`() {
    val messages: List<ChatMessage> = sampleMessages()
    val parsed: ChatTranscript.ParsedTranscript =
      ChatTranscript.parseTranscript(content = ChatTranscript.generateTranscript(messages, sessionMeta))

    assertEquals(
      messages.size,
      parsed.messages.size
    )
    for (index in messages.indices) {
      assertEquals(
        "fullContent mismatch at message $index",
        messages[index].fullContent,
        parsed.messages[index].fullContent
      )
    }
  }

  @Test
  fun `round trip preserves event sequence and tool call details`() {
    val messages: List<ChatMessage> = sampleMessages()
    val parsed: ChatTranscript.ParsedTranscript =
      ChatTranscript.parseTranscript(content = ChatTranscript.generateTranscript(messages, sessionMeta))

    val originalAssistant: ChatMessage = messages[1]
    val parsedAssistant: ChatMessage = parsed.messages[1]

    assertEquals(
      originalAssistant.events.size,
      parsedAssistant.events.size
    )
    for (index in originalAssistant.events.indices) {
      val original: ChatEvent = originalAssistant.events[index]
      val parsedEvent: ChatEvent = parsedAssistant.events[index]
      assertEquals(
        "event type at index $index",
        original::class,
        parsedEvent::class
      )

      when (original) {
        is ChatEvent.ToolCall -> {
          val parsedCall: ChatEvent.ToolCall = parsedEvent as ChatEvent.ToolCall
          assertEquals(
            original.info.alias,
            parsedCall.info.alias
          )
          assertEquals(
            original.info.toolCallId,
            parsedCall.info.toolCallId
          )
          assertEquals(
            original.info.success,
            parsedCall.info.success
          )
          assertEquals(
            original.info.result,
            parsedCall.info.result
          )
          assertEquals(
            original.info.arguments,
            parsedCall.info.arguments
          )
        }

        is ChatEvent.Error -> {
          val parsedError: ChatEvent.Error = parsedEvent as ChatEvent.Error
          assertEquals(
            original.code,
            parsedError.code
          )
          assertEquals(
            original.tool,
            parsedError.tool
          )
          assertEquals(
            original.message,
            parsedError.message
          )
        }

        else -> assertEquals(original, parsedEvent)
      }
    }
  }

  @Test
  fun `round trip preserves metadata token usage and attachments`() {
    val messages: List<ChatMessage> = sampleMessages()
    val parsed: ChatTranscript.ParsedTranscript =
      ChatTranscript.parseTranscript(content = ChatTranscript.generateTranscript(messages, sessionMeta))

    assertEquals(
      sessionMeta,
      parsed.sessionMeta
    )

    val originalAssistant: ChatMessage = messages[1]
    val parsedAssistant: ChatMessage = parsed.messages[1]
    assertEquals(
      originalAssistant.modelName,
      parsedAssistant.modelName
    )
    assertEquals(
      originalAssistant.provider,
      parsedAssistant.provider
    )
    assertEquals(
      originalAssistant.serverName,
      parsedAssistant.serverName
    )
    assertEquals(
      originalAssistant.tokenUsage,
      parsedAssistant.tokenUsage
    )

    val parsedUser: ChatMessage = parsed.messages[0]
    assertEquals(
      1,
      parsedUser.attachments.size
    )
    val attachment: AttachedText = parsedUser.attachments[0] as AttachedText
    assertEquals(
      "Main.kt",
      attachment.preview
    )
    assertEquals(
      "/project/src/Main.kt",
      attachment.content
    )
  }

  @Test
  fun `user message content round trips verbatim`() {
    val messages: List<ChatMessage> = sampleMessages()
    val parsed: ChatTranscript.ParsedTranscript =
      ChatTranscript.parseTranscript(content = ChatTranscript.generateTranscript(messages, sessionMeta))
    assertEquals(
      messages[0].content,
      parsed.messages[0].content
    )
  }

  @Test
  fun `parseMeta extracts header without parsing messages`() {
    val transcriptMarkdown: String = ChatTranscript.generateTranscript(sampleMessages(), sessionMeta)
    assertEquals(
      sessionMeta,
      ChatTranscript.parseMeta(transcriptMarkdown)
    )
  }

  @Test
  fun `parseMeta returns blank sessionMeta for non transcript content`() {
    assertEquals(
      SessionMeta(
        title = "", modelName = "", sessionId = "", createdAt = 0L, updatedAt = 0L
      ), ChatTranscript.parseMeta("# just markdown")
    )
  }

  @Test
  fun `titleFor uses first non blank user line truncated`() {
    val messages = listOf(
      ChatMessage(role = "user", content = "\n  Fix the render block bug in ChatMessage.kt and its tests\nmore", timestamp = 1L),
      ChatMessage(role = "assistant", content = "", timestamp = 2L)
    )
    assertEquals(
      "Fix the render block bug in ChatMessage.kt and i",
      ChatTranscript.titleFor(messages)
    )
  }

  @Test
  fun `titleFor falls back to empty for no user message`() {
    assertEquals(
      "",
      ChatTranscript.titleFor(messages = listOf(ChatMessage(role = "assistant", content = "")))
    )
  }
}
