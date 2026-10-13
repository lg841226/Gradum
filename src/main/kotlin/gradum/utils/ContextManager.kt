package gradum.utils

import gradum.server.ChatDb
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("ContextManager")

/**
 * Maximum number of messages to keep in persisted context history.
 *
 * Single source of truth is [MAX_HISTORY_MESSAGES] in
 * MessageHistoryTruncator, shared with the in-memory
 * truncation in `Agent` so both budgets can never drift apart.
 */
const val MAX_CONTEXT_MESSAGES: Int = MAX_HISTORY_MESSAGES

/**
 * Persists the agent's conversation history and the set of files that have
 * been fully read, so a later session can resume where the previous one
 * left off without re-reading the same files.
 */
class ContextManager(outputDirectory: Path) {

  /** Session id taken from the output directory name (convention: .../sessions/<sessionId>/). */
  private val sessionId: String = outputDirectory.fileName.toString()

  /**
   * Project root derived from the output directory layout: the session
   * layout lives at `<root>/.gradum/sessions/<sessionId>/`, the legacy
   * layout directly at `<root>/.gradum/`. Both are inside the project, so
   * the per-project database lands in the same `.gradum` folder that
   * already holds the session files.
   */
  private val projectRoot: Path =
    if (sessionId == ".gradum") outputDirectory.parent
    else outputDirectory.parent.parent.parent

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

    val storedMessages: List<Map<String, Any>> = ChatDb.loadContext(
      projectRoot = projectRoot, sessionId = sessionId
    ).mapNotNull { message ->
      decryptMessageIfNeeded(message = LinkedHashMap(message))
    }

    logMessageStats(storedMessages); cachedMessages = storedMessages
    return storedMessages
  }

  fun saveContext(messages: List<Map<String, Any>>, modelName: String): Boolean {
    cachedMessages = null
    val cleanedMessages: List<Map<String, Any>> = cleanMessageHistory(messages)
    val serializedMessages: List<Map<String, Any>> = cleanedMessages.map { message ->
      encryptMessageIfNeeded(message)
    }
    return ChatDb.saveContext(
      sessionId = sessionId,
      modelName = modelName,
      projectRoot = projectRoot,
      messages = serializedMessages
    )
  }

  /**
   * Rewinds the persisted context to just before the message identified by
   * [messageId]: that message and everything after it is dropped, so a
   * withdrawn user turn (and its tool calls / assistant reply) leaves no
   * trace in the resumed context. Returns `true` only when the id was found
   * and the store was updated; `false` when the target is absent (no change).
   */
  fun rewindTo(messageId: String): Boolean {
    if (messageId.isBlank()) return false

    return ChatDb.rewindTo(projectRoot = projectRoot, sessionId = sessionId, messageId = messageId)
      .also { rewound -> if (rewound) cachedMessages = null }
  }

  private fun cleanMessageHistory(messages: List<Map<String, Any>>): List<Map<String, Any>> {
    val cleanedMessages: MutableList<Map<String, Any>> = mutableListOf()
    val preservedToolCallIds: Set<String> = collectPreservedToolCallIds(messages)

    for (message in messages) {
      val role: String = message["role"] as? String ?: ""
      val rawContent: Any? = message["content"]
      val content: String = rawContent as? String ?: ""
      val isSystemOrEmptyAssistant: Boolean =
        (role == "system") ||
          (role == "assistant" && content.isBlank() && readListOfMaps(message["tool_calls"]).isEmpty())

      if (isSystemOrEmptyAssistant) continue

      when (role) {
        "tool" -> {
          val callId: String = message["tool_call_id"] as? String ?: ""
          if (callId in preservedToolCallIds) cleanedMessages.add(message)
        }

        "assistant" -> cleanAssistantMessage(message, content, preservedToolCallIds, cleanedMessages)

        else -> cleanedMessages.add(
          buildMap {
            put("role", role)
            put("content", rawContent ?: "")
            message["id"]?.let { put("id", it) }
          }
        )
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
      cleanedMessages.add(mapOf("role" to "assistant", "content" to content))
      return
    }

    val preservedCalls: List<Map<String, Any>> = toolCalls.filter { toolCall ->
      val callId: String = toolCall["id"] as? String ?: ""
      callId in preservedToolCallIds
    }
    val trimmedContent: String = content

    if (preservedCalls.isNotEmpty()) {
      cleanedMessages.add(
        mapOf(
          "role" to "assistant",
          "content" to trimmedContent,
          "tool_calls" to preservedCalls
        )
      )
    } else {
      cleanedMessages.add(
        mapOf("role" to "assistant", "content" to trimmedContent)
      )
    }
  }

  /**
   * Runtime-safe coercion of a `Any?` value (typically a message map
   * decoded from stored JSON) into a `Map<String, Any>`. Non-`String` keys
   * are dropped, null values become empty strings. Returns an empty map if
   * the input is not a map at all.
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

  private fun decryptMessageIfNeeded(message: MutableMap<String, Any>): MutableMap<String, Any>? {
    val isEncrypted: Boolean = message["_encrypted"]?.toString() == "true"
    val encryptedContent: String = message["content"] as? String ?: ""

    if (!isEncrypted) return message

    return try {
      message["content"] = decryptMessageContent(encryptedContent)
      message.remove("_encrypted")
      message
    } catch (decryptException: Exception) {
      logger.warn("Failed to decrypt message: ${decryptException.message}"); null
    }
  }

  private fun logMessageStats(messages: List<Map<String, Any>>) {
    val roleCounts: Map<String, Int> = messages
      .mapNotNull { it["role"] as? String }
      .groupingBy { it }
      .eachCount()

    logger.info("Context loaded: {} messages {}", messages.size, roleCounts)
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
