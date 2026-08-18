/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatSessionStore.kt  2026-08-17 13:56:04 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.history

import com.intellij.openapi.diagnostic.Logger
import gradum.idea.chat.history.ChatSessionStore.Companion.HEADER_LINE_LIMIT
import gradum.idea.chat.model.ChatMessage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Files.readString
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.stream.Stream
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
 * Project-scoped file store for chat session transcripts.
 *
 * Every session lives under `<projectRoot>/.gradum/sessions/<sessionId>/`
 * (the same directory rule the server uses for that session's `context.json`
 * — see [docs/CHAT_HISTORY_PLAN.md §1]). The plugin writes a
 * [ChatTranscript] `conversation.md` into it; deleteSession removes the
 * whole directory so server context is cascaded away too.
 *
 * @param projectRoot Absolute path of the IntelliJ project (from
 *   `Project.basePath`). All file operations are relative to it, never
 *   absolute-from-user-input, so deleteSession cannot escape the
 *   `.gradum/sessions/` root.
 */
class ChatSessionStore(private val projectRoot: Path) {

  private val log: Logger = Logger.getInstance(ChatSessionStore::class.java)

  /** Root directory holding all session directories: `<root>/.gradum/sessions`. */
  val sessionsRoot: Path get() = projectRoot.resolve(".gradum").resolve("sessions")

  /** Resolves the directory for one session. Callers must not trust [sessionId] blindly. */
  fun sessionDir(sessionId: String): Path = sessionsRoot.resolve(sessionId)

  /** Whether a session with [sessionId] currently has a saved transcript. */
  fun hasSession(sessionId: String): Boolean =
    Files.exists(sessionDir(sessionId).resolve(TRANSCRIPT_FILE))

  /**
   * Persists messages as a ChatTranscript for session sessionMeta.sessionId.
   *
   * Atomic write (temp file + rename, like the server's `ContextManager`)
   * so a crash mid-write never leaves a half-written transcript.
   */
  fun saveSession(sessionMeta: SessionMeta, messages: List<ChatMessage>) {
    val sessionDirectory: Path = sessionDir(sessionMeta.sessionId)
    try {
      Files.createDirectories(sessionDirectory)
      val content: String = ChatTranscript.generateTranscript(messages, sessionMeta)
      // Unique temp name per write: a fixed "$TRANSCRIPT_FILE.tmp" would let
      // two concurrent saves to the same session clobber each other's
      // half-written buffer before either rename lands.
      val tempFile: Path = sessionDirectory.resolve("$TRANSCRIPT_FILE.tmp-${System.nanoTime()}-${Random.nextInt(1_000_000)}")
      val targetFile: Path = sessionDirectory.resolve(TRANSCRIPT_FILE)
      Files.writeString(tempFile, content, Charsets.UTF_8)
      try {
        Files.move(
          tempFile, targetFile,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      } catch (atomicMoveException: AtomicMoveNotSupportedException) {
        log.warn("ATOMIC_MOVE not supported on this filesystem; falling back", atomicMoveException)
        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
      }
    } catch (saveException: Exception) {
      log.error("Failed to save session ${sessionMeta.sessionId}", saveException)
    }
  }

  /**
   * Lists all sessions, most recently updated first.
   *
   * Reads only each transcript's leading header lines
   * ([ChatTranscript.parseMeta]), so listing stays cheap even for large
   * conversations: the full body is never loaded from disk.
   */
  fun listSessions(): List<SessionMeta> {
    if (!Files.isDirectory(sessionsRoot)) return emptyList()

    val sessionList: MutableList<SessionMeta> = mutableListOf()
    Files.list(sessionsRoot).use { dirStream: Stream<Path> ->
      dirStream
        .filter { Files.isDirectory(it) }
        .forEach { sessionDirectory: Path ->
          val transcriptFile: Path = sessionDirectory.resolve(TRANSCRIPT_FILE)
          if (!Files.isRegularFile(transcriptFile)) return@forEach
          try {
            val sessionMeta: SessionMeta = ChatTranscript.parseMeta(readHeaderLines(transcriptFile))
            if (sessionMeta.sessionId.isNotEmpty()) sessionList.add(sessionMeta)
          } catch (listException: Exception) {
            log.warn("Skipping unreadable session transcript at $transcriptFile", listException)
          }
        }
    }
    return sessionList.sortedByDescending { it.updatedAt }
  }

  /** Reads only the leading [HEADER_LINE_LIMIT] lines of a transcript file. */
  private fun readHeaderLines(transcriptFile: Path): String {
    val headerBuilder: StringBuilder = StringBuilder()
    Files.newBufferedReader(transcriptFile, Charsets.UTF_8).use { bufferedReader ->
      var currentLine: String? = bufferedReader.readLine()
      var linesRead = 0
      while (currentLine != null && linesRead < HEADER_LINE_LIMIT) {
        headerBuilder.append(currentLine).append('\n')
        currentLine = bufferedReader.readLine()
        linesRead++
      }
    }
    return headerBuilder.toString()
  }

  /**
   * Loads one session's [ChatTranscript.ParsedTranscript] from disk.
   *
   * @return The parsed transcript, or `null` when the session does not exist
   *   or its transcript is unreadable.
   */
  @Suppress("UseOptimizedEelFunctions")
  fun loadSession(sessionId: String): ChatTranscript.ParsedTranscript? {
    val transcriptFile: Path = sessionDir(sessionId).resolve(TRANSCRIPT_FILE)
    if (!Files.isRegularFile(transcriptFile)) return null
    return try {
      ChatTranscript.parseTranscript(readString(transcriptFile, Charsets.UTF_8))
    } catch (loadException: Exception) {
      log.warn("Failed to loadProperties session $sessionId", loadException)
      null
    }
  }

  /**
   * Merges [sessionIds] (two or more) into a brand-new session whose
   * transcript is every source conversation interleaved chronologically by
   * message timestamp ("timeline weave" — see [interleaveMessages]).
   *
   * The source sessions are left untouched: merge is non-destructive. The
   * merged session gets a fresh [nextSessionId], the caller-supplied
   * [resultTitle], a `createdAt` of the earliest origin, and an `updatedAt`
   * of now so it surfaces at the top of the recent list.
   *
   * @return The new session id, or `null` when any source session is missing
   *   or its transcript cannot be parsed.
   */
  fun mergeSessions(sessionIds: List<String>, resultTitle: String): String? {
    val transcripts: List<ChatTranscript.ParsedTranscript> =
      sessionIds.map { transcriptId -> loadSession(transcriptId) ?: return null }
    val mergedMessages: List<ChatMessage> = interleaveMessages(transcripts.map { it.messages })
    val newSessionId: String = nextSessionId()
    saveSession(
      SessionMeta(
        title = resultTitle,
        sessionId = newSessionId,
        updatedAt = System.currentTimeMillis(),
        createdAt = transcripts.minOf { it.sessionMeta.createdAt },
        modelName = mergedMessages.lastOrNull()?.modelName.orEmpty()
      ),
      mergedMessages
    )
    return newSessionId
  }

  /**
   * Rewrites [sessionId]'s transcript header with [newTitle]. The
   * conversation body is untouched — messages are re-serialized unchanged
   * into the same session directory.
   *
   * @return `false` when the session does not exist or [newTitle] is blank.
   */
  fun renameSession(sessionId: String, newTitle: String): Boolean {
    val trimmedTitle: String = newTitle.trim()
    if (trimmedTitle.isEmpty()) return false
    val transcript: ChatTranscript.ParsedTranscript = loadSession(sessionId) ?: return false
    saveSession(transcript.sessionMeta.copy(title = trimmedTitle), transcript.messages)
    return true
  }

  /**
   * Deletes a session's entire directory (transcript included).
   *
   * The path is re-verified against [sessionsRoot] after resolution as a
   * belt-and-suspenders guard against path traversal — a session id coming
   * from the UI is trusted, but this must never be able to reach outside
   * `.gradum/sessions/`.
   *
   * @return `true` when the directory existed and was removed.
   */
  fun deleteSession(sessionId: String): Boolean {
    val sessionDirectory: Path = sessionDir(sessionId).normalize()
    if (!sessionDirectory.startsWith(sessionsRoot.normalize())) {
      log.warn("Refusing to delete session outside sessions root: $sessionDirectory")
      return false
    }
    if (!Files.isDirectory(sessionDirectory)) return false
    return try {
      Files.walk(sessionDirectory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
      log.info("Deleted session directory $sessionDirectory")
      true
    } catch (deleteException: Exception) {
      log.error("Failed to delete session $sessionId", deleteException)
      false
    }
  }

  companion object {
    const val TRANSCRIPT_FILE: String = "conversation.md"

    /** The session header always lives in the first lines; no need to read the body. */
    private const val HEADER_LINE_LIMIT: Int = 8

    /**
     * Chronological interleave of multiple conversations for
     * [ChatSessionStore.mergeSessions]. The concatenated message lists are
     * stably sorted by [ChatMessage.timestamp], so each source's own order
     * stays intact whenever timestamps tie.
     */
    fun interleaveMessages(conversations: List<List<ChatMessage>>): List<ChatMessage> =
      conversations.flatten().sortedWith(compareBy { it.timestamp })

    /** `yyyyMMdd-HHmmss-xxxxxx` — time prefix sorts lexicographically; 6-char hex suffix guards same-second collisions. */
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
      val suffixChars = CharArray(SUFFIX_CHARS) { HEX_CHARS[Random.nextInt(HEX_CHARS.size)] }
      return "$timeStamp-${String(suffixChars)}"
    }
  }
}
