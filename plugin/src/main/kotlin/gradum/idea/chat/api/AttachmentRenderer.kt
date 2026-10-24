package gradum.idea.chat.api

import gradum.idea.chat.api.AttachmentRenderer.MAX_RENDERED_ERRORS
import gradum.idea.editor.ErrorEntry

/**
 * Renders the `gradum:` namespaced reference tags that the model sees for
 * every file-shaped attachment: `<gradum:file/>`, `<gradum:symbol/>` and
 * `<gradum:diagnostics>`.
 *
 * The tags carry no file content: the model must call `read_file` with the
 * given path (and `lineRange` when present) to fetch current content at
 * execution time, so a snapshot taken at attach time can never go stale.
 * The namespace marks the tags as IDE-injected, never user input.
 *
 * Rendering lives on the plugin side on purpose. The server treats the tag as
 * opaque text; every formatting decision (attribute syntax, escaping,
 * truncation) belongs to the side that owns attachment semantics.
 */
internal object AttachmentRenderer {

  private const val MAX_RENDERED_ERRORS: Int = 30
  private const val MAX_MESSAGE_CHARS: Int = 200

  /** Whole-file reference tag. */
  fun renderFileTag(path: String): String =
    "<gradum:file path=\"${escapeAttribute(path)}\"/>"

  /** Symbol reference tag: the named symbol inside [startLine]..[endLine]. */
  fun renderSymbolTag(path: String, symbolName: String, startLine: Int, endLine: Int): String =
    buildString {
      append("<gradum:symbol path=\"").append(escapeAttribute(path)).append('"')
      append(" symbol=\"").append(escapeAttribute(symbolName)).append('"')
      append(" lineRange=\"").append(startLine).append(" ~ ").append(endLine).append("\"/>")
    }

  /**
   * Diagnostics snapshot tag. Each problem becomes one
   * `<item line="N">message</item>` child (no line info omits the attribute);
   * at most [MAX_RENDERED_ERRORS] items are kept, the remainder collapses into
   * a trailing `<item dropped="N"/>`.
   */
  fun renderDiagnosticsTag(path: String, errors: List<ErrorEntry>): String {
    val kept: List<ErrorEntry> = errors.take(n = MAX_RENDERED_ERRORS)
    val dropped: Int = errors.size - kept.size
    return buildString {
      append("<gradum:diagnostics path=\"").append(escapeAttribute(path)).append("\">")
      for (entry: ErrorEntry in kept) {
        append("<item")
        if (entry.line > 0) append(" line=\"").append(entry.line).append('"')
        append(">").append(escapeText(sanitizeMessage(entry.message))).append("</item>")
      }
      if (dropped > 0) append("<item dropped=\"").append(dropped).append("\"/>")
      append("</gradum:diagnostics>")
    }
  }

  // Single-line message body, truncated with an explicit marker at the cap
  private fun sanitizeMessage(raw: String): String =
    raw.replace(oldChar = '\n', newChar = ' ')
      .replace(oldChar = '\t', newChar = ' ')
      .trim()
      .take(n = MAX_MESSAGE_CHARS)
      .let { if (it.length == MAX_MESSAGE_CHARS) "$it…" else it }

  private fun escapeText(raw: String): String =
    raw.replace(oldValue = "&", newValue = "&amp;")
      .replace(oldValue = "<", newValue = "&lt;")
      .replace(oldValue = ">", newValue = "&gt;")

  private fun escapeAttribute(raw: String): String =
    escapeText(raw).replace(oldValue = "\"", newValue = "&quot;")
}
