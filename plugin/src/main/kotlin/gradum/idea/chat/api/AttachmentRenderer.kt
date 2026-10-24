package gradum.idea.chat.api

import gradum.idea.chat.api.AttachmentRenderer.MAX_RENDERED_ERRORS
import gradum.idea.editor.ErrorEntry

/**
 * Renders the `<FileAttachment/>` reference tag that the model sees for every
 * file-shaped attachment.
 *
 * The tag carries no file content: the model must call `read_file` with the
 * given path (and `lineRange` when present) to fetch current content at
 * execution time, so a snapshot taken at attach time can never go stale.
 *
 * Rendering lives on the plugin side on purpose. The server treats the tag as
 * opaque text; every formatting decision (attribute syntax, sanitization,
 * truncation) belongs to the side that owns attachment semantics.
 */
internal object AttachmentRenderer {

  private const val MAX_RENDERED_ERRORS: Int = 30
  private const val MAX_MESSAGE_CHARS: Int = 200

  /**
   * Builds one reference tag. Optional attributes are emitted only when their
   * source value is present; [errorSummary] comes from [renderErrorSummary].
   */
  fun renderFileTag(
    path: String,
    symbolName: String? = null,
    startLine: Int? = null,
    endLine: Int? = null,
    errorSummary: String? = null
  ): String {
    val rangeLabel: String =
      if (startLine != null && endLine != null) "$startLine ~ $endLine" else ""
    return buildString {
      append("<FileAttachment path=\"").append(sanitizeText(raw = path)).append('"')
      if (!symbolName.isNullOrBlank()) append(" symbol=\"").append(sanitizeText(raw = symbolName)).append('"')
      if (rangeLabel.isNotBlank()) append(" lineRange=\"").append(rangeLabel).append('"')
      if (!errorSummary.isNullOrBlank()) append(" errors=\"").append(errorSummary).append('"')
      append(" content=\"reference\"/>")
    }
  }

  /**
   * Serializes problem lines as `"line:message; line:message"`, entries with no
   * line info use `?`. Keeps the tag compact: at most [MAX_RENDERED_ERRORS]
   * entries, the remainder collapsed into a trailing `(+N more)`.
   */
  fun renderErrorSummary(errors: List<ErrorEntry>): String {
    if (errors.isEmpty()) return ""
    val kept: List<ErrorEntry> = errors.take(n = MAX_RENDERED_ERRORS)
    val body: String = kept.joinToString(separator = "; ") { entry ->
      val lineLabel: String = if (entry.line > 0) entry.line.toString() else "?"
      "$lineLabel:${sanitizeText(raw = entry.message)}"
    }
    val dropped: Int = errors.size - kept.size
    return if (dropped > 0) "$body; ($dropped more)" else body
  }

  private fun sanitizeText(raw: String): String =
    raw.replace(oldChar = '\n', newChar = ' ')
      .replace(oldChar = '\t', newChar = ' ')
      .replace(oldValue = "\"", newValue = "'")
      .trim()
      .take(n = MAX_MESSAGE_CHARS)
      .let { if (it.length == MAX_MESSAGE_CHARS) "$it…" else it }
}
