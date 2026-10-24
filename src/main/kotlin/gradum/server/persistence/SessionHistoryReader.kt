package gradum.server.persistence

import gradum.utils.JsonUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// Raw-history tool name of the delegate skill
private const val DELEGATE_TOOL_NAME: String = "delegate_task"

/** A user row, as both the transcript and the bubble view read it */
data class UserRow(
  val id: String,
  val text: String,
  val attachmentNames: List<String>
)

/** One tool call of an assistant row, with its result row already paired */
data class ProjectedToolCall(
  val name: String,
  /** Alias recorded on the result row; blank when history carries none */
  val alias: String,
  val callId: String,
  /** Raw arguments: JSON string (OpenAI shape) or map (Ollama shape) */
  val arguments: Any?,
  val result: String
)

/** One assistant row's content */
data class AssistantRow(
  val id: String,
  val modelName: String,
  val thinking: String,
  val response: String,
  val toolCalls: List<ProjectedToolCall>,
  /** Token usage of this row, or null when none with positive total */
  val tokenUsage: Map<String, Any>?
)

/** A read assistant row plus the row to resume at, past trailing `tool` rows consumed */
data class AssistantSpan(
  val row: AssistantRow,
  val nextIndex: Int
)

// A folded assistant turn: its bubble (absent when turn would render empty) and next row
private data class FoldedTurn(
  val bubble: Map<String, Any?>?,
  val nextIndex: Int
)

/**
 * The one reader of a session's raw agent history.
 *
 * `ContextManager` persists history as raw rows (one per model step, plus a
 * `tool` row per result). Both renderings start here: `SessionTranscriptWriter`
 * emits Markdown markers row by row, while [toBubbles] folds consecutive rows
 * into the single bubble per turn the client renders. Payload quirks (OpenAI vs
 * Ollama tool arguments, alias only on result row, unpaired results) handled here.
 */
object SessionHistoryReader {

  private val lenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * Reads stored sidecar's `messages` array into map form the readers take.
   * Numbers arrive as `Long`/`Double` here but as `Int` on save path,
   * so every reader goes through `Number`.
   */
  fun readStoredMessages(rawMessages: List<JsonElement>): List<Map<String, Any?>> =
    rawMessages.mapNotNull { element: JsonElement ->
      stringKeyedOrNull(JsonUtil.fromJsonElement(element))
    }

  /** Reads a user row: its id, display text and attachment names */
  fun readUser(message: Map<String, Any?>): UserRow {
    return UserRow(
      id = message["id"] as? String ?: "",
      text = userText(message),
      attachmentNames = attachmentNames(message)
    )
  }

  /**
   * Reads assistant row at [index] and consumes its tool results: every
   * consecutive following `tool` row is paired to a call by `tool_call_id`
   * (falling back to first unclaimed result). Row without tool calls consumes
   * nothing, so returned [AssistantSpan.nextIndex] is `index + 1`.
   */
  fun readAssistant(messages: List<Map<String, Any?>>, index: Int): AssistantSpan {
    val message: Map<String, Any?> = messages[index]
    val toolCalls: List<Map<*, *>> = readListOfMaps(message["tool_calls"])
    val results: MutableList<Map<*, *>> = mutableListOf()
    var nextIndex: Int = index + 1
    if (toolCalls.isNotEmpty()) {
      while (nextIndex < messages.size && messages[nextIndex]["role"] == "tool") {
        results.add(messages[nextIndex])
        nextIndex++
      }
    }

    return AssistantSpan(
      row = AssistantRow(
        id = message["id"] as? String ?: "",
        modelName = message["modelName"] as? String ?: "",
        thinking = message["thinking"] as? String ?: "",
        response = message["content"] as? String ?: "",
        toolCalls = toolCalls.map { call: Map<*, *> -> projectedCall(call, results) },
        tokenUsage = positiveUsage(message)
      ),
      nextIndex = nextIndex
    )
  }

  /**
   * Folds raw rows into client's bubble view: one bubble per user message,
   * one per turn (consecutive assistant rows merged, each row's thinking /
   * tool calls / response concatenated). Empty turns dropped. Shape mirrors
   * plugin's `RenderBlock`.
   */
  fun toBubbles(messages: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val bubbles: MutableList<Map<String, Any?>> = mutableListOf()
    var index = 0
    while (index < messages.size) {
      when (messages[index]["role"] as? String) {
        "user" -> {
          bubbles.add(userBubble(messages[index]))
          index++
        }

        "assistant" -> {
          val turn: FoldedTurn = foldedTurn(messages, index)
          turn.bubble?.let { bubbles.add(it) }
          index = turn.nextIndex
        }

        // `tool` row with no assistant row above it pairs with nothing
        else -> index++
      }
    }
    return bubbles
  }

  /**
   * Arguments of one tool call as JSON object string: OpenAI history carries
   * JSON verbatim, Ollama-shaped carries a map
   */
  fun argumentsJson(rawArguments: Any?): String {
    return when (rawArguments) {
      is String -> rawArguments.ifBlank { "{}" }
      is Map<*, *> -> {
        val stringKeyed: Map<String, Any?> = rawArguments.entries
          .associate { (key, value) -> key.toString() to value }
        if (stringKeyed.isEmpty()) "{}" else JsonUtil.encodeMap(input = stringKeyed)
      }

      else -> "{}"
    }
  }

  // Folds every consecutive assistant / tool row from [startIndex] into one turn
  private fun foldedTurn(messages: List<Map<String, Any?>>, startIndex: Int): FoldedTurn {
    val thinkingParts: MutableList<String> = mutableListOf()
    val responseParts: MutableList<String> = mutableListOf()
    val toolCalls: MutableList<ProjectedToolCall> = mutableListOf()
    var modelName = ""
    var id = ""
    var tokenUsage: Map<String, Any>? = null

    var index = startIndex
    while (index < messages.size) {
      when (messages[index]["role"] as? String) {
        "assistant" -> {
          val span: AssistantSpan = readAssistant(messages, index)
          val row: AssistantRow = span.row
          if (row.thinking.isNotBlank()) thinkingParts.add(row.thinking)
          if (row.response.isNotBlank()) responseParts.add(row.response)
          toolCalls.addAll(row.toolCalls)
          if (modelName.isBlank()) modelName = row.modelName
          if (id.isBlank()) id = row.id
          if (row.tokenUsage != null) tokenUsage = row.tokenUsage
          index = span.nextIndex
        }

        "tool" -> index++
        else -> break
      }
    }

    val thinking: String = thinkingParts.joinToString(separator = "\n\n")
    val response: String = responseParts.joinToString(separator = "\n\n")
    val bubble: Map<String, Any?>? =
      if (thinking.isNotBlank() || response.isNotBlank() || toolCalls.isNotEmpty()) {
        assistantBubble(id, modelName, thinking, toolCalls, response, tokenUsage)
      } else {
        null
      }
    return FoldedTurn(bubble = bubble, nextIndex = index)
  }

  private fun userBubble(message: Map<String, Any?>): Map<String, Any?> {
    val user: UserRow = readUser(message)
    return mapOf(
      "role" to "user",
      "id" to user.id,
      "content" to user.text,
      "attachments" to user.attachmentNames.map { attachmentName: String ->
        mapOf("name" to attachmentName)
      }
    )
  }

  private fun assistantBubble(
    id: String,
    modelName: String,
    thinking: String,
    toolCalls: List<ProjectedToolCall>,
    response: String,
    tokenUsage: Map<String, Any>?
  ): Map<String, Any?> {
    val blocks: MutableList<Map<String, Any?>> = mutableListOf()
    if (thinking.isNotBlank())
      blocks.add(mapOf("type" to "thinking", "content" to thinking))

    for (call: ProjectedToolCall in toolCalls) {
      val arguments: Map<String, Any?> = argumentsObject(call.arguments) +
        if (call.name == DELEGATE_TOOL_NAME) mapOf("pending" to false) else emptyMap()
      blocks.add(
        mapOf(
          "type" to "toolCall",
          "toolCallId" to call.callId,
          "alias" to call.alias.ifBlank { call.name },
          "success" to true,
          "result" to call.result,
          "arguments" to arguments
        )
      )
    }

    if (response.isNotBlank())
      blocks.add(mapOf("type" to "response", "content" to response))

    val bubble: MutableMap<String, Any?> = mutableMapOf(
      "role" to "assistant",
      "id" to id,
      "modelName" to modelName,
      "blocks" to blocks
    )
    if (tokenUsage != null) bubble["tokenUsage"] = tokenUsage
    return bubble
  }

  // Pairs one tool call with its result row. Raw history records no success
  // flag, so rebuilt call is always successful (same fidelity as transcript).
  private fun projectedCall(
    call: Map<*, *>,
    remainingResults: MutableList<Map<*, *>>
  ): ProjectedToolCall {
    val function: Map<*, *> = call["function"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val callId: String = call["id"] as? String ?: ""
    val toolResult: Map<*, *>? = when {
      callId.isNotBlank() -> remainingResults.firstOrNull { it["tool_call_id"] == callId }
        ?: remainingResults.firstOrNull()

      else -> remainingResults.firstOrNull()
    }
    if (toolResult != null) remainingResults.remove(element = toolResult)

    return ProjectedToolCall(
      name = (function["name"] as? String ?: "").ifBlank { call["name"] as? String ?: "" },
      alias = toolResult?.get("alias") as? String ?: "",
      callId = callId,
      arguments = function["arguments"] ?: call["arguments"],
      result = toolResult?.get("content") as? String ?: ""
    )
  }

  // Token usage when row carries positive total, else null
  private fun positiveUsage(message: Map<String, Any?>): Map<String, Any>? {
    val usage: Map<*, *> = message["tokenUsage"] as? Map<*, *> ?: return null
    val totalTokens: Int = (usage["totalTokens"] as? Number)?.toInt() ?: 0
    if (totalTokens <= 0) return null
    return mapOf(
      "promptTokens" to ((usage["promptTokens"] as? Number)?.toInt() ?: 0),
      "completionTokens" to ((usage["completionTokens"] as? Number)?.toInt() ?: 0),
      "totalTokens" to totalTokens,
      "contextTokens" to ((usage["contextTokens"] as? Number)?.toInt() ?: 0)
    )
  }

  // Tool-call arguments as JSON object: JSON string parsed, map copied
  private fun argumentsObject(rawArguments: Any?): Map<String, Any?> {
    val decoded: Any? = when (rawArguments) {
      is String -> rawArguments.takeIf { it.isNotBlank() }
        ?.let { runCatching { lenientJson.parseToJsonElement(it) }.getOrNull() }
        ?.let { JsonUtil.fromJsonElement(it) }

      is Map<*, *> -> rawArguments
      else -> null
    }
    return stringKeyedOrNull(decoded) ?: emptyMap()
  }

  // Map with toString-ed keys, so decoded JSON object and Kotlin map share one shape
  private fun stringKeyedOrNull(value: Any?): Map<String, Any?>? =
    (value as? Map<*, *>)
      ?.entries
      ?.associate { (key, entryValue) -> key.toString() to entryValue }

  private fun userText(message: Map<String, Any?>): String {
    val displayText: Any? = message["displayText"]
    if (displayText is String) return displayText
    return when (val content: Any? = message["content"]) {
      is String -> content
      is List<*> -> content.filterIsInstance<Map<*, *>>()
        .firstOrNull { it["type"] == "text" }?.get("text") as? String ?: ""

      else -> ""
    }
  }

  // Attachment display names recorded on user message (base64 payloads excluded)
  private fun attachmentNames(message: Map<String, Any?>): List<String> {
    return readListOfMaps(message["attachments"]).mapNotNull { attachment: Map<*, *> ->
      val attachmentName: String = attachment["name"] as? String ?: ""
      attachmentName.takeIf { it.isNotEmpty() }
    }
  }

  private fun readListOfMaps(rawList: Any?): List<Map<*, *>> =
    (rawList as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
}
