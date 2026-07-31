/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumLatexTest.kt  2026-07-31 11:05:53 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.commonmark.node.Block
import org.commonmark.node.Document
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class GradumLatexTest {

  // ─── Test fixtures ──────────────────────────────────────────────────

  private val testTint: Color = Color(0xFF60A5FA)
  private val testLinkColor: Color = Color(0xFF3B82F6)
  private val testImageAltColor: Color = Color(0xFF888888)
  private val testFontSizeSp: Float = 14f

  /**
   * JVM-only CommonMark parser with the LaTeX block extension.
   * Mirrors the [Parser] used by [splitPlainAtBlocks] in production.
   */
  private val latexBlockParser: Parser = Parser.builder()
    .extensions(listOf(LatexBlockExtension.create()))
    .build()

  /** Render a single paragraph of inline text through the inline walker. */
  private fun inlineRender(text: String): InlineMarkdownRenderResult =
    parseInlineMarkdown(
      plainText = text,
      fontSizeSp = testFontSizeSp,
      chipTint = testTint,
      linkColor = testLinkColor,
      imageAltColor = testImageAltColor
    )

  /**
   * Visible text of an [AnnotatedString] after dropping PUA placeholders
   * (code / image / footnote / LaTeX chips all share the PUA block,
   * plus the paren-form LaTeX markers in the U+E100..U+E1FF range).
   */
  private fun visibleText(annotated: AnnotatedString): String {
    val builder: StringBuilder = StringBuilder(annotated.length)
    for (char in annotated) {
      if (char.code in 0xE000..0xE07F) continue
      if (char.code in 0xE100..0xE1FF) continue
      builder.append(char)
    }
    return builder.toString()
  }

  // ─── Regex sanity ───────────────────────────────────────────────────

  @Test
  fun `INLINE_LATEX_REGEX matches a simple single-pair formula`() {
    // `$x^2$` — single-dollar form. The regex has two capturing
    // groups: group 1 is the `$$…$$` body, group 2 is the `$…$`
    // body. The first alt is `$$…$$`; the second is `$…$`. For
    // a single-dollar input the first alt doesn't match, so the
    // second alt fires and the body lives in `groupValues[2]`.
    val match = INLINE_LATEX_REGEX.find("the formula is \$x^2\$ here")
    assertNotNull("regex should match a single-dollar pair", match)
    assertEquals("x^2", match!!.groupValues[2])
    // The dollar-dollar group is empty for a single-dollar input.
    assertTrue(
      "first group (for dollar-dollar form) should be empty for a single-dollar input, was '${match.groupValues[1]}'",
      match.groupValues[1].isEmpty()
    )
  }

  @Test
  fun `INLINE_LATEX_REGEX matches a dollar-dollar pair into the first capture group`() {
    // `$$x^2$$` (all on one line) — the first alt wins because
    // it appears first in the alternation, and the body lives
    // in `groupValues[1]`. The second group stays empty.
    val match = INLINE_LATEX_REGEX.find("the formula is \$\$x^2\$\$ here")
    assertNotNull("regex should match a dollar-dollar pair", match)
    assertEquals("x^2", match!!.groupValues[1])
    // The single-dollar group is empty for a dollar-dollar input.
    assertTrue(
      "second group (for single-dollar form) should be empty for a dollar-dollar input, was '${match.groupValues[2]}'",
      match.groupValues[2].isEmpty()
    )
  }

  @Test
  fun `INLINE_LATEX_REGEX returns null for an unclosed dollar`() {
    assertNull(INLINE_LATEX_REGEX.find("partial \$x^2 here"))
  }

  @Test
  fun `INLINE_LATEX_REGEX returns null for empty dollars`() {
    assertNull(INLINE_LATEX_REGEX.find("empty $$ here"))
  }

  @Test
  fun `INLINE_LATEX_REGEX skips newlines inside the formula`() {
    // The regex is single-line by design; a newline inside `$…$`
    // means it's not an inline formula. CommonMark would put that
    // on a new line as prose.
    assertNull(INLINE_LATEX_REGEX.find("split $\nx^2$ across lines"))
  }

  @Test
  fun `INLINE_LATEX_REGEX is non-greedy across adjacent pairs`() {
    // `$x$$y$` should match `$x$` and `$y$` independently, not
    // try to span them as one match. Both matches are in the
    // `$…$` form so the body lives in `groupValues[2]`.
    val matches = INLINE_LATEX_REGEX.findAll("\$x\$\$y\$").toList()
    assertEquals(2, matches.size)
    assertEquals("x", matches[0].groupValues[2])
    assertEquals("y", matches[1].groupValues[2])
  }

  @Test
  fun `INLINE_LATEX_REGEX handles a single dollar as plain text`() {
    // One lone `$` is not a formula — must NOT match.
    assertNull(INLINE_LATEX_REGEX.find("only one dollar $ here"))
  }

  // ─── INLINE_LATEX_PAREN_REGEX (the `\(…\)` form) ────────────────────

  @Test
  fun `INLINE_LATEX_PAREN_REGEX matches a simple paren formula`() {
    val match = INLINE_LATEX_PAREN_REGEX.find("the formula is \\(x\\) here")
    assertNotNull("regex should match `\\(x\\)`", match)
    // Single capturing group.
    assertEquals("x", match!!.groupValues[1])
  }

  @Test
  fun `INLINE_LATEX_PAREN_REGEX matches a discriminant expression`() {
    // The exact example from the user's feedback: `\(\Delta > 0\)`.
    // Before this regex was added, the model output leaked through
    // as literal text — this test pins the regression down.
    val match = INLINE_LATEX_PAREN_REGEX.find("当 \\(\\Delta > 0\\) 时，方程有两个不相等的实根")
    assertNotNull("regex should match `\\(\\Delta > 0\\)`", match)
    assertEquals("\\Delta > 0", match!!.groupValues[1])
  }

  @Test
  fun `INLINE_LATEX_PAREN_REGEX returns null for an unclosed paren pair`() {
    assertNull(INLINE_LATEX_PAREN_REGEX.find("partial \\(x^2 here"))
  }

  @Test
  fun `INLINE_LATEX_PAREN_REGEX rejects nested parens by design`() {
    // Documented limitation: the body exclusion is `[^()\n]+?`,
    // so parens inside the body are forbidden and a nested-paren
    // formula can't be captured as one match. We assert the
    // engine does NOT produce a single full-input match for the
    // nested form.
    val matches = INLINE_LATEX_PAREN_REGEX.findAll("see \\(\\frac{(a)}{(b)}\\) here").toList()
    assertTrue(
      "nested-paren formula should not produce a single full-input match; got ${matches.size} matches",
      matches.none { it.value == "\\(\\frac{(a)}{(b)}\\)" }
    )
  }

  @Test
  fun `INLINE_LATEX_PAREN_REGEX skips newlines inside the formula`() {
    assertNull(INLINE_LATEX_PAREN_REGEX.find("split \\(\nx^2\\) across lines"))
  }

  // ─── preprocessParenLatexFormulas ───────────────────────────────────

  @Test
  fun `preprocessParenLatexFormulas replaces each match with a unique PUA marker`() {
    val input = "当 \\(\\Delta > 0\\) 时，方程有两个不相等的实根"
    val result: PreprocessedParenLatex = preprocessParenLatexFormulas(input)
    // The text contains exactly one marker (a single PUA char in
    // U+E100..U+E1FF). The surrounding prose and the formula body
    // are NOT in the rewritten text — the formula moved to the
    // side table.
    val rewrittenNoMarkers: String = result.text.filter { it.code !in 0xE100..0xE1FF }
    assertEquals("当  时，方程有两个不相等的实根", rewrittenNoMarkers)
    // Side table has the formula text keyed by the marker char.
    assertEquals(1, result.formulaByMarker.size)
    val formula: String? = result.formulaByMarker.values.firstOrNull()
    assertEquals("\\Delta > 0", formula)
  }

  @Test
  fun `preprocessParenLatexFormulas returns the original text when there is no match`() {
    val input = "no paren latex here, just plain prose"
    val result: PreprocessedParenLatex = preprocessParenLatexFormulas(input)
    assertEquals(input, result.text)
    assertTrue(result.formulaByMarker.isEmpty())
  }

  @Test
  fun `preprocessParenLatexFormulas handles multiple paren formulas in one input`() {
    val input = "a \\(x\\) b \\(y\\) c \\(z\\) end"
    val result: PreprocessedParenLatex = preprocessParenLatexFormulas(input)
    // 3 markers in the rewritten text, 3 entries in the side table.
    val markerCount: Int = result.text.count { it.code in 0xE100..0xE1FF }
    assertEquals(3, markerCount)
    assertEquals(3, result.formulaByMarker.size)
    assertEquals(listOf("x", "y", "z"), result.formulaByMarker.values.toList())
  }

  @Test
  fun `INLINE_LATEX_PAREN_MARKER_REGEX matches any char in the reserved PUA range`() {
    // Sanity check: the marker regex finds all three sample chars
    // in the U+E100..U+E1FF range and nothing outside it.
    val matches: List<MatchResult> = INLINE_LATEX_PAREN_MARKER_REGEX
      .findAll("\uE100hello\uE150world\uE1FF")
      .toList()
    assertEquals(3, matches.size)
    // The code / image / footnote / LaTeX chip bases must NOT match.
    val chipMatches: List<MatchResult> = INLINE_LATEX_PAREN_MARKER_REGEX
      .findAll("\uE000\uE001\uE002\uE003")
      .toList()
    assertEquals(
      "chip placeholder PUA bases (U+E000..U+E003) must not match the paren-marker regex",
      0, chipMatches.size
    )
  }

  @Test
  fun `inline paren-form LaTeX is recognized by the walker and emits a placeholder chip`() {
    // The full pipeline: preprocessor turns `\(…\)` into a PUA
    // marker, CommonMark parses the rewritten text, the walker
    // scans for the marker and emits a LaTeX chip. The user's
    // `\(\Delta > 0\)` example must produce a LaTeX chip, not
    // leak through as literal text.
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender("当 \\(\\Delta > 0\\) 时").render)
    // One LaTeX chip for the paren-form formula.
    assertEquals(
      "expected exactly 1 inline chip for the paren-form formula, was ${render.inlineContent.size}",
      1, render.inlineContent.size
    )
    // The chip key uses the LaTeX PUA base (U+E003), not the
    // code / image / footnote bases.
    assertEquals("\uE003", render.inlineContent.keys.first())
    // Visible text retains the surrounding prose and drops the
    // PUA marker (which the walker replaced with a chip). Note
    // the marker itself is in the U+E100..U+E1FF range — also
    // stripped by `visibleText`.
    assertEquals("当  时", visibleText(render.annotated))
  }

  @Test
  fun `inline paren-form and dollar-form LaTeX coexist in the same paragraph`() {
    // Both forms should be picked up by the walker in one pass.
    val render: InlineMarkdownRender = requireNotNull(
      inlineRender("a \\(b\\) c and \$d\$ e").render
    )
    // Two chips: one for each formula. The walker allocates a
    // fresh PUA key per chip, so the map has 2 entries.
    assertEquals(2, render.inlineContent.size)
    // Both visible text bodies are stripped — only the surrounding
    // prose remains in the AnnotatedString.
    assertEquals("a  c and  e", visibleText(render.annotated))
  }

  // ─── Block LaTeX — AST shape ────────────────────────────────────────

  @Test
  fun `block LatexBlock parses single-line on its own lines`() {
    val document: Document = latexBlockParser.parse("$$\nx^2\n$$") as Document
    val first: org.commonmark.node.Node = requireNotNull(document.firstChild)
    assertTrue("first block should be a LatexBlock, was ${first.javaClass.simpleName}", first is LatexBlock)
    assertEquals("x^2", (first as LatexBlock).formula)
  }

  @Test
  fun `block LatexBlock parses multi-line formula with internal newlines`() {
    val document: Document = latexBlockParser.parse(
      "$$\n\\int_0^\\infty e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}\n$$"
    ) as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals("\\int_0^\\infty e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}", block.formula)
  }

  @Test
  fun `block LatexBlock trims leading and trailing blank lines from formula`() {
    val document: Document = latexBlockParser.parse("$$\n\n\\int\n\n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    // The formula should be `\\int`, not `\\n\\int\\n`. The
    // `\\n` blank lines at the boundary are stripped.
    assertEquals("\\int", block.formula)
  }

  @Test
  fun `block LatexBlock trims stray spaces on the first and last non-blank line`() {
    // `$$\n  x^2  \n$$` — the inner line has leading / trailing
    // spaces that the LaTeX library would otherwise feed
    // through to the renderer. The parser trims the first /
    // last surviving line so the recovered formula is clean.
    val document: Document = latexBlockParser.parse("$$\n  x^2  \n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals("x^2", block.formula)
  }

  @Test
  fun `block LatexBlock trims spaces on first and last line of a multi-line body`() {
    // `$$\n  a  \n  b  \n  c  \n$$` — only the first and last
    // surviving lines are trimmed; internal lines keep their
    // indent (and any trailing whitespace) so the multi-line
    // formula is preserved.
    val document: Document = latexBlockParser.parse("$$\n  a  \n  b  \n  c  \n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals("a\n  b  \nc", block.formula)
  }

  @Test
  fun `block LatexBlock at the start of a document`() {
    val document: Document = latexBlockParser.parse("$$\nx^2\n$$\n\nprose after") as Document
    assertEquals(2, countChildren(document))
    assertTrue(document.firstChild is LatexBlock)
    val second: org.commonmark.node.Node = requireNotNull(document.firstChild.next)
    assertTrue(second is Paragraph)
    val firstInline: org.commonmark.node.Node = requireNotNull((second as Paragraph).firstChild)
    assertEquals("prose after", (firstInline as org.commonmark.node.Text).literal)
  }

  @Test
  fun `block LatexBlock followed by another LatexBlock in the same document`() {
    val document: Document = latexBlockParser.parse(
      "$$\na\n$$\n\n$$\nb\n$$"
    ) as Document
    val blocks: List<org.commonmark.node.Node> =
      generateSequence(document.firstChild) { it.next }.toList()
    assertEquals(2, blocks.size)
    assertEquals("a", (blocks[0] as LatexBlock).formula)
    assertEquals("b", (blocks[1] as LatexBlock).formula)
  }

  @Test
  fun `block LatexBlock rejects marker lines with leading or trailing text`() {
    // `$$ x^2 $$` on a single line is plain prose (and the inline
    // regex will later match `$x^2$` inside the paragraph).
    val document: Document = latexBlockParser.parse("this is $$ x^2 $$ inline") as Document
    assertTrue(
      "no LatexBlock expected, was ${document.firstChild.javaClass.simpleName}",
      document.firstChild is Paragraph
    )
  }

  @Test
  fun `block LatexBlock handles empty formula between two blank-marked lines`() {
    // `$$\n\n$$` opens and immediately closes — the body is a
    // single blank line which is trimmed to empty.
    val document: Document = latexBlockParser.parse("$$\n\n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals("", block.formula)
  }

  @Test
  fun `block LatexBlock does not capture surrounding paragraphs as text`() {
    val source = "preceding prose\n\n$$\nx^2\n$$\n\nfollowing prose"
    val document: Document = latexBlockParser.parse(source) as Document
    val blocks: List<Block> = generateSequence<Block>(document.firstChild as Block) { it.next as? Block }
      .toList()
    assertEquals(3, blocks.size)
    assertTrue("first should be Paragraph", blocks[0] is Paragraph)
    assertTrue("second should be LatexBlock", blocks[1] is LatexBlock)
    assertTrue("third should be Paragraph", blocks[2] is Paragraph)
  }

  // ─── Block LaTeX — segmentation integration ─────────────────────────

  @Test
  fun `splitPlainAtBlocks wraps a LatexBlock as a NonProseBlock with round-trippable text`() {
    val source = "intro\n\n$$\nx^2\n$$\n\noutro"
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(source)
    assertEquals(3, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertTrue(segments[1] is MarkdownSegment.NonProseBlock)
    assertTrue(segments[2] is MarkdownSegment.Plain)
    val latexSegment: String = (segments[1] as MarkdownSegment.NonProseBlock).text
    assertEquals("$$\nx^2\n$$", latexSegment)
  }

  @Test
  fun `splitPlainAtBlocks reparse of the wrapped text yields a LatexBlock again`() {
    // Round-trip: LatexBlock → NonProseBlock text → reparse →
    // LatexBlock. The formula body is preserved across the
    // serialize/parse round-trip. The source uses the
    // canonical `$$\n<body>\n$$` form (each `$$` on its own
    // line per the plan rule) so the block parser sees a
    // proper close marker.
    val source = "$$\ne^{i\\pi} + 1 = 0\n$$"
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(source)
    assertEquals(1, segments.size)
    val block: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val reparsed: Document = latexBlockParser.parse(block.text) as Document
    val rebuilt: LatexBlock = reparsed.firstChild as LatexBlock
    assertEquals("e^{i\\pi} + 1 = 0", rebuilt.formula)
  }

  @Test
  fun `splitPlainAtBlocks handles multiple blocks each on its own line`() {
    val source = "text\n\n$$\na\n$$\n\nmid\n\n$$\nb\n$$"
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(source)
    // 4 top-level blocks: Paragraph("text"), LatexBlock("a"),
    // Paragraph("mid"), LatexBlock("b"). The blank line between
    // the LaTeX block and the following paragraph doesn't
    // produce an extra empty Paragraph node — CommonMark
    // collapses them.
    assertEquals(4, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertTrue(segments[1] is MarkdownSegment.NonProseBlock)
    assertTrue(segments[2] is MarkdownSegment.Plain)
    assertTrue(segments[3] is MarkdownSegment.NonProseBlock)
  }

  // ─── Block LaTeX — serialization shape ──────────────────────────────

  @Test
  fun `production reparse parser in BlockRenderer also recognizes LatexBlock`() {
    // The renderer in [BlockRenderer] reparses a
    // [MarkdownSegment.NonProseBlock]'s text to dispatch the
    // right composable (LatexBlock → [RenderLatexBlock], Heading
    // → [Jewel Heading], …). If this parser doesn't have
    // [LatexBlockExtension] registered, the reparse would
    // collapse `$$\n<formula>\n$$` back to a Paragraph and the
    // formula would render as plain prose — not as math.
    //
    // We use the production parser (exposed as `internal` for
    // exactly this kind of test) instead of the test's
    // [latexBlockParser] so this test catches a real
    // regression if a future refactor drops the extension from
    // [BlockRenderer.blockReparseParser].
    val reparsed: Document = blockReparseParser.parse("$$\nx^2\n$$") as Document
    assertTrue(
      "BlockRenderer's production parser must produce a LatexBlock, " +
        "was ${reparsed.firstChild?.javaClass?.simpleName}",
      reparsed.firstChild is LatexBlock
    )
    assertEquals("x^2", (reparsed.firstChild as LatexBlock).formula)
  }

  @Test
  fun `LatexBlock serialize round-trips through Parser and back`() {
    val original: LatexBlock = LatexBlock().also { it.formula = "x^2" }
    val document: Document = Document().also { it.appendChild(original) }
    val serialized: String = serializeInto(document)
    assertEquals("$$\nx^2\n$$", serialized)
    val reparsed: Document = latexBlockParser.parse(serialized) as Document
    assertEquals("x^2", (reparsed.firstChild as LatexBlock).formula)
  }

  @Test
  fun `LatexBlock serialize preserves an empty formula`() {
    val original: LatexBlock = LatexBlock().also { it.formula = "" }
    val document: Document = Document().also { it.appendChild(original) }
    val serialized: String = serializeInto(document)
    assertEquals("$$\n\n$$", serialized)
  }

  @Test
  fun `LatexBlock serialize preserves multi-line formula newlines`() {
    val original: LatexBlock = LatexBlock().also { it.formula = "a\nb\nc" }
    val document: Document = Document().also { it.appendChild(original) }
    val serialized: String = serializeInto(document)
    assertEquals("$$\na\nb\nc\n$$", serialized)
  }

  // ─── Inline LaTeX — walker output ──────────────────────────────────

  @Test
  fun `inline latex formula is recognized and emits a placeholder chip`() {
    val result: InlineMarkdownRenderResult = inlineRender("the formula is \$x^2\$ here")
    val render: InlineMarkdownRender = requireNotNull(result.render)
    // The visible text (after stripping PUA) keeps the surrounding
    // prose; the formula becomes a PUA placeholder in the middle.
    val visible: String = visibleText(render.annotated)
    assertEquals("the formula is  here", visible)
    // A chip is registered for the formula; the chip count grew
    // by one (footnote / image chips would also grow the same
    // counter, so we only assert the chip map is non-empty for
    // this text).
    assertTrue(
      "inline-content chip map should have at least one entry, was ${render.inlineContent.size}",
      render.inlineContent.isNotEmpty()
    )
  }

  @Test
  fun `inline latex preserves the visible text for adjacent prose`() {
    val render: InlineMarkdownRender = requireNotNull(inlineRender("\$x^2\$").render)
    // The single formula should produce just one PUA chip with
    // no surrounding visible chars.
    assertEquals("", visibleText(render.annotated))
    assertTrue(render.inlineContent.isNotEmpty())
  }

  @Test
  fun `inline latex detects multiple formulas in one line`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender("first \$a\$ and \$b\$ and \$c\$ end").render)
    // 3 formulas → 3 chips in the inline content map. The map's
    // size itself is the count we need (no other inline features
    // — links / images / code / footnote — are present in this
    // text).
    assertEquals("expected 3 inline chips for 3 formulas", 3, render.inlineContent.size)
    // The three chip keys must be distinct (each call to the
    // allocator appends a fresh PUA char).
    assertEquals(3, render.inlineContent.keys.distinct().size)
    // Visible text retains the surrounding prose.
    assertEquals("first  and  and  end", visibleText(render.annotated))
  }

  @Test
  fun `inline latex inside emphasis is still recognized`() {
    // `*$x^2$*` — the walker recurses into Emphasis, the inner
    // Text literal `$x^2$` is matched by the regex.
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender("*\$x^2\$*").render)
    assertEquals("", visibleText(render.annotated))
    assertTrue(render.inlineContent.isNotEmpty())
  }

  @Test
  fun `inline latex inside strong emphasis is still recognized`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender("**\$x^2\$**").render)
    assertEquals("", visibleText(render.annotated))
    assertTrue(render.inlineContent.isNotEmpty())
  }

  @Test
  fun `inline latex inside a link text is recognized but link text is preserved`() {
    // The link's *visible* text still shows the formula source
    // (the user can copy it). The PUA chip is added for the
    // rendered formula at the call site. Both representations
    // are intentional: link text is plain string for click /
    // a11y, the chip is the visual render.
    val result: InlineMarkdownRenderResult = inlineRender("[\$x^2\$](https://example.com)")
    val render: InlineMarkdownRender = requireNotNull(result.render)
    // The AnnotatedString shows the chip; the link text fallback
    // (used by the link segment splitter) keeps the source text.
    assertTrue(render.inlineContent.isNotEmpty())
  }

  @Test
  fun `inline latex inside a code span is not recognized - the walker does not recurse into Code`() {
    // The walker treats a Code node as a single literal — its
    // child is the code's `Text` literal, not the source
    // characters. The inline regex doesn't fire on code spans.
    //
    // Note: the code span REPLACES its inner text with a PUA
    // placeholder for chip rendering (the actual code text is
    // kept as a string annotation, not in the visible string).
    // So after stripping PUA chars the visible text reads as
    // "use [] here" — the `$x^2$` is hidden in the chip, not
    // re-flowed into the surrounding text.
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender("use `\$x^2\$` here").render)
    // The PUA chip from the code span is the code chip, not a
    // LaTeX chip. We can't distinguish them here without
    // inspecting the InlineTextContent lambda, but the chip
    // count is exactly 1 (one code chip) — if the LaTeX regex
    // also fired we'd see 2.
    assertEquals(1, render.inlineContent.size)
    // The single registered chip key uses the code PUA base
    // (U+E000), not the LaTeX base (U+E003).
    val registeredKey: String = render.inlineContent.keys.first()
    assertEquals(
      "expected the code PUA base, not the LaTeX base",
      "\uE000",
      registeredKey
    )
  }

  @Test
  fun `inline latex fallback - dollar with no closing pair is plain text`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender("cost is \$5 and no closing").render)
    // No closing `$` → no inline chip. The whole thing is plain text.
    val visible: String = visibleText(render.annotated)
    assertEquals("cost is \$5 and no closing", visible)
    assertTrue(render.inlineContent.isEmpty())
  }

  @Test
  fun `inline latex does not match when dollar is mid-word in plain prose`() {
    // `$5.99` is a price, not a formula. No closing `$` → no match.
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender("the price is \$5.99 today").render)
    assertEquals("the price is \$5.99 today", visibleText(render.annotated))
  }

  @Test
  fun `inline latex chip key uses the LaTeX placeholder base and increments per call`() {
    // First chip key is the PUA base alone; second chip is the
    // base repeated twice. The placeholder base is at
    // U+E003 (see [LATEX_PLACEHOLDER_BASE] in InlineMarkdown).
    // We allocate both within a single parse so they share one
    // [RenderState] — the counter increments across both
    // allocations. (Calling the parse twice gives a fresh
    // counter each time and the second key would also be just
    // `"\uE003"`.)
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender("\$x\$ and \$y\$").render)
    val keys: List<String> = render.inlineContent.keys.toList()
    assertEquals("expected 2 LaTeX chips", 2, keys.size)
    assertEquals("\uE003", keys[0])
    assertEquals("\uE003\uE003", keys[1])
  }

  // ─── RenderState API ───────────────────────────────────────────────

  // RenderState is private to InlineMarkdown.kt so we exercise it
  // through the public inline walker (see the test above). The
  // walker exposes the chip map on InlineMarkdownRender.inlineContent,
  // which is enough to verify the PUA key shape and counter
  // behavior without spinning up Compose.

  // ─── Mixed block + inline ──────────────────────────────────────────

  @Test
  fun `block and inline formulas coexist in a document`() {
    val source = "Here is \$a+b=c\$ inline, then a block:\n\n\$\$\nE = mc^2\n\$\$"
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(source)
    assertEquals(2, segments.size)
    // First segment is a Plain carrying the prose with the inline formula.
    assertTrue(segments[0] is MarkdownSegment.Plain)
    val proseText: String = (segments[0] as MarkdownSegment.Plain).text
    assertTrue(
      "first segment should contain the inline formula source, was `$proseText`",
      proseText.contains("\$a+b=c\$")
    )
    // Second segment is the LaTeX block as NonProseBlock.
    assertTrue(segments[1] is MarkdownSegment.NonProseBlock)
    val blockText: String = (segments[1] as MarkdownSegment.NonProseBlock).text
    assertEquals("$$\nE = mc^2\n$$", blockText)
  }

  @Test
  fun `block formula in a list item is recognized as a LatexBlock child`() {
    // A list item can contain a LatexBlock as one of its child
    // blocks (alongside paragraphs etc.). This is the user's
    // most common "math inside a list" pattern.
    val document: Document = latexBlockParser.parse(
      "- intro\n\n  $$\n  x^2\n  $$"
    ) as Document
    val blocks: List<Block> = generateSequence<Block>(document.firstChild as Block) { it.next as? Block }
      .toList()
    assertEquals(1, blocks.size)
    val first: Block = blocks[0]
    assertTrue(
      "expected BulletList, was ${first.javaClass.simpleName}",
      first.javaClass.simpleName == "BulletList"
    )
  }

  // ─── Edge cases ────────────────────────────────────────────────────

  @Test
  fun `block LatexBlock handles deeply nested braces and underscores in the formula`() {
    val formula = "f(x) = \\sum_{i=1}^{N} \\frac{x_i^2}{\\sqrt{y_{i,j}}}"
    // `\$\$` escapes the literal `$$` markers, `$formula` is a
    // template ref to the Kotlin variable, and the trailing
    // `\$\$` is the closing marker.
    val document: Document = latexBlockParser.parse("\$\$\n$formula\n\$\$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(formula, block.formula)
  }

  @Test
  fun `block LatexBlock handles special regex characters in the formula body`() {
    // The LaTeX body can contain regex metacharacters (.*+?). They
    // should not affect parsing — the block parser works on a
    // line-by-line basis, not a regex.
    val formula = "a + b = c (with .*+? and [brackets] in it)"
    val document: Document = latexBlockParser.parse("\$\$\n$formula\n\$\$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(formula, block.formula)
  }

  @Test
  fun `block LatexBlock inside fenced code is not a block - code wins`() {
    // Fenced code blocks start with ``` and take priority over
    // the LatexBlock parser. A `$$` inside a fenced code is
    // plain code content.
    val document: Document = latexBlockParser.parse(
      "```\n$$\nx^2\n$$\n```"
    ) as Document
    val first: Block = document.firstChild as Block
    assertFalse(
      "Fenced code should be a FencedCodeBlock, not a LatexBlock, was ${first.javaClass.simpleName}",
      first is LatexBlock
    )
  }

  @Test
  fun `inline latex placeholder does not collide with code or image or footnote placeholders`() {
    // Distinct PUA bases (see InlineMarkdown.kt):
    //   code  = U+E000
    //   image = U+E001
    //   footnote = U+E002
    //   latex = U+E003
    // The first call to each allocator emits a single PUA char; a
    // collision would show up as overlapping keys in the
    // inline-content map when all 4 are used in the same text.
    val render: InlineMarkdownRender = requireNotNull(
      inlineRender("`code` \$x\$ ![alt](url) [1]")
        .render
    )
    // 4 distinct placeholders.
    assertEquals(4, render.inlineContent.size)
    // Every placeholder key is unique (no PUA base collision).
    assertEquals(render.inlineContent.size, render.inlineContent.keys.toSet().size)
  }

  @Test
  fun `block LatexBlock falls back to fallback text when formula is empty after stripping`() {
    // `$$\n\n\n$$` — three blank lines inside. The body is
    // fully blank, trimmed to empty. The renderer should fall
    // back to raw text rather than render an empty canvas.
    val result: Block = latexBlockParser.parse("$$\n\n\n$$").firstChild as Block
    val formula: String = (result as LatexBlock).formula
    assertTrue("empty-formula LatexBlock should fall back", formula.isEmpty())
  }

  @Test
  fun `inline latex placeholder is registered with the surrounding text font size`() {
    // The RenderState.allocateLatex keys the placeholder width
    // by the surrounding font size (so a 14sp chip and a 18sp
    // chip get different sizes). We assert the chip is
    // registered; size verification would need a Compose render
    // which the JVM test environment can't drive.
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender("text \$a\$ text").render)
    assertEquals(1, render.inlineContent.size)
    val placeholder: Any = render.inlineContent.values.first().placeholder
    // The Placeholder has a `width: TextUnit`; the test surface
    // (JVM, no Compose UI) lets us at least confirm the
    // Placeholder object is non-null and has a width > 0.
    assertNotNull(placeholder)
  }

  // ─── Single-line $$...$$ block parsing ─────────────────────────────

  @Test
  fun `single-line dollar-dollar is parsed as a LatexBlock`() {
    val document: Document = latexBlockParser.parse("\$\$2x^2 - 7x + 3 = 0\$\$") as Document
    val first: org.commonmark.node.Node = requireNotNull(document.firstChild)
    assertTrue("first block should be a LatexBlock, was ${first.javaClass.simpleName}", first is LatexBlock)
    assertEquals("2x^2 - 7x + 3 = 0", (first as LatexBlock).formula)
  }

  @Test
  fun `single-line dollar-dollar with leading and trailing spaces trims the formula`() {
    val document: Document = latexBlockParser.parse("\$\$  x^2  \$\$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals("x^2", block.formula)
  }

  @Test
  fun `single-line dollar-dollar with complex formula preserves content`() {
    val document: Document = latexBlockParser.parse("\$\$\\frac{1}{2} + \\sqrt{25}\$\$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals("\\frac{1}{2} + \\sqrt{25}", block.formula)
  }

  @Test
  fun `single-line dollar-dollar does not match exactly four dollars`() {
    // `$$$$` is only 4 chars (two `$$` markers with empty body),
    // which is NOT valid — needs > 4 chars to have formula content.
    val document: Document = latexBlockParser.parse("\$\$\$\$") as Document
    assertTrue(
      "$$$$ should not be a LatexBlock, was ${document.firstChild.javaClass.simpleName}",
      document.firstChild is Paragraph
    )
  }

  @Test
  fun `single-line dollar-dollar on its own line is parsed as LatexBlock`() {
    // When $$...$$ is the only content on a line, the block parser sees it.
    val source = "\$\$x^2\$\$"
    val document: Document = latexBlockParser.parse(source) as Document
    val blocks: List<org.commonmark.node.Node> =
      generateSequence(document.firstChild) { it.next }.toList()
    assertTrue("should be a LatexBlock, was ${blocks[0].javaClass.simpleName}", blocks[0] is LatexBlock)
    assertEquals("x^2", (blocks[0] as LatexBlock).formula)
  }

  @Test
  fun `single-line dollar-dollar surrounded by prose stays as Paragraph`() {
    // When text precedes $$...$$ on the same line, CommonMark wraps the
    // whole line in a Paragraph — the block parser never sees $$.
    // This is a CommonMark limitation: block parsers only see lines
    // at block boundaries.
    val source = "before \$\$x^2\$\$ after"
    val document: Document = latexBlockParser.parse(source) as Document
    assertTrue(
      "should be a Paragraph, was ${document.firstChild.javaClass.simpleName}",
      document.firstChild is Paragraph
    )
  }

  @Test
  fun `single-line dollar-dollar followed by another paragraph`() {
    val source = "\$\$E = mc^2\$\$\n\nnext paragraph"
    val document: Document = latexBlockParser.parse(source) as Document
    val blocks: List<org.commonmark.node.Node> =
      generateSequence(document.firstChild) { it.next }.toList()
    assertTrue("first should be LatexBlock", blocks[0] is LatexBlock)
    assertEquals("E = mc^2", (blocks[0] as LatexBlock).formula)
    assertTrue("second should be Paragraph", blocks[1] is Paragraph)
  }

  @Test
  fun `single-line dollar-dollar with underscores and braces`() {
    val formula = "\\sum_{i=1}^{N} x_i^2"
    val document: Document = latexBlockParser.parse("\$\$$formula\$\$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(formula, block.formula)
  }

  @Test
  fun `inline dollar-dollar on its own line is parsed as block`() {
    // $$...$$ alone on a line → LatexBlock.
    val document: Document = latexBlockParser.parse("\$\$x^2\$\$") as Document
    val blocks: List<org.commonmark.node.Node> =
      generateSequence(document.firstChild) { it.next }.toList()
    assertTrue("should be a LatexBlock, was ${blocks[0].javaClass.simpleName}", blocks[0] is LatexBlock)
    assertEquals("x^2", (blocks[0] as LatexBlock).formula)
  }

  // ─── Helpers ───────────────────────────────────────────────────────

  private fun countChildren(node: org.commonmark.node.Node): Int =
    generateSequence(node.firstChild) { it.next }.count()
}
