package gradum.idea.chat.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Pins [ChatMessage.appendBlock]: consecutive string blocks of the same kind
 * coalesce, and a tool call whose `toolCallId` already exists updates that row
 * in place, so the completed `tool_call` replaces the pending placeholder from
 * `tool_call_start` instead of stacking a duplicate.
 */
class ChatMessageAppendTest {

  private fun assistantMessage(): ChatMessage =
    ChatMessage(role = "assistant")

  private fun pendingCall(id: String, alias: String = "Ran"): RenderBlock.ToolCall =
    RenderBlock.ToolCall(
      alias = alias,
      success = true,
      pending = true,
      toolCallId = id,
      arguments = mapOf("command" to "git status")
    )

  @Test
  fun `consecutive response blocks coalesce into one`() {
    val message = assistantMessage()
      .appendBlock(RenderBlock.Response("hello "))
      .appendBlock(RenderBlock.Response("world"))

    assertEquals(1, message.renderBlocks.size)
    assertEquals("hello world", message.responseContent)
    assertEquals("hello world", message.fullContent)
  }

  @Test
  fun `consecutive thinking blocks coalesce into one`() {
    val message = assistantMessage()
      .appendBlock(RenderBlock.Thinking("step one"))
      .appendBlock(RenderBlock.Thinking(" and two"))

    assertEquals(1, message.renderBlocks.size)
    assertEquals("step one and two", (message.renderBlocks.first() as RenderBlock.Thinking).content)
  }

  @Test
  fun `a response after thinking starts a new block`() {
    val message = assistantMessage()
      .appendBlock(RenderBlock.Thinking("weighing"))
      .appendBlock(RenderBlock.Response("done"))

    assertEquals(2, message.renderBlocks.size)
    assertTrue(message.renderBlocks[0] is RenderBlock.Thinking)
    assertTrue(message.renderBlocks[1] is RenderBlock.Response)
  }

  @Test
  fun `pending tool call appends a pending render block`() {
    val message = assistantMessage().appendBlock(pendingCall(id = "call_1"))

    assertEquals(1, message.renderBlocks.size)
    val block = message.renderBlocks.last() as RenderBlock.ToolCall
    assertTrue(block.pending)
    assertEquals("call_1", block.toolCallId)
    assertEquals("Ran", block.alias)
  }

  @Test
  fun `completed tool call replaces its pending placeholder`() {
    val finished = assistantMessage()
      .appendBlock(pendingCall(id = "call_1"))
      .appendBlock(
        RenderBlock.ToolCall(
          alias = "Ran",
          success = true,
          toolCallId = "call_1",
          result = "{\"exitCode\":0,\"output\":\"On branch master\"}",
          arguments = mapOf("command" to "git status")
        )
      )

    assertEquals("one block after replacement", 1, finished.renderBlocks.size)
    val block = finished.renderBlocks.last() as RenderBlock.ToolCall
    assertFalse("pending flag cleared", block.pending)
    assertTrue(block.success)
    assertEquals("{\"exitCode\":0,\"output\":\"On branch master\"}", block.result)
  }

  @Test
  fun `completed tool call with no matching pending id appends a new block`() {
    val message = assistantMessage()
      .appendBlock(pendingCall(id = "call_9", alias = "Read"))
      .appendBlock(pendingCall(id = "call_10"))

    assertEquals(2, message.renderBlocks.size)
  }

  @Test
  fun `two pending calls with distinct ids both survive`() {
    val message = assistantMessage()
      .appendBlock(RenderBlock.ToolCall(alias = "Read", success = true, toolCallId = "call_1", pending = true))
      .appendBlock(RenderBlock.ToolCall(alias = "Grep", success = true, toolCallId = "call_2", pending = true))

    assertEquals(2, message.renderBlocks.size)
    assertTrue((message.renderBlocks[0] as RenderBlock.ToolCall).pending)
    assertTrue((message.renderBlocks[1] as RenderBlock.ToolCall).pending)
  }

  @Test
  fun `updateLastError stamps the last failed tool call`() {
    val message = assistantMessage()
      .appendBlock(RenderBlock.ToolCall(alias = "Read", success = true, toolCallId = "call_1"))
      .appendBlock(RenderBlock.ToolCall(alias = "Ran", success = false, toolCallId = "call_2"))
      .updateLastError(friendlyMessage = "friendly", errorDetail = "detail")

    val failed = message.renderBlocks.last() as RenderBlock.ToolCall
    assertEquals("friendly", failed.errorMessage)
    assertEquals("detail", failed.errorDetail)
  }

  @Test
  fun `updateLastError is a no-op without a failed tool call`() {
    val message = assistantMessage()
      .appendBlock(RenderBlock.ToolCall(alias = "Read", success = true, toolCallId = "call_1"))

    assertSame(message, message.updateLastError(friendlyMessage = "friendly", errorDetail = "detail"))
  }
}
