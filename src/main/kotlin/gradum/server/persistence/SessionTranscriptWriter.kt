package gradum.server.persistence

import gradum.server.persistence.SessionTranscriptWriter.HEADER_LINE_LIMIT
import gradum.server.persistence.SessionTranscriptWriter.parseAttributes
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger: Logger = LoggerFactory.getLogger("SessionTranscriptWriter")

/**
 * Server-side writer of the structured-Markdown chat transcript
 * (`conversation.md`) for a session. The sidecar `session-state.json` is the
 * machine-readable authority (`SessionStateStore`); this file is its
 * human-readable render, kept so `GET /sessions` can read the header cheaply.
 * The format is a `gradum-transcript v1` header with a session metadata line,
 * then `## user` / `## assistant` sections whose content blocks are framed by
 * `gradum-msg` / `gradum-thinking` / `gradum-toolcall` / `gradum-toolresult`
 * / `gradum-response` / `gradum-attachment` markers. The client no longer
 * parses it back; the sidecar is what it cold-loads.
 *
 * Messages arrive as the raw agent history maps (role / content plus the
 * bookkeeping keys `id`, `modelName`, `thinking`, `tokenUsage`,
 * `attachments`, `alias`, `tool_calls`, `tool_call_id`); tool result
 * messages are folded into the toolcall blocks of the assistant message
 * that issued the call.
 */
object SessionTranscriptWriter {

  const val TRANSCRIPT_FILE: String = "conversation.md"
  const val MAX_TITLE_LENGTH: Int = 48
  private const val HEADER_LINE_LIMIT: Int = 8
  private const val HEADER_V1: String = "<!-- gradum-transcript v1 -->"
  private const val SESSION_PREFIX: String = "<!-- gradum-session"
  private const val THINKING_MARKER: String = "<!-- gradum-thinking -->"
  private const val TOOLRESULT_MARKER: String = "<!-- gradum-toolresult -->"
  private const val RESPONSE_MARKER: String = "<!-- gradum-response -->"
  private const val USER_HEADER: String = "## user"
  private const val ASSISTANT_HEADER: String = "## assistant"
  private val ATTRIBUTE_PATTERN: Regex = Regex(pattern = """(\w+)="((?:\\.|[^"])*)"""")

  data class TranscriptMeta(
    val title: String,
    val sessionId: String,
    val modelName: String,
    val createdAt: Long,
    val updatedAt: Long
  )

  /**
   * Renders [messages] into `<projectRoot>/.gradum/sessions/<sessionId>/conversation.md`
   * (temp file + atomic rename, like `ContextManager`). The header's
   * `createdAt` is [createdAt] when given (merge passes its earliest origin),
   * else the ChatDb session row when one exists, else the write time; the
   * per-message timestamps fall back to the write time: the in-memory
   * history carries no per-message clock, and the stored rows share one
   * `created_at` per save anyway.
   */
  fun writeTranscript(
    projectRoot: Path,
    sessionId: String,
    modelName: String,
    messages: List<Map<String, Any>>,
    title: String? = null,
    createdAt: Long? = null,
  ) {
    val sessionDirectory: Path = sessionDirectory(projectRoot, sessionId) ?: return
    val trimmedId: String = sessionId.trim()
    val nowMillis: Long = System.currentTimeMillis()
    val sessionCreatedAt: Long = createdAt
      ?: ChatDb.sessionTimestamps(projectRoot = projectRoot, sessionId = trimmedId)?.first
      ?: nowMillis
    val sessionMeta = TranscriptMeta(
      title = title ?: titleFor(messages),
      sessionId = trimmedId,
      modelName = modelName,
      createdAt = sessionCreatedAt,
      updatedAt = nowMillis
    )
    Files.createDirectories(sessionDirectory)
    atomicWrite(
      targetFile = sessionDirectory.resolve(TRANSCRIPT_FILE),
      content = renderTranscript(sessionMeta, messages)
    )
  }

  /**
   * Lists every session's header metadata, most recently updated first.
   * Reads only the leading [HEADER_LINE_LIMIT] lines of each transcript,
   * so listing stays cheap for large conversations.
   */
  fun listSessions(projectRoot: Path): List<TranscriptMeta> {
    val sessionsRoot: Path = projectRoot.resolve(".gradum").resolve("sessions")
    if (!Files.isDirectory(sessionsRoot)) return emptyList()

    val sessionList: MutableList<TranscriptMeta> = mutableListOf()
    Files.list(sessionsRoot).use { directoryStream ->
      directoryStream
        .filter { directory -> Files.isDirectory(directory) }
        .forEach { sessionDirectory: Path ->
          val sessionMeta: TranscriptMeta? =
            readSessionMeta(transcriptFile = sessionDirectory.resolve(TRANSCRIPT_FILE))
          if (sessionMeta != null && sessionMeta.sessionId.isNotEmpty()) sessionList.add(sessionMeta)
        }
    }
    return sessionList.sortedByDescending { it.updatedAt }
  }

  /** Header metadata of one session's transcript; null when absent or unparseable. */
  fun readMeta(projectRoot: Path, sessionId: String): TranscriptMeta? {
    val sessionDirectory: Path = sessionDirectory(projectRoot, sessionId) ?: return null
    return readSessionMeta(transcriptFile = sessionDirectory.resolve(TRANSCRIPT_FILE))
  }

  /**
   * Rewrites the session marker's `title` in the transcript header, leaving
   * the conversation body byte-identical. Returns `false` when the session
   * does not exist or [newTitle] is blank.
   */
  fun renameSession(projectRoot: Path, sessionId: String, newTitle: String): Boolean {
    val trimmedTitle: String = newTitle.trim()
    if (trimmedTitle.isEmpty()) return false
    val sessionDirectory: Path = sessionDirectory(projectRoot, sessionId) ?: return false
    val transcriptFile: Path = sessionDirectory.resolve(TRANSCRIPT_FILE)

    return Files.isRegularFile(transcriptFile) && try {
      val transcriptContent: String = Files.readString(transcriptFile, Charsets.UTF_8)
      var replacedMarker = false
      val updatedLines: List<String> = transcriptContent.lines().map { line: String ->
        if (!replacedMarker && line.startsWith(SESSION_PREFIX)) {
          replacedMarker = true
          sessionMarkerLine(markerLine = line, newTitle = trimmedTitle)
        } else {
          line
        }
      }
      if (!replacedMarker) return false
      atomicWrite(targetFile = transcriptFile, content = updatedLines.joinToString(separator = "\n"))
      true
    } catch (renameException: Exception) {
      logger.warn("Failed to rename session {}: {}", sessionId, renameException.message)
      false
    }
  }

  /** Resolves the session directory, rejecting blank/legacy/path-traversing ids. */
  internal fun sessionDirectory(projectRoot: Path, sessionId: String): Path? {
    val trimmedId: String = sessionId.trim()
    val isUnsafeId: Boolean = trimmedId.isEmpty() || trimmedId == "." || trimmedId == ".." ||
      trimmedId == ".gradum" || trimmedId.contains(char = '/') || trimmedId.contains(char = '\\')
    if (isUnsafeId) return null
    val sessionsRoot: Path = projectRoot.resolve(".gradum").resolve("sessions")
    val resolvedDirectory: Path = sessionsRoot.resolve(trimmedId).normalize()
    if (resolvedDirectory.parent != sessionsRoot.normalize()) return null
    return resolvedDirectory
  }

  /** Derives the title: first non-blank line of the first user message, truncated. */
  private fun titleFor(messages: List<Map<String, Any>>): String {
    val firstUserMessage: Map<String, Any> =
      messages.firstOrNull { it["role"] == "user" } ?: return ""
    val firstLine: String =
      SessionBubbleProjection.readUser(firstUserMessage).text
        .lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    return firstLine.take(n = MAX_TITLE_LENGTH)
  }

  private fun renderTranscript(meta: TranscriptMeta, messages: List<Map<String, Any>>): String {
    val contentBuilder: StringBuilder = StringBuilder()
    contentBuilder.append(HEADER_V1).append('\n')
    contentBuilder.append(
      "<!-- gradum-session " +
        "id=\"${escapeValue(meta.sessionId)}\" " +
        "title=\"${escapeValue(meta.title)}\" " +
        "createdAt=\"${meta.createdAt}\" " +
        "updatedAt=\"${meta.updatedAt}\" " +
        "model=\"${escapeValue(meta.modelName)}\" -->\n\n"
    )

    val writtenAt: Long = meta.updatedAt
    var messageIndex = 0
    while (messageIndex < messages.size) {
      val message: Map<String, Any> = messages[messageIndex]
      when (message["role"] as? String) {
        "user" -> {
          val user: UserRow = SessionBubbleProjection.readUser(message)
          contentBuilder.append(USER_HEADER).append('\n')
          contentBuilder.append(
            "<!-- gradum-msg role=\"user\" timestamp=\"$writtenAt\"${idAttribute(user.id)} -->"
          ).append('\n')
          for (attachmentName: String in user.attachmentNames) {
            contentBuilder.append(
              "<!-- gradum-attachment name=\"${escapeValue(attachmentName)}\" path=\"\" -->\n"
            )
          }
          contentBuilder.append(user.text).append('\n')
          messageIndex++
        }

        "assistant" -> {
          val row: AssistantRow = SessionBubbleProjection.readAssistant(messages, messageIndex)
          if (row.thinking.isBlank() && row.response.isBlank() && row.toolCalls.isEmpty()) {
            messageIndex++; continue
          }

          contentBuilder.append(ASSISTANT_HEADER).append('\n')
          contentBuilder.append(
            "<!-- gradum-msg role=\"assistant\" timestamp=\"$writtenAt\"${idAttribute(row.id)}" +
              " model=\"${escapeValue(row.modelName)}\"" +
              " provider=\"\" server=\"\"${tokenAttributes(row.tokenUsage)} -->"
          ).append('\n')

          if (row.thinking.isNotBlank()) {
            contentBuilder.append(THINKING_MARKER).append('\n')
            contentBuilder.append(row.thinking).append('\n')
          }

          for (call: ProjectedToolCall in row.toolCalls) {
            contentBuilder.append(
              "<!-- gradum-toolcall " +
                "name=\"${escapeValue(call.name)}\" " +
                "alias=\"${escapeValue(call.alias)}\" " +
                "id=\"${escapeValue(call.callId)}\" " +
                "success=\"true\" " +
                "errorMessage=\"\" errorDetail=\"\" -->\n"
            )
            contentBuilder.append(SessionBubbleProjection.argumentsJson(call.arguments)).append('\n')
            contentBuilder.append(TOOLRESULT_MARKER).append('\n')
            contentBuilder.append(call.result).append('\n')
          }

          if (row.response.isNotBlank()) {
            contentBuilder.append(RESPONSE_MARKER).append('\n')
            contentBuilder.append(row.response).append('\n')
          }
          messageIndex = row.nextIndex
        }

        else -> messageIndex++
      }
    }
    return contentBuilder.toString()
  }

  /** ` id="..."` fragment when the message carries a non-blank id, else empty. */
  private fun idAttribute(messageId: String): String {
    if (messageId.isBlank()) return ""
    return " id=\"${escapeValue(messageId)}\""
  }

  /** Token-usage attributes for a row's usage map (never present with a zero total). */
  private fun tokenAttributes(usage: Map<String, Any>?): String {
    if (usage == null) return ""
    val promptTokens: Int = (usage["promptTokens"] as? Number)?.toInt() ?: 0
    val completionTokens: Int = (usage["completionTokens"] as? Number)?.toInt() ?: 0
    val totalTokens: Int = (usage["totalTokens"] as? Number)?.toInt() ?: 0
    val contextTokens: Int = (usage["contextTokens"] as? Number)?.toInt() ?: 0
    return " promptTokens=\"$promptTokens\"" +
      " completionTokens=\"$completionTokens\"" +
      " totalTokens=\"$totalTokens\"" +
      " contextTokens=\"$contextTokens\""
  }

  /**
   * Rebuilds the session marker line with [newTitle], preserving every other
   * attribute verbatim (values were unescaped by [parseAttributes] and carry
   * no quotes, so re-escaping only applies to id/title/model).
   */
  private fun sessionMarkerLine(markerLine: String, newTitle: String): String {
    val attributeMap: Map<String, String> = parseAttributes(markerLine)
    return "<!-- gradum-session " +
      "id=\"${escapeValue(attributeMap["id"].orEmpty())}\" " +
      "title=\"${escapeValue(newTitle)}\" " +
      "createdAt=\"${attributeMap["createdAt"].orEmpty()}\" " +
      "updatedAt=\"${attributeMap["updatedAt"].orEmpty()}\" " +
      "model=\"${escapeValue(attributeMap["model"].orEmpty())}\" -->"
  }

  /** Reads the session metadata from a transcript's leading lines; null when absent. */
  private fun readSessionMeta(transcriptFile: Path): TranscriptMeta? {
    if (!Files.isRegularFile(transcriptFile)) return null
    return try {
      Files.newBufferedReader(transcriptFile, Charsets.UTF_8).use { bufferedReader ->
        var linesRead = 0
        var currentLine: String? = bufferedReader.readLine()
        while (currentLine != null && linesRead < HEADER_LINE_LIMIT) {
          if (currentLine.startsWith(SESSION_PREFIX)) {
            val attributeMap: Map<String, String> = parseAttributes(currentLine)
            return TranscriptMeta(
              title = attributeMap["title"].orEmpty(),
              sessionId = attributeMap["id"].orEmpty(),
              modelName = attributeMap["model"].orEmpty(),
              createdAt = attributeMap["createdAt"]?.toLongOrNull() ?: 0L,
              updatedAt = attributeMap["updatedAt"]?.toLongOrNull() ?: 0L
            )
          }
          currentLine = bufferedReader.readLine()
          linesRead++
        }
        null
      }
    } catch (readException: Exception) {
      logger.warn("Skipping unreadable session transcript at $transcriptFile: ${readException.message}")
      null
    }
  }

  /** Temp file and atomic rename, matching `ContextManager`'s crash-safe write. */
  private fun atomicWrite(targetFile: Path, content: String) {
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
      logger.warn(
        "ATOMIC_MOVE not supported on this filesystem; falling back: ${moveException.message}"
      )
      Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun parseAttributes(marker: String): Map<String, String> {
    val attributeMap: MutableMap<String, String> = mutableMapOf()
    for (match: MatchResult in ATTRIBUTE_PATTERN.findAll(input = marker)) {
      attributeMap[match.groupValues[1]] = unescapeValue(match.groupValues[2])
    }
    return attributeMap
  }

  /** Escaping rules for marker attribute values (`\` and `"`, newlines folded to spaces). */
  private fun escapeValue(value: String): String {
    return value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\r", " ")
      .replace("\n", " ")
  }

  private fun unescapeValue(value: String): String {
    if (value.indexOf(char = '\\') < 0) return value
    val unescapedBuilder: StringBuilder = StringBuilder(value.length)
    var currentIndex = 0
    while (currentIndex < value.length) {
      val currentChar: Char = value[currentIndex]
      if (currentChar == '\\' && currentIndex + 1 < value.length) {
        unescapedBuilder.append(value[currentIndex + 1])
        currentIndex += 2
      } else {
        unescapedBuilder.append(currentChar)
        currentIndex++
      }
    }
    return unescapedBuilder.toString()
  }
}
