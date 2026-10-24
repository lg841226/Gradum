package gradum.agent

import gradum.Provider
import gradum.skill.Skill
import gradum.utils.JsonUtil
import gradum.utils.MAX_HISTORY_MESSAGES
import gradum.utils.takeLastTurns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("ConversationHistory")

/**
 * Manages the conversation message list for an agent session.
 *
 * Owns the raw message list ([messages]) and provides focused operations
 * for adding user/assistant/tool messages, truncating to a turn-aware
 * budget, and extracting the last non-empty assistant result.
 */
class ConversationHistory {

  private val messages: MutableList<Map<String, Any>> = mutableListOf()

  val size: Int get() = messages.size

  fun isEmpty(): Boolean = messages.isEmpty()

  fun toList(): List<Map<String, Any>> = messages.toList()

  fun addAll(msgs: List<Map<String, Any>>) {
    messages.addAll(elements = msgs)
  }

  val systemPrompt: Map<String, Any>?
    get() = messages.firstOrNull { it["role"] == "system" }

  fun getResult(): String? {
    val lastAssistant = messages.lastOrNull { msg ->
      msg["role"] == "assistant" && msg.containsKey("content")
        && (msg["content"] as? String)?.isNotBlank() == true
    }
    return lastAssistant?.let { it["content"] as? String }
  }

  fun addSystemMessage(content: String) {
    messages.add(
      index = 0, element = mapOf(
        "role" to "system", "content" to content
      )
    )
  }

  fun appendSystemMessage(content: String) {
    messages.add(mapOf("role" to "system", "content" to content))
  }

  fun addUserMessage(
    text: String,
    messageId: String? = null,
    displayText: String = "",
    projectRoot: String = ".",
    attachments: List<AttachmentPayload>
  ) {
    val attachmentMetas: MutableList<Map<String, Any>> = mutableListOf()
    val parts = mutableListOf<Map<String, Any>>(
      mapOf("type" to "text", "text" to text)
    )
    for (attachment in attachments) {
      if (attachment.type != "image" && attachment.type != "file") {
        logger.warn("Ignoring unsupported attachment type '${attachment.type}' (filename=${attachment.filename})")
        continue
      }
      if (attachment.type == "image") {
        parts.add(
          mapOf(
            "type" to "image",
            "data" to attachment.data,
            "mime" to (attachment.mime ?: "image/jpeg"),
            "filename" to (attachment.filename ?: "")
          )
        )
        attachmentMetas.add(
          mapOf(
            "name" to (attachment.filename ?: ""),
            "mime" to (attachment.mime ?: "image/jpeg")
          )
        )
        continue
      }

      val filePath: String? = attachment.path?.takeIf { it.isNotBlank() }
      if (filePath == null) {
        logger.warn("File attachment missing path (symbolName=${attachment.symbolName})")
        continue
      }
      val injected: String = injectFileAttachmentText(
        filePath = filePath, attachment = attachment
      )
      if (injected.isBlank()) continue

      parts.add(mapOf("type" to "text", "text" to injected))
      attachmentMetas.add(
        buildMap {
          put("name", attachment.filename ?: filePath)
          put("mime", "text/plain")
          attachment.startLine?.let { put("startLine", it) }
          attachment.endLine?.let { put("endLine", it) }
          attachment.symbolName?.let { put("symbolName", it) }
          put("path", filePath)
        }
      )
    }
    val userMessage = buildMap {
      if (!messageId.isNullOrBlank()) put("id", messageId)

      put("role", "user")
      put("content", if (attachmentMetas.isEmpty()) text else parts)

      if (displayText.isNotBlank() && displayText != text) put("displayText", displayText)
      if (attachmentMetas.isNotEmpty()) put("attachments", attachmentMetas.toList())
    }
    messages.add(userMessage)
  }

  fun addAssistantMessage(
    content: String,
    modelName: String,
    provider: Provider,
    thinking: String = "",
    tokenUsage: Map<String, Int>? = null,
    processedCalls: List<ProcessedToolCall>?
  ) {
    val assistantMessage =
      if (!processedCalls.isNullOrEmpty()) {
        val toolCallsList =
          if (provider != Provider.OLLAMA) {
            processedCalls.map { call ->
              mapOf(
                "id" to call.callIdentifier,
                "type" to "function",
                "function" to mapOf(
                  "name" to call.callData.functionName,
                  "arguments" to Json.encodeToString(
                    serializer<Map<String, JsonElement>>(),
                    value = call.callData.functionArguments
                  )
                )
              )
            }
          } else {
            processedCalls.map { call ->
              mapOf(
                "function" to mapOf(
                  "name" to call.callData.functionName,
                  "arguments" to call.callData.functionArguments.entries.associate {
                    it.key to JsonUtil.fromJsonElement(it.value)
                  }
                )
              )
            }
          }
        buildMap {
          put("role", "assistant")
          put("content", content)
          put("modelName", modelName)
          put("tool_calls", toolCallsList)
          if (thinking.isNotBlank()) put("thinking", thinking)
          tokenUsage?.let { usage -> put("tokenUsage", usage) }
        }
      } else {
        buildMap {
          put("content", content)
          put("role", "assistant")
          put("modelName", modelName)
          if (thinking.isNotBlank()) put("thinking", thinking)
          tokenUsage?.let { usage -> put("tokenUsage", usage) }
        }
      }
    messages.add(assistantMessage)
  }

  fun addToolMessage(
    alias: String, content: String, toolCallId: String?, provider: Provider
  ) {
    val toolMessage = buildMap {
      put("role", "tool")
      put("alias", alias)
      put("content", content)
      if (provider != Provider.OLLAMA && toolCallId != null)
        put("tool_call_id", toolCallId)
    }
    messages.add(toolMessage)
  }

  fun truncate(maxMessages: Int = MAX_HISTORY_MESSAGES) {
    val systemMsg = systemPrompt
    val nonSystem = messages.filter { it["role"] != "system" }
    if (nonSystem.size <= maxMessages) return

    val preSize = nonSystem.size
    val keepFromEnd = takeLastTurns(messages = nonSystem, maxTurns = maxMessages)
    if (keepFromEnd.size in (maxMessages + 1)..<preSize) {
      logger.warn(
        "Cannot fit conversation in $maxMessages messages " +
          "without breaking a turn; falling back to raw tail cut " +
          "(non-system size = $preSize)"
      )
    }

    messages.clear()
    if (systemMsg != null) messages.add(systemMsg)
    messages.addAll(elements = keepFromEnd)
  }

  fun toolMessageIndices(alias: String): List<Int> = messages.withIndex()
    .filter { (_, entry) -> entry["role"] == "tool" && entry["alias"] == alias }
    .map { (index, _) -> index }

  fun recordAndCompact(
    skillInstance: Skill?, currentResult: Map<String, Any>, ownMessageIndices: List<Int>
  ): Map<String, Any> {
    return skillInstance?.recordAndCompactHistory(ownMessageIndices, currentResult, messages)
      ?: currentResult
  }
}

/**
 * Creates a reference-only FileAttachment tag for the user message.
 *
 * Unlike the previous implementation, this does NOT read file content from disk.
 * The model must call read_file with the given path and lineRange to fetch
 * current content at execution time. This avoids stale inline snapshots when
 * the user edits the file between attachment and model response.
 */
private fun injectFileAttachmentText(
  filePath: String, attachment: AttachmentPayload
): String {
  val label = attachment.symbolName?.takeIf { it.isNotBlank() } ?: File(filePath).name
  val rangeLabel =
    if (attachment.startLine != null && attachment.endLine != null)
      "${attachment.startLine} ~ ${attachment.endLine}"
    else ""
  return buildString {
    append("<FileAttachment path=\"$filePath\"")
    if (attachment.symbolName?.isNotBlank() == true) append(" symbol=\"$label\"")
    if (rangeLabel.isNotBlank()) append(" lineRange=\"$rangeLabel\"")
    append(" content=\"reference\"/>")
  }
}
