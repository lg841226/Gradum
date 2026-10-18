package gradum.idea.chat.history

import com.intellij.openapi.diagnostic.Logger
import gradum.idea.chat.api.GradumApiClient
import java.nio.file.Path

data class SessionMeta(
  val title: String,
  val modelName: String,
  val sessionId: String,
  val createdAt: Long,
  val updatedAt: Long
)

/**
 * Project-scoped store for chat sessions, backed by the Gradum server REST
 * API. The server owns `<projectRoot>/.gradum/sessions/`: it writes each
 * session's `session-state.json` sidecar and `conversation.md` render itself,
 * so the plugin lists via `GET /sessions`, cold-loads the authoritative
 * projection via `GET /session/state`, and renames/deletes/merges via
 * `POST /session/rename` / `POST /session/delete` / `POST /sessions/merge`.
 *
 * @param projectRoot Absolute path of the IntelliJ project (from
 *   `Project.basePath`); sent to the server so it resolves the same
 *   sessions directory the agent writes to.
 * @param apiClient HTTP client sharing the chat panel's base URL and
 *   bearer-token handling.
 */
class ChatSessionStore(
  private val projectRoot: Path,
  private val apiClient: GradumApiClient
) {

  private val log: Logger = Logger.getInstance(ChatSessionStore::class.java)

  private val projectRootPath: String get() = projectRoot.toString()

  /**
   * Lists all sessions, most recently updated first.
   *
   * @return The server's session list, or an empty list when the server is
   *   unreachable so the UI degrades to "no history" instead of crashing.
   */
  suspend fun listSessions(): List<SessionMeta> {
    return try {
      apiClient.listSessions(projectRoot = projectRootPath).map { sessionRow: GradumApiClient.ApiSessionSummary ->
        SessionMeta(
          title = sessionRow.title,
          modelName = sessionRow.modelName,
          sessionId = sessionRow.sessionId,
          createdAt = sessionRow.createdAt,
          updatedAt = sessionRow.updatedAt
        )
      }
    } catch (listException: Exception) {
      if (listException is kotlinx.coroutines.CancellationException) throw listException
      log.warn("Failed to list sessions from ${apiClient.baseUrl}", listException)
      emptyList()
    }
  }

  /**
   * Loads one session's authoritative projection (`GET /session/state`).
   *
   * @return The projection, or `null` when the session does not exist on the
   *   server or the request failed.
   */
  suspend fun loadSessionState(sessionId: String): GradumApiClient.ApiSessionState? =
    apiClient.fetchSessionState(projectRoot = projectRootPath, sessionId = sessionId)

  /**
   * Merges [sessionIds] (two or more) into a fresh server-side session.
   *
   * The server reads each source's sidecar, concatenates their messages in
   * timeline order, numbers [baseTitle] per the existing sessions, and writes
   * the merged session itself, so the plugin makes no merge decisions.
   *
   * @return The new session id, or `null` when a source session is missing or
   *   the request fails.
   */
  suspend fun mergeSessions(sessionIds: List<String>, baseTitle: String): String? =
    apiClient.mergeSessions(projectRoot = projectRootPath, sessionIds = sessionIds, baseTitle = baseTitle)

  /**
   * Renames [sessionId]'s transcript header title on the server. The
   * conversation body is untouched.
   *
   * @return `false` when the session does not exist, [newTitle] is blank, or
   *   the request fails.
   */
  suspend fun renameSession(sessionId: String, newTitle: String): Boolean {
    val trimmedTitle: String = newTitle.trim()

    return trimmedTitle.isNotEmpty() && apiClient.renameSession(
      projectRoot = projectRootPath,
      sessionId = sessionId,
      title = trimmedTitle
    )
  }

  /**
   * Deletes a session on the server: its transcript directory and the
   * matching chat DB row.
   *
   * @return `true` when the server confirmed deletion.
   */
  suspend fun deleteSession(sessionId: String): Boolean =
    apiClient.deleteSession(projectRoot = projectRootPath, sessionId = sessionId)
}
