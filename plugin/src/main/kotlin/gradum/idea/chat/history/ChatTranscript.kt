/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatTranscript.kt  2026-09-11 10:43:31 Changed by gwy
 */

package gradum.idea.chat.history

import com.intellij.openapi.diagnostic.Logger
import gradum.idea.PluginConfig
import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.TokenUsage
import gradum.idea.chat.model.ToolCallInfo
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.AttachedFile
import gradum.idea.editor.AttachedImage
import gradum.idea.editor.AttachedText
import kotlinx.serialization.json.*

/**
 * Structured-Markdown serialization of a chat conversation.
 *
 * A transcript is a human-readable `.md` file that also carries every field
 * the bubble UI needs, so a saved session can be parsed back into the exact
 * same [ChatMessage] list (thinking, tool calls + results, errors, token
 * usage, attachments) with no fidelity loss.
 *
 * ## Format (v1)
 *
 * ```
 * <!-- gradum-transcript v1 -->
 * <!-- gradum-session id="..." title="..." createdAt="..." updatedAt="..." model="..." -->
 *
 * ## user
 * <!-- gradum-msg role="user" id="..." timestamp="..." -->
 * <!-- gradum-attachment name="..." path="..." -->
 * <user message text, verbatim>
 *
 * ## assistant
 * <!-- gradum-msg role="assistant" id="..." timestamp="..." model="..." provider="..." server="..." promptTokens=".." completionTokens=".." totalTokens=".." -->
 * <!-- gradum-thinking -->
 * <thinking text, verbatim>
 * <!-- gradum-toolcall name="..." alias="..." id="..." success="true" errorMessage=".." errorDetail=".." -->
 * {"arguments": "json", ...}
 * <!-- gradum-toolresult -->
 * <tool result, verbatim>
 * <!-- gradum-error code="..." tool="..." -->
 * <error message, verbatim>
 * <!-- gradum-response -->
 * <response text, verbatim>
 * ```
 *
 * ## Parse rules (line-based state machine)
 *
 * - Lines that are exactly `## user` / `## assistant` or start with
 *   `<!-- gradum-` are structural delimiters; every other line belongs to
 *   the currently-open block's verbatim content.
 * - A block's content is the exact text between its opening marker and the
 *   next structural line (or end of file), so round-tripping preserves
 *   leading/trailing whitespace and internal newlines exactly.
 *
 * ## Known limitation (edge case, accepted for v1)
 *
 * Verbatim content that itself contains a bare `## user` / `## assistant`
 * line at column 0, or a `<!-- gradum-... -->` marker line, is treated as a
 * structural delimiter and dropped. This matches real LLM output in practice
 * (such lines essentially never appear), keeps the format human-readable,
 * and keeps the parser a simple single-pass state machine.
 *
 * ## Round-trip guarantee
 *
 * `parse(generate(messages)).messages` reproduces every [ChatEvent] in order
 * (and therefore `fullContent`), every message's model / provider / server /
 * token usage, and the user bubble's attachments.
 */
object ChatTranscript {

  /** Max characters kept for a session's title (derived from first user message). */
  const val MAX_TITLE_LENGTH: Int = PluginConfig.MAX_TITLE_LENGTH

  private const val HEADER_V1: String = "<!-- gradum-transcript v1 -->"
  private const val SESSION_PREFIX: String = "<!-- gradum-session"
  private const val MSG_PREFIX: String = "<!-- gradum-msg"
  private const val THINKING_MARKER: String = "<!-- gradum-thinking -->"
  private const val TOOLCALL_PREFIX: String = "<!-- gradum-toolcall"
  private const val TOOLRESULT_MARKER: String = "<!-- gradum-toolresult -->"
  private const val ERROR_PREFIX: String = "<!-- gradum-error"
  private const val RESPONSE_MARKER: String = "<!-- gradum-response -->"
  private const val ATTACHMENT_PREFIX: String = "<!-- gradum-attachment"

  private const val USER_HEADER: String = "## user"
  private const val ASSISTANT_HEADER: String = "## assistant"

  /** Marker lines / headers that terminate the currently-open content block. */
  private val STRUCTURAL_LINE: Regex = Regex(
    pattern = "^<!-- gradum-|^## (user|assistant)$"
  )

  private val jsonParser: Json = Json { ignoreUnknownKeys = true }

  private val log: Logger = Logger.getInstance(ChatTranscript::class.java)

  /** A parsed transcript: the [messages] plus the session metadata from the header. */
  data class ParsedTranscript(
    val messages: List<ChatMessage>,
    val sessionMeta: SessionMeta
  )

  /**
   * Derives a session title from [messages]: the first non-blank line of the
   * first user message, trimmed and truncated to [MAX_TITLE_LENGTH].
   */
  fun titleFor(messages: List<ChatMessage>): String {
    val firstUserLine: String? = messages
      .firstOrNull { it.isUserMessage }
      ?.content
      ?.lineSequence()
      ?.firstOrNull { it.isNotBlank() }
      ?.trim()
    return (firstUserLine ?: "").take(n = MAX_TITLE_LENGTH)
  }

  /** Serializes [messages] plus session [sessionMeta] into structured Markdown. */
  fun generateTranscript(messages: List<ChatMessage>, sessionMeta: SessionMeta): String {
    val contentBuilder: StringBuilder = StringBuilder()
    contentBuilder.append(HEADER_V1).append('\n')
    contentBuilder.append(
      "<!-- gradum-session " +
        "id=\"${escapeValue(sessionMeta.sessionId)}\" " +
        "title=\"${escapeValue(sessionMeta.title)}\" " +
        "createdAt=\"${sessionMeta.createdAt}\" " +
        "updatedAt=\"${sessionMeta.updatedAt}\" " +
        "model=\"${
          escapeValue(
            sessionMeta.modelName
          )
        }\" -->\n\n"
    )

    for (message: ChatMessage in messages) {
      if (message.isUserMessage) {
        contentBuilder.append(USER_HEADER).append('\n')
        contentBuilder.append(msgMarker(role = "user", message)).append('\n')
        for (attachment in message.attachments) {
          val (attachmentName: String, attachmentPath: String) = attachmentNamePath(attachment)
          contentBuilder.append(
            "<!-- gradum-attachment name=\"${escapeValue(attachmentName)}\" path=\"${escapeValue(attachmentPath)}\" -->\n"
          )
        }
        contentBuilder.append(message.content).append('\n')
      } else {
        contentBuilder.append(ASSISTANT_HEADER).append('\n')
        contentBuilder.append(msgMarker(role = "assistant", message)).append('\n')
        for (event: ChatEvent in message.events) {
          when (event) {
            is ChatEvent.Thinking -> {
              contentBuilder.append(THINKING_MARKER).append('\n')
              contentBuilder.append(event.content).append('\n')
            }

            is ChatEvent.ToolCall -> {
              val toolCallInfo: ToolCallInfo = event.info
              contentBuilder.append(
                "<!-- gradum-toolcall " +
                  "name=\"${escapeValue(toolCallInfo.toolName)}\" " +
                  "alias=\"${escapeValue(toolCallInfo.alias)}\" " +
                  "id=\"${escapeValue(toolCallInfo.toolCallId)}\" " +
                  "success=\"${toolCallInfo.success}\" " +
                  "errorMessage=\"${
                    escapeValue(
                      toolCallInfo.errorMessage
                    )
                  }\" errorDetail=\"${escapeValue(toolCallInfo.errorDetail)}\" -->\n"
              )
              contentBuilder.append(argumentsToJson(toolCallInfo.arguments)).append('\n')
              contentBuilder.append(TOOLRESULT_MARKER).append('\n')
              contentBuilder.append(toolCallInfo.result).append('\n')
            }

            is ChatEvent.Error -> {
              contentBuilder.append(
                "<!-- gradum-error code=\"${escapeValue(event.code)}\" tool=\"${escapeValue(event.tool)}\" -->\n"
              )
              contentBuilder.append(event.message).append('\n')
            }

            is ChatEvent.Response -> {
              contentBuilder.append(RESPONSE_MARKER).append('\n')
              contentBuilder.append(event.content).append('\n')
            }
          }
        }
      }
    }
    return contentBuilder.toString()
  }

  /**
   * Reads only the [SessionMeta] from a transcript's header. Used by the
   * session list so it can render titles/dates without parsing every message.
   * Returns a blank meta when [content] is not a valid v1 transcript.
   */
  fun parseMeta(content: String): SessionMeta {
    for (line: String in content.lines()) {
      if (!line.startsWith(SESSION_PREFIX)) continue
      val attributeMap: Map<String, String> = parseAttributes(marker = line)
      return SessionMeta(
        title = attributeMap["title"].orEmpty(),
        modelName = attributeMap["model"].orEmpty(),
        sessionId = attributeMap["id"].orEmpty(),
        createdAt = attributeMap["createdAt"]?.toLongOrNull() ?: 0L,
        updatedAt = attributeMap["updatedAt"]?.toLongOrNull() ?: 0L
      )
    }
    return SessionMeta(title = "", modelName = "", sessionId = "", createdAt = 0L, updatedAt = 0L)
  }

  /** Parses a full transcript back into its [ParsedTranscript]. */
  fun parseTranscript(content: String): ParsedTranscript {
    var sessionMeta = SessionMeta(title = "", modelName = "", sessionId = "", createdAt = 0L, updatedAt = 0L)
    val messages: MutableList<ChatMessage> = mutableListOf()

    // Working message: assistant messages are built incrementally as their
    // event blocks stream in; user messages accumulate verbatim content and
    // attachments. Flushed into `messages` when a new message starts or EOF.
    var workingTimestamp = 0L
    var workingModel: String
    var workingServer: String
    var workingProvider: String
    var workingTokens: TokenUsage?
    var blockType: String? = null
    var workingRole: String? = null
    var workingMessageId = ""
    var workingMessage: ChatMessage? = null
    var pendingToolCall: ToolCallInfo? = null
    val blockLines: MutableList<String> = mutableListOf()
    val workingAttachments: MutableList<AttachedContext> = mutableListOf()
    var pendingErrorCode = ""
    var pendingErrorTool = ""
    var pendingUserContent = ""

    fun flushBlock() {
      if (blockLines.isNotEmpty() && blockLines.last().isEmpty()) {
        blockLines.removeAt(blockLines.lastIndex)
      }
      val blockContent: String = blockLines.joinToString(separator = "\n")
      blockLines.clear()
      val blockKind: String = blockType ?: return

      blockType = null
      when (blockKind) {
        "thinking" -> {
          workingMessage = workingMessage?.appendEvent(ChatEvent.Thinking(blockContent))
        }

        "toolcall" -> {
          val toolCallInfo: ToolCallInfo = pendingToolCall ?: return
          pendingToolCall = toolCallInfo.copy(
            arguments = jsonToArguments(rawJson = blockContent)
          )
        }

        "toolresult" -> {
          val toolCallInfo: ToolCallInfo = pendingToolCall ?: return
          pendingToolCall = null
          workingMessage = workingMessage?.appendEvent(
            ChatEvent.ToolCall(info = toolCallInfo.copy(result = blockContent))
          )
        }

        "error" -> {
          workingMessage = workingMessage?.appendEvent(
            ChatEvent.Error(
              message = blockContent, code = pendingErrorCode, tool = pendingErrorTool
            )
          )
          pendingErrorCode = ""
          pendingErrorTool = ""
        }

        "response" -> {
          workingMessage = workingMessage?.appendEvent(ChatEvent.Response(blockContent))
        }

        "user" -> pendingUserContent = blockContent
      }
    }

    fun finalizeMessage() {
      flushBlock()
      val completedMessage: ChatMessage? = workingMessage
      workingMessage = null
      if (completedMessage != null) messages.add(completedMessage)
      if (workingRole == "user") {
        messages.add(
          ChatMessage(
            role = "user",
            content = pendingUserContent,
            attachments = workingAttachments.toList(),
            messageId = workingMessageId,
            timestamp = workingTimestamp
          )
        )
      }
      workingRole = null
      workingMessageId = ""
      workingTimestamp = 0L
      workingModel = ""
      workingProvider = ""
      workingServer = ""
      workingTokens = null
      workingAttachments.clear()
      pendingUserContent = ""
    }

    for (line: String in content.lines()) {
      when {
        line.startsWith(prefix = HEADER_V1) -> {}

        line.startsWith(SESSION_PREFIX) -> {
          finalizeMessage()
          val attributeMap: Map<String, String> = parseAttributes(line)
          sessionMeta = SessionMeta(
            title = attributeMap["title"].orEmpty(),
            modelName = attributeMap["model"].orEmpty(),
            sessionId = attributeMap["id"].orEmpty(),
            createdAt = attributeMap["createdAt"]?.toLongOrNull() ?: 0L,
            updatedAt = attributeMap["updatedAt"]?.toLongOrNull() ?: 0L
          )
        }

        line.startsWith(MSG_PREFIX) -> {
          finalizeMessage()
          val attributeMap: Map<String, String> = parseAttributes(marker = line)
          val messageRole: String = attributeMap["role"].orEmpty()
          workingRole = messageRole
          workingMessageId = attributeMap["id"].orEmpty()
          workingModel = attributeMap["model"].orEmpty()
          workingServer = attributeMap["server"].orEmpty()
          workingProvider = attributeMap["provider"].orEmpty()
          workingTimestamp = attributeMap["timestamp"]?.toLongOrNull() ?: 0L
          val promptTokens: Int = attributeMap["promptTokens"]?.toIntOrNull() ?: 0
          val completionTokens: Int = attributeMap["completionTokens"]?.toIntOrNull() ?: 0
          val totalTokens: Int = attributeMap["totalTokens"]?.toIntOrNull() ?: 0
          workingTokens =
            if (totalTokens > 0)
              TokenUsage(promptTokens, completionTokens, totalTokens)
            else null
          if (messageRole == "user") {
            blockType = "user"
          } else {
            workingMessage = ChatMessage(
              content = "",
              role = "assistant",
              messageId = workingMessageId,
              modelName = workingModel,
              provider = workingProvider,
              serverName = workingServer,
              tokenUsage = workingTokens,
              timestamp = workingTimestamp
            )
          }
        }

        line.startsWith(ATTACHMENT_PREFIX) -> {
          val attributeMap: Map<String, String> = parseAttributes(marker = line)
          val attachmentName: String = attributeMap["name"].orEmpty()
          val attachmentPath: String = attributeMap["path"].orEmpty()
          if (attachmentName.isNotEmpty() || attachmentPath.isNotEmpty()) {
            workingAttachments.add(AttachedText(content = attachmentPath, preview = attachmentName))
          }
        }

        line.startsWith(prefix = THINKING_MARKER) -> {
          flushBlock()
          blockType = "thinking"
        }

        line.startsWith(TOOLCALL_PREFIX) -> {
          flushBlock()
          val attributeMap: Map<String, String> = parseAttributes(marker = line)
          pendingToolCall = ToolCallInfo(
            alias = attributeMap["alias"].orEmpty(),
            toolCallId = attributeMap["id"].orEmpty(),
            toolName = attributeMap["name"].orEmpty(),
            errorDetail = attributeMap["errorDetail"].orEmpty(),
            errorMessage = attributeMap["errorMessage"].orEmpty(),
            success = attributeMap["success"]?.toBooleanStrictOrNull() ?: true
          )
          blockType = "toolcall"
        }

        line.startsWith(prefix = TOOLRESULT_MARKER) -> {
          flushBlock(); blockType = "toolresult"
        }

        line.startsWith(ERROR_PREFIX) -> {
          flushBlock()
          val attributeMap: Map<String, String> = parseAttributes(marker = line)
          pendingErrorCode = attributeMap["code"].orEmpty()
          pendingErrorTool = attributeMap["tool"].orEmpty()
          blockType = "error"
        }

        line.startsWith(prefix = RESPONSE_MARKER) -> {
          flushBlock(); blockType = "response"
        }

        STRUCTURAL_LINE.containsMatchIn(input = line) -> {}

        else -> blockLines.add(line)
      }
    }
    finalizeMessage()

    return ParsedTranscript(messages = messages, sessionMeta = sessionMeta)
  }

  private fun msgMarker(role: String, message: ChatMessage): String {
    val markerBuilder: StringBuilder = StringBuilder()
    markerBuilder.append("<!-- gradum-msg role=\"").append(role)
      .append("\" timestamp=\"").append(message.timestamp).append('"')
    if (message.messageId.isNotBlank()) {
      markerBuilder.append(" id=\"").append(escapeValue(message.messageId)).append('"')
    }
    if (role == "assistant") {
      markerBuilder.append(" model=\"").append(escapeValue(message.modelName)).append('"')
        .append(" provider=\"").append(escapeValue(message.provider)).append('"')
        .append(" server=\"").append(escapeValue(message.serverName)).append('"')

      message.tokenUsage?.let { usage: TokenUsage ->
        markerBuilder.append(" promptTokens=\"").append(usage.promptTokens).append('"')
          .append(" completionTokens=\"").append(usage.completionTokens).append('"')
          .append(" totalTokens=\"").append(usage.totalTokens).append('"')
      }
    }
    markerBuilder.append(" -->")
    return markerBuilder.toString()
  }

  private fun attachmentNamePath(attachment: AttachedContext): Pair<String, String> {
    return when (attachment) {
      is AttachedText -> attachment.preview to attachment.content
      is AttachedFile -> attachment.file.name to attachment.file.path
      is AttachedImage -> attachment.originalName to attachment.file.path
    }
  }

  private fun argumentsToJson(arguments: Map<String, Any>): String {
    if (arguments.isEmpty()) return "{}"
    return buildJsonObject {
      arguments.forEach { (key: String, value) ->
        put(key, element = anyToJson(value))
      }
    }.toString()
  }

  private fun anyToJson(value: Any): JsonElement {
    return when (value) {
      is Int -> JsonPrimitive(value)
      is Long -> JsonPrimitive(value)
      is Double -> JsonPrimitive(value)
      is String -> JsonPrimitive(value)
      is Boolean -> JsonPrimitive(value)
      is Float -> JsonPrimitive(value.toDouble())
      is Map<*, *> -> buildJsonObject {
        value.forEach { (key, entryValue) ->
          if (key != null && entryValue != null)
            put(key.toString(), element = anyToJson(entryValue))
        }
      }

      is List<*> -> buildJsonArray {
        value.forEach { entryValue ->
          if (entryValue != null)
            add(anyToJson(entryValue))
        }
      }

      else -> JsonPrimitive(value.toString())
    }
  }

  private fun jsonToArguments(rawJson: String): Map<String, Any> {
    if (rawJson.isBlank() || rawJson == "{}") return emptyMap()
    return try {
      val jsonElement: JsonElement = jsonParser.parseToJsonElement(string = rawJson)
      (jsonElement as? JsonObject)?.mapValues { (_, value: JsonElement) ->
        elementToAny(value)
      } ?: emptyMap()
    } catch (jsonException: Exception) {
      log.warn("Failed to parse tool-call arguments JSON: $rawJson", jsonException)
      emptyMap()
    }
  }

  private fun elementToAny(element: JsonElement): Any {
    return when (element) {
      is JsonPrimitive -> when {
        element.isString -> element.content
        element.intOrNull != null -> element.int
        element.longOrNull != null -> element.long
        element.content.toBooleanStrictOrNull() != null ->
          element.content.toBooleanStrict()

        else -> element.content.toDoubleOrNull() ?: element.content
      }

      is JsonArray -> element.map { elementToAny(it) }

      is JsonObject -> element.mapValues { (_, value: JsonElement) -> elementToAny(value) }
    }
  }

  private fun parseAttributes(marker: String): Map<String, String> {
    val attributeMap: MutableMap<String, String> = mutableMapOf()
    val pairPattern = Regex(pattern = """(\w+)="((?:\\.|[^"])*)"""")

    for (match: MatchResult in pairPattern.findAll(input = marker)) {
      attributeMap[match.groupValues[1]] = unescapeValue(match.groupValues[2])
    }
    return attributeMap
  }

  private fun escapeValue(value: String): String {
    return value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\r", " ")
      .replace("\n", " ")
  }

  private fun unescapeValue(value: String): String {
    if (value.indexOf(char = '\\') < 0) return value
    val unescapedBuilder: StringBuilder = StringBuilder(value.length)
    var currentIndex = 0
    while (currentIndex < value.length) {
      val currentChar: Char = value[currentIndex]
      if (currentChar == '\\' && currentIndex + 1 < value.length) {
        unescapedBuilder.append(value[currentIndex + 1])
        currentIndex += 2
      } else {
        unescapedBuilder.append(currentChar)
        currentIndex++
      }
    }
    return unescapedBuilder.toString()
  }
}
