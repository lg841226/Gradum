/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ConversationHistory.kt  2026-08-31 19:21:55 Changed by gwy
 */

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

  fun addUserMessage(text: String, attachments: List<AttachmentPayload>) {
    if (attachments.isEmpty()) {
      messages.add(mapOf("role" to "user", "content" to text))
      return
    }
    val parts = mutableListOf<Map<String, Any>>(
      mapOf("type" to "text", "text" to text)
    )
    for ((type, mime, data, filename) in attachments) {
      if (type != "image") {
        logger.warn("Ignoring unsupported attachment type '$type' (filename=$filename)")
        continue
      }
      parts.add(
        mapOf(
          "type" to "image",
          "data" to data,
          "mime" to (mime ?: "image/jpeg"),
          "filename" to (filename ?: "")
        )
      )
    }
    messages.add(mapOf("role" to "user", "content" to parts))
  }

  fun addAssistantMessage(
    content: String,
    modelName: String,
    provider: Provider,
    processedCalls: List<ProcessedToolCall>?
  ) {
    val assistantMessage =
      if (!processedCalls.isNullOrEmpty()) {
        val toolCallsList =
          if (provider == Provider.OPENAI) {
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
        mapOf(
          "role" to "assistant",
          "content" to content,
          "modelName" to modelName,
          "tool_calls" to toolCallsList
        )
      } else {
        mapOf(
          "content" to content,
          "role" to "assistant",
          "modelName" to modelName
        )
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
      if (provider == Provider.OPENAI && toolCallId != null)
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

  /**
   * Returns the indices of all tool messages with the given [alias].
   * Used by [ToolExecutor] to identify which history entries to compact.
   */
  fun toolMessageIndices(alias: String): List<Int> = messages.withIndex()
    .filter { (_, entry) -> entry["role"] == "tool" && entry["alias"] == alias }
    .map { (index, _) -> index }

  /**
   * Delegates to [Skill.recordAndCompactHistory] with the internal message list.
   * Returns the processed result (maybe stripped of volatile keys).
   */
  fun recordAndCompact(
    skillInstance: Skill?, currentResult: Map<String, Any>, ownMessageIndices: List<Int>
  ): Map<String, Any> {
    return skillInstance?.recordAndCompactHistory(ownMessageIndices, currentResult, messages)
      ?: currentResult
  }
}
