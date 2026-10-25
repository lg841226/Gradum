package gradum.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One row of the agent conversation, as produced by [ConversationHistory] and
 * consumed by the persistence layer. The serialized shape of these classes is
 * the single source of truth for stored history; no legacy keys, no Map mirrors.
 */
@Serializable
sealed interface MessageRecord {
  val role: String
}

/**
 * Stored stand-in for an encrypted user or assistant row: [cipher] carries the
 * encrypted JSON of the original record, [id] preserves the user message id
 * so rewind can still locate the row without decrypting.
 */
@Serializable
@SerialName("sealed")
data class SealedMessageRecord(
  val cipher: String,
  val id: String? = null
) : MessageRecord {
  override val role: String get() = "sealed"
}

@Serializable
@SerialName("system")
data class SystemMessageRecord(
  val content: String
) : MessageRecord {
  override val role: String get() = "system"
}

@Serializable
@SerialName("user")
data class UserMessageRecord(
  val ts: Long,
  val id: String? = null,
  val content: MessageContent,
  val displayText: String? = null,
  val attachments: List<AttachmentMeta> = emptyList()
) : MessageRecord {
  override val role: String get() = "user"
}

@Serializable
@SerialName("assistant")
data class AssistantMessageRecord(
  val ts: Long,
  val content: String,
  val modelName: String,
  val thinking: String? = null,
  val tokenUsage: TokenUsageRecord? = null,
  val toolCalls: List<ToolCallRecord> = emptyList()
) : MessageRecord {
  override val role: String get() = "assistant"
}

@Serializable
@SerialName("tool")
data class ToolMessageRecord(
  val ts: Long,
  val alias: String,
  val content: String,
  val callId: String? = null,
  val durationMs: Long? = null,
  val success: Boolean? = null,
  val displayKind: String? = null
) : MessageRecord {
  override val role: String get() = "tool"
}

@Serializable
sealed interface MessageContent

@Serializable
@SerialName("plain")
data class PlainContent(
  val text: String
) : MessageContent

@Serializable
@SerialName("parts")
data class PartsContent(
  val parts: List<ContentPart>
) : MessageContent

@Serializable
sealed interface ContentPart

@Serializable
@SerialName("text")
data class TextPart(
  val text: String
) : ContentPart

@Serializable
@SerialName("image")
data class ImagePart(
  val data: String,
  val mime: String,
  val filename: String
) : ContentPart

@Serializable
data class AttachmentMeta(
  val name: String,
  val mime: String,
  val path: String? = null,
  val endLine: Int? = null,
  val startLine: Int? = null,
  val errorCount: Int? = null,
  val symbolName: String? = null
)

@Serializable
data class ToolCallRecord(
  val name: String,
  val argumentsJson: String,
  val callId: String? = null
)

@Serializable
data class TokenUsageRecord(
  val totalTokens: Int,
  val promptTokens: Int,
  val contextTokens: Int,
  val completionTokens: Int
)

/**
 * Builders are the only way to construct records outside tests: every field
 * crosses a validation gate here, so dirty data can neither enter nor leave.
 */
fun systemMessage(content: String): SystemMessageRecord = SystemMessageRecord(content = content)

fun userMessage(configure: UserMessageBuilder.() -> Unit): UserMessageRecord =
  UserMessageBuilder().apply(configure).build()

fun assistantMessage(configure: AssistantMessageBuilder.() -> Unit): AssistantMessageRecord =
  AssistantMessageBuilder().apply(configure).build()

fun toolResult(configure: ToolResultBuilder.() -> Unit): ToolMessageRecord =
  ToolResultBuilder().apply(configure).build()

class UserMessageBuilder internal constructor() {
  var id: String? = null
  var text: String = ""
  var displayText: String? = null
  var ts: Long = System.currentTimeMillis()

  private val parts: MutableList<ContentPart> = mutableListOf()
  private val attachmentMetas: MutableList<AttachmentMeta> = mutableListOf()

  fun image(data: String, mime: String, filename: String) {
    require(data.isNotBlank()) { "image data must not be blank" }
    require(mime.isNotBlank()) { "image mime must not be blank" }
    parts.add(ImagePart(data = data, mime = mime, filename = filename))
    attachmentMetas.add(AttachmentMeta(name = filename, mime = mime))
  }

  fun file(
    name: String,
    path: String,
    rendered: String,
    endLine: Int? = null,
    startLine: Int? = null,
    errorCount: Int? = null,
    symbolName: String? = null
  ) {
    require(name.isNotBlank()) { "file attachment name must not be blank" }
    require(path.isNotBlank()) { "file attachment path must not be blank" }
    require(rendered.isNotBlank()) { "file attachment rendered text must not be blank" }
    if (startLine != null && endLine != null) {
      require(startLine <= endLine) { "line range inverted: $startLine > $endLine" }
    }
    parts.add(TextPart(text = rendered))
    attachmentMetas.add(
      AttachmentMeta(
        name = name, mime = "text/plain", path = path,
        endLine = endLine, startLine = startLine,
        errorCount = errorCount, symbolName = symbolName
      )
    )
  }

  internal fun build(): UserMessageRecord {
    val content: MessageContent =
      if (parts.isEmpty()) PlainContent(text = text)
      else PartsContent(parts = listOf(TextPart(text = text)) + parts.toList())
    return UserMessageRecord(
      ts = ts,
      id = id?.takeIf { it.isNotBlank() },
      content = content,
      displayText = displayText?.takeIf { it.isNotBlank() },
      attachments = attachmentMetas.toList()
    )
  }
}

class AssistantMessageBuilder internal constructor() {
  var content: String = ""
  var modelName: String = ""
  var thinking: String? = null
  var ts: Long = System.currentTimeMillis()

  private val toolCalls: MutableList<ToolCallRecord> = mutableListOf()
  private var usage: TokenUsageRecord? = null

  fun toolCall(name: String, argumentsJson: String, callId: String? = null) {
    require(name.isNotBlank()) { "tool call name must not be blank" }
    require(isJsonObject(argumentsJson)) { "tool call arguments must be a JSON object" }
    toolCalls.add(
      ToolCallRecord(name = name, argumentsJson = argumentsJson, callId = callId?.takeIf { it.isNotBlank() })
    )
  }

  fun tokenUsage(
    promptTokens: Int,
    completionTokens: Int,
    contextTokens: Int = 0,
    totalTokens: Int = promptTokens + completionTokens
  ) {
    require(promptTokens >= 0 && completionTokens >= 0 && totalTokens >= 0 && contextTokens >= 0) {
      "token usage values must not be negative"
    }
    usage = TokenUsageRecord(
      totalTokens = totalTokens,
      promptTokens = promptTokens,
      contextTokens = contextTokens,
      completionTokens = completionTokens
    )
  }

  internal fun build(): AssistantMessageRecord = AssistantMessageRecord(
    ts = ts,
    content = content,
    tokenUsage = usage,
    modelName = modelName,
    toolCalls = toolCalls.toList(),
    thinking = thinking?.takeIf { it.isNotBlank() }
  )

  private fun isJsonObject(raw: String): Boolean =
    try {
      Json.parseToJsonElement(raw).let { it is kotlinx.serialization.json.JsonObject }
    } catch (_: Exception) {
      false
    }
}

class ToolResultBuilder internal constructor() {
  var alias: String = ""
  var content: String = ""
  var callId: String? = null
  var durationMs: Long? = null
  var success: Boolean? = null
  var displayKind: String? = null
  var ts: Long = System.currentTimeMillis()

  internal fun build(): ToolMessageRecord {
    require(alias.isNotBlank()) { "tool result alias must not be blank" }
    val recordedDuration: Long? = durationMs
    require(recordedDuration == null || recordedDuration >= 0) { "durationMs must not be negative" }
    return ToolMessageRecord(
      ts = ts,
      alias = alias,
      content = content,
      success = success,
      durationMs = recordedDuration,
      callId = callId?.takeIf { it.isNotBlank() },
      displayKind = displayKind?.takeIf { it.isNotBlank() }
    )
  }
}

fun UserMessageRecord.userText(): String =
  when (val contentValue: MessageContent = content) {
    is PlainContent -> contentValue.text
    is PartsContent -> contentValue.parts.filterIsInstance<TextPart>()
      .joinToString(separator = "\n\n") { part -> part.text }
  }

val messageRecordJson: Json = Json {
  ignoreUnknownKeys = true
  classDiscriminator = "role"
}

fun MessageRecord.encodeJson(): String =
  messageRecordJson.encodeToString(MessageRecord.serializer(), this)

/**
 * Strict read gate: a payload that does not decode into a known record is
 * corruption, not an empty message; the caller drops the row and logs.
 */
fun decodeMessageRecord(payload: String): MessageRecord? =
  try {
    messageRecordJson.decodeFromString(MessageRecord.serializer(), payload)
  } catch (_: Exception) {
    null
  }

/**
 * Renders a record into the classic row map the session sidecar and the
 * sub-agent conversation event publish to clients. This is the published
 * contract shape, kept stable on purpose; records are the internal truth.
 */
fun MessageRecord.toStoredMap(): Map<String, Any?> =
  when (this) {
    is SystemMessageRecord -> mapOf(
      "role" to role, "content" to content
    )

    is UserMessageRecord -> buildMap {
      id?.let { put("id", it) }
      put("role", role)
      put("content", content.toStoredValue())
      put("ts", ts)
      displayText?.let { put("displayText", it) }
      if (attachments.isNotEmpty()) put("attachments", attachments.map { it.toStoredMap() })
    }

    is AssistantMessageRecord -> buildMap {
      put("role", role)
      put("content", content)
      put("modelName", modelName)
      put("ts", ts)
      if (toolCalls.isNotEmpty()) put("tool_calls", toolCalls.map { it.toStoredMap() })
      thinking?.let { put("thinking", it) }
      tokenUsage?.let { put("tokenUsage", it.toStoredMap()) }
    }

    is ToolMessageRecord -> buildMap {
      put("role", role)
      put("alias", alias)
      put("content", content)
      put("ts", ts)
      callId?.let { put("tool_call_id", it) }
      displayKind?.let { put("displayKind", it) }
      durationMs?.let { put("durationMs", it) }
      success?.let { put("success", it) }
    }

    is SealedMessageRecord -> mapOf(
      "role" to role, "cipher" to cipher, "id" to id
    )
  }

private fun MessageContent.toStoredValue(): Any =
  when (this) {
    is PlainContent -> text
    is PartsContent -> parts.map { part ->
      when (part) {
        is TextPart -> mapOf("type" to "text", "text" to part.text)
        is ImagePart -> mapOf(
          "type" to "image", "data" to part.data,
          "mime" to part.mime, "filename" to part.filename
        )
      }
    }
  }

private fun AttachmentMeta.toStoredMap(): Map<String, Any?> = buildMap {
  put("name", name)
  put("mime", mime)
  path?.let { put("path", it) }
  startLine?.let { put("startLine", it) }
  endLine?.let { put("endLine", it) }
  symbolName?.let { put("symbolName", it) }
  errorCount?.let { put("errorCount", it) }
}

private fun ToolCallRecord.toStoredMap(): Map<String, Any?> = mapOf(
  "id" to callId,
  "type" to "function",
  "function" to mapOf("name" to name, "arguments" to argumentsJson)
)

private fun TokenUsageRecord.toStoredMap(): Map<String, Any?> = mapOf(
  "promptTokens" to promptTokens,
  "completionTokens" to completionTokens,
  "totalTokens" to totalTokens,
  "contextTokens" to contextTokens
)
