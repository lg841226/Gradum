package gradum.utils

import gradum.mcp.McpToolCatalog
import gradum.server.persistence.ChatDb
import gradum.server.persistence.SessionStateStore
import gradum.server.persistence.SessionTranscriptWriter
import gradum.skill.Skill
import gradum.skill.SkillRegistry
import gradum.skill.ToolDisplay
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("ContextManager")
private val jsonFormatter: Json = Json { prettyPrint = true }

/**
 * Persists the agent's conversation history and the set of files that have
 * been fully read, so a later session can resume where the previous one
 * left off without re-reading the same files.
 */
class ContextManager(private val outputDirectory: Path) {

  // Session id taken from the output directory name
  // (convention: .../sessions/<sessionId>/ or .../.gradum/ for legacy).
  private val sessionId: String = outputDirectory.fileName.toString()

  // Project root derived from the output directory layout:
  // - Session layout: `<root>/.gradum/sessions/<sessionId>/`
  // - Legacy layout: `<root>/.gradum/`
  // In both cases the per-project database lives in the same `.gradum` folder
  // that already holds the session files.
  // Falls back to the current working directory if the parent chain is shorter
  // than expected (e.g., when running in tests with minimal paths).
  private val projectRoot: Path =
    if (sessionId == ".gradum") {
      outputDirectory.parent ?: Path.of(".")
    } else {
      outputDirectory.parent?.parent?.parent ?: Path.of(".")
    }

  // Cached decrypted messages to avoid re-reading and decrypting from
  // disk on every call.
  //
  // `@Volatile` because [loadContext] and [saveContext] can race
  // across coroutines or threads (load reads the field, save writes
  // `null` to invalidate it). Without the volatile, a load on
  // another thread could see a stale non-null value after a save
  // has invalidated it.
  @Volatile
  private var cachedMessages: List<Map<String, Any>>? = null

  // Whether to use the H2 database backend instead of the legacy JSON file.
  // Controlled by the system property `-Dgradum.db.path=<path>`.
  private val useDatabase: Boolean = System.getProperty("gradum.db.path") != null

  private val contextFilePath: Path = outputDirectory.resolve("context.json")

  fun loadContext(): List<Map<String, Any>> = if (useDatabase) {
    // Database mode: delegate to ChatDb
    val storedMessages: List<Map<String, Any>> = ChatDb.loadContext(
      projectRoot = projectRoot, sessionId = sessionId
    ).mapNotNull { message ->
      decryptMessageIfNeeded(message = LinkedHashMap(message))
    }
    logMessageStats(storedMessages)
    cachedMessages = storedMessages
    storedMessages
  } else {
    // Legacy file mode
    cachedMessages?.let { return it }

    val contextFile: File = contextFilePath.toFile()

    if (!contextFile.exists()) {
      return emptyList()
    }

    return try {
      val rawContent: String = contextFile.readText(Charsets.UTF_8)
      val parsedJson: JsonObject = jsonFormatter.parseToJsonElement(rawContent).jsonObject

      val rawMessages: JsonArray = parsedJson["messages"]?.jsonArray ?: run {
        logger.warn("Context file has no 'messages' field"); return emptyList()
      }

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
          "Dropped $decryptionFailures message(s) that failed to decrypt"
        )
      }

      logMessageStats(decryptedMessages); cachedMessages = decryptedMessages
      decryptedMessages
    } catch (loadException: Exception) {
      logger.error("Failed to load context: ${loadException.javaClass.simpleName}: ${loadException.message}")
      emptyList()
    }
  }

  fun saveContext(messages: List<Map<String, Any>>, modelName: String): Boolean {
    writeDerivedSessionFiles(messages = messages, modelName = modelName)

    return if (useDatabase) {
      cachedMessages = null
      val cleanedMessages: List<Map<String, Any>> = cleanMessageHistory(messages)
      val serializedMessages: List<Map<String, Any>> = cleanedMessages.map { message ->
        encryptMessageIfNeeded(message)
      }
      ChatDb.saveContext(
        sessionId = sessionId,
        modelName = modelName,
        projectRoot = projectRoot,
        messages = serializedMessages
      )
    } else {
      outputDirectory.toFile().mkdirs()
      cachedMessages = null

      val cleanedMessages: List<Map<String, Any>> = cleanMessageHistory(messages)
      val droppedCount: Int = messages.size - cleanedMessages.size

      val serializedMessages: List<Map<String, Any>> = cleanedMessages.map { message ->
        encryptMessageIfNeeded(message)
      }

      val contextMap: Map<String, Any> =
        mapOf("version" to "1", "model" to modelName, "messages" to serializedMessages)
      val contextJson: String = JsonUtil.encodeMap(contextMap)

      try {
        AtomicFiles.write(contextFilePath, contextJson)
        val writtenSize: Long = contextFilePath.toFile().length()

        logger.info(
          "{} to {} messages (dropped {}, model={}), {} bytes encrypted",
          messages.size, cleanedMessages.size, droppedCount, modelName, writtenSize
        ); true
      } catch (saveException: Exception) {
        logger.error("Failed to save context: ${saveException.javaClass.simpleName}: ${saveException.message}"); false
      }
    }
  }

  // Renders the session's two derived files from [messages]: the
  // machine-readable `session-state.json` sidecar and the human-readable
  // `conversation.md` transcript. Both come from the same projection, so they
  // cannot disagree; a failure to write either is logged but never aborts the
  // context save.
  private fun writeDerivedSessionFiles(messages: List<Map<String, Any>>, modelName: String) {
    val projection: List<Map<String, Any?>> = SessionStateStore.projectionMessages(messages)
    try {
      SessionStateStore.write(
        projectRoot = projectRoot,
        sessionId = sessionId,
        modelName = modelName,
        messages = projection
      )
    } catch (stateException: Exception) {
      logger.warn(
        "Failed to write session state: ${stateException.javaClass.simpleName}: ${stateException.message}"
      )
    }

    try {
      SessionTranscriptWriter.writeTranscript(
        projectRoot = projectRoot,
        sessionId = sessionId,
        modelName = modelName,
        messages = projection
      )
    } catch (transcriptException: Exception) {
      logger.warn(
        "Failed to write session transcript: ${transcriptException.javaClass.simpleName}: ${transcriptException.message}"
      )
    }
  }

  /**
   * Rewinds the persisted context to just before the message identified by
   * [messageId]: that message and everything after it is dropped, so a
   * withdrawn user turn (and its tool calls / assistant reply) leaves no
   * trace in the resumed context. Returns `true` only when the id was found
   * and the store was updated; `false` when the target is absent (no change).
   *
   * The derived sidecar and transcript are refreshed from the surviving
   * history in both modes, so a rewind never leaves them showing dropped turns.
   */
  fun rewindTo(messageId: String): Boolean = if (useDatabase) {
    if (messageId.isBlank()) return false
    val rewound: Boolean =
      ChatDb.rewindTo(projectRoot = projectRoot, sessionId = sessionId, messageId = messageId)
    if (rewound) {
      cachedMessages = null
      // The database rewind only touches the model context; re-render the
      // derived files from what survives, preserving the stored model name.
      val existingModel: String =
        SessionTranscriptWriter.readMeta(projectRoot, sessionId)?.modelName.orEmpty()
      writeDerivedSessionFiles(messages = loadContext(), modelName = existingModel)
    }
    rewound
  } else {
    if (messageId.isBlank()) return false
    val messages: List<Map<String, Any>> = loadContext()
    val targetIndex: Int = messages.indexOfFirst { it["id"] == messageId }
    if (targetIndex < 0) return false
    saveContext(messages.subList(0, targetIndex), modelName = modelName())
  }

  // Reads the `model` field from the context header (blank when absent/unreadable).
  private fun modelName(): String =
    if (useDatabase) {
      // In database mode the model name is embedded in the encrypted blob;
      // we don't have a separate header field, so return empty string.
      ""
    } else {
      val contextFile: File = contextFilePath.toFile()
      if (!contextFile.exists()) return ""

      return try {
        val parsedJson: JsonObject =
          jsonFormatter.parseToJsonElement(contextFile.readText(Charsets.UTF_8)).jsonObject
        parsedJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
      } catch (readException: Exception) {
        logger.warn("Failed to read model field: ${readException.message}")
        ""
      }
    }

  // Prepares history for persistence. Text turns (user / assistant) are kept
  // in full; tool results are replaced with compact action stubs carrying
  // only what the skill's [ToolDisplay] declares (label and key argument) plus
  // the result size. Raw outputs are volatile snapshots (file contents,
  // command output change over time), so they are deliberately not stored:
  // the model re-runs a tool when it needs fresh data.
  //
  // System messages are dropped here because they are re-injected fresh on
  // every request. No message-count truncation happens on this path; the
  // request-side budget (MessageHistoryTruncator) decides how much history
  // is re-sent to the model.
  private fun cleanMessageHistory(messages: List<Map<String, Any>>): List<Map<String, Any>> {
    val toolCallsByCallId: Map<String, Map<String, Any>> = collectToolCallsByCallId(messages)
    val cleanedMessages: MutableList<Map<String, Any>> = mutableListOf()

    for (message in messages) {
      val role: String = message["role"] as? String ?: ""
      val rawContent: Any? = message["content"]
      val content: String = rawContent as? String ?: ""

      when (role) {
        "system" -> continue

        "assistant" -> {
          if (content.isNotBlank() || readListOfMaps(message["tool_calls"]).isNotEmpty()) {
            cleanedMessages.add(message)
          }
        }

        "tool" -> cleanedMessages.add(buildToolStub(message, toolCallsByCallId))

        else -> cleanedMessages.add(
          buildMap {
            put("role", role)
            put("content", rawContent ?: "")
            message["id"]?.let { put("id", it) }
          }
        )
      }
    }
    return cleanedMessages
  }

  // Maps every tool call id to its call entry (`function` name and JSON
  // arguments), read from the assistant messages, so a tool result can be
  // reduced to a stub that still names the action it belongs to.
  private fun collectToolCallsByCallId(messages: List<Map<String, Any>>): Map<String, Map<String, Any>> {
    val callsByCallId: MutableMap<String, Map<String, Any>> = mutableMapOf()
    for (message in messages) {
      if (message["role"] != "assistant") continue
      for (toolCall: Map<String, Any> in readListOfMaps(message["tool_calls"])) {
        val callId: String = toolCall["id"] as? String ?: ""
        if (callId.isNotBlank()) callsByCallId[callId] = toolCall
      }
    }
    return callsByCallId
  }

  // Builds the stored stand-in for a tool result: the skill's display label
  // and key argument from its [ToolDisplay] declaration, plus the payload
  // size. Falls back to the raw function name when the skill (or its
  // display) is unknown. The original output is never persisted.
  private fun buildToolStub(
    toolMessage: Map<String, Any>,
    toolCallsByCallId: Map<String, Map<String, Any>>
  ): Map<String, Any> {
    val callId: String = toolMessage["tool_call_id"] as? String ?: ""
    val toolCall: Map<String, Any> = toolCallsByCallId[callId] ?: emptyMap()
    val function: Map<String, Any> = readStringMap(toolCall["function"])
    val functionName: String = function["name"] as? String ?: ""

    val skillInstance: Skill? = functionName.takeIf { it.isNotBlank() }?.let {
      SkillRegistry.getSkill(skillName = it) ?: McpToolCatalog.adapterFor(name = it)
    }
    val toolDisplay: ToolDisplay? = skillInstance?.toolDisplay
    val displayLabel: String = toolDisplay?.label?.takeIf { it.isNotBlank() } ?: functionName.ifBlank { "tool" }

    val stubBuilder: StringBuilder = StringBuilder("[$displayLabel]")
    val paramKey: String = toolDisplay?.paramKey ?: ""
    val paramValue: String = readArgumentValue(function["arguments"], paramKey)
    if (paramKey.isNotBlank() && paramValue.isNotBlank()) {
      stubBuilder.append(" ").append(paramKey).append("=").append(paramValue)
    }

    val resultSizeKb: Int = (toolMessage["content"] as? String)?.length ?: 0
    stubBuilder.append(" (result not stored, ").append(resultSizeKb).append(" bytes)")

    return buildMap {
      put("role", "tool")
      put("tool_call_id", callId)
      put("content", stubBuilder.toString())
    }
  }

  // Extracts the [paramKey] field from a tool call's JSON `arguments`
  // string, shortened for display. Returns an empty string when anything
  // is missing or unparseable: the stub stays readable either way.
  private fun readArgumentValue(rawArguments: Any?, paramKey: String): String {
    if (paramKey.isBlank() || rawArguments !is String) return ""
    return try {
      val parsedArguments: JsonObject = jsonFormatter.parseToJsonElement(rawArguments).jsonObject
      val rawValue: String = parsedArguments[paramKey]?.jsonPrimitive?.contentOrNull ?: ""
      if (rawValue.length > 120) rawValue.take(120) + "..." else rawValue
    } catch (_: Exception) {
      ""
    }
  }

  // Runtime-safe coercion of a `Any?` value (typically read from a
  // `Map<String, Any>` produced by [convertJsonElement]) into a
  // `Map<String, Any>`. Non-`String` keys are dropped, null values
  // become empty strings. Returns an empty map if the input is not a
  // map at all.
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
      is JsonArray, is JsonObject -> JsonUtil.fromJsonElement(element) ?: ""
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
