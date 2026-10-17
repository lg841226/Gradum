package gradum.idea.chat.history

import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.model.ToolCallInfo
import gradum.idea.editor.AttachedText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that [SessionProjection] rebuilds the bubble view from the server's
 * `session-state.json` shape (the raw agent history maps): tool results fold
 * into the call that issued them, user attachments survive by name, and a
 * message the transcript writer would render empty is skipped.
 */
class SessionProjectionTest {

  private fun project(vararg rawMessages: String): List<ChatMessage> =
    SessionProjection.toMessages(
      projectedMessages = rawMessages.map { rawMessage: String ->
        Json.parseToJsonElement(rawMessage) as JsonObject
      },
      timestamp = SESSION_TIMESTAMP
    )

  @Test
  fun `user message keeps its display text id and attachment names`() {
    val messages: List<ChatMessage> = project(
      """{"id":"m1","role":"user","content":"wrapped prompt","displayText":"explain this","attachments":[{"name":"shot.png","mime":"image/png"}]}"""
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
  fun `list content joins its text parts when there is no display text`() {
    val messages: List<ChatMessage> = project(
      """{"role":"user","content":[{"type":"text","text":"first"},{"type":"image","mime":"image/png","filename":"a.png"},{"type":"text","text":"second"}]}"""
    )

    assertEquals("first\n\nsecond", messages.first().content)
  }

  @Test
  fun `assistant message rebuilds thinking tool calls and response in order`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","content":"done","modelName":"qwen2.5","thinking":"weighing options","tokenUsage":{"promptTokens":10,"completionTokens":4,"totalTokens":14,"contextTokens":10},"tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"Main.kt\"}"}}]}""",
      """{"role":"tool","alias":"Read","tool_call_id":"call-1","content":"file body"}"""
    )

    assertEquals(1, messages.size)
    val assistant: ChatMessage = messages.first()
    assertEquals("qwen2.5", assistant.modelName)
    assertEquals(14, assistant.tokenUsage?.totalTokens)

    val events: List<ChatEvent> = assistant.events
    assertEquals(3, events.size)
    assertTrue(events[0] is ChatEvent.Thinking)
    assertTrue(events[1] is ChatEvent.ToolCall)
    assertTrue(events[2] is ChatEvent.Response)

    val toolCall: ToolCallInfo = (events[1] as ChatEvent.ToolCall).info
    assertEquals("read_file", toolCall.toolName)
    assertEquals("Read", toolCall.alias)
    assertEquals("Main.kt", toolCall.arguments["path"])
    assertEquals("file body", toolCall.result)

    val toolBlock: RenderBlock.ToolCall = assistant.renderBlocks
      .filterIsInstance<RenderBlock.ToolCall>().first()
    assertEquals("Read", toolBlock.alias)
  }

  @Test
  fun `arguments already shaped as an object are read as-is`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","content":"","tool_calls":[{"function":{"name":"grep","arguments":{"pattern":"foo"}}}]}""",
      """{"role":"tool","content":"hit"}"""
    )

    val toolCall: ToolCallInfo = messages.first().events
      .filterIsInstance<ChatEvent.ToolCall>().first().info
    assertEquals("foo", toolCall.arguments["pattern"])
    assertEquals("grep", toolCall.alias)
  }

  @Test
  fun `an empty assistant row is skipped but its tool rows are consumed`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","content":"","tool_calls":[]}""",
      """{"role":"tool","content":"orphan"}""",
      """{"role":"assistant","content":"visible"}"""
    )

    assertEquals(1, messages.size)
    assertEquals("visible", messages.first().responseContent)
  }

  @Test
  fun `every rebuilt message carries the session timestamp`() {
    val messages: List<ChatMessage> = project(
      """{"role":"user","content":"hi"}""",
      """{"role":"assistant","content":"hello"}"""
    )

    assertTrue(messages.all { it.timestamp == SESSION_TIMESTAMP })
  }

  @Test
  fun `a turn stored as several assistant rows merges into one bubble`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"Main.kt\"}"}}],"modelName":"qwen2.5"}""",
      """{"role":"tool","alias":"Read","tool_call_id":"call-1","content":"file body"}""",
      """{"role":"assistant","content":"the file is empty","modelName":"qwen2.5","tokenUsage":{"promptTokens":10,"completionTokens":4,"totalTokens":14,"contextTokens":10}}"""
    )

    assertEquals(1, messages.size)
    val assistant: ChatMessage = messages.first()
    assertEquals("qwen2.5", assistant.modelName)
    assertEquals(14, assistant.tokenUsage?.totalTokens)

    val events: List<ChatEvent> = assistant.events
    assertEquals(2, events.size)
    assertTrue(events[0] is ChatEvent.ToolCall)
    assertTrue(events[1] is ChatEvent.Response)
    assertEquals("the file is empty", assistant.responseContent)
    assertEquals("file body", (events[0] as ChatEvent.ToolCall).info.result)
  }

  @Test
  fun `thinking across rows folds into one event before the tool calls`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","thinking":"step one","tool_calls":[{"id":"call-1","type":"function","function":{"name":"ls","arguments":"{}"}}]}""",
      """{"role":"tool","tool_call_id":"call-1","content":"a, b"}""",
      """{"role":"assistant","thinking":"step two","content":"done"}"""
    )

    assertEquals(1, messages.size)
    val events: List<ChatEvent> = messages.first().events
    assertEquals(3, events.size)
    assertEquals("step one\n\nstep two", (events[0] as ChatEvent.Thinking).content)
    assertTrue(events[1] is ChatEvent.ToolCall)
    assertEquals("done", (events[2] as ChatEvent.Response).content)
  }

  @Test
  fun `missing numeric fields default to zero and the last usage row wins`() {
    val partial: List<ChatMessage> = project(
      """{"role":"assistant","content":"x","tokenUsage":{"totalTokens":5}}""",
      """{"role":"assistant","content":"y"}"""
    )

    assertEquals(1, partial.size)
    assertEquals("x\n\ny", partial[0].responseContent)
    assertEquals(5, partial[0].tokenUsage?.totalTokens)
    assertEquals(0, partial[0].tokenUsage?.contextTokens)
  }

  @Test
  fun `an empty history maps to no messages`() {
    assertTrue(SessionProjection.toMessages(emptyList()).isEmpty())
  }

  @Test
  fun `a rebuilt delegate call is finished and carries no sub-chat state`() {
    val messages: List<ChatMessage> = project(
      """{"role":"assistant","tool_calls":[{"id":"call-1","type":"function","function":{"name":"delegate_task","arguments":"{\"task\":\"investigate\",\"title\":\"Investigate\"}"}}]}""",
      """{"role":"tool","alias":"Delegate","tool_call_id":"call-1","content":"sub-agent result"}"""
    )

    val toolCall: ToolCallInfo = messages.first().events
      .filterIsInstance<ChatEvent.ToolCall>().first().info
    assertEquals("delegate_task", toolCall.toolName)
    assertEquals("Delegate", toolCall.alias)
    assertEquals("Investigate", toolCall.arguments["title"])
    assertEquals(false, toolCall.arguments["pending"])
    assertNull(toolCall.arguments["startTimestamp"])
    assertNull(toolCall.arguments["subChatMessages"])
  }

  private companion object {
    const val SESSION_TIMESTAMP: Long = 1700000000000L
  }
}
