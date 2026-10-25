package gradum.utils

import gradum.agent.*
import gradum.mcp.McpToolCatalog
import gradum.server.persistence.ChatDb
import gradum.server.persistence.SessionStateStore
import gradum.server.persistence.SessionTranscriptWriter
import gradum.skill.SkillRegistry
import gradum.skill.ToolDisplay
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

private val logger: Logger = LoggerFactory.getLogger("ContextManager")
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
  private var cachedMessages: List<MessageRecord>? = null

  // Whether to use the H2 database backend instead of the legacy JSON file.
  // Controlled by the system property `-Dgradum.db.path=<path>`.
  private val useDatabase: Boolean = System.getProperty("gradum.db.path") != null

  private val contextFilePath: Path = outputDirectory.resolve("context.json")

  fun loadContext(): List<MessageRecord> = if (useDatabase) {
    val storedMessages: List<MessageRecord> = ChatDb.loadContext(
      projectRoot = projectRoot, sessionId = sessionId
    ).mapNotNull { payload -> unsealPayload(payload) }
    logMessageStats(storedMessages)
    cachedMessages = storedMessages
    storedMessages
  } else {
    cachedMessages?.let { return it }

    val contextFile: File = contextFilePath.toFile()

    if (!contextFile.exists()) {
      return emptyList()
    }

    return try {
      val rawContent: String = contextFile.readText(Charsets.UTF_8)
      val parsedJson: JsonObject = jsonFormatter.parseToJsonElement(rawContent).jsonObject

      val rawMessages = parsedJson["messages"]?.jsonArray ?: run {
        logger.warn("Context file has no 'messages' field"); return emptyList()
      }

      var decodeFailures = 0
      val decryptedMessages: List<MessageRecord> = rawMessages.mapNotNull { element ->
        val record: MessageRecord? = unsealPayload(element.toString())
        if (record == null) {
          decodeFailures++; null
        } else record
      }
      if (decodeFailures > 0) {
        logger.warn("Dropped $decodeFailures message(s) that failed to decode or decrypt")
      }

      logMessageStats(decryptedMessages); cachedMessages = decryptedMessages
      decryptedMessages
    } catch (loadException: Exception) {
      logger.error("Failed to load context: ${loadException.javaClass.simpleName}: ${loadException.message}")
      emptyList()
    }
  }

  fun saveContext(messages: List<MessageRecord>, modelName: String): Boolean {
    writeDerivedSessionFiles(messages = messages, modelName = modelName)

    return if (useDatabase) {
      cachedMessages = null
      val cleanedMessages: List<MessageRecord> = cleanMessageHistory(messages)
      val sealedMessages: List<MessageRecord> = cleanedMessages.map { message -> sealIfNeeded(message) }
      ChatDb.saveContext(
        sessionId = sessionId,
        modelName = modelName,
        projectRoot = projectRoot,
        messages = sealedMessages
      )
    } else {
      outputDirectory.toFile().mkdirs()
      cachedMessages = null

      val cleanedMessages: List<MessageRecord> = cleanMessageHistory(messages)
      val droppedCount: Int = messages.size - cleanedMessages.size

      val sealedMessages: List<MessageRecord> = cleanedMessages.map { message -> sealIfNeeded(message) }
      val contextJson: String = jsonFormatter.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
          put("version", "2")
          put("model", modelName)
          put(
            "messages",
            JsonArray(sealedMessages.map { message -> messageRecordJson.parseToJsonElement(message.encodeJson()) })
          )
        }
      )

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
  private fun writeDerivedSessionFiles(messages: List<MessageRecord>, modelName: String) {
    val projection: List<Map<String, Any?>> = SessionStateStore.projectionMessages(messages)
    try {
      SessionStateStore.write(
        projectRoot = projectRoot,
        sessionId = sessionId,
        modelName = modelName,
        messages = messages
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
    val messages: List<MessageRecord> = loadContext()
    val targetIndex: Int = messages.indexOfFirst { message ->
      message is UserMessageRecord && message.id == messageId
    }
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
        parsedJson["model"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
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
  private fun cleanMessageHistory(messages: List<MessageRecord>): List<MessageRecord> {
    val toolCallsByCallId: Map<String, ToolCallRecord> = messages
      .filterIsInstance<AssistantMessageRecord>()
      .flatMap { assistant -> assistant.toolCalls }
      .filter { call -> !call.callId.isNullOrBlank() }
      .associateBy { call -> call.callId.orEmpty() }

    val cleanedMessages: MutableList<MessageRecord> = mutableListOf()

    for (message in messages) {
      when (message) {
        is SystemMessageRecord -> continue

        is AssistantMessageRecord -> {
          if (message.content.isNotBlank() || message.toolCalls.isNotEmpty()) {
            cleanedMessages.add(message)
          }
        }

        is ToolMessageRecord -> cleanedMessages.add(buildToolStub(message, toolCallsByCallId))

        is UserMessageRecord -> cleanedMessages.add(message)
        is SealedMessageRecord -> cleanedMessages.add(message)
      }
    }
    return cleanedMessages
  }

  // Builds the stored stand-in for a tool result: the skill's display label
  // and key argument from its [ToolDisplay] declaration, plus the payload
  // size. Falls back to the raw function name when the skill (or its
  // display) is unknown. The original output is never persisted.
  private fun buildToolStub(
    toolMessage: ToolMessageRecord,
    toolCallsByCallId: Map<String, ToolCallRecord>
  ): ToolMessageRecord {
    val callId: String = toolMessage.callId.orEmpty()
    val toolCall: ToolCallRecord? = toolCallsByCallId[callId]
    val functionName: String = toolCall?.name.orEmpty()

    val skillInstance = functionName.takeIf { it.isNotBlank() }?.let {
      SkillRegistry.getSkill(skillName = it) ?: McpToolCatalog.adapterFor(name = it)
    }
    val toolDisplay: ToolDisplay? = skillInstance?.toolDisplay
    val displayLabel: String = toolDisplay?.label?.takeIf { it.isNotBlank() } ?: functionName.ifBlank { "tool" }

    val stubBuilder: StringBuilder = StringBuilder("[$displayLabel]")
    val paramKey: String = toolDisplay?.paramKey ?: ""
    val paramValue: String = toolCall?.let { call -> readArgumentValue(call.argumentsJson, paramKey) } ?: ""
    if (paramKey.isNotBlank() && paramValue.isNotBlank()) {
      stubBuilder.append(" ").append(paramKey).append("=").append(paramValue)
    }

    stubBuilder.append(" (result not stored, ").append(toolMessage.content.length).append(" bytes)")

    return toolMessage.copy(content = stubBuilder.toString())
  }

  // Extracts the [paramKey] field from a tool call's JSON `arguments`
  // string, shortened for display. Returns an empty string when anything
  // is missing or unparseable: the stub stays readable either way.
  private fun readArgumentValue(rawArguments: String, paramKey: String): String {
    if (paramKey.isBlank()) return ""
    return try {
      val parsedArguments: JsonObject =
        jsonFormatter.parseToJsonElement(rawArguments).jsonObject
      val rawValue: String = parsedArguments[paramKey]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
      if (rawValue.length > 120) rawValue.take(120) + "..." else rawValue
    } catch (_: Exception) {
      ""
    }
  }

  // Encrypts user / assistant rows into envelopes; tool rows stay plaintext.
  private fun sealIfNeeded(message: MessageRecord): MessageRecord {
    val shouldEncrypt: Boolean = message is UserMessageRecord || message is AssistantMessageRecord
    if (!shouldEncrypt) return message

    return SealedMessageRecord(
      cipher = encryptMessageContent(message.encodeJson()),
      id = (message as? UserMessageRecord)?.id
    )
  }

  // Strict read gate: decodes a stored payload and unwraps an encrypted
  // envelope. A payload that neither decodes nor decrypts is corruption,
  // not an empty message; the caller drops the row and logs.
  private fun unsealPayload(payload: String): MessageRecord? {
    val record: MessageRecord = decodeMessageRecord(payload) ?: run {
      logger.warn("Dropped undecodable message payload")
      return null
    }
    if (record !is SealedMessageRecord) return record

    return try {
      decodeMessageRecord(decryptMessageContent(record.cipher))
    } catch (decryptException: Exception) {
      logger.warn("Failed to decrypt message: ${decryptException.message}")
      null
    }
  }

  private fun logMessageStats(messages: List<MessageRecord>) {
    val roleCounts: Map<String, Int> = messages
      .groupingBy { it.role }
      .eachCount()

    logger.info("Context loaded: {} messages {}", messages.size, roleCounts)
  }
}
