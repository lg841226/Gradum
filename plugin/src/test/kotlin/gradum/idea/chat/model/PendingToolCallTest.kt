/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PendingToolCallTest.kt  2026-09-25 01:19:19 Changed by gwy
 */

package gradum.idea.chat.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the pending tool-call lifecycle: a `tool_call_start` event
 * renders a pending placeholder, and the completed `tool_call` event
 * with the same `toolCallId` replaces it in place (both in [events]
 * and [renderBlocks]) instead of stacking a duplicate row.
 */
class PendingToolCallTest {

  private fun assistantMessage(): ChatMessage =
    ChatMessage(role = "assistant")

  @Test
  fun `pending tool call appends a pending render block`() {
    val message = assistantMessage().appendEvent(
      ChatEvent.ToolCall(
        info = ToolCallInfo(
          toolName = "run_cmd",
          alias = "Ran",
          toolCallId = "call_1",
          arguments = mapOf("command" to "git status"),
          pending = true
        )
      )
    )

    val block = message.renderBlocks.last() as RenderBlock.ToolCall
    assertTrue(block.pending)
    assertEquals(
      "call_1",
      block.toolCallId
    )
    assertEquals(
      "Ran",
      block.alias
    )
    assertEquals(
      1,
      message.renderBlocks.size
    )
    assertEquals(
      1,
      message.events.size
    )
  }

  @Test
  fun `completed tool call replaces its pending placeholder in place`() {
    val pending = assistantMessage().appendEvent(
      ChatEvent.ToolCall(
        info = ToolCallInfo(
          toolName = "run_cmd",
          alias = "Ran",
          toolCallId = "call_1",
          arguments = mapOf("command" to "git status"),
          pending = true
        )
      )
    )

    val finished = pending.appendEvent(
      ChatEvent.ToolCall(
        info = ToolCallInfo(
          toolName = "run_cmd",
          alias = "Ran",
          toolCallId = "call_1",
          success = true,
          result = "{\"exitCode\":0,\"output\":\"On branch master\"}",
          arguments = mapOf("command" to "git status")
        )
      )
    )

    assertEquals(
      "one event after replacement",
      1,
      finished.events.size
    )
    assertEquals(
      "one block after replacement",
      1,
      finished.renderBlocks.size
    )
    val block = finished.renderBlocks.last() as RenderBlock.ToolCall
    assertFalse("pending flag cleared", block.pending)
    assertTrue(block.success)
    assertEquals(
      "{\"exitCode\":0,\"output\":\"On branch master\"}",
      block.result
    )
  }

  @Test
  fun `completed tool call with no matching pending id appends a new block`() {
    val message = assistantMessage().appendEvent(
      ChatEvent.ToolCall(
        info = ToolCallInfo(
          toolName = "read_file",
          alias = "Read",
          toolCallId = "call_9",
          arguments = mapOf("path" to "/src/Main.kt"),
          pending = true
        )
      )
    ).appendEvent(
      ChatEvent.ToolCall(
        info = ToolCallInfo(
          toolName = "run_cmd",
          alias = "Ran",
          toolCallId = "call_10",
          arguments = mapOf("command" to "git status")
        )
      )
    )

    assertEquals(
      2,
      message.renderBlocks.size
    )
    assertEquals(
      2,
      message.events.size
    )
  }

  @Test
  fun `two pending calls with distinct ids both survive`() {
    val message = assistantMessage().appendEvent(
      ChatEvent.ToolCall(
        info = ToolCallInfo(toolName = "read_file", toolCallId = "call_1", pending = true)
      )
    ).appendEvent(
      ChatEvent.ToolCall(
        info = ToolCallInfo(toolName = "grep", toolCallId = "call_2", pending = true)
      )
    )

    assertEquals(
      2,
      message.renderBlocks.size
    )
    assertTrue((message.renderBlocks[0] as RenderBlock.ToolCall).pending)
    assertTrue((message.renderBlocks[1] as RenderBlock.ToolCall).pending)
  }
}
