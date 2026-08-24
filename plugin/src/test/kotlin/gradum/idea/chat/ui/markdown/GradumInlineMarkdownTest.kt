/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumInlineMarkdownTest.kt  2026-08-22 15:13:38 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.commonmark.node.BlockQuote
import org.commonmark.node.Heading
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class GradumInlineMarkdownTest {
  private val testTint: Color = Color(0xFF60A5FA)
  private val testLinkColor: Color = Color(0xFF3B82F6)
  private val testImageAltColor: Color = Color(0xFF888888)
  private val testFontSizeSp: Float = 14f

  /** Build a render for [text] with the standard test theme values. */
  private fun render(text: String): InlineMarkdownRenderResult =
    parseInlineMarkdown(
      plainText = text,
      fontSizeSp = testFontSizeSp,
      codeColor = testTint,
      linkColor = testLinkColor,
      imageAltColor = testImageAltColor,
    )

  /**
   * Unwrap the [InlineMarkdownRender] from the result of [render]. Fails
   * the test if the parser bailed — callers are tests that need a
   * non-null render. The bail path is exercised separately via [render]
   * + null checks on `.render`.
   */
  private fun inlineRender(text: String): InlineMarkdownRender =
    requireNotNull(render(text).render)

  /** Drop PUA placeholders so assertions can read the visible text. */
  private fun visibleText(annotated: AnnotatedString): String {
    val out: StringBuilder = StringBuilder()
    for (element in annotated) {
      val currentChar: Char = element
      if (currentChar != '\uE000') out.append(currentChar)
    }
    return out.toString()
  }

  /** Count PUA placeholders — the chip is rendered as one PUA char in the annotated text. */
  private fun countPua(annotated: AnnotatedString): Int =
    annotated.count { ch -> ch == '\uE000' }

  @Test
  fun `blank input returns null render and null bailReason`() {
    // Blank input is "no rendering needed" — not a real bail. The
    // caller treats it as "use Markdown(...)" with no diagnostic.
    val blankResult: InlineMarkdownRenderResult = render("")
    assertNull(blankResult.render)
    assertNull(blankResult.bailReason)
    val whitespaceResult: InlineMarkdownRenderResult = render("   \n  \t  ")
    assertNull(whitespaceResult.render)
    assertNull(whitespaceResult.bailReason)
  }

  @Test
  fun `heading input bails with non-prose block reason`() {
    val headingResult: InlineMarkdownRenderResult = render("# Heading")
    assertNull(headingResult.render)
    // The reason should name the offending block type so a user
    // reading the ToolTip knows what kind of content tripped the bail.
    assertTrue(
      "bailReason should mention Heading: ${headingResult.bailReason}",
      headingResult.bailReason!!.contains("Heading"),
    )
  }

  @Test
  fun `list input bails with non-prose block reason`() {
    // CommonMark produces BulletList for "- item" and OrderedList for
    // "1. item" — not the abstract ListBlock base class.
    val bulletResult: InlineMarkdownRenderResult = render("- item 1\n- item 2")
    assertNull(bulletResult.render)
    assertTrue(
      "bailReason should mention BulletList: ${bulletResult.bailReason}",
      bulletResult.bailReason!!.contains("BulletList"),
    )
    val orderedResult: InlineMarkdownRenderResult = render("1. first\n2. second")
    assertNull(orderedResult.render)
    assertTrue(
      "bailReason should mention OrderedList: ${orderedResult.bailReason}",
      orderedResult.bailReason!!.contains("OrderedList"),
    )
  }

  @Test
  fun `blockquote input bails with non-prose block reason`() {
    val blockquoteResult: InlineMarkdownRenderResult = render("> quoted line")
    assertNull(blockquoteResult.render)
    assertTrue(
      "bailReason should mention BlockQuote: ${blockquoteResult.bailReason}",
      blockquoteResult.bailReason!!.contains("BlockQuote"),
    )
  }

  @Test
  fun `fenced code block bails with non-prose block reason`() {
    // A fenced code block alone is not a paragraph; we hand it to
    // Jewel Markdown (which has GradumCodeBlockRenderer) for rendering.
    val codeResult: InlineMarkdownRenderResult = render("```\nfoo\n```")
    assertNull(codeResult.render)
    assertTrue(
      "bailReason should mention FencedCodeBlock: ${codeResult.bailReason}",
      codeResult.bailReason!!.contains("FencedCodeBlock"),
    )
  }

  @Test
  fun `mixed paragraph and heading bails - bail-out is all-or-nothing`() {
    // v2 safety-net: a Plain segment containing a non-`Paragraph`
    // block bails. The upstream `splitPlainAtBlocks` splits on block
    // boundaries before the inline parser sees the text, so the inline
    // parser only ever sees paragraph-only segments in normal flow.
    // This test pins the safety-net: a direct call with un-split input
    // bails rather than silently dropping the non-paragraph blocks.
    val mixedResult: InlineMarkdownRenderResult = render("some prose\n\n# heading after")
    assertNull(mixedResult.render)
    assertNotNull(mixedResult.bailReason)
  }

  @Test
  fun `reference link definition bails with its block reason`() {
    // `[text][ref]` produces a `LinkReferenceDefinition` block in
    // addition to the paragraph — that's a non-`Paragraph` block, so
    // the parser bails. In normal flow the upstream `splitPlainAtBlocks`
    // splits this into a `Plain` sub-segment and a `NonProseBlock`, so
    // the inline parser would see the paragraph alone. This test
    // exercises the safety-net path.
    val refResult: InlineMarkdownRenderResult =
      render("see [text][ref]\n\n[ref]: https://example.com")
    assertNull(refResult.render)
    assertTrue(
      "bailReason should mention LinkReferenceDefinition: ${refResult.bailReason}",
      refResult.bailReason!!.contains("LinkReferenceDefinition"),
    )
  }

  @Test
  fun `plain text round-trips without spans`() {
    val render: InlineMarkdownRender = inlineRender("just some prose")
    assertEquals("just some prose", visibleText(render.annotated))
    // Empty SpanStyle ranges are skipped to keep the annotated string clean.
    assertEquals(0, render.annotated.spanStyles.size)
    assertEquals(0, render.inlineContent.size)
    assertEquals(1, render.paragraphCount)
  }

  @Test
  fun `plain text preserves internal whitespace`() {
    // CommonMark strips leading / trailing whitespace from a paragraph
    // (it's normalized during block parsing), but internal whitespace
    // is preserved. We assert the internal-whitespace guarantee.
    val render: InlineMarkdownRender = inlineRender("a  spaced  word")
    assertEquals("a  spaced  word", visibleText(render.annotated))
  }

  @Test
  fun `bold renders with FontWeight_SemiBold span`() {
    val render: InlineMarkdownRender = inlineRender("a **bold** word")
    val boldSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.fontWeight == FontWeight.SemiBold }
    assertEquals(1, boldSpans.size)
    assertEquals("bold", visibleText(render.annotated.subSequence(boldSpans[0].start, boldSpans[0].end)))
  }

  @Test
  fun `inline code produces one PUA placeholder`() {
    val render: InlineMarkdownRender = inlineRender("use `foo()` here")
    assertEquals(1, countPua(render.annotated))
  }

  @Test
  fun `inline code registers exactly one chip`() {
    val render: InlineMarkdownRender = inlineRender("use `foo()` here")
    assertEquals(1, render.inlineContent.size)
  }

  @Test
  fun `multiple inline codes produce multiple chips with unique keys`() {
    val render: InlineMarkdownRender = inlineRender("`a` and `bb` and `ccc`")
    assertEquals(3, render.inlineContent.size)
    // Each chip key must be unique — the PUA placeholder substrings
    // are repeated to differentiate them.
    assertEquals(3, render.inlineContent.keys.distinct().size)
  }

  @Test
  fun `inline code with backticks uses double backticks`() {
    // CommonMark: ``code with `backtick` inside``
    val render: InlineMarkdownRender = inlineRender("``code with `backtick` inside``")
    assertEquals(1, render.inlineContent.size)
    assertEquals(1, countPua(render.annotated))
  }

  @Test
  fun `inline code text is exposed via the code text annotation`() {
    // The code text is NOT the map key in `inlineContent` (that's the
    // PUA placeholder, which Compose needs as `StringAnnotation.item`
    // to do the chip lookup). The raw code text is exposed via a
    // separate annotation tag, so v3 can implement click-to-copy
    // without re-walking the AST.
    val render: InlineMarkdownRender = inlineRender("see `myFunc`")
    val codeAnnotations: List<AnnotatedString.Range<String>> =
      render.annotated.getStringAnnotations(
        tag = "INLINE_CODE_TEXT",
        start = 0,
        end = render.annotated.length,
      )
    assertEquals(1, codeAnnotations.size)
    assertEquals("myFunc", codeAnnotations[0].item)
  }

  @Test
  fun `INLINE_CONTENT_TAG annotation item matches the map key`() {
    // Pins the bug that originally caused every chip to fall back to
    // default Markdown rendering. Compose's `inlineContent` scans for
    // annotations with the FIXED internal tag Compose owns. User-defined tags are ignored. The previous v1 used
    // `pushStringAnnotation("INLINE_CODE", ...)`, which Compose
    // ignored, so the chip was silently dropped. We now use
    // `appendInlineContent(id, alternateText)` and use the PUA
    // placeholder as both the annotation item and the map key.
    val render: InlineMarkdownRender = inlineRender("use `foo()` here")
    val codeAnnotations: List<AnnotatedString.Range<String>> =
      render.annotated.getStringAnnotations(
        tag = INLINE_CONTENT_TAG,
        start = 0,
        end = render.annotated.length,
      )
    assertEquals(1, codeAnnotations.size)
    // The annotation item must be one of the keys in `inlineContent`.
    // For a single chip, that's the only key.
    assertEquals(render.inlineContent.keys.single(), codeAnnotations[0].item)
  }

  @Test
  fun `link renders with link color and permanent underline span`() {
    // The chat's link rule is "blue text + permanent underline",
    // not "blue text + underline on hover" — see `linkStateStyles`
    // KDoc for why. The walker emits a `SpanStyle` with
    // `TextDecoration.Underline` for the link's text range so
    // the `Markdown(...)` fallback path (and the legacy
    // `UrlAnnotation` + `pointerInput` path on the prose
    // `Text`) paint a stable underline, even when the IDE LaF
    // hover scanState is unreliable. The hover-affordance is added
    // by the `ExternalLink` path via `LinkUnderlineBehavior`,
    // not by the inline span. The walker's contribution here is
    // color + underline.
    val render: InlineMarkdownRender = inlineRender("see [the docs](https://example.com)")
    val linkUnderlineSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.textDecoration == TextDecoration.Underline }
    assertTrue(
      "link range must carry an Underline SpanStyle for stability",
      linkUnderlineSpans.isNotEmpty(),
    )
    // The link's color is applied to the link text range.
    val linkColorSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.color == testLinkColor }
    assertTrue(
      "link range must carry the link color",
      linkColorSpans.isNotEmpty(),
    )
  }

  @Test
  fun `link inside strikethrough composes strike and underline`() {
    // A [Strikethrough] wrapping a [Link] should produce a span
    // that has BOTH `LineThrough` (from the strike) and
    // `Underline` (from the new color-only / always-underline
    // link rule). `combineDecoration` composes the two so the
    // decoration is `Underline + LineThrough` rather than
    // dropping the strike or sneaking the underline back in.
    val render: InlineMarkdownRender = inlineRender("~~[old docs](https://example.com)~~")
    val composedSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span ->
        val dec: TextDecoration? = span.item.textDecoration
        dec != null &&
          dec.contains(TextDecoration.Underline) &&
          dec.contains(TextDecoration.LineThrough)
      }
    assertTrue(
      "strikethrough-wrapped link should carry Underline + LineThrough",
      composedSpans.isNotEmpty(),
    )
  }

  @Test
  fun `inline image renders alt text as italic placeholder and does NOT register a URL annotation`() {
    // The chat doesn't render the binary, so `![alt](imageUrl)` is a
    // *placeholder*, not a link — see the KDoc on `renderImageInline`.
    // The previous v1.5 path registered the image's URL as a
    // UrlAnnotation and applied the link color, which made the alt
    // text visually indistinguishable from a link and surprised the
    // user when a message mixed images and autolinks (the feedback
    // case: `![alt](imageUrl) <https://example.com> <email@example.com>`,
    // where the alt text and the autolinks all read as one blue
    // link). Pinning here: image is italic + imageAltColor, and
    // emits zero URL annotations.
    val render: InlineMarkdownRender = inlineRender("see ![diagram](https://example.com/diagram.png)")
    assertEquals(
      "image must not register a URL annotation — it is a placeholder, not a link",
      0,
      render.urlAnnotations.size,
    )
    val italicSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.fontStyle == FontStyle.Italic }
    assertTrue(
      "image alt text must render as italic",
      italicSpans.isNotEmpty(),
    )
    val imageAltSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.color == testImageAltColor }
    assertTrue(
      "image alt text must use the dedicated imageAltColor, not the link color",
      imageAltSpans.isNotEmpty(),
    )
  }

  @Test
  fun `link emits a URL annotation covering the link text range`() {
    val render: InlineMarkdownRender = inlineRender("visit [Example](https://example.com) today")
    val urlAnnotations: List<UrlAnnotation> = render.urlAnnotations
    assertEquals(1, urlAnnotations.size)
    assertEquals("https://example.com", urlAnnotations[0].url)
    val linkText: String = visibleText(render.annotated.subSequence(urlAnnotations[0].start, urlAnnotations[0].end))
    assertEquals("Example", linkText)
  }

  @Test
  fun `inline link destination is captured correctly`() {
    // Reference-style links like `[text][ref]` produce a
    // `LinkReferenceDefinition` block, which means the document
    // contains a non-`Paragraph` child and the parser bails (v2
    // scope: paragraph-only). Inline links like `[text](https://...)`
    // resolve inline with no extra block, so they're always supported.
    val render: InlineMarkdownRender = inlineRender("see [Example](https://example.com/page)")
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://example.com/page", render.urlAnnotations[0].url)
  }

  @Test
  fun `bare https URL is detected as link`() {
    val render: InlineMarkdownRender = inlineRender("visit https://example.com today")
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://example.com", render.urlAnnotations[0].url)
    val linkText: String = visibleText(render.annotated.subSequence(render.urlAnnotations[0].start, render.urlAnnotations[0].end))
    assertEquals("https://example.com", linkText)
  }

  @Test
  fun `bare http URL is detected as link`() {
    val render: InlineMarkdownRender = inlineRender("check http://localhost:8080/api now")
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("http://localhost:8080/api", render.urlAnnotations[0].url)
  }

  @Test
  fun `bare URL followed by period excludes trailing punctuation`() {
    val render: InlineMarkdownRender = inlineRender("see https://example.com.")
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://example.com", render.urlAnnotations[0].url)
  }

  @Test
  fun `bare URL inside inline code is NOT detected as link`() {
    val render: InlineMarkdownRender = inlineRender("use `https://example.com` in code")
    assertEquals(0, render.urlAnnotations.size)
  }

  @Test
  fun `multiple bare URLs in one paragraph are all detected`() {
    val render: InlineMarkdownRender = inlineRender("a https://first.com and https://second.org here")
    assertEquals(2, render.urlAnnotations.size)
    assertEquals("https://first.com", render.urlAnnotations[0].url)
    assertEquals("https://second.org", render.urlAnnotations[1].url)
  }

  @Test
  fun `bare URL with path containing hyphens and dots is detected as link`() {
    val render: InlineMarkdownRender = inlineRender("see http://www.apache.org/licenses/LICENSE-2.0.")
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("http://www.apache.org/licenses/LICENSE-2.0", render.urlAnnotations[0].url)
  }

  @Test
  fun `bare URL with path containing hyphens and html extension is detected as link`() {
    val render: InlineMarkdownRender = inlineRender("see https://www.eclipse.org/legal/epl-v10.html.")
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://www.eclipse.org/legal/epl-v10.html", render.urlAnnotations[0].url)
  }

  @Test
  fun `bare URL with path containing multiple hyphens is detected as link`() {
    val render: InlineMarkdownRender = inlineRender("visit https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html.")
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html", render.urlAnnotations[0].url)
  }

  @Test
  fun `bare URL in markdown link text does NOT duplicate annotations`() {
    val render: InlineMarkdownRender = inlineRender("[click](https://example.com)")
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://example.com", render.urlAnnotations[0].url)
  }

  @Test
  fun `bold with code in same paragraph - both render`() {
    val render: InlineMarkdownRender = inlineRender("**bold** and `code` together")
    assertTrue(
      "expected bold span",
      render.annotated.spanStyles.any { span -> span.item.fontWeight == FontWeight.SemiBold },
    )
    assertEquals(1, render.inlineContent.size)
  }

  @Test
  fun `two paragraphs separated by blank line render as two paragraphs`() {
    val render: InlineMarkdownRender = inlineRender("first.\n\nsecond.")
    assertEquals(2, render.paragraphCount)
    val visible: String = visibleText(render.annotated)
    assertEquals("first.\n\nsecond.", visible)
  }

  @Test
  fun `escaped asterisks render as literal asterisks - no emphasis`() {
    val render: InlineMarkdownRender = inlineRender("not \\*italic\\* here")
    val visible: String = visibleText(render.annotated)
    assertEquals("not *italic* here", visible)
    assertTrue(render.annotated.spanStyles.none { span -> span.item.fontStyle == FontStyle.Italic })
  }

  @Test
  fun `soft line break becomes a single space`() {
    val render: InlineMarkdownRender = inlineRender("line one\nline two")
    val visible: String = visibleText(render.annotated)
    assertEquals("line one line two", visible)
  }

  @Test
  fun `paragraph with only inline code renders correctly`() {
    // The chip is the source of truth for the code text — the
    // `InlineTextContent` lambda closes over the `code` String and
    // passes it to `InlineCodeChip`. We can't easily reach into the
    // chip from a unit test, so the chip-registered assertion is the
    // meaningful one.
    val render: InlineMarkdownRender = inlineRender("`only code`")
    assertEquals(1, render.inlineContent.size)
    assertEquals(1, countPua(render.annotated))
  }

  @Test
  fun `parser does not throw on unclosed backtick`() {
    // CommonMark treats `foo` (no closing backtick) as literal text.
    // The parser should not throw — a defensive try/catch wraps the
    // walker and converts any throw into a bail with a descriptive
    // reason (logged to the IDE log).
    val result: InlineMarkdownRenderResult = render("a `foo with no closing")
    assertNotNull(result)
    // A single paragraph with literal text containing an unclosed
    // backtick should still render successfully (the backtick is just
    // literal text in the AST).
    assertNotNull(result.render)
  }

  @Test
  fun `strikethrough renders with LineThrough span`() {
    // GFM extension: `~~struck~~`. With the Strikethrough extension
    // enabled, the parser produces a Strikethrough inline node that
    // the renderer translates to a SpanStyle with LineThrough. The
    // visible text is preserved.
    val render: InlineMarkdownRender = inlineRender("a ~~struck~~ word")
    val strikeSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span ->
        span.item.textDecoration?.contains(TextDecoration.LineThrough) == true
      }
    assertEquals(1, strikeSpans.size)
    assertEquals(
      "struck",
      visibleText(render.annotated.subSequence(strikeSpans[0].start, strikeSpans[0].end)),
    )
  }

  @Test
  fun `strikethrough accumulates with bold - both styles apply`() {
    // `~~**bold strike**~~` — the inner bold gets BOTH the bold
    // weight AND the line-through decoration.
    val render: InlineMarkdownRender = inlineRender("~~**bold strike**~~")
    val strikeSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span ->
        span.item.textDecoration?.contains(TextDecoration.LineThrough) == true
      }
    assertEquals(1, strikeSpans.size)
    val strikeRange: AnnotatedString.Range<SpanStyle> = strikeSpans[0]
    assertEquals(FontWeight.SemiBold, strikeRange.item.fontWeight)
    assertEquals(
      "bold strike",
      visibleText(render.annotated.subSequence(strikeRange.start, strikeRange.end)),
    )
  }

  @Test
  fun `strikethrough inside link - link underline plus strike`() {
    // `~~[struck link](https://x.test)~~` — the link's text is
    // struck-through, and the link's underline decoration coexists
    // with the strike (composed via combineDecoration).
    val render: InlineMarkdownRender = inlineRender("see ~~[struck link](https://x.test)~~ here")
    val strikeSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span ->
        span.item.textDecoration?.contains(TextDecoration.LineThrough) == true
      }
    assertTrue("expected at least one strike span", strikeSpans.isNotEmpty())
    // The link's URL is still emitted (URL annotations are not
    // stripped by the strike wrapper).
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://x.test", render.urlAnnotations[0].url)
  }

  @Test
  fun `strikethrough and inline code in same paragraph both render`() {
    // Mix of formatting — the chip and the strike should be
    // independent (the strike only spans the tided text, the chip is
    // a separate `inlineContent` entry).
    val render: InlineMarkdownRender = inlineRender("struck ~~here~~ with `code`")
    val strikeSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span ->
        span.item.textDecoration?.contains(TextDecoration.LineThrough) == true
      }
    assertEquals(1, strikeSpans.size)
    assertEquals(1, render.inlineContent.size)
  }

  @Test
  fun `cjkAwareWidthRatio for pure Latin uses latin ratio`() {
    // 5 ASCII chars × 0.6 = 3.0
    assertEquals(3.0f, cjkAwareWidthRatio("hello"), 0.0001f)
  }

  @Test
  fun `cjkAwareWidthRatio for pure CJK uses CJK ratio`() {
    // 3 Chinese ideographs × 1.0 = 3.0 — much wider than the
    // previous Latin-only `0.6f * 3 = 1.8` would have allocated. This
    // is the bug the user reported: chip clipped Chinese code spans
    // because the old computation under-allocated width for full-width
    // characters.
    assertEquals(3.0f, cjkAwareWidthRatio("中文测"), 0.0001f)
  }

  @Test
  fun `cjkAwareWidthRatio for mixed CJK and Latin sums per char`() {
    // "中a文" — 2 CJK × 1.0 + 1 Latin × 0.6 = 2.6
    assertEquals(2.6f, cjkAwareWidthRatio("中a文"), 0.0001f)
  }

  @Test
  fun `cjkAwareWidthRatio recognizes full-width punctuation`() {
    // "，" (U+FF0C) is full-width comma → CJK ratio.
    // "a,b" — comma is ASCII Latin; "a，b" — comma is full-width.
    assertEquals(1.8f, cjkAwareWidthRatio("a,b"), 0.0001f) // 3 × 0.6
    assertEquals(2.2f, cjkAwareWidthRatio("a，b"), 0.0001f) // 2 × 0.6 + 1 × 1.0
  }

  @Test
  fun `cjkAwareWidthRatio empty string returns zero`() {
    assertEquals(0f, cjkAwareWidthRatio(""), 0.0001f)
  }

  @Test
  fun `inline code with Chinese characters renders successfully`() {
    // The fix for the "chip clips Chinese code" bug. We can't easily
    // reach into the chip's Placeholder from a unit test (it's stored
    // in a private map and computed inside a closure), but the chip
    // width is derived from `cjkAwareWidthRatio`, so a longer code
    // string of CJK chars produces a wider chip than the same length
    // of Latin chars. We verify the helper directly in the dedicated
    // tests above and assert the public render still succeeds here
    // (no exception, one chip registered).
    val render: InlineMarkdownRender = inlineRender("use `中文测试` here")
    assertEquals(1, render.inlineContent.size)
    assertEquals(1, countPua(render.annotated))
  }

  // parseInlineNodes — the AST-walking entry point used by
  // RenderHeading to avoid the serialize-then-re-parse round-trip that
  // would change block structure for headings starting with a list
  // marker (e.g. `### 2. **bold**` → `2. **bold**` → OrderedList,
  // which bails the inline parser).

  @Test
  fun `parseInlineNodes on a heading walks its inline children directly`() {
    // Build the heading AST by parsing the original Markdown source
    // once with commonmark — this is what RenderNonProseBlock does
    // before dispatching to RenderHeading.
    val parser: Parser = Parser.builder().build()
    val headingNode: Heading =
      parser.parse("### 2. **游戏速度不一致**").firstChild as Heading
    val result: InlineMarkdownRenderResult = parseInlineNodes(
      parentNode = headingNode,
      linkColor = testLinkColor,
      fontSizeSp = testFontSizeSp,
      imageAltColor = testImageAltColor,
    )
    val render: InlineMarkdownRender = requireNotNull(result.render)
    val visible: String = visibleText(render.annotated)
    // The list-marker-looking "2. " prefix is preserved as text and
    // the strong-emphasis renders as a bold span — neither was
    // possible through the old serialize-then-re-parse flow.
    assertEquals("2. 游戏速度不一致", visible)
    val boldSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.fontWeight == FontWeight.SemiBold }
    assertEquals(1, boldSpans.size)
    val boldText: String =
      visibleText(render.annotated.subSequence(boldSpans[0].start, boldSpans[0].end))
    assertEquals("游戏速度不一致", boldText)
  }

  @Test
  fun `parseInlineNodes on a heading without a list marker also renders bold`() {
    val parser: Parser = Parser.builder().build()
    val headingNode: Heading =
      parser.parse("## **bold heading**").firstChild as Heading
    val result: InlineMarkdownRenderResult = parseInlineNodes(
      parentNode = headingNode,
      linkColor = testLinkColor,
      fontSizeSp = testFontSizeSp,
      imageAltColor = testImageAltColor,
    )
    val render: InlineMarkdownRender = requireNotNull(result.render)
    val boldSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.fontWeight == FontWeight.SemiBold }
    assertEquals(1, boldSpans.size)
    assertEquals(
      "bold heading",
      visibleText(render.annotated.subSequence(boldSpans[0].start, boldSpans[0].end)),
    )
  }

  @Test
  fun `parseInlineNodes on a heading with a link renders the link span`() {
    val parser: Parser = Parser.builder().build()
    val headingNode: Heading =
      parser.parse("### see [docs](https://example.com) please").firstChild as Heading
    val result: InlineMarkdownRenderResult = parseInlineNodes(
      parentNode = headingNode,
      linkColor = testLinkColor,
      fontSizeSp = testFontSizeSp,
      imageAltColor = testImageAltColor,
    )
    val render: InlineMarkdownRender = requireNotNull(result.render)
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://example.com", render.urlAnnotations[0].url)
  }

  @Test
  fun `parseInlineNodes on a manually-built heading with only text returns the text`() {
    // Empty / synthetic AST — proves the walker handles a fresh
    // Heading that wasn't produced by commonmark (e.g. constructed
    // by a future caller).
    val headingNode: Heading = Heading().apply { level = 3 }
    headingNode.appendChild(Text("just text"))
    val result: InlineMarkdownRenderResult = parseInlineNodes(
      parentNode = headingNode,
      linkColor = testLinkColor,
      fontSizeSp = testFontSizeSp,
      imageAltColor = testImageAltColor,
    )
    val render: InlineMarkdownRender = requireNotNull(result.render)
    assertEquals("just text", visibleText(render.annotated))
  }

  @Test
  fun `parseInlineNodes on a paragraph that starts with a list marker renders bold`() {
    // The list-item / blockquote cases share the same root cause as
    // the heading case: a Paragraph's inline content starting with
    // a list marker (e.g. `1. `) gets reparsed as an OrderedList
    // by `parseInlineMarkdown`, which bails on non-Paragraph blocks.
    // Walking the AST directly preserves the formatting. Here we
    // build the Paragraph manually (the way RenderListItem /
    // RenderBlockQuoteChild do) and verify bold survives.
    // AST: Document → OrderedList → ListItem → Paragraph → StrongEmphasis.
    // The `1. ` is the ListItem's marker (rendered separately by the
    // list renderer), NOT a Text child of the Paragraph — so the
    // Paragraph's children are just `**bold item**`.
    val parser: Parser = Parser.builder().build()
    val paragraphNode: Paragraph = parser.parse("1. **bold item**")
      .firstChild.firstChild.firstChild as Paragraph
    val result: InlineMarkdownRenderResult = parseInlineNodes(
      parentNode = paragraphNode,
      linkColor = testLinkColor,
      fontSizeSp = testFontSizeSp,
      imageAltColor = testImageAltColor,
    )
    val render: InlineMarkdownRender = requireNotNull(result.render)
    val visible: String = visibleText(render.annotated)
    assertEquals("bold item", visible)
    val boldSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.fontWeight == FontWeight.SemiBold }
    assertEquals(1, boldSpans.size)
    assertEquals(
      "bold item",
      visibleText(render.annotated.subSequence(boldSpans[0].start, boldSpans[0].end)),
    )
  }

  @Test
  fun `parseInlineNodes on a paragraph inside a blockquote renders bold`() {
    // A blockquote containing a list-marker-shaped paragraph is the
    // same lossy round-trip as the heading case — commonmark
    // re-interpreted the leading `1. ` as an OrderedList, the inline
    // parser bailed, the bold was lost. Walking the AST keeps it.
    // AST: Document → BlockQuote → OrderedList → ListItem → Paragraph → StrongEmphasis
    val parser: Parser = Parser.builder().build()
    val blockQuote: BlockQuote = parser.parse("> 1. **bold quote**").firstChild as BlockQuote
    val paragraphNode: Paragraph =
      blockQuote.firstChild.firstChild.firstChild as Paragraph
    val result: InlineMarkdownRenderResult = parseInlineNodes(
      parentNode = paragraphNode,
      linkColor = testLinkColor,
      fontSizeSp = testFontSizeSp,
      imageAltColor = testImageAltColor,
    )
    val render: InlineMarkdownRender = requireNotNull(result.render)
    val boldSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.fontWeight == FontWeight.SemiBold }
    assertEquals(1, boldSpans.size)
    assertEquals(
      "bold quote",
      visibleText(render.annotated.subSequence(boldSpans[0].start, boldSpans[0].end)),
    )
  }
}
