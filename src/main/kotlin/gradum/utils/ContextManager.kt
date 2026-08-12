/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ContextManager.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.utils

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("ContextManager")
private val jsonFormatter: Json = Json { prettyPrint = true }

/**
 * Maximum number of messages to keep in persisted context history.
 *
 * Single source of truth is [MAX_HISTORY_MESSAGES] in
 * [gradum.utils.MessageHistoryTruncator], shared with the in-memory
 * truncation in `Agent` so both budgets can never drift apart.
 */
const val MAX_CONTEXT_MESSAGES: Int = MAX_HISTORY_MESSAGES

/**
 * Persists the agent's conversation history and the set of files that have
 * been fully read, so a later session can resume where the previous one
 * left off without re-reading the same files.
 */
class ContextManager(private val outputDirectory: Path) {

  private val contextFilePath: Path = outputDirectory.resolve("context.json")
  private val contextTempFilePath: Path = outputDirectory.resolve("context.json.tmp")

  /**
   * Cached decrypted messages to avoid re-reading and decrypting from
   * disk on every call.
   *
   * `@Volatile` because [loadContext] and [saveContext] can race
   * across coroutines / threads (load reads the field, save writes
   * `null` to invalidate it). Without the volatile, a load on
   * another thread could see a stale non-null value after a save
   * has invalidated it.
   */
  @Volatile
  private var cachedMessages: List<Map<String, Any>>? = null

  fun loadContext(): List<Map<String, Any>> {
    cachedMessages?.let { return it }

    val contextFile: File = contextFilePath.toFile()

    if (!contextFile.exists()) {
      logger.info("No context file at ${contextFilePath.toAbsolutePath()}")
      return emptyList()
    }

    logger.info("Loading context (${contextFile.length()} bytes)")

    return try {
      val rawContent: String = contextFile.readText(Charsets.UTF_8)
      val parsedJson: JsonObject = jsonFormatter.parseToJsonElement(rawContent).jsonObject

      val rawMessages: JsonArray = parsedJson["messages"]?.jsonArray ?: run {
        logger.warn("Context file has no 'messages' field"); return emptyList()
      }

      logger.info("Found ${rawMessages.size} messages in context file")

      var decryptionFailures = 0
      val decryptedMessages: List<Map<String, Any>> = rawMessages.mapNotNull { element ->
        val messageMap: MutableMap<String, Any> = parseJsonObject(element.jsonObject)
        val decrypted: Map<String, Any>? = decryptMessageIfNeeded(messageMap)
        if (decrypted == null) {
          decryptionFailures++; null
        } else decrypted
      }
      if (decryptionFailures > 0) {
        logger.warn(
          "Dropped $decryptionFailures message(s) that failed to decrypt — " +
            "they would have been fed to the LLM as base64 ciphertext otherwise"
        )
      }

      logMessageStats(decryptedMessages); cachedMessages = decryptedMessages
      decryptedMessages
    } catch (loadException: Exception) {
      logger.error("Failed to load context: ${loadException.message}", loadException)
      emptyList()
    }
  }

  fun saveContext(messages: List<Map<String, Any>>, modelName: String): Boolean {
    outputDirectory.toFile().mkdirs()
    cachedMessages = null

    logger.info("Saving context: ${messages.size} messages, model=$modelName")

    val cleanedMessages: List<Map<String, Any>> = cleanMessageHistory(messages)

    logger.info("After cleanup: ${cleanedMessages.size} messages (dropped ${messages.size - cleanedMessages.size})")

    val serializedMessages: List<Map<String, Any>> = cleanedMessages.map { message ->
      encryptMessageIfNeeded(message)
    }

    val contextMap: Map<String, Any> =
      mapOf("version" to "1", "model" to modelName, "messages" to serializedMessages)
    val contextJson: String = JsonUtil.encodeMap(contextMap)

    return try {
      val tempFile: File = contextTempFilePath.toFile()
      tempFile.writeText(contextJson, Charsets.UTF_8)
      try {
        Files.move(
          contextTempFilePath,
          contextFilePath,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } catch (moveException: AtomicMoveNotSupportedException) {
        logger.warn("ATOMIC_MOVE not supported on this filesystem; falling back", moveException)
        Files.move(contextTempFilePath, contextFilePath, StandardCopyOption.REPLACE_EXISTING)
      }
      val writtenSize: Long = contextFilePath.toFile().length()

      logger.info("Context saved: $writtenSize bytes, ${serializedMessages.size} messages encrypted"); true
    } catch (saveException: Exception) {
      logger.error("Failed to save context: ${saveException.message}", saveException); false
    }
  }

  private fun cleanMessageHistory(messages: List<Map<String, Any>>): List<Map<String, Any>> {
    val cleanedMessages: MutableList<Map<String, Any>> = mutableListOf()
    val preservedToolCallIds: Set<String> = collectPreservedToolCallIds(messages)

    for (message in messages) {
      val role: String = message["role"] as? String ?: ""
      val content: String = message["content"] as? String ?: ""
      val isSystemOrEmptyAssistant: Boolean = (role == "system") || (role == "assistant" && content.isBlank())

      if (isSystemOrEmptyAssistant) continue

      when (role) {
        "tool" -> {
          val callId: String = message["tool_call_id"] as? String ?: ""
          if (callId in preservedToolCallIds) cleanedMessages.add(message)
        }

        "assistant" -> cleanAssistantMessage(message, content, preservedToolCallIds, cleanedMessages)

        else -> cleanedMessages.add(mapOf("role" to role, "content" to content.trim()))
      }
    }
    return takeLastTurns(cleanedMessages, MAX_CONTEXT_MESSAGES)
  }

  /**
   * Collects the call ids of `read_file` / `explore_project` tool calls
   * that must survive cleanup because the LLM routinely refers back to
   * their results.
   */
  private fun collectPreservedToolCallIds(messages: List<Map<String, Any>>): Set<String> {
    val preservedToolCallIds: MutableSet<String> = mutableSetOf()
    for (message in messages) {
      val role: String = message["role"] as? String ?: ""
      if (role != "assistant") continue

      for (toolCall: Map<String, Any> in readListOfMaps(message["tool_calls"])) {
        val function: Map<String, Any> = readStringMap(toolCall["function"])
        if (function.isEmpty()) continue
        val functionName: String = function["name"] as? String ?: ""
        val callId: String = toolCall["id"] as? String ?: ""

        if (functionName in listOf("read_file", "explore_project") && callId.isNotBlank()) {
          preservedToolCallIds.add(callId)
        }
      }
    }
    return preservedToolCallIds
  }

  private fun cleanAssistantMessage(
    message: Map<String, Any>,
    content: String,
    preservedToolCallIds: Set<String>,
    cleanedMessages: MutableList<Map<String, Any>>,
  ) {
    val toolCalls: List<Map<String, Any>> = readListOfMaps(message["tool_calls"])
    if (toolCalls.isEmpty()) {
      cleanedMessages.add(mapOf("role" to "assistant", "content" to content.trim()))
      return
    }

    val preservedCalls: List<Map<String, Any>> = toolCalls.filter { toolCall ->
      val callId: String = toolCall["id"] as? String ?: ""
      callId in preservedToolCallIds
    }
    val trimmedContent: String = content.trim()

    if (preservedCalls.isNotEmpty()) {
      val droppedCallCount: Int = toolCalls.size - preservedCalls.size
      if (droppedCallCount > 0) {
        logger.info(
          "Assistant message has $droppedCallCount tool call(s) trimmed " +
            "(kept ${preservedCalls.size} of ${toolCalls.size} read_file / " +
            "explore_project results); the model can still see the surviving " +
            "tool_calls and their results, so the gap is inferable from history"
        )
      }
      cleanedMessages.add(
        mapOf(
          "role" to "assistant",
          "content" to trimmedContent,
          "tool_calls" to preservedCalls
        )
      )
    } else {
      val droppedCount: Int = toolCalls.size - preservedCalls.size
      if (droppedCount > 0) {
        logger.info(
          "Assistant message lost all $droppedCount tool call(s) during " +
            "context cleanup (none were read_file / explore_project); the " +
            "model can infer the gap from the missing tool result messages"
        )
      }
      cleanedMessages.add(
        mapOf("role" to "assistant", "content" to trimmedContent)
      )
    }
  }

  /**
   * Runtime-safe coercion of a `Any?` value (typically read from a
   * `Map<String, Any>` produced by [convertJsonElement]) into a
   * `Map<String, Any>`. Non-`String` keys are dropped, null values
   * become empty strings. Returns an empty map if the input is not a
   * map at all.
   */
  private fun readStringMap(rawMap: Any?): Map<String, Any> {
    if (rawMap !is Map<*, *>) return emptyMap()

    return rawMap
      .filterKeys { it is String }
      .mapKeys { it.key as String }
      .mapValues { it.value ?: "" }
      .toMap()
  }

  private fun readListOfMaps(rawList: Any?): List<Map<String, Any>> {
    if (rawList !is List<*>) return emptyList()
    val parsedMaps: MutableList<Map<String, Any>> = mutableListOf()
    for (rawItem: Any? in rawList) {
      val stringMap: Map<String, Any> = readStringMap(rawItem)
      if (stringMap.isNotEmpty()) parsedMaps.add(stringMap)
    }
    return parsedMaps
  }

  private fun parseJsonObject(jsonObject: JsonObject): MutableMap<String, Any> {
    val parsedMap: MutableMap<String, Any> = mutableMapOf()
    for ((key: String, jsonElement) in jsonObject) parsedMap[key] = convertJsonElement(jsonElement)
    return parsedMap
  }

  private fun convertJsonElement(element: JsonElement): Any {
    return when (element) {
      is JsonPrimitive if element.isString -> element.content
      is JsonPrimitive -> JsonUtil.fromJsonElement(element) ?: element.toString()
      is JsonArray, is JsonObject -> JsonUtil.fromJsonElement(element).toString()
    }
  }

  private fun decryptMessageIfNeeded(message: MutableMap<String, Any>): MutableMap<String, Any>? {
    val isEncrypted: Boolean = message["_encrypted"]?.toString() == "true"
    val encryptedContent: String = message["content"] as? String ?: ""

    if (!isEncrypted) return message

    return try {
      message["content"] = decryptMessageContent(encryptedContent)
      message.remove("_encrypted")
      message
    } catch (decryptException: Exception) {
      logger.warn("Failed to decrypt message: ${decryptException.message}", decryptException); null
    }
  }

  private fun logMessageStats(messages: List<Map<String, Any>>) {
    val roleCounts: Map<String, Int> = messages
      .mapNotNull { it["role"] as? String }
      .groupingBy { it }
      .eachCount()

    logger.info("Context loaded: ${messages.size} messages (roles: $roleCounts)")
  }

  private fun encryptMessageIfNeeded(message: Map<String, Any>): Map<String, Any> {
    val role: String = message["role"] as? String ?: ""
    val contentText: String = message["content"] as? String ?: ""
    val encryptedContent: String = encryptMessageContent(contentText)
    val hasContent: Boolean = message["content"] != null
    val shouldEncrypt: Boolean = role in listOf("user", "assistant") && hasContent

    if (!shouldEncrypt) return message

    return message.toMutableMap().apply {
      this["content"] = encryptedContent
      this["_encrypted"] = "true"
    }
  }
}
