package gradum.server.persistence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * Merges several sessions into one, server-side.
 *
 * The plugin used to weave the source transcripts in memory and upload the
 * result; the server now reads each source's sidecar, concatenates the
 * messages in timeline order, and writes the merged session's sidecar and
 * transcript itself. Sources are ordered by their transcript `updatedAt`
 * (ties keep the request order), which reproduces the old client weave where
 * every message of a session carried the session's timestamp.
 */
object SessionMerger {

  data class MergeResult(val sessionId: String, val title: String)

  private data class SourceSession(
    val messages: List<Map<String, Any?>>,
    val updatedAt: Long,
    val createdAt: Long
  )

  /**
   * Creates a session from [sourceIds]' histories, numbered from [baseTitle].
   *
   *   or the merged state could not be written.
   *
   * Returns the merge result, or `null` when a source has no readable sidecar.
   */
  fun merge(projectRoot: Path, sourceIds: List<String>, baseTitle: String): MergeResult? {
    val sources: List<SourceSession> = sourceIds.map { sessionId: String ->
      val messages: List<Map<String, Any?>> = readMessages(projectRoot = projectRoot, sessionId = sessionId)
        ?: return null
      val meta: SessionTranscriptWriter.TranscriptMeta? =
        SessionTranscriptWriter.readMeta(projectRoot = projectRoot, sessionId = sessionId)
      SourceSession(
        messages = messages,
        updatedAt = meta?.updatedAt ?: 0L,
        createdAt = meta?.createdAt ?: 0L
      )
    }
    val orderedMessages: List<Map<String, Any?>> =
      sources.sortedBy { session: SourceSession -> session.updatedAt }.flatMap { it.messages }
    if (orderedMessages.isEmpty()) return null

    val newSessionId: String = SessionIds.next()
    val mergedTitle: String = numberedTitle(projectRoot = projectRoot, baseTitle = baseTitle)
    val modelName: String = orderedMessages.asReversed()
      .firstNotNullOfOrNull { message: Map<String, Any?> ->
        (message["modelName"] as? String)?.takeIf { it.isNotBlank() }
      }
      .orEmpty()
    val earliestCreatedAt: Long? = sources.map { it.createdAt }.filter { it > 0L }.minOrNull()

    val stateWritten: Boolean = SessionStateStore.write(
      projectRoot = projectRoot,
      sessionId = newSessionId,
      modelName = modelName,
      messages = orderedMessages
    )
    if (!stateWritten) return null
    SessionTranscriptWriter.writeTranscript(
      projectRoot = projectRoot,
      sessionId = newSessionId,
      modelName = modelName,
      messages = orderedMessages,
      title = mergedTitle,
      createdAt = earliestCreatedAt
    )
    return MergeResult(sessionId = newSessionId, title = mergedTitle)
  }

  private fun numberedTitle(projectRoot: Path, baseTitle: String): String {
    val base: String = baseTitle.trim()
    val numberedPattern = Regex(pattern = "^${Regex.escape(literal = base)}\\s+(\\d+)$")
    val maxIndex: Int = SessionTranscriptWriter.listSessions(projectRoot = projectRoot)
      .maxOfOrNull { meta: SessionTranscriptWriter.TranscriptMeta ->
        numberedPattern.matchEntire(input = meta.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
      } ?: 0
    return "$base ${maxIndex + 1}"
  }

  private fun readMessages(projectRoot: Path, sessionId: String): List<Map<String, Any?>>? {
    val storedState: JsonObject = SessionStateStore.read(projectRoot = projectRoot, sessionId = sessionId)
      ?: return null
    val rawMessages: JsonArray = storedState["messages"] as? JsonArray ?: return emptyList()
    return SessionHistoryReader.readStoredMessages(rawMessages)
  }
}
