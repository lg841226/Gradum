package gradum.idea.chat.history

import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.RenderBlock
import gradum.idea.editor.AttachedText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that [SessionProjection] decodes the server's folded projection
 * (`GET /session/state` -> `messages`) into the bubble view: blocks keep their
 * order, tool-call arguments keep their plain values, and a bubble with no
 * usable role decodes to nothing.
 */
class SessionProjectionTest {

  private fun project(vararg bubbles: String): List<ChatMessage> =
    SessionProjection.toMessages(
      projectedMessages = bubbles.map { bubble: String ->
        Json.parseToJsonElement(bubble) as JsonObject
      },
      timestamp = SESSION_TIMESTAMP
    )

  @Test
  fun `user bubble keeps its content id and attachment names`() {
    val messages: List<ChatMessage> = project(
      """{"role":"user","id":"m1","content":"explain this","attachments":[{"name":"shot.png"}]}"""
    )

    assertEquals(1, messages.size)
    val user: ChatMessage = messages.first()
    assertTrue(user.isUserMessage)
    assertEquals("explain this", user.content)
    assertEquals("m1", user.messageId)
    assertEquals(1, user.attachments.size)
    assertEquals("shot.png", (user.attachments.first() as AttachedText).preview)
  }

  @Test
  fun `assistant bubble decodes its blocks in order`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","id":"m2","modelName":"qwen2.5","tokenUsage":{"promptTokens":10,"completionTokens":4,"totalTokens":14,"contextTokens":10},"blocks":[{"type":"thinking","content":"weighing options"},{"type":"toolCall","alias":"Read","success":true,"toolCallId":"call-1","result":"file body","arguments":{"path":"Main.kt"}},{"type":"response","content":"done"}]}"""
    )

    assertEquals(1, messages.size)
    val assistant: ChatMessage = messages.first()
    assertEquals("qwen2.5", assistant.modelName)
    assertEquals("m2", assistant.messageId)
    assertEquals(14, assistant.tokenUsage?.totalTokens)

    val blocks: List<RenderBlock> = assistant.renderBlocks
    assertEquals(3, blocks.size)
    assertEquals("weighing options", (blocks[0] as RenderBlock.Thinking).content)
    assertTrue(blocks[1] is RenderBlock.ToolCall)
    assertEquals("done", (blocks[2] as RenderBlock.Response).content)

    val toolCall: RenderBlock.ToolCall = blocks[1] as RenderBlock.ToolCall
    assertEquals("Read", toolCall.alias)
    assertEquals(true, toolCall.success)
    assertEquals("call-1", toolCall.toolCallId)
    assertEquals("file body", toolCall.result)
    assertEquals("Main.kt", toolCall.arguments["path"])
  }

  @Test
  fun `tool call arguments keep their plain values`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","blocks":[{"type":"toolCall","alias":"Delegate","success":true,"arguments":{"pending":false,"depth":2,"title":"Investigate"}}]}"""
    )

    val toolCall: RenderBlock.ToolCall = messages.first().renderBlocks
      .filterIsInstance<RenderBlock.ToolCall>().first()
    assertEquals(false, toolCall.arguments["pending"])
    assertEquals(2, toolCall.arguments["depth"])
    assertEquals("Investigate", toolCall.arguments["title"])
  }

  @Test
  fun `a usage without a positive total is dropped`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","content":"hi","tokenUsage":{"promptTokens":3,"totalTokens":0}}"""
    )

    assertEquals(1, messages.size)
    assertNull(messages.first().tokenUsage)
  }

  @Test
  fun `every decoded message carries the session timestamp`() {
    val messages: List<ChatMessage> = project(
      """{"role":"user","content":"hi"}""",
      """{"role":"assistant","blocks":[{"type":"response","content":"hello"}]}"""
    )

    assertTrue(messages.all { it.timestamp == SESSION_TIMESTAMP })
  }

  @Test
  fun `an empty projection maps to no messages`() {
    assertTrue(SessionProjection.toMessages(emptyList()).isEmpty())
  }

  @Test
  fun `an unknown role decodes to nothing`() {
    assertTrue(project("""{"role":"system","content":"secret"}""").isEmpty())
  }

  @Test
  fun `an unknown block kind is skipped`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","blocks":[{"type":"mystery"},{"type":"response","content":"kept"}]}"""
    )

    val blocks: List<RenderBlock> = messages.first().renderBlocks
    assertEquals(1, blocks.size)
    assertEquals("kept", (blocks.first() as RenderBlock.Response).content)
  }

  private companion object {
    const val SESSION_TIMESTAMP: Long = 1700000000000L
  }
}
