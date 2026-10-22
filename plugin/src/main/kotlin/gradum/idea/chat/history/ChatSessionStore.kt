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
 *   `Project.basePath`); sent to the server so it resolves the same
 *   sessions directory the agent writes to.
 *   bearer-token handling.
 *
 * Parameters:
 * - projectRoot: Absolute path of the IntelliJ project (from
 * - apiClient: HTTP client sharing the chat panel's base URL and
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
   *   unreachable so the UI degrades to "no history" instead of crashing.
   *
   * Returns The server's session list, or an empty list when the server is.
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
   *   server or the request failed.
   *
   * Returns The projection, or `null` when the session does not exist on the.
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
   *   the request fails.
   *
   * Returns The new session id, or `null` when a source session is missing or.
   */
  suspend fun mergeSessions(sessionIds: List<String>, baseTitle: String): String? =
    apiClient.mergeSessions(projectRoot = projectRootPath, sessionIds = sessionIds, baseTitle = baseTitle)

  /**
   * Renames [sessionId]'s transcript header title on the server. The
   * conversation body is untouched.
   *
   *   the request fails.
   *
   * Returns `false` when the session does not exist, [newTitle] is blank, or.
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
   * Returns `true` when the server confirmed deletion..
   */
  suspend fun deleteSession(sessionId: String): Boolean =
    apiClient.deleteSession(projectRoot = projectRootPath, sessionId = sessionId)
}
