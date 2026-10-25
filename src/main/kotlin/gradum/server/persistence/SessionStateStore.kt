package gradum.server.persistence

import gradum.agent.MessageRecord
import gradum.agent.SealedMessageRecord
import gradum.agent.toStoredMap
import gradum.utils.AtomicFiles
import gradum.utils.JsonUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger: Logger = LoggerFactory.getLogger("SessionStateStore")

/**
 * Structured sidecar of a session's full conversation history, written next
 * to `conversation.md` by the same `ContextManager` save that renders the
 * transcript.
 *
 * `conversation.md` is for humans; this file is the machine-readable
 * projection a client reconciles against, so it keeps every field the bubble
 * UI needs (thinking, tool results, token usage, attachment names) that the
 * cleaned model context (`context.json` / `ChatDb`) deliberately drops.
 *
 * One writer, two renderings from the same in-memory history, so the two
 * files cannot drift. Rows are read back as untyped maps by
 * `SessionHistoryReader`.
 */
object SessionStateStore {

  const val FORMAT_VERSION: Int = 1
  const val STATE_FILE: String = "session-state.json"

  /**
   * Writes the session's rich history next to its transcript, shaped for the
   * UI by [projectionMessages]. Returns `false` on an invalid session id or a
   * write failure; never throws.
   */
  fun write(
    projectRoot: Path,
    sessionId: String,
    modelName: String,
    messages: List<MessageRecord>
  ): Boolean = writeRows(
    projectRoot = projectRoot,
    sessionId = sessionId,
    modelName = modelName,
    rows = projectionMessages(messages)
  )

  /**
   * Writes pre-projected rows (the sidecar's own map shape, as read back from
   * other sessions). Used by [SessionMerger], whose inputs come from sidecar
   * files rather than in-memory records.
   */
  fun writeRows(
    projectRoot: Path,
    sessionId: String,
    modelName: String,
    rows: List<Map<String, Any?>>
  ): Boolean {
    val sessionDirectory: Path = SessionTranscriptWriter.sessionDirectory(projectRoot, sessionId) ?: return false
    val state: Map<String, Any?> = mapOf(
      "version" to FORMAT_VERSION,
      "sessionId" to sessionId.trim(),
      "modelName" to modelName,
      "messages" to rows
    )
    return try {
      AtomicFiles.write(sessionDirectory.resolve(STATE_FILE), JsonUtil.encodeMap(input = state))
      true
    } catch (writeException: Exception) {
      logger.warn("Failed to write session state for {}: {}", sessionId, writeException.message)
      false
    }
  }

  fun read(projectRoot: Path, sessionId: String): JsonObject? {
    val sessionDirectory: Path = SessionTranscriptWriter.sessionDirectory(projectRoot, sessionId) ?: return null
    val stateFile: Path = sessionDirectory.resolve(STATE_FILE)
    if (!Files.isRegularFile(stateFile)) return null

    return try {
      val parsed: JsonElement = Json.parseToJsonElement(Files.readString(stateFile, Charsets.UTF_8))
      parsed as? JsonObject
    } catch (readException: Exception) {
      logger.warn("Failed to read session state for {}: {}", sessionId, readException.message)
      null
    }
  }

  /**
   * Shapes the in-memory records into what the client renders: system prompts
   * are dropped (re-injected fresh on every request, never shown), encrypted
   * envelopes are unwrapped through their stored map projection, and base64
   * image payloads are reduced to their metadata. The transcript is rendered
   * from this same projection, so the two files describe one conversation and
   * cannot drift.
   */
  fun projectionMessages(messages: List<MessageRecord>): List<Map<String, Any?>> {
    return messages
      .filter { message: MessageRecord -> message.role != "system" && message !is SealedMessageRecord }
      .map { message: MessageRecord -> redactImageParts(message.toStoredMap()) }
  }

  // Replaces each image part's base64 payload with its metadata only
  private fun redactImageParts(row: Map<String, Any?>): Map<String, Any?> {
    val content: Any = row["content"] ?: return row
    if (content !is List<*>) return row
    val redactedParts: List<Any?> = content.map { part: Any? ->
      if (part is Map<*, *> && part["type"] == "image") {
        mapOf(
          "type" to "image",
          "mime" to (part["mime"] ?: "image/jpeg"),
          "filename" to (part["filename"] ?: "")
        )
      } else {
        part
      }
    }
    return row + ("content" to redactedParts)
  }
}
