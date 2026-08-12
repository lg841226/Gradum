/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * LatexBlockExtension.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import org.commonmark.Extension
import org.commonmark.node.Block
import org.commonmark.node.CustomBlock
import org.commonmark.parser.Parser
import org.commonmark.parser.block.*

/**
 * A LaTeX block: `$$\n<formula>\n$$`. The `$$` markers must sit on
 * their own lines (per the project's LaTeX plan). The body is the
 * raw formula source — we don't parse it, the renderer hands it
 * to the LaTeX library. Stored verbatim (no whitespace trimming
 * beyond the [LatexBlockParser.closeBlock] edge-strip) so the
 * user can re-serialize and round-trip without surprises.
 */
class LatexBlock : CustomBlock() {
  /** The raw LaTeX source between the `$$` markers. May contain newlines. */
  var formula: String = ""

  override fun accept(visitor: org.commonmark.node.Visitor) {
    visitor.visit(this)
  }
}

/**
 * CommonMark [Extension] for `$$…$$` blocks. The custom block
 * parser is the only thing the Gradum chat needs — there is no
 * HTML / text-content renderer for the chat, so we don't register
 * any [org.commonmark.renderer.html.HtmlRenderer.HtmlRendererExtension]
 * on the extension surface. (The chat ignores the stock
 * `HtmlRenderer` entirely — the Markdown UI is Compose, not HTML.)
 *
 * Register on every commonmark [Parser] in the project so the
 * inline path (`splitPlainAtBlocks` → `RenderNonProseBlock`
 * round-trip) and the reparse path (code-block fallback
 * `GradumMarkdownProcessor`) both see the same `LatexBlock` nodes.
 */
class LatexBlockExtension private constructor() : Parser.ParserExtension {

  override fun extend(parserBuilder: Parser.Builder) {
    parserBuilder.customBlockParserFactory(LatexBlockParser.Factory())
  }

  companion object {
    @JvmStatic
    fun create(): Extension = LatexBlockExtension()
  }
}

/**
 * Detects `$$` on its own line as the start of a LaTeX block and
 * reads until the next `$$` on its own line. Both markers are
 * stripped from the captured formula; the [LatexBlock] node
 * stores only the inner source.
 *
 * The opening line must be exactly `$$` (no trailing text, no
 * leading whitespace past the indent) — this matches the user's
 * plan rule "`$$` 必须独占行". A line like `$$ x^2 $$` does NOT
 * open a block; that is treated as plain prose (and the inline
 * `$x^2$` regex would still match the inner pair). This avoids
 * accidental matches against dollar amounts in chat.
 */
internal class LatexBlockParser : AbstractBlockParser() {

  private val block: LatexBlock = LatexBlock()
  private val lines: MutableList<String> = mutableListOf()

  override fun getBlock(): Block = block

  override fun tryContinue(state: ParserState): BlockContinue {
    val rawLine: String = state.line.content.toString()
    val isClosingLine: Boolean = rawLine.trim() == "$$"
    if (isClosingLine) return BlockContinue.finished()
    lines.add(rawLine)
    return BlockContinue.atIndex(state.index)
  }

  /**
   * Called once the closing line has been consumed — store the
   * captured body. The opening `$$` was already consumed by
   * [Factory.tryStart]; the closing line is `$$` itself, not
   * part of the formula. First / last blank lines are trimmed
   * so a `$$\n\int\n$$` source becomes the formula `"\int"`
   * rather than `"\n\int\n"`. We also `trim()` the first and
   * last surviving line so a casual `$$\n x^2 \n$$` (with
   * stray spaces around the body) is normalized to `x^2` —
   * the LaTeX library is sensitive to leading / trailing
   * whitespace and chat users won't think to remove it.
   * Internal newlines and the whitespace between non-edge
   * lines are preserved.
   */
  override fun closeBlock() {
    val trimmedEdges: List<String> = lines.trimBlankEdges()
    if (trimmedEdges.isEmpty()) {
      block.formula = ""
      return
    }
    val firstIndex = 0
    val lastIndex: Int = trimmedEdges.size - 1
    val cleanedLines: List<String> = buildList(trimmedEdges.size) {
      trimmedEdges.forEachIndexed { lineIndex, line ->
        val normalized: String = when (lineIndex) {
          firstIndex, lastIndex -> line.trim()
          else -> line
        }
        add(normalized)
      }
    }
    block.formula = cleanedLines.joinToString(separator = "\n")
  }

  /**
   * `AbstractBlockParser#addLine` is also called for the
   * opening line. We don't need it — the factory's `tryStart`
   * already consumed the opening `$$`. Override to a no-op
   * so the opening line isn't double-added to [lines].
   */
  override fun addLine(line: org.commonmark.parser.SourceLine) {
    /* no-op — opening line was already consumed by tryStart */
  }

  class Factory : AbstractBlockParserFactory() {
    override fun tryStart(
      state: ParserState,
      matchedBlockParser: MatchedBlockParser
    ): BlockStart? {
      val rawLine: String = state.line.content.toString()
      val trimmedLine: String = rawLine.trim()
      if (trimmedLine == "$$") {
        return BlockStart.of(LatexBlockParser()).atIndex(state.index)
      }
      if (trimmedLine.startsWith("$$") && trimmedLine.endsWith("$$") && trimmedLine.length > 4) {
        return BlockStart.of(SingleLineLatexBlockParser(trimmedLine)).atIndex(state.index)
      }
      return null
    }
  }
}

/**
 * A block parser for single-line `$$...$$` formulas. Unlike
 * [LatexBlockParser] which requires `$$` on separate lines,
 * this parser captures the formula content from a single line
 * like `$$2x^2 - 7x + 3 = 0$$`.
 */
internal class SingleLineLatexBlockParser(private val formula: String) : AbstractBlockParser() {

  private val block: LatexBlock = LatexBlock()

  init {
    block.formula = formula.removePrefix("$$").removeSuffix("$$").trim()
  }

  override fun getBlock(): Block = block

  override fun tryContinue(state: ParserState): BlockContinue = BlockContinue.finished()

  override fun closeBlock() { /* formula already set in init */
  }

  override fun addLine(line: org.commonmark.parser.SourceLine) { /* no-op */
  }
}

/** Strip empty / whitespace-only lines from the start and end of a list. */
private fun List<String>.trimBlankEdges(): List<String> {
  if (isEmpty()) return this
  var startIndex = 0
  var endIndex: Int = size - 1
  while (startIndex <= endIndex && this[startIndex].isBlank()) startIndex += 1
  while (endIndex >= startIndex && this[endIndex].isBlank()) endIndex -= 1
  if (startIndex > endIndex) return emptyList()
  return subList(startIndex, endIndex + 1)
}
