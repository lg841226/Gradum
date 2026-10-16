package gradum.idea.chat.history

import com.intellij.openapi.diagnostic.Logger
import gradum.idea.chat.api.GradumApiClient
import gradum.idea.chat.model.ChatMessage
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Lightweight metadata for one persisted chat session, shown in the
 * Welcome screen's "Recent Chats" list.
 */
data class SessionMeta(
  val title: String,
  val modelName: String,
  val sessionId: String,
  val createdAt: Long,
  val updatedAt: Long
)

/**
 * Project-scoped store for chat session transcripts, backed by the Gradum
 * server REST API. The server owns `<projectRoot>/.gradum/sessions/` and
 * writes each session's `conversation.md` itself after every turn, so the
 * plugin no longer touches those files: it lists/loads via
 * `GET /sessions` / `GET /session/transcript`, renames/deletes via
 * `POST /session/rename` / `POST /session/delete`, and only the merge flow
 * uploads a client-woven transcript via `POST /session/save`
 * (see [gradum.idea.chat.history.ChatTranscript] for the v1 format).
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
   * Loads one session's [ChatTranscript.ParsedTranscript] from the server.
   *
   * @return The parsed transcript, or `null` when the session does not exist
   *   on the server or its transcript cannot be parsed.
   */
  suspend fun loadSession(sessionId: String): ChatTranscript.ParsedTranscript? {
    val transcriptContent: String =
      apiClient.loadTranscript(projectRoot = projectRootPath, sessionId = sessionId) ?: return null
    return try {
      ChatTranscript.parseTranscript(content = transcriptContent)
    } catch (parseException: Exception) {
      log.warn("Failed to parse transcript for session $sessionId", parseException)
      null
    }
  }

  /**
   * Merges [sessionIds] (two or more) into a brand-new session whose
   * transcript is every source conversation interleaved chronologically by
   * message timestamp ("timeline weave": see [interleaveMessages]).
   *
   * The source sessions are left untouched: merge is non-destructive. The
   * merged messages are serialized with the v1 [ChatTranscript] format and
   * uploaded to the server via `POST /session/save`, since the server is the
   * single writer of the sessions directory. The merged session gets a fresh
   * [nextSessionId], the caller-supplied [resultTitle], a `createdAt` of the
   * earliest origin, and an `updatedAt` of now so it surfaces at the top of
   * the recent list.
   *
   * @return The new session id, or `null` when any source session is
   *   missing, its transcript cannot be parsed, or the upload fails.
   */
  suspend fun mergeSessions(sessionIds: List<String>, resultTitle: String): String? {
    val transcripts: List<ChatTranscript.ParsedTranscript> =
      sessionIds.map { transcriptId: String ->
        loadSession(sessionId = transcriptId) ?: return null
      }
    val mergedMessages: List<ChatMessage> = interleaveMessages(conversations = transcripts.map { it.messages })
    val newSessionId: String = nextSessionId()
    val saved: Boolean = apiClient.saveTranscript(
      projectRoot = projectRootPath,
      sessionId = newSessionId,
      title = resultTitle,
      content = ChatTranscript.generateTranscript(
        messages = mergedMessages,
        sessionMeta = SessionMeta(
          title = resultTitle,
          sessionId = newSessionId,
          updatedAt = System.currentTimeMillis(),
          createdAt = transcripts.minOf { it.sessionMeta.createdAt },
          modelName = mergedMessages.lastOrNull()?.modelName.orEmpty()
        )
      )
    )
    if (!saved) {
      log.warn("Server rejected merged transcript for session $newSessionId")
      return null
    }
    return newSessionId
  }

  /**
   * Renames [sessionId]'s transcript header title on the server. The
   * conversation body is untouched.
   *
   * @return `false` when the session does not exist, [newTitle] is blank, or
   *   the request fails.
   */
  suspend fun renameSession(sessionId: String, newTitle: String): Boolean {
    val trimmedTitle: String = newTitle.trim()
    if (trimmedTitle.isEmpty()) return false
    return apiClient.renameSession(
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

  companion object {

    /**
     * Chronological interleave of multiple conversations for
     * [ChatSessionStore.mergeSessions]. The concatenated message lists are
     * stably sorted by [timestamp], so each source's own order
     * stays intact whenever timestamps tie.
     */
    fun interleaveMessages(conversations: List<List<ChatMessage>>): List<ChatMessage> =
      conversations.flatten().sortedWith(comparator = compareBy { it.timestamp })

    /** `yyyyMMdd-HHmmss-xxxxxx`: time prefix sorts lexicographically; 6-char hex suffix guards same-second collisions. */
    private val SESSION_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val HEX_CHARS: CharArray = "0123456789abcdef".toCharArray()

    private const val SUFFIX_CHARS: Int = 6

    /**
     * Generates a session id: `yyyyMMdd-HHmmss-xxxxxx` (see
     * [docs/CHAT_HISTORY_PLAN.md §1.2]). The 6-char hex suffix keeps
     * same-second collision probability negligible (~0.03% for 100 ids in
     * one second, birthday-paradox) while the time prefix keeps the id
     * lexicographically sortable.
     */
    fun nextSessionId(): String {
      val timeStamp: String = LocalDateTime.now().format(SESSION_ID_FORMAT)
      val suffixChars = CharArray(size = SUFFIX_CHARS) {
        HEX_CHARS[Random.nextInt(until = HEX_CHARS.size)]
      }
      return "$timeStamp-${String(suffixChars)}"
    }
  }
}
