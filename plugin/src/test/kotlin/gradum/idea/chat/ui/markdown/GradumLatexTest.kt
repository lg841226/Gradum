/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumLatexTest.kt  2026-08-25 13:39:50 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.commonmark.node.Block
import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class GradumLatexTest {

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
      codeColor = testTint,
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

  @Test
  fun `INLINE_LATEX_REGEX matches a simple single-pair formula`() {
    val match = INLINE_LATEX_REGEX.find(input = $$"the formula is $x^2$ here")
    assertNotNull(
      "regex should match a single-dollar pair",
      match
    )
    assertEquals(
      "x^2",
      match!!.groupValues[2]
    )
    assertTrue(
      "first group (for dollar-dollar form) should be empty for a single-dollar input, was '${match.groupValues[1]}'",
      match.groupValues[1].isEmpty()
    )
  }

  @Test
  fun `INLINE_LATEX_REGEX matches a dollar-dollar pair into the first capture group`() {
    val match = INLINE_LATEX_REGEX.find(input = $$$"the formula is $$x^2$$ here")
    assertNotNull(
      "regex should match a dollar-dollar pair",
      match
    )
    assertEquals(
      "x^2",
      match!!.groupValues[1]
    )
    assertTrue(
      "second group (for single-dollar form) should be empty for a dollar-dollar input, was '${match.groupValues[2]}'",
      match.groupValues[2].isEmpty()
    )
  }

  @Test
  fun `INLINE_LATEX_REGEX returns null for an unclosed dollar`() {
    assertNull(INLINE_LATEX_REGEX.find(input = $$"partial $x^2 here"))
  }

  @Test
  fun `INLINE_LATEX_REGEX returns null for empty dollars`() {
    assertNull(INLINE_LATEX_REGEX.find(input = "empty $$ here"))
  }

  @Test
  fun `INLINE_LATEX_REGEX skips newlines inside the formula`() {
    assertNull(INLINE_LATEX_REGEX.find(input = "split $\nx^2$ across lines"))
  }

  @Test
  fun `INLINE_LATEX_REGEX is non-greedy across adjacent pairs`() {
    val matches = INLINE_LATEX_REGEX.findAll(input = $$$"$x$$y$").toList()
    assertEquals(
      2,
      matches.size
    )
    assertEquals(
      "x",
      matches[0].groupValues[2]
    )
    assertEquals(
      "y",
      matches[1].groupValues[2]
    )
  }

  @Test
  fun `INLINE_LATEX_REGEX handles a single dollar as plain text`() {
    assertNull(INLINE_LATEX_REGEX.find(input = "only one dollar $ here"))
  }

  @Test
  fun `INLINE_LATEX_PAREN_REGEX matches a simple paren formula`() {
    val match = INLINE_LATEX_PAREN_REGEX.find(input = "the formula is \\(x\\) here")
    assertNotNull(
      "regex should match `\\(x\\)`",
      match
    )
    assertEquals(
      "x",
      match!!.groupValues[1]
    )
  }

  @Test
  fun `INLINE_LATEX_PAREN_REGEX matches a discriminant expression`() {
    val match = INLINE_LATEX_PAREN_REGEX.find(input = "当 \\(\\Delta > 0\\) 时，方程有两个不相等的实根")
    assertNotNull(
      "regex should match `\\(\\Delta > 0\\)`",
      match
    )
    assertEquals(
      "\\Delta > 0",
      match!!.groupValues[1]
    )
  }

  @Test
  fun `INLINE_LATEX_PAREN_REGEX returns null for an unclosed paren pair`() {
    assertNull(INLINE_LATEX_PAREN_REGEX.find("partial \\(x^2 here"))
  }

  @Test
  fun `INLINE_LATEX_PAREN_REGEX rejects nested parens by design`() {

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

  @Test
  fun `preprocessParenLatexFormulas replaces each match with a unique PUA marker`() {
    val input = "当 \\(\\Delta > 0\\) 时，方程有两个不相等的实根"
    val result: PreprocessedParenLatex = preprocessParenLatexFormulas(rawText = input)
    val rewrittenNoMarkers: String = result.text.filter { it.code !in 0xE100..0xE1FF }

    assertEquals(
      "当  时，方程有两个不相等的实根",
      rewrittenNoMarkers
    )
    assertEquals(
      1,
      result.formulaByMarker.size
    )
    val formula: String? = result.formulaByMarker.values.firstOrNull()
    assertEquals(
      "\\Delta > 0",
      formula
    )
  }

  @Test
  fun `preprocessParenLatexFormulas returns the original text when there is no match`() {
    val input = "no paren latex here, just plain prose"
    val result: PreprocessedParenLatex = preprocessParenLatexFormulas(rawText = input)
    assertEquals(
      input,
      result.text
    )
    assertTrue(result.formulaByMarker.isEmpty())
  }

  @Test
  fun `preprocessParenLatexFormulas handles multiple paren formulas in one input`() {
    val input = "a \\(x\\) b \\(y\\) c \\(z\\) end"
    val result: PreprocessedParenLatex = preprocessParenLatexFormulas(input)
    val markerCount: Int = result.text.count { it.code in 0xE100..0xE1FF }
    assertEquals(
      3,
      markerCount
    )
    assertEquals(
      3,
      result.formulaByMarker.size
    )
    assertEquals(
      listOf("x", "y", "z"),
      result.formulaByMarker.values.toList()
    )
  }

  @Test
  fun `INLINE_LATEX_PAREN_MARKER_REGEX matches any char in the reserved PUA range`() {
    val matches: List<MatchResult> = INLINE_LATEX_PAREN_MARKER_REGEX
      .findAll(input = "\uE100hello\uE150world\uE1FF")
      .toList()

    val chipMatches: List<MatchResult> = INLINE_LATEX_PAREN_MARKER_REGEX
      .findAll(input = "\uE000\uE001\uE002\uE003")
      .toList()

    assertEquals(
      3,
      matches.size
    )

    assertEquals(
      "chip placeholder PUA bases (U+E000..U+E003) must not match the paren-marker regex",
      0,
      chipMatches.size
    )
  }

  @Test
  fun `inline paren-form LaTeX is recognized by the walker and emits a placeholder chip`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender(text = "当 \\(\\Delta > 0\\) 时").render)
    assertEquals(
      "expected exactly 1 inline chip for the paren-form formula, was ${render.inlineContent.size}",
      1,
      render.inlineContent.size
    )
    assertEquals(
      "\uE003",
      render.inlineContent.keys.first()
    )
    assertEquals(
      "当  时",
      visibleText(render.annotated)
    )
  }

  @Test
  fun `inline paren-form and dollar-form LaTeX coexist in the same paragraph`() {
    val render: InlineMarkdownRender = requireNotNull(
      inlineRender(text = $$"a \\(b\\) c and $d$ e").render
    )
    assertEquals(
      2,
      render.inlineContent.size
    )
    assertEquals(
      "a  c and  e",
      visibleText(render.annotated)
    )
  }

  @Test
  fun `block LatexBlock parses single-line on its own lines`() {
    val document: Document = latexBlockParser.parse("$$\nx^2\n$$") as Document

    val first: Node = requireNotNull(document.firstChild)
    assertTrue(
      "first block should be a LatexBlock, was ${first.javaClass.simpleName}",
      first is LatexBlock
    )
    assertEquals(
      "x^2",
      (first as LatexBlock).formula
    )
  }

  @Test
  fun `block LatexBlock parses multi-line formula with internal newlines`() {
    val document: Document = latexBlockParser.parse(
      "$$\n\\int_0^\\infty e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}\n$$"
    ) as Document

    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(
      "\\int_0^\\infty e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}",
      block.formula
    )
  }

  @Test
  fun `block LatexBlock trims leading and trailing blank lines from formula`() {
    val document: Document = latexBlockParser.parse("$$\n\n\\int\n\n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock

    assertEquals(
      "\\int",
      block.formula
    )
  }

  @Test
  fun `block LatexBlock trims stray spaces on the first and last non-blank line`() {
    val document: Document = latexBlockParser.parse("$$\n  x^2  \n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(
      "x^2",
      block.formula
    )
  }

  @Test
  fun `block LatexBlock trims spaces on first and last line of a multi-line body`() {
    val document: Document = latexBlockParser.parse("$$\n  a  \n  b  \n  c  \n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(
      "a\n  b  \nc",
      block.formula
    )
  }

  @Test
  fun `block LatexBlock at the start of a document`() {
    val document: Document = latexBlockParser.parse("$$\nx^2\n$$\n\nprose after") as Document
    val second: Node = requireNotNull(document.firstChild.next)
    val firstInline: Node = requireNotNull((second as Paragraph).firstChild)

    assertEquals(
      2,
      countChildren(node = document)
    )
    assertTrue(document.firstChild is LatexBlock)
    assertEquals(
      "prose after",
      (firstInline as org.commonmark.node.Text).literal
    )
  }

  @Test
  fun `block LatexBlock followed by another LatexBlock in the same document`() {
    val document: Document = latexBlockParser.parse(
      "$$\na\n$$\n\n$$\nb\n$$"
    ) as Document
    val blocks: List<Node> = generateSequence(seed = document.firstChild) { it.next }.toList()

    assertEquals(
      2,
      blocks.size
    )
    assertEquals(
      "a",
      (blocks[0] as LatexBlock).formula
    )
    assertEquals(
      "b",
      (blocks[1] as LatexBlock).formula
    )
  }

  @Test
  fun `block LatexBlock rejects marker lines with leading or trailing text`() {
    val document: Document = latexBlockParser.parse("this is $$ x^2 $$ inline") as Document
    assertTrue(
      "no LatexBlock expected, was ${document.firstChild.javaClass.simpleName}",
      document.firstChild is Paragraph
    )
  }

  @Test
  fun `block LatexBlock handles empty formula between two blank-marked lines`() {
    val document: Document = latexBlockParser.parse("$$\n\n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(
      "",
      block.formula
    )
  }

  @Test
  fun `block LatexBlock does not capture surrounding paragraphs as text`() {
    val source = "preceding prose\n\n$$\nx^2\n$$\n\nfollowing prose"
    val document: Document = latexBlockParser.parse(source) as Document
    val blocks = generateSequence(seed = document.firstChild as Block) { it.next as? Block }
      .toList()
    assertEquals(
      3,
      blocks.size
    )
    assertTrue(
      "first should be Paragraph",
      blocks[0] is Paragraph
    )
    assertTrue(
      "second should be LatexBlock",
      blocks[1] is LatexBlock
    )
    assertTrue(
      "third should be Paragraph",
      blocks[2] is Paragraph
    )
  }

  @Test
  fun `splitPlainAtBlocks wraps a LatexBlock as a NonProseBlock with round-trippable text`() {
    val source = "intro\n\n$$\nx^2\n$$\n\noutro"
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(plainText = source)
    assertEquals(
      3,
      segments.size
    )
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertTrue(segments[1] is MarkdownSegment.NonProseBlock)
    assertTrue(segments[2] is MarkdownSegment.Plain)
    val latexSegment: String = (segments[1] as MarkdownSegment.NonProseBlock).text
    assertEquals(
      "$$\nx^2\n$$",
      latexSegment
    )
  }

  @Test
  fun `splitPlainAtBlocks reparse of the wrapped text yields a LatexBlock again`() {
    val source = "$$\ne^{i\\pi} + 1 = 0\n$$"

    val segments: List<MarkdownSegment> = splitPlainAtBlocks(plainText = source)
    assertEquals(
      1,
      segments.size
    )

    val block: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val reparsed: Document = latexBlockParser.parse(block.text) as Document
    val rebuilt: LatexBlock = reparsed.firstChild as LatexBlock

    assertEquals(
      "e^{i\\pi} + 1 = 0",
      rebuilt.formula
    )
  }

  @Test
  fun `splitPlainAtBlocks handles multiple blocks each on its own line`() {
    val source = "text\n\n$$\na\n$$\n\nmid\n\n$$\nb\n$$"
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(plainText = source)
    assertEquals(
      4,
      segments.size
    )
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertTrue(segments[1] is MarkdownSegment.NonProseBlock)
    assertTrue(segments[2] is MarkdownSegment.Plain)
    assertTrue(segments[3] is MarkdownSegment.NonProseBlock)
  }

  @Test
  fun `production reparse parser in BlockRenderer also recognizes LatexBlock`() {
    val reparsed: Document = blockReparseParser.parse("$$\nx^2\n$$") as Document
    assertTrue(
      "BlockRenderer's production parser must produce a LatexBlock, " +
        "was ${reparsed.firstChild?.javaClass?.simpleName}",
      reparsed.firstChild is LatexBlock
    )
    assertEquals(
      "x^2",
      (reparsed.firstChild as LatexBlock).formula
    )
  }

  @Test
  fun `LatexBlock serialize round-trips through Parser and back`() {
    val original: LatexBlock = LatexBlock().also { it.formula = "x^2" }
    val document: Document = Document().also { it.appendChild(original) }
    val serialized: String = serializeInto(currentNode = document)

    assertEquals(
      "$$\nx^2\n$$",
      serialized
    )
    val reparsed: Document = latexBlockParser.parse(serialized) as Document
    assertEquals(
      "x^2",
      (reparsed.firstChild as LatexBlock).formula
    )
  }

  @Test
  fun `LatexBlock serialize preserves an empty formula`() {
    val original: LatexBlock = LatexBlock().also { it.formula = "" }
    val document: Document = Document().also { it.appendChild(original) }
    val serialized: String = serializeInto(currentNode = document)
    assertEquals(
      "$$\n\n$$",
      serialized
    )
  }

  @Test
  fun `LatexBlock serialize preserves multi-line formula newlines`() {
    val original: LatexBlock = LatexBlock().also { it.formula = "a\nb\nc" }
    val document: Document = Document().also { it.appendChild(original) }
    val serialized: String = serializeInto(currentNode = document)

    assertEquals(
      "$$\na\nb\nc\n$$",
      serialized
    )
  }


  @Test
  fun `inline latex formula is recognized and emits a placeholder chip`() {
    val result: InlineMarkdownRenderResult = inlineRender(text = $$"the formula is $x^2$ here")
    val render: InlineMarkdownRender = requireNotNull(result.render)
    val visible: String = visibleText(render.annotated)

    assertEquals(
      "the formula is  here",
      visible
    )

    assertTrue(
      "inline-content chip map should have at least one entry, was ${render.inlineContent.size}",
      render.inlineContent.isNotEmpty()
    )
  }

  @Test
  fun `inline latex preserves the visible text for adjacent prose`() {
    val render: InlineMarkdownRender = requireNotNull(inlineRender(text = $$"$x^2$").render)
    assertEquals(
      "",
      visibleText(render.annotated)
    )
    assertTrue(render.inlineContent.isNotEmpty())
  }

  @Test
  fun `inline latex detects multiple formulas in one line`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender(text = $$"first $a$ and $b$ and $c$ end").render)

    assertEquals(
      "expected 3 inline chips for 3 formulas",
      3,
      render.inlineContent.size
    )
    assertEquals(
      3,
      render.inlineContent.keys.distinct().size
    )
    assertEquals(
      "first  and  and  end",
      visibleText(render.annotated)
    )
  }

  @Test
  fun `inline latex inside emphasis is still recognized`() {
    val render: InlineMarkdownRender = requireNotNull(inlineRender(text = $$"*$x^2$*").render)
    assertEquals(
      "",
      visibleText(render.annotated)
    )
    assertTrue(render.inlineContent.isNotEmpty())
  }

  @Test
  fun `inline latex inside strong emphasis is still recognized`() {
    val render: InlineMarkdownRender = requireNotNull(inlineRender(text = $$"**$x^2$**").render)
    assertEquals(
      "",
      visibleText(render.annotated)
    )
    assertTrue(render.inlineContent.isNotEmpty())
  }

  @Test
  fun `inline latex inside a link text is recognized but link text is preserved`() {
    val result: InlineMarkdownRenderResult = inlineRender(text = $$"[$x^2$](https://example.com)")
    val render: InlineMarkdownRender = requireNotNull(result.render)
    assertTrue(render.inlineContent.isNotEmpty())
  }

  @Test
  fun `inline latex inside a code span is not recognized - the walker does not recurse into Code`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender(text = $$"use `$x^2$` here").render)

    assertEquals(
      0,
      render.inlineContent.size
    )
    assertEquals(
      $$"use $x^2$ here",
      visibleText(render.annotated)
    )
  }

  @Test
  fun `inline latex fallback - dollar with no closing pair is plain text`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender(text = "cost is $5 and no closing").render)
    val visible: String = visibleText(render.annotated)
    assertEquals(
      "cost is $5 and no closing",
      visible
    )
    assertTrue(render.inlineContent.isEmpty())
  }

  @Test
  fun `inline latex does not match when dollar is mid-word in plain prose`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender(text = "the price is $5.99 today").render)
    assertEquals(
      "the price is $5.99 today",
      visibleText(render.annotated)
    )
  }

  @Test
  fun `inline latex chip key uses the LaTeX placeholder base and increments per call`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender($$"$x$ and $y$").render)
    val keys: List<String> = render.inlineContent.keys.toList()

    assertEquals(
      "expected 2 LaTeX chips",
      2,
      keys.size
    )
    assertEquals(
      "\uE003",
      keys[0]
    )
    assertEquals(
      "\uE003\uE003",
      keys[1]
    )
  }

  @Test
  fun `block and inline formulas coexist in a document`() {
    val source = $$"Here is $a+b=c$ inline, then a block:\n\n$$\nE = mc^2\n$$"
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(plainText = source)
    val proseText: String = (segments[0] as MarkdownSegment.Plain).text
    val blockText: String = (segments[1] as MarkdownSegment.NonProseBlock).text
    assertEquals(
      2,
      segments.size
    )
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertTrue(
      "first segment should contain the inline formula source, was `$proseText`",
      proseText.contains(other = $$"$a+b=c$")
    )
    assertTrue(segments[1] is MarkdownSegment.NonProseBlock)
    assertEquals(
      "$$\nE = mc^2\n$$",
      blockText
    )
  }

  @Test
  fun `block formula in a list item is recognized as a LatexBlock child`() {
    val document: Document = latexBlockParser.parse("- intro\n\n  $$\n  x^2\n  $$") as Document
    val blocks = generateSequence(seed = document.firstChild as Block) {
      it.next as? Block
    }.toList()
    val first: Block = blocks[0]

    assertEquals(
      1,
      blocks.size
    )
    assertTrue(
      "expected BulletList, was ${first.javaClass.simpleName}",
      first.javaClass.simpleName == "BulletList"
    )
  }


  @Test
  fun `block LatexBlock handles deeply nested braces and underscores in the formula`() {
    val formula = "f(x) = \\sum_{i=1}^{N} \\frac{x_i^2}{\\sqrt{y_{i,j}}}"
    val document: Document = latexBlockParser.parse("$$\n$formula\n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(
      formula,
      block.formula
    )
  }

  @Test
  fun `block LatexBlock handles special regex characters in the formula body`() {
    val formula = "a + b = c (with .*+? and [brackets] in it)"
    val document: Document = latexBlockParser.parse("$$\n$formula\n$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock

    assertEquals(
      formula,
      block.formula
    )
  }

  @Test
  fun `block LatexBlock inside fenced code is not a block - code wins`() {
    val document: Document = latexBlockParser.parse("```\n$$\nx^2\n$$\n```") as Document
    val first: Block = document.firstChild as Block
    assertFalse(
      "Fenced code should be a FencedCodeBlock, not a LatexBlock, was ${first.javaClass.simpleName}",
      first is LatexBlock
    )
  }

  @Test
  fun `inline placeholders do not collide - latex, image and footnote coexist, code stays text`() {
    val render: InlineMarkdownRender = requireNotNull(
      inlineRender(text = $$"`code` $x$ ![alt](url) [1]").render
    )

    assertEquals(
      3,
      render.inlineContent.size
    )
    assertEquals(
      render.inlineContent.size,
      render.inlineContent.keys.toSet().size
    )
  }

  @Test
  fun `block LatexBlock falls back to fallback text when formula is empty after stripping`() {
    val result: Block = latexBlockParser.parse("$$\n\n\n$$").firstChild as Block
    val formula: String = (result as LatexBlock).formula

    assertTrue(
      "empty-formula LatexBlock should fall back",
      formula.isEmpty()
    )
  }

  @Test
  fun `inline latex placeholder is registered with the surrounding text font size`() {
    val render: InlineMarkdownRender =
      requireNotNull(inlineRender(text = $$"text $a$ text").render)
    assertEquals(
      1,
      render.inlineContent.size
    )
    val placeholder: Any = render.inlineContent.values.first().placeholder
    assertNotNull(placeholder)
  }

  @Test
  fun `single-line dollar-dollar is parsed as a LatexBlock`() {
    val document: Document = latexBlockParser.parse("$$2x^2 - 7x + 3 = 0$$") as Document
    val first: Node = requireNotNull(document.firstChild)
    assertTrue(
      "first block should be a LatexBlock, was ${first.javaClass.simpleName}",
      first is LatexBlock
    )
    assertEquals(
      "2x^2 - 7x + 3 = 0",
      (first as LatexBlock).formula
    )
  }

  @Test
  fun `single-line dollar-dollar with leading and trailing spaces trims the formula`() {
    val document: Document = latexBlockParser.parse("$$  x^2  $$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(
      "x^2",
      block.formula
    )
  }

  @Test
  fun `single-line dollar-dollar with complex formula preserves content`() {
    val document: Document = latexBlockParser.parse("$$\\frac{1}{2} + \\sqrt{25}$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(
      "\\frac{1}{2} + \\sqrt{25}",
      block.formula
    )
  }

  @Test
  fun `single-line dollar-dollar does not match exactly four dollars`() {
    val document: Document = latexBlockParser.parse("$$$$") as Document
    assertTrue(
      "$$$$ should not be a LatexBlock, was ${document.firstChild.javaClass.simpleName}",
      document.firstChild is Paragraph
    )
  }

  @Test
  fun `single-line dollar-dollar on its own line is parsed as LatexBlock`() {
    val source = $$$"$$x^2$$"
    val document: Document = latexBlockParser.parse(source) as Document
    val blocks: List<Node> = generateSequence(seed = document.firstChild) { it.next }.toList()
    assertTrue(
      "should be a LatexBlock, was ${blocks[0].javaClass.simpleName}",
      blocks[0] is LatexBlock
    )
    assertEquals(
      "x^2",
      (blocks[0] as LatexBlock).formula
    )
  }

  @Test
  fun `single-line dollar-dollar surrounded by prose stays as Paragraph`() {
    val source = $$$"before $$x^2$$ after"
    val document: Document = latexBlockParser.parse(source) as Document
    assertTrue(
      "should be a Paragraph, was ${document.firstChild.javaClass.simpleName}",
      document.firstChild is Paragraph
    )
  }

  @Test
  fun `single-line dollar-dollar followed by another paragraph`() {
    val source = $$$"$$E = mc^2$$\n\nnext paragraph"
    val document: Document = latexBlockParser.parse(source) as Document
    val blocks: List<Node> = generateSequence(seed = document.firstChild) { it.next }.toList()

    assertTrue(
      "first should be LatexBlock",
      blocks[0] is LatexBlock
    )
    assertEquals(
      "E = mc^2",
      (blocks[0] as LatexBlock).formula
    )
    assertTrue(
      "second should be Paragraph",
      blocks[1] is Paragraph
    )
  }

  @Test
  fun `single-line dollar-dollar with underscores and braces`() {
    val formula = "\\sum_{i=1}^{N} x_i^2"
    val document: Document = latexBlockParser.parse("$$$formula$$") as Document
    val block: LatexBlock = document.firstChild as LatexBlock
    assertEquals(
      formula,
      block.formula
    )
  }

  @Test
  fun `inline dollar-dollar on its own line is parsed as block`() {
    val document: Document = latexBlockParser.parse($$$"$$x^2$$") as Document
    val blocks: List<Node> = generateSequence(seed = document.firstChild) { it.next }.toList()
    assertTrue(
      "should be a LatexBlock, was ${blocks[0].javaClass.simpleName}",
      blocks[0] is LatexBlock
    )
    assertEquals(
      "x^2",
      (blocks[0] as LatexBlock).formula
    )
  }

  @Test
  fun `currency amounts are not mis-parsed as inline latex`() {
    val render: InlineMarkdownRenderResult = inlineRender(text = "The total is $5.99 and $1,000 was the cap.")
    assertNotNull(
      "paragraph must parse",
      render.render
    )
    assertEquals(
      "a currency span must not become a formula chip",
      0, render.render!!.inlineContent.size
    )
    assertEquals(
      "currency text must be preserved verbatim",
      "The total is $5.99 and $1,000 was the cap.",
      visibleText(render.render.annotated)
    )
  }

  @Test
  fun `dollar-form latex inside a code span stays literal`() {
    val render: InlineMarkdownRenderResult = inlineRender(text = $$"`$x^2$` is a formula example.")
    assertNotNull(
      "paragraph must parse",
      render.render
    )
    assertEquals(
      0,
      render.render!!.inlineContent.size
    )
    assertEquals(
      $$"$x^2$ is a formula example.",
      visibleText(render.render.annotated)
    )
  }

  private fun countChildren(node: Node): Int =
    generateSequence(seed = node.firstChild) { it.next }.count()
}
