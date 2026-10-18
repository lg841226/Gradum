package gradum.idea.chat.history

import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.model.TokenUsage
import gradum.idea.editor.AttachedText
import kotlinx.serialization.json.*

/**
 * Decodes the server's cold projection (`GET /session/state`) into the bubble
 * UI's [ChatMessage] list.
 *
 * The server has already folded the stored history: one entry per user message
 * and one per assistant turn, each turn's tool results paired to the call that
 * issued them, each entry's [RenderBlock] ordering resolved. Nothing of the
 * agent-history payload (OpenAI vs Ollama tool arguments, `tool` rows, blank
 * thinking) is known on this side.
 */
object SessionProjection {

  /**
   * Maps [projectedMessages] into renderable messages. Every reconstructed
   * message shares [timestamp] because the history carries no per-message clock
   * (the transcript writer makes the same choice).
   */
  fun toMessages(projectedMessages: List<JsonObject>, timestamp: Long = 0L): List<ChatMessage> =
    projectedMessages.mapNotNull { bubble: JsonObject -> toMessage(bubble, timestamp) }

  private fun toMessage(bubble: JsonObject, timestamp: Long): ChatMessage? {
    return when (bubble.text("role")) {
      "user" -> ChatMessage(
        role = "user",
        timestamp = timestamp,
        attachments = attachments(bubble),
        content = bubble.text("content"),
        messageId = bubble.text("id")
      )

      "assistant" -> ChatMessage(
        role = "assistant",
        timestamp = timestamp,
        renderBlocks = (bubble["blocks"] as? JsonArray)
          ?.mapNotNull { block: JsonElement -> toBlock(block) }
          ?: emptyList(),
        modelName = bubble.text("modelName"),
        messageId = bubble.text("id"),
        tokenUsage = tokenUsage(bubble)
      )

      else -> null
    }
  }

  private fun toBlock(element: JsonElement): RenderBlock? {
    val block: JsonObject = element as? JsonObject ?: return null
    return when (block.text("type")) {
      "thinking" -> RenderBlock.Thinking(block.text("content"))
      "response" -> RenderBlock.Response(block.text("content"))
      "toolCall" -> RenderBlock.ToolCall(
        alias = block.text("alias"),
        success = block.boolean("success"),
        result = block.text("result"),
        toolCallId = block.text("toolCallId"),
        arguments = toolArguments(block["arguments"])
      )

      else -> null
    }
  }

  private fun attachments(bubble: JsonObject): List<AttachedText> {
    val attachments: JsonArray = bubble["attachments"] as? JsonArray ?: return emptyList()
    return attachments.filterIsInstance<JsonObject>()
      .map { it.text("name") }
      .filter { it.isNotEmpty() }
      .map { attachmentName: String -> AttachedText(content = "", preview = attachmentName) }
  }

  private fun tokenUsage(bubble: JsonObject): TokenUsage? {
    val usage: JsonObject = bubble["tokenUsage"] as? JsonObject ?: return null
    val totalTokens: Int = usage.number("totalTokens")
    if (totalTokens <= 0) return null
    return TokenUsage(
      promptTokens = usage.number("promptTokens"),
      completionTokens = usage.number("completionTokens"),
      totalTokens = totalTokens,
      contextTokens = usage.number("contextTokens")
    )
  }

  private fun toolArguments(element: JsonElement?): Map<String, Any> {
    val arguments: JsonObject = element as? JsonObject ?: return emptyMap()
    return arguments.mapValues { (_, value: JsonElement) -> toPlainValue(value) }
  }

  private fun toPlainValue(element: JsonElement): Any {
    return when (element) {
      is JsonPrimitive -> when {
        element.isString -> element.content
        else -> element.booleanOrNull ?: element.intOrNull ?: element.longOrNull
        ?: element.content.toDoubleOrNull() ?: element.content
      }

      is JsonArray -> element.map { toPlainValue(it) }
      is JsonObject -> element.mapValues { (_, value: JsonElement) -> toPlainValue(value) }
    }
  }

  private fun JsonObject.text(key: String): String {
    val value: JsonPrimitive = this[key] as? JsonPrimitive ?: return ""
    return value.contentOrNull.orEmpty()
  }

  private fun JsonObject.boolean(key: String): Boolean {
    val value: JsonPrimitive = this[key] as? JsonPrimitive ?: return false
    return value.booleanOrNull ?: false
  }

  private fun JsonObject.number(key: String): Int {
    val value: JsonPrimitive = this[key] as? JsonPrimitive ?: return 0
    return value.content.toIntOrNull() ?: 0
  }
}
