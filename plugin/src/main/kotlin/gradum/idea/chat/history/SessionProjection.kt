package gradum.idea.chat.history

import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.TokenUsage
import gradum.idea.chat.model.ToolCallInfo
import gradum.idea.editor.AttachedText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Rebuilds the bubble UI's [ChatMessage] list from the server's
 * `session-state.json` projection, i.e. the raw agent-history maps.
 *
 * The shapes line up with the Markdown renderer's own reading of that history
 * (`SessionTranscriptWriter`): system prompts were already dropped server-side,
 * a `tool` result is folded into the tool-call row that issued it, and a user
 * message keeps only its attachment names. A message the server would render
 * empty is skipped.
 *
 * This is the cold-state reader: on reopening a session and on adopting the
 * projection after a stream gap, the client shows exactly what the server
 * persisted instead of re-deriving it from events.
 */
object SessionProjection {

  private val jsonParser: Json = Json { ignoreUnknownKeys = true }

  /**
   * Maps [projectedMessages] into renderable messages. Every reconstructed
   * message shares [timestamp] because the history carries no per-message clock
   * (the transcript writer makes the same choice).
   */
  fun toMessages(projectedMessages: List<JsonObject>, timestamp: Long = 0L): List<ChatMessage> {
    val rebuiltMessages: MutableList<ChatMessage> = mutableListOf()
    var messageIndex = 0
    while (messageIndex < projectedMessages.size) {
      val projectedMessage: JsonObject = projectedMessages[messageIndex]
      when (projectedMessage.text("role")) {
        "user" -> {
          rebuiltMessages.add(userMessage(projectedMessage, timestamp))
          messageIndex++
        }

        "assistant" -> {
          val (assistantMessage: ChatMessage?, nextIndex: Int) =
            assistantMessage(projectedMessage, projectedMessages, messageIndex, timestamp)
          if (assistantMessage != null) rebuiltMessages.add(assistantMessage)
          messageIndex = nextIndex
        }

        else -> messageIndex++
      }
    }
    return rebuiltMessages
  }

  private fun userMessage(message: JsonObject, timestamp: Long): ChatMessage {
    val attachments: List<AttachedText> = attachmentNames(message).map { attachmentName: String ->
      AttachedText(content = "", preview = attachmentName)
    }
    return ChatMessage(
      role = "user",
      timestamp = timestamp,
      attachments = attachments,
      content = userText(message),
      messageId = message.text("id")
    )
  }

  /**
   * Rebuilds one assistant message and reports the index to resume at, having
   * consumed the `tool` result rows that immediately follow its tool calls.
   */
  private fun assistantMessage(
    message: JsonObject,
    allMessages: List<JsonObject>,
    startIndex: Int,
    timestamp: Long
  ): Pair<ChatMessage?, Int> {
    val thinkingText: String = message.text("thinking")
    val responseText: String = message.text("content")
    val toolCalls: JsonArray = message["tool_calls"] as? JsonArray ?: JsonArray(emptyList())

    val toolResults: MutableList<JsonObject> = mutableListOf()
    var lookahead: Int = startIndex + 1
    while (lookahead < allMessages.size && allMessages[lookahead].text("role") == "tool") {
      toolResults.add(allMessages[lookahead])
      lookahead++
    }

    val events: MutableList<ChatEvent> = mutableListOf()
    if (thinkingText.isNotBlank()) events.add(ChatEvent.Thinking(thinkingText))
    events.addAll(toolCallEvents(toolCalls, toolResults))
    if (thinkingText.isBlank() && responseText.isBlank() && events.isEmpty()) return null to lookahead
    if (responseText.isNotBlank()) events.add(ChatEvent.Response(responseText))

    var rebuiltMessage: ChatMessage = ChatMessage(
      role = "assistant",
      timestamp = timestamp,
      modelName = message.text("modelName"),
      tokenUsage = tokenUsage(message)
    )
    events.forEach { event: ChatEvent -> rebuiltMessage = rebuiltMessage.appendEvent(event) }
    return rebuiltMessage to lookahead
  }

  /**
   * Pairs each tool call with its result row. The raw history stores the
   * arguments the model produced and the result as an opaque string, and no
   * success flag, so a rebuilt call is always marked successful — the same
   * fidelity the Markdown transcript offers on reload.
   */
  private fun toolCallEvents(toolCalls: JsonArray, toolResults: List<JsonObject>): List<ChatEvent> {
    val unmatchedResults: MutableList<JsonObject> = toolResults.toMutableList()
    return toolCalls.mapNotNull { callElement: JsonElement ->
      val call: JsonObject = callElement as? JsonObject ?: return@mapNotNull null
      val function: JsonObject = call["function"] as? JsonObject ?: JsonObject(emptyMap())
      val toolName: String = function.text("name").ifBlank { call.text("name") }
      val callId: String = call.text("id")
      val toolResult: JsonObject? = takeResult(unmatchedResults, callId)
      ChatEvent.ToolCall(
        info = ToolCallInfo(
          toolName = toolName,
          alias = toolResult?.text("alias").orEmpty().ifBlank { toolName },
          toolCallId = callId,
          success = true,
          result = toolResult?.text("content").orEmpty(),
          arguments = toolArguments(function["arguments"] ?: call["arguments"])
        )
      )
    }
  }

  /** Removes and returns the result matching [callId], falling back to the first, mirroring the transcript writer. */
  private fun takeResult(remainingResults: MutableList<JsonObject>, callId: String): JsonObject? {
    val matched: JsonObject? = when {
      callId.isNotBlank() -> remainingResults.firstOrNull { it.text("tool_call_id") == callId }
        ?: remainingResults.firstOrNull()

      else -> remainingResults.firstOrNull()
    }
    if (matched != null) remainingResults.remove(matched)
    return matched
  }

  private fun userText(message: JsonObject): String {
    val displayText: String = message.text("displayText")
    if (displayText.isNotBlank()) return displayText
    return when (val content: JsonElement? = message["content"]) {
      is JsonPrimitive -> content.content
      is JsonArray -> content.filterIsInstance<JsonObject>()
        .filter { it.text("type") == "text" }
        .mapNotNull { it["text"] as? JsonPrimitive }
        .joinToString(separator = "\n\n") { it.content }

      else -> ""
    }
  }

  private fun attachmentNames(message: JsonObject): List<String> {
    val attachments: JsonArray = message["attachments"] as? JsonArray ?: return emptyList()
    return attachments.filterIsInstance<JsonObject>()
      .map { it.text("name") }
      .filter { it.isNotEmpty() }
  }

  private fun tokenUsage(message: JsonObject): TokenUsage? {
    val usage: JsonObject = message["tokenUsage"] as? JsonObject ?: return null
    val totalTokens: Int = usage.number("totalTokens")
    if (totalTokens <= 0) return null
    return TokenUsage(
      promptTokens = usage.number("promptTokens"),
      completionTokens = usage.number("completionTokens"),
      totalTokens = totalTokens,
      contextTokens = usage.number("contextTokens")
    )
  }

  /** Arguments are a JSON-encoded string for most providers and a nested object for Ollama. */
  private fun toolArguments(element: JsonElement?): Map<String, Any> {
    return when (element) {
      is JsonPrimitive -> parseArguments(element.content)
      is JsonObject -> element.mapValues { (_, value: JsonElement) -> toPlainValue(value) }
      else -> emptyMap()
    }
  }

  private fun parseArguments(rawJson: String): Map<String, Any> {
    if (rawJson.isBlank()) return emptyMap()
    return try {
      val parsed: JsonElement = jsonParser.parseToJsonElement(rawJson)
      (parsed as? JsonObject)?.mapValues { (_, value: JsonElement) -> toPlainValue(value) } ?: emptyMap()
    } catch (_: Exception) {
      emptyMap()
    }
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

  /** Lenient string field: missing or JSON-null both read as empty. */
  private fun JsonObject.text(key: String): String {
    val value: JsonPrimitive = this[key] as? JsonPrimitive ?: return ""
    return value.contentOrNull.orEmpty()
  }

  /** Numeric field read as an [Int], defaulting to 0 for missing or non-numeric values. */
  private fun JsonObject.number(key: String): Int {
    val value: JsonPrimitive = this[key] as? JsonPrimitive ?: return 0
    return value.content.toIntOrNull() ?: 0
  }
}
