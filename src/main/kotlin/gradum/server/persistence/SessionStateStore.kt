package gradum.server.persistence

import gradum.utils.JsonUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
 * files cannot drift.
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
    messages: List<Map<String, Any>>
  ): Boolean {
    val sessionDirectory: Path = SessionTranscriptWriter.sessionDirectory(projectRoot, sessionId) ?: return false
    val state: Map<String, Any> = mapOf(
      "version" to FORMAT_VERSION,
      "sessionId" to sessionId.trim(),
      "modelName" to modelName,
      "messages" to projectionMessages(messages)
    )
    return try {
      atomicWrite(sessionDirectory.resolve(STATE_FILE), JsonUtil.encodeMap(input = state))
      true
    } catch (writeException: Exception) {
      logger.warn("Failed to write session state for {}: {}", sessionId, writeException.message)
      false
    }
  }

  /** Parses the sidecar; null when the session id is invalid, absent, or unreadable. */
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
   * Shapes the in-memory history into what the client renders: system prompts
   * are dropped (re-injected fresh on every request, never shown) and base64
   * image payloads are reduced to their metadata. The transcript is rendered
   * from this same projection, so the two files describe one conversation and
   * cannot drift.
   */
  fun projectionMessages(messages: List<Map<String, Any>>): List<Map<String, Any>> {
    return messages
      .filter { message: Map<String, Any> -> message["role"] != "system" }
      .map { message: Map<String, Any> ->
        val content: Any? = message["content"]
        if (content !is List<*>) return@map message
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
        message + ("content" to redactedParts)
      }
  }

  /** Temp file + atomic rename, matching the transcript writer's durability. */
  private fun atomicWrite(targetFile: Path, content: String) {
    Files.createDirectories(targetFile.parent)
    val tempFile: Path = targetFile.resolveSibling("${targetFile.fileName}.tmp-${System.nanoTime()}")
    Files.writeString(tempFile, content, Charsets.UTF_8)
    try {
      Files.move(
        tempFile,
        targetFile,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (moveException: AtomicMoveNotSupportedException) {
      logger.warn("ATOMIC_MOVE not supported on this filesystem; falling back: ${moveException.message}")
      Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
