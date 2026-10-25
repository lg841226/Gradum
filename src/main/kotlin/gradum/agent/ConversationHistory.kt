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
    displayText: String = "",
    messageId: String? = null,
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
      val rendered: String? = attachment.attachmentText?.takeIf { it.isNotBlank() }
      if (filePath == null || rendered == null) {
        logger.warn(
          "File attachment missing path or rendered text " +
            "(symbolName=${attachment.symbolName}, filename=${attachment.filename})"
        )
        continue
      }

      parts.add(mapOf("type" to "text", "text" to rendered))
      attachmentMetas.add(
        buildMap {
          put("name", attachment.filename ?: filePath)
          put("mime", "text/plain")
          attachment.startLine?.let { put("startLine", it) }
          attachment.endLine?.let { put("endLine", it) }
          attachment.symbolName?.let { put("symbolName", it) }
          put("path", filePath)
          attachment.errorCount?.let { put("errorCount", it) }
        }
      )
    }
    val userMessage = buildMap {
      if (!messageId.isNullOrBlank()) put("id", messageId)

      put("role", "user")
      put("content", if (attachmentMetas.isEmpty()) text else parts)
      put("ts", System.currentTimeMillis())

      if (displayText != text) put("displayText", displayText)
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
          put("ts", System.currentTimeMillis())
          put("tool_calls", toolCallsList)
          if (thinking.isNotBlank()) put("thinking", thinking)
          tokenUsage?.let { usage -> put("tokenUsage", usage) }
        }
      } else {
        buildMap {
          put("content", content)
          put("role", "assistant")
          put("modelName", modelName)
          put("ts", System.currentTimeMillis())
          if (thinking.isNotBlank()) put("thinking", thinking)
          tokenUsage?.let { usage -> put("tokenUsage", usage) }
        }
      }
    messages.add(assistantMessage)
  }

  fun addToolMessage(
    alias: String,
    content: String,
    provider: Provider,
    toolCallId: String?,
    durationMs: Long? = null,
    success: Boolean? = null,
    displayKind: String? = null
  ) {
    val toolMessage = buildMap {
      put("role", "tool")
      put("alias", alias)
      put("content", content)
      put("ts", System.currentTimeMillis())
      if (provider != Provider.OLLAMA && toolCallId != null)
        put("tool_call_id", toolCallId)
      if (displayKind != null) put("displayKind", displayKind)
      if (durationMs != null) put("durationMs", durationMs)
      if (success != null) put("success", success)
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
