package gradum.server.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that [SessionBubbleProjection] folds the stored raw history into the
 * bubble view the client renders: one bubble per user message and per assistant
 * turn, tool results paired to the call that issued them, a turn the transcript
 * writer would render empty skipped.
 */
class SessionBubbleProjectionTest {

  private fun project(vararg rawMessages: Map<String, Any>): List<Map<String, Any?>> =
    SessionBubbleProjection.toBubbles(rawMessages.toList())

  @Suppress("UNCHECKED_CAST")
  private fun List<Map<String, Any?>>.blocks(bubbleIndex: Int = 0): List<Map<String, Any?>> =
    this[bubbleIndex]["blocks"] as List<Map<String, Any?>>

  @Test
  fun `user bubble keeps its display text id and attachment names`() {
    val bubbles: List<Map<String, Any?>> = project(
      mapOf(
        "id" to "m1",
        "role" to "user",
        "content" to "wrapped prompt",
        "displayText" to "explain this",
        "attachments" to listOf(mapOf("name" to "shot.png", "mime" to "image/png"))
      )
    )

    assertEquals(1, bubbles.size)
    assertEquals("user", bubbles[0]["role"])
    assertEquals("explain this", bubbles[0]["content"])
    assertEquals("m1", bubbles[0]["id"])
    assertEquals(listOf(mapOf("name" to "shot.png")), bubbles[0]["attachments"])
  }

  @Test
  fun `list content joins its text parts when there is no display text`() {
    val bubbles: List<Map<String, Any?>> = project(
      mapOf(
        "role" to "user",
        "content" to listOf(
          mapOf("type" to "text", "text" to "first"),
          mapOf("type" to "image", "mime" to "image/png", "filename" to "a.png"),
          mapOf("type" to "text", "text" to "second")
        )
      )
    )

    assertEquals("first\n\nsecond", bubbles.first()["content"])
  }

  @Test
  fun `assistant turn rebuilds thinking tool calls and response in order`() {
    val bubbles: List<Map<String, Any?>> = project(
      mapOf(
        "id" to "m2",
        "role" to "assistant",
        "content" to "done",
        "modelName" to "qwen2.5",
        "thinking" to "weighing options",
        "tokenUsage" to mapOf(
          "promptTokens" to 10, "completionTokens" to 4, "totalTokens" to 14, "contextTokens" to 10
        ),
        "tool_calls" to listOf(
          mapOf(
            "id" to "call-1",
            "type" to "function",
            "function" to mapOf("name" to "read_file", "arguments" to """{"path":"Main.kt"}""")
          )
        )
      ),
      mapOf("role" to "tool", "alias" to "Read", "tool_call_id" to "call-1", "content" to "file body")
    )

    assertEquals(1, bubbles.size)
    val bubble: Map<String, Any?> = bubbles.first()
    assertEquals("assistant", bubble["role"])
    assertEquals("m2", bubble["id"])
    assertEquals("qwen2.5", bubble["modelName"])
    assertEquals(
      mapOf(
        "promptTokens" to 10, "completionTokens" to 4, "totalTokens" to 14, "contextTokens" to 10
      ),
      bubble["tokenUsage"]
    )

    val blocks: List<Map<String, Any?>> = bubbles.blocks()
    assertEquals(listOf("thinking", "toolCall", "response"), blocks.map { it["type"] })
    assertEquals("weighing options", blocks[0]["content"])

    val toolCall: Map<String, Any?> = blocks[1]
    assertEquals("Read", toolCall["alias"])
    assertEquals("call-1", toolCall["toolCallId"])
    assertEquals(true, toolCall["success"])
    assertEquals("file body", toolCall["result"])
    assertEquals(mapOf("path" to "Main.kt"), toolCall["arguments"])
    assertEquals("done", blocks[2]["content"])
  }

  @Test
  fun `arguments already shaped as a map are copied as-is`() {
    val bubbles: List<Map<String, Any?>> = project(
      mapOf(
        "role" to "assistant",
        "content" to "",
        "tool_calls" to listOf(
          mapOf("function" to mapOf("name" to "grep", "arguments" to mapOf("pattern" to "foo")))
        )
      ),
      mapOf("role" to "tool", "content" to "hit")
    )

    val toolCall: Map<String, Any?> = bubbles.blocks().single()
    assertEquals("grep", toolCall["alias"])
    assertEquals(mapOf("pattern" to "foo"), toolCall["arguments"])
  }

  @Test
  fun `an empty assistant row is skipped but its tool rows are consumed`() {
    val bubbles: List<Map<String, Any?>> = project(
      mapOf("role" to "assistant", "content" to "", "tool_calls" to emptyList<Any>()),
      mapOf("role" to "tool", "content" to "orphan"),
      mapOf("role" to "assistant", "content" to "visible")
    )

    assertEquals(1, bubbles.size)
    assertEquals("visible", bubbles.blocks().single()["content"])
  }

  @Test
  fun `a turn stored as several assistant rows merges into one bubble`() {
    val bubbles: List<Map<String, Any?>> = project(
      mapOf(
        "id" to "m2",
        "role" to "assistant",
        "modelName" to "qwen2.5",
        "tool_calls" to listOf(
          mapOf(
            "id" to "call-1",
            "type" to "function",
            "function" to mapOf("name" to "read_file", "arguments" to """{"path":"Main.kt"}""")
          )
        )
      ),
      mapOf("role" to "tool", "alias" to "Read", "tool_call_id" to "call-1", "content" to "file body"),
      mapOf(
        "role" to "assistant",
        "content" to "the file is empty",
        "modelName" to "qwen2.5",
        "tokenUsage" to mapOf(
          "promptTokens" to 10, "completionTokens" to 4, "totalTokens" to 14, "contextTokens" to 10
        )
      )
    )

    assertEquals(1, bubbles.size)
    val bubble: Map<String, Any?> = bubbles.first()
    assertEquals("m2", bubble["id"])
    assertEquals("qwen2.5", bubble["modelName"])
    assertEquals(14, (bubble["tokenUsage"] as Map<*, *>)["totalTokens"])

    val blocks: List<Map<String, Any?>> = bubbles.blocks()
    assertEquals(listOf("toolCall", "response"), blocks.map { it["type"] })
    assertEquals("file body", blocks[0]["result"])
    assertEquals("the file is empty", blocks[1]["content"])
  }

  @Test
  fun `thinking across rows folds into one block before the tool calls`() {
    val bubbles: List<Map<String, Any?>> = project(
      mapOf(
        "role" to "assistant",
        "thinking" to "step one",
        "tool_calls" to listOf(
          mapOf("id" to "call-1", "function" to mapOf("name" to "ls", "arguments" to "{}"))
        )
      ),
      mapOf("role" to "tool", "tool_call_id" to "call-1", "content" to "a, b"),
      mapOf("role" to "assistant", "thinking" to "step two", "content" to "done")
    )

    assertEquals(1, bubbles.size)
    val blocks: List<Map<String, Any?>> = bubbles.blocks()
    assertEquals(listOf("thinking", "toolCall", "response"), blocks.map { it["type"] })
    assertEquals("step one\n\nstep two", blocks[0]["content"])
    assertEquals("done", blocks[2]["content"])
  }

  @Test
  fun `missing usage fields default to zero and the last usage row wins`() {
    val bubbles: List<Map<String, Any?>> = project(
      mapOf("role" to "assistant", "content" to "x", "tokenUsage" to mapOf("totalTokens" to 5)),
      mapOf("role" to "assistant", "content" to "y")
    )

    assertEquals(1, bubbles.size)
    val bubble: Map<String, Any?> = bubbles.first()
    assertEquals("x\n\ny", bubbles.blocks().single()["content"])

    val usage: Map<*, *> = bubble["tokenUsage"] as Map<*, *>
    assertEquals(5, usage["totalTokens"])
    assertEquals(0, usage["contextTokens"])
  }

  @Test
  fun `an empty history maps to no bubbles`() {
    assertTrue(SessionBubbleProjection.toBubbles(emptyList()).isEmpty())
  }

  @Test
  fun `a folded delegate call is finished and carries no sub-chat state`() {
    val bubbles: List<Map<String, Any?>> = project(
      mapOf(
        "role" to "assistant",
        "tool_calls" to listOf(
          mapOf(
            "id" to "call-1",
            "function" to mapOf(
              "name" to "delegate_task",
              "arguments" to """{"task":"investigate","title":"Investigate"}"""
            )
          )
        )
      ),
      mapOf(
        "role" to "tool",
        "alias" to "Delegate",
        "tool_call_id" to "call-1",
        "content" to "sub-agent result"
      )
    )

    val toolCall: Map<String, Any?> = bubbles.blocks().single()
    assertEquals("Delegate", toolCall["alias"])

    val arguments: Map<*, *> = toolCall["arguments"] as Map<*, *>
    assertEquals("Investigate", arguments["title"])
    assertEquals(false, arguments["pending"])
    assertNull(arguments["startTimestamp"])
    assertNull(arguments["subChatMessages"])
  }
}
