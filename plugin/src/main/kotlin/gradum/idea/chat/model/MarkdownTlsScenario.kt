/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MarkdownTlsScenario.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.model

/**
 * Compiles a Markdown document that embeds one or more `<tls>...</tls>`
 * blocks into a single, order-preserving tool-call scenario XML.
 *
 * All text between scenario blocks becomes a `<tt>` AI-reply segment, so
 * opening the `.md` in debug playback mode replays a full agent turn.
 *
 * ```markdown
 * Let me inspect the config first.
 * <tls>
 *   <t nam="read_file" pth="src/main/kotlin/gradum/AgentConfiguration.kt" lin="1-30"/>
 * </tls>
 * ```
 *
 * Fenced (```tls) variants are not treated specially; a raw `<tls>` block
 * is the only marker. Text containing the literal `]]>` sequence is escaped
 * so the emitted CDATA stays well-formed.
 */
object MarkdownTlsScenario {

  private val tlsBlockPattern = Regex("<tls\\b[^>]*>.*?</tls>", RegexOption.DOT_MATCHES_ALL)

  /** Returns the combined scenario XML, or `null` when [markdown] has no `<tls>` blocks. */
  fun compile(markdown: String, scenarioName: String? = null): String? {
    val blocks = tlsBlockPattern.findAll(markdown).toList()
    if (blocks.isEmpty()) return null

    val output = StringBuilder("<tls")
    if (!scenarioName.isNullOrBlank()) {
      output.append(" nam=\"").append(xmlEscape(scenarioName)).append('"')
    }
    output.append('>')

    var cursor = 0
    for (match in blocks) {
      val narration = markdown.substring(cursor, match.range.first).trim()
      if (narration.isNotEmpty()) {
        output.append("<tt>").append(cdata(narration)).append("</tt>")
      }
      val inner = match.value
        .substringAfter('>')
        .substringBeforeLast("<")
        .trim()
      if (inner.isNotEmpty()) output.append('\n').append(inner).append('\n')
      cursor = match.range.last + 1
    }

    val tail = markdown.substring(cursor).trim()
    if (tail.isNotEmpty()) {
      output.append("<tt>").append(cdata(tail)).append("</tt>")
    }

    output.append("</tls>")
    return output.toString()
  }

  private fun cdata(text: String): String {
    // `]]>` terminates a CDATA section; split and re-open it inside the
    // same element so the emitted XML stays well-formed.
    val safe = text.replace("]]>", "]]]]><![CDATA[>")
    return "<![CDATA[$safe]]>"
  }

  private fun xmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
}
