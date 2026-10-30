package gradum.idea.settings

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.ProjectManager
import gradum.idea.utils.JsonUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

object TurnEventLoader {

  private val json = Json { ignoreUnknownKeys = true }

  data class TurnEvent(
    val endMs: Long,
    val startMs: Long,
    val alias: String,
    val isFailed: Boolean,
    val durationMs: Long
  )

  data class Turn(
    val index: Int,
    val turnEndMs: Long,
    val turnStartMs: Long,
    val userMessage: String,
    val events: List<TurnEvent>
  ) {
    val durationMs: Long get() = turnEndMs - turnStartMs
    val hasEvents: Boolean get() = events.isNotEmpty()
  }

  data class LoadResult(
    val turns: List<Turn>,
    val error: String? = null
  )

  fun loadTurnsAsync(
    scope: CoroutineScope,
    onResult: (LoadResult) -> Unit
  ) {
    scope.launch(Dispatchers.IO) {
      val result = loadTurns()
      withContext(Dispatchers.EDT) {
        onResult(result)
      }
    }
  }

  fun loadTurns(): LoadResult {
    val project = ProjectManager.getInstance().openProjects.firstOrNull()
      ?: return LoadResult(emptyList(), "No open project")

    val projectRoot = project.basePath
      ?: return LoadResult(emptyList(), "Project has no base path")

    val sessionsDir = Paths.get(projectRoot, ".gradum", "sessions")
    if (!Files.isDirectory(sessionsDir)) {
      return LoadResult(emptyList(), "Sessions directory not found")
    }

    // Ordered by the sidecar's last-modified time, not by directory name:
    // ACP sessions use UUID names whose lexicographic order carries no
    // timing, so name-sorting would pin the view to an arbitrary session.
    val sessionDirs: List<Path> = Files.list(sessionsDir)
      .filter { Files.isDirectory(it) }
      .filter { Files.isRegularFile(it.resolve("session-state.json")) }
      .toList()
      .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }

    if (sessionDirs.isEmpty()) {
      return LoadResult(emptyList(), "No sessions found")
    }

    val latestSessionId: String = sessionDirs.first().fileName.toString()
    val stateFile = sessionsDir.resolve(latestSessionId).resolve("session-state.json")

    if (!Files.isRegularFile(stateFile)) {
      return LoadResult(emptyList(), "Session state file not found")
    }

    return try {
      val jsonText = stateFile.toFile().readText()
      val parsed = json.parseToJsonElement(jsonText) as JsonObject
      parseTurns(parsed)
    } catch (ex: Exception) {
      LoadResult(emptyList(), "Failed to parse session: ${ex.message}")
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun parseTurns(sessionJson: JsonObject): LoadResult {
    val messagesArray = sessionJson["messages"] as? JsonArray
      ?: return LoadResult(emptyList(), "Session has no messages")

    val rawMessages = messagesArray.mapNotNull { element ->
      (element as? JsonObject)?.let { JsonUtil.fromJsonElement(it) as? Map<String, Any?> }
    }

    val turns = mutableListOf<Turn>()
    var currentUserMessage: Map<String, Any?>? = null
    var turnIndex = 0
    var legacyTurnSeen = false

    fun flushTurn() {
      val userMessage = currentUserMessage ?: return
      currentUserMessage = null
      turnIndex++
      // Turns recorded before timing stamps existed carry no ts; they are
      // skipped, not estimated, and newer turns still render.
      val turn = buildTurn(turnIndex, userMessage, rawMessages)
      if (turn == null) {
        legacyTurnSeen = true
      } else if (turn.hasEvents) {
        turns.add(turn)
      }
    }

    for (message in rawMessages) {
      when (message["role"] as? String) {
        "user" -> {
          flushTurn()
          currentUserMessage = message
        }
      }
    }
    flushTurn()

    if (turns.isEmpty() && legacyTurnSeen) {
      return LoadResult(emptyList(), "This session has no timing data; send a new message to record it")
    }

    return LoadResult(turns)
  }

  private fun messageHasTimestamp(message: Map<String, Any?>): Boolean =
    message["ts"] is Number

  /** Returns null when the turn predates timing stamps and must be skipped */
  private fun buildTurn(
    turnIndex: Int,
    userMessage: Map<String, Any?>,
    rawMessages: List<Map<String, Any?>>
  ): Turn? {
    val userTs: Any? = userMessage["ts"]
    if (userTs !is Number) return null

    val userIndex = rawMessages.indexOf(userMessage)
    val turnStartMs = userTs.toLong()

    val nextUserIndex = rawMessages.drop(userIndex + 1)
      .indexOfFirst { it["role"] == "user" }
      .takeIf { it >= 0 }
      ?.plus(userIndex + 1)
      ?: rawMessages.size

    val turnMessages = rawMessages.subList(userIndex + 1, nextUserIndex)
    if (turnMessages.any { !messageHasTimestamp(it) }) return null

    val turnEndMs = turnMessages.maxOfOrNull { (it["ts"] as Number).toLong() } ?: turnStartMs

    val events = turnMessages
      .filter { it["role"] == "tool" }
      .map { message -> buildEvent(message) }

    return Turn(
      events = events,
      index = turnIndex,
      turnEndMs = turnEndMs,
      userMessage = extractUserText(userMessage),
      turnStartMs = turnStartMs
    )
  }

  private fun buildEvent(message: Map<String, Any?>): TurnEvent {
    val endMs = (message["ts"] as Number).toLong()
    val durationMs = (message["durationMs"] as? Number)?.toLong() ?: 0L
    val success = message["success"] as? Boolean

    return TurnEvent(
      endMs = endMs,
      durationMs = durationMs,
      isFailed = success == false,
      startMs = endMs - durationMs,
      alias = message["alias"] as? String ?: "tool"
    )
  }

  private fun extractUserText(userMessage: Map<String, Any?>): String {
    val displayText = userMessage["displayText"] as? String
    if (displayText != null) return displayText
    val content = userMessage["content"]
    if (content is String) return content
    if (content is List<*>) {
      return content.filterIsInstance<Map<*, *>>()
        .filter { it["type"] == "text" }
        .joinToString(separator = "\n") { it["text"] as? String ?: "" }
        .trim()
    }
    return ""
  }

  fun formatWallClock(timestampMs: Long): String {
    return DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
      .format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))
  }
}
