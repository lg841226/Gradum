/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MarkdownTlsScenario.kt  2026-08-09 22:20:00 Changed by gwy
 */

package gradum.idea.chat.model

/**
 * Compiles a Markdown document that embeds one or more `<tls>...</tls>`
 * blocks into a single, order-preserving tool-call scenario XML.
 *
 * Only the tool blocks are carried over: narration paragraphs between the
 * blocks are NOT embedded as scenario text, because AI reply text is
 * produced by the real LLM stream — playback scenarios declare tool calls
 * only. A Markdown doc therefore plays back as one assistant turn whose
 * narration comes from the model and whose tool calls are exactly the
 * `<tls>` blocks the author placed.
 *
 * ```markdown
 * Let me inspect the config first.
 *
 * <tls>
 *   <t nam="read_file" pth="src/main/kotlin/gradum/AgentConfiguration.kt" lin="1-30"/>
 * </tls>
 *
 * Found something; searching for more.
 * ```
 *
 * compiles to:
 *
 * ```
 * <tls name="intro.md">
 *   <t nam="read_file" pth="src/main/kotlin/gradum/AgentConfiguration.kt" lin="1-30"/>
 * </tls>
 * ```
 *
 * Fenced (```tls) variants are not treated specially; a raw `<tls>`
 * block is the only marker.
 */
object MarkdownTlsScenario {

  private val TLS_BLOCK: Regex = Regex("<tls\\b[^>]*>.*?</tls>", RegexOption.DOT_MATCHES_ALL)

  /**
   * @return a combined scenario XML string, or `null` when [markdown]
   *   contains no `<tls>` blocks (caller should fall back to plain
   *   Markdown rendering).
   */
  fun compile(markdown: String, scenarioName: String? = null): String? {
    val blocks = TLS_BLOCK.findAll(markdown).toList()
    if (blocks.isEmpty()) return null

    val output = StringBuilder()
    output.append("<tls")
    if (!scenarioName.isNullOrBlank()) {
      output.append(" nam=\"").append(xmlEscape(scenarioName)).append('"')
    }
    output.append('>')

    var first = true
    for (match in blocks) {
      val inner = match.value
        .substringAfter('>')
        .substringBeforeLast("<")
        .trim()
      if (inner.isEmpty()) continue
      if (!first) output.append('\n')
      output.append('\n').append(inner).append('\n')
      first = false
    }

    output.append("</tls>")
    return output.toString()
  }

  private fun xmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
}