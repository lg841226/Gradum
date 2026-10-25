package gradum.agent

import gradum.skill.Skill
import gradum.utils.MAX_HISTORY_MESSAGES
import gradum.utils.takeLastTurns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ConversationHistory")

private val argumentsJsonFormat: Json = Json.Default

/**
 * Manages the conversation message list for an agent session.
 *
 * Owns the typed message records ([messages]) and provides focused operations
 * for adding user/assistant/tool messages, truncating to a turn-aware
 * budget, and extracting the last non-empty assistant result.
 */
class ConversationHistory {

  private val messages: MutableList<MessageRecord> = mutableListOf()

  val size: Int get() = messages.size

  fun isEmpty(): Boolean = messages.isEmpty()

  fun toList(): List<MessageRecord> = messages.toList()

  fun addAll(msgs: List<MessageRecord>) {
    messages.addAll(elements = msgs)
  }

  private val systemPrompt: SystemMessageRecord?
    get() = messages.firstOrNull { it is SystemMessageRecord } as? SystemMessageRecord

  fun getResult(): String? {
    val lastAssistant = messages.lastOrNull { message ->
      message is AssistantMessageRecord && message.content.isNotBlank()
    }
    return (lastAssistant as? AssistantMessageRecord)?.content
  }

  fun addSystemMessage(content: String) {
    messages.add(index = 0, element = systemMessage(content = content))
  }

  fun appendSystemMessage(content: String) {
    messages.add(systemMessage(content = content))
  }

  fun addUserMessage(
    text: String,
    displayText: String = "",
    messageId: String? = null,
    attachments: List<AttachmentPayload>
  ) {
    messages.add(
      userMessage {
        id = messageId
        this.text = text
        if (displayText.isNotBlank() && displayText != text) this.displayText = displayText

        for ((type, data, mime, filename, path, endLine, startLine, errorCount, symbolName, attachmentText) in attachments) {
          if (type == "image") {
            image(
              data = data,
              mime = mime ?: "image/jpeg",
              filename = filename ?: ""
            )
            continue
          }

          if (type == "file") {
            val filePath: String? = path?.takeIf { it.isNotBlank() }
            val rendered: String? = attachmentText?.takeIf { it.isNotBlank() }
            if (filePath == null || rendered == null) {
              logger.warn(
                "File attachment missing path or rendered text " +
                  "(symbolName=$symbolName, filename=$filename)"
              )
              continue
            }
            file(
              name = filename ?: filePath,
              path = filePath,
              rendered = rendered,
              endLine = endLine,
              startLine = startLine,
              errorCount = errorCount,
              symbolName = symbolName
            )
            continue
          }

          logger.warn(
            "Ignoring unsupported attachment type '$type' (filename=$filename)"
          )
        }
      }
    )
  }

  fun addAssistantMessage(
    content: String,
    modelName: String,
    thinking: String = "",
    tokenUsage: Map<String, Int>? = null,
    processedCalls: List<ProcessedToolCall>?
  ) {
    messages.add(
      assistantMessage {
        this.content = content
        this.modelName = modelName
        this.thinking = thinking.ifBlank { null }
        tokenUsage?.let { usage ->
          tokenUsage(
            promptTokens = usage["promptTokens"] ?: 0,
            completionTokens = usage["completionTokens"] ?: 0,
            contextTokens = usage["contextTokens"] ?: 0,
            totalTokens = usage["totalTokens"] ?: 0
          )
        }
        for (call: ProcessedToolCall in processedCalls.orEmpty()) {
          toolCall(
            name = call.callData.functionName,
            argumentsJson = argumentsJsonFormat.encodeToString(
              serializer<Map<String, JsonElement>>(),
              call.callData.functionArguments
            ),
            callId = call.callIdentifier
          )
        }
      }
    )
  }

  fun addToolMessage(
    alias: String,
    content: String,
    toolCallId: String?,
    durationMs: Long? = null,
    success: Boolean? = null,
    displayKind: String? = null
  ) {
    messages.add(
      toolResult {
        this.alias = alias
        this.content = content
        callId = toolCallId
        this.displayKind = displayKind
        this.durationMs = durationMs
        this.success = success
      }
    )
  }

  fun truncate(maxMessages: Int = MAX_HISTORY_MESSAGES) {
    val systemMsg: SystemMessageRecord? = systemPrompt
    val nonSystem = messages.filter { it !is SystemMessageRecord }
    if (nonSystem.size <= maxMessages) return

    val preSize: Int = nonSystem.size
    val keepFromEnd: List<MessageRecord> =
      takeLastTurns(messages = nonSystem, maxTurns = maxMessages, roleOf = { message -> message.role })
    if (keepFromEnd.size in (maxMessages + 1)..<preSize) {
      logger.warn(
        "Cannot fit conversation in $maxMessages messages " +
          "without breaking a turn; falling back to raw tail cut " +
          "(non-system size = $preSize)"
      )
    }

    messages.clear()
    systemMsg?.let { messages.add(it) }
    messages.addAll(elements = keepFromEnd)
  }

  fun toolMessageIndices(alias: String): List<Int> = messages.withIndex()
    .filter { (_, entry) -> entry is ToolMessageRecord && entry.alias == alias }
    .map { (index, _) -> index }

  fun recordAndCompact(
    skillInstance: Skill?, currentResult: Map<String, Any>, ownMessageIndices: List<Int>
  ): Map<String, Any> {
    return skillInstance?.recordAndCompactHistory(ownMessageIndices, currentResult, messages)
      ?: currentResult
  }
}
