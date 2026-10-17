package gradum.idea.chat.history

import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.TokenUsage
import gradum.idea.chat.model.ToolCallInfo
import gradum.idea.editor.AttachedText
import kotlinx.serialization.json.*

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
            mergedAssistantMessage(messageIndex, timestamp, projectedMessages)
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
   * Rebuilds one assistant bubble from every consecutive `assistant` / `tool`
   * row starting at [startIndex], reporting the index to resume at.
   *
   * A single turn is persisted as several assistant rows : one per model step
   * (tool-call rows first, the final text row last) : but the live view renders
   * the whole turn as one bubble, because the event stream keeps appending to
   * the same message. The rebuild merges the rows the same way: all thinking
   * folds into one event, every tool call pairs with its result row, and the
   * response texts concatenate, so a reopened session shows one bubble instead
   * of a split "fold group + reply" pair.
   */
  private fun mergedAssistantMessage(
    startIndex: Int,
    timestamp: Long,
    allMessages: List<JsonObject>
  ): Pair<ChatMessage?, Int> {
    val thinkingParts: MutableList<String> = mutableListOf()
    val responseParts: MutableList<String> = mutableListOf()
    val callRows: MutableList<Pair<JsonArray, List<JsonObject>>> = mutableListOf()
    var modelName = ""
    var messageId = ""
    var usage: TokenUsage? = null

    var index: Int = startIndex
    while (index < allMessages.size) {
      val row: JsonObject = allMessages[index]
      when (row.text("role")) {
        "assistant" -> {
          row.text("thinking").takeIf { it.isNotBlank() }?.let { thinkingParts.add(it) }
          row.text("content").takeIf { it.isNotBlank() }?.let { responseParts.add(it) }
          if (modelName.isBlank()) modelName = row.text("modelName")
          if (messageId.isBlank()) messageId = row.text("id")
          row["tokenUsage"]?.let { usage = tokenUsage(row) ?: usage }

          val toolCalls: JsonArray = row["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
          if (toolCalls.isNotEmpty()) {
            val results: MutableList<JsonObject> = mutableListOf()
            var lookahead: Int = index + 1
            while (lookahead < allMessages.size && allMessages[lookahead].text("role") == "tool") {
              results.add(allMessages[lookahead])
              lookahead++
            }
            callRows.add(toolCalls to results)
            index = lookahead
          } else {
            index++
          }
        }

        "tool" -> index++

        else -> return finishedAssistantMessage(
          timestamp, messageId, modelName, usage, thinkingParts, responseParts, callRows
        ) to index
      }
    }
    return finishedAssistantMessage(
      timestamp, messageId, modelName, usage, thinkingParts, responseParts, callRows
    ) to index
  }

  /** Assembles the merged rows into one bubble, or null when the turn is empty. */
  private fun finishedAssistantMessage(
    timestamp: Long,
    messageId: String,
    modelName: String,
    usage: TokenUsage?,
    thinkingParts: List<String>,
    responseParts: List<String>,
    callRows: List<Pair<JsonArray, List<JsonObject>>>
  ): ChatMessage? {
    val thinkingText: String = thinkingParts.joinToString(separator = "\n\n")
    val responseText: String = responseParts.joinToString(separator = "\n\n")
    if (thinkingText.isBlank() && responseText.isBlank() && callRows.isEmpty()) return null

    var rebuiltMessage = ChatMessage(
      role = "assistant",
      timestamp = timestamp,
      modelName = modelName,
      messageId = messageId,
      tokenUsage = usage
    )
    if (thinkingText.isNotBlank())
      rebuiltMessage = rebuiltMessage.appendEvent(ChatEvent.Thinking(thinkingText))

    callRows.forEach { (toolCalls: JsonArray, toolResults: List<JsonObject>) ->
      toolCallEvents(toolCalls, toolResults).forEach { event: ChatEvent ->
        rebuiltMessage = rebuiltMessage.appendEvent(event)
      }
    }
    if (responseText.isNotBlank())
      rebuiltMessage = rebuiltMessage.appendEvent(ChatEvent.Response(responseText))

    return rebuiltMessage
  }

  /**
   * Pairs each tool call with its result row. The raw history stores the
   * arguments the model produced and the result as an opaque string, and no
   * success flag, so a rebuilt call is always marked successful: the same
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

  private fun JsonObject.text(key: String): String {
    val value: JsonPrimitive = this[key] as? JsonPrimitive ?: return ""
    return value.contentOrNull.orEmpty()
  }

  private fun JsonObject.number(key: String): Int {
    val value: JsonPrimitive = this[key] as? JsonPrimitive ?: return 0
    return value.content.toIntOrNull() ?: 0
  }
}
