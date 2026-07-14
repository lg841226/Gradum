package gradum.idea.chat.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradumInlineMarkdownTest {

  // -----------------------------------------------------------------
  // Test helpers
  // -----------------------------------------------------------------

  private val testTint: Color = Color(0xFF60A5FA)
  private val testLinkColor: Color = Color(0xFF3B82F6)
  private val testFontSizeSp: Float = 14f

  /** Build a render for [text] with the standard test theme values. */
  private fun render(text: String): InlineMarkdownRenderResult =
    parseInlineMarkdown(
      plainText = text,
      fontSizeSp = testFontSizeSp,
      chipTint = testTint,
      linkColor = testLinkColor,
    )

  /**
   * Unwrap the [InlineMarkdownRender] from the result of [render]. Fails
   * the test if the parser bailed — callers are tests that need a
   * non-null render (the bail path is exercised separately via
   * [render] + null checks on `.render`).
   */
  private fun inlineRender(text: String): InlineMarkdownRender =
    requireNotNull(render(text).render)

  /** Collect the visible text from an AnnotatedString (filters out PUA placeholders). */
  private fun visibleText(annotated: AnnotatedString): String {
    val out: StringBuilder = StringBuilder()
    for (i in 0 until annotated.length) {
      val ch: Char = annotated[i]
      // PUA placeholder char is U+E000
      if (ch != '\uE000') out.append(ch)
    }
    return out.toString()
  }

  // -----------------------------------------------------------------
  // Bailing-out cases (parser returns render == null + a bailReason)
  // -----------------------------------------------------------------

  @Test
  fun `blank input returns null render and null bailReason`() {
    // Blank input is "no rendering needed" — not a real bail. The
    // caller treats it as "use Markdown(...)" with no diagnostic.
    val blank: InlineMarkdownRenderResult = render("")
    assertNull(blank.render)
    assertNull(blank.bailReason)
    val whitespace: InlineMarkdownRenderResult = render("   \n  \t  ")
    assertNull(whitespace.render)
    assertNull(whitespace.bailReason)
  }

  @Test
  fun `heading input bails with non-prose block reason`() {
    val result: InlineMarkdownRenderResult = render("# Heading")
    assertNull(result.render)
    assertNotNull(result.bailReason)
    // The reason should name the offending block type so a user
    // reading the ToolTip knows what kind of content tripped the bail.
    assertTrue(
      "bailReason should mention Heading: ${result.bailReason}",
      result.bailReason!!.contains("Heading"),
    )
  }

  @Test
  fun `list input bails with non-prose block reason`() {
    // CommonMark produces BulletList for "- item" and OrderedList for
    // "1. item" — not the abstract ListBlock base class.
    val r1: InlineMarkdownRenderResult = render("- item 1\n- item 2")
    assertNull(r1.render)
    assertTrue(
      "bailReason should mention BulletList: ${r1.bailReason}",
      r1.bailReason!!.contains("BulletList"),
    )
    val r2: InlineMarkdownRenderResult = render("1. first\n2. second")
    assertNull(r2.render)
    assertTrue(
      "bailReason should mention OrderedList: ${r2.bailReason}",
      r2.bailReason!!.contains("OrderedList"),
    )
  }

  @Test
  fun `blockquote input bails with non-prose block reason`() {
    val result: InlineMarkdownRenderResult = render("> quoted line")
    assertNull(result.render)
    assertTrue(
      "bailReason should mention BlockQuote: ${result.bailReason}",
      result.bailReason!!.contains("BlockQuote"),
    )
  }

  @Test
  fun `fenced code block bails with non-prose block reason`() {
    // A fenced code block alone is not a paragraph; we hand it to
    // Jewel Markdown (which has GradumCodeBlockRenderer) for rendering.
    val result: InlineMarkdownRenderResult = render("```\nfoo\n```")
    assertNull(result.render)
    assertTrue(
      "bailReason should mention FencedCodeBlock: ${result.bailReason}",
      result.bailReason!!.contains("FencedCodeBlock"),
    )
  }

  @Test
fun `mixed paragraph and heading bails — bail-out is all-or-nothing`() {
  // v2 safety-net: a Plain segment that contains a non-`Paragraph`
  // block bails. The upstream segmenter (`splitPlainAtBlocks` in
  // GradumMarkdownTable.kt) splits the message on block boundaries
  // BEFORE the inline parser sees it, so the inline parser only ever
  // sees paragraph-only segments in normal flow. This test pins the
  // safety-net behavior: if a caller hands a mixed segment directly
  // to the inline parser, it bails (rather than silently dropping
  // the non-paragraph blocks — which previously caused messages
  // to render incomplete, e.g. only 20 chars of an 804-char
  // segment visible).
  val r: InlineMarkdownRenderResult = render("some prose\n\n# heading after")
  assertNull(r.render)
  assertNotNull(r.bailReason)
}

@Test
fun `reference link with definition bails with LinkReferenceDefinition reason`() {
  // `[text][ref]` produces a `LinkReferenceDefinition` block (in
  // addition to the paragraph) — that's a non-`Paragraph` block, so
  // the parser bails. The bailReason names the specific type so the
  // developer can see why. Note: in normal flow, the upstream
  // `splitPlainAtBlocks` would split this into a `Plain` sub-segment
  // (containing the paragraph) and a `NonProseBlock` (containing the
  // reference definition), so the inline parser would actually see
  // the paragraph alone and render the resolved link correctly. The
  // test exercises the safety-net path: a direct call to
  // `parseInlineMarkdown` with the un-split input.
  val result: InlineMarkdownRenderResult = render("see [text][ref]\n\n[ref]: https://example.com")
  assertNull(result.render)
  assertTrue(
    "bailReason should mention LinkReferenceDefinition: ${result.bailReason}",
    result.bailReason!!.contains("LinkReferenceDefinition"),
  )
}

// -----------------------------------------------------------------
// Plain text
// -----------------------------------------------------------------

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

  // -----------------------------------------------------------------
  // Bold / italic / strong
  // -----------------------------------------------------------------

  @Test
  fun `bold renders with FontWeight_Bold span`() {
    val render: InlineMarkdownRender = inlineRender("a **bold** word")
    val boldSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
    assertEquals(1, boldSpans.size)
    assertEquals("bold", visibleText(render.annotated.subSequence(boldSpans[0].start, boldSpans[0].end)))
  }

  @Test
  fun `italic renders with FontStyle_Italic span`() {
    val render: InlineMarkdownRender = inlineRender("an *italic* word")
    val italicSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.fontStyle == FontStyle.Italic }
    assertEquals(1, italicSpans.size)
    assertEquals("italic", visibleText(render.annotated.subSequence(italicSpans[0].start, italicSpans[0].end)))
  }

  @Test
  fun `bold and italic in the same paragraph both render`() {
    val render: InlineMarkdownRender = inlineRender("**bold** and *italic*")
    assertTrue(render.annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    assertTrue(render.annotated.spanStyles.any { span -> span.item.fontStyle == FontStyle.Italic })
  }

  @Test
  fun `nested emphasis accumulates styles — bold containing italic`() {
    // CommonMark: "**bold *italic* inside**" — the inner italic word
    // is both bold and italic.
    val render: InlineMarkdownRender = inlineRender("**bold *italic* inside**")
    val italicSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { span -> span.item.fontStyle == FontStyle.Italic }
    assertEquals(1, italicSpans.size)
    val italicRange: AnnotatedString.Range<SpanStyle> = italicSpans[0]
    val italicText: String = visibleText(render.annotated.subSequence(italicRange.start, italicRange.end))
    assertEquals("italic", italicText)
    // The italic range must ALSO have bold (style is accumulated)
    assertEquals(FontWeight.Bold, italicRange.item.fontWeight)
  }

  // -----------------------------------------------------------------
  // Inline code
  // -----------------------------------------------------------------

  @Test
  fun `inline code produces one PUA placeholder in the annotated text`() {
    val render: InlineMarkdownRender = inlineRender("use `foo()` here")
    val puaCount: Int = render.annotated.count { char -> char == '\uE000' }
    assertEquals(1, puaCount)
  }

  @Test
  fun `inline code registers exactly one chip in inlineContent`() {
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
  fun `inline code can contain backticks when using double backticks`() {
    // CommonMark: ``code with `backtick` inside``
    val render: InlineMarkdownRender = inlineRender("``code with `backtick` inside``")
    assertEquals(1, render.inlineContent.size)
    val puaCount: Int = render.annotated.count { char -> char == '\uE000' }
    assertEquals(1, puaCount)
  }

  @Test
  fun `inline code text is exposed via the INLINE_CODE_TEXT annotation`() {
    // The code text (e.g. "myFunc") is NOT the map key in
    // `inlineContent` — that's the PUA placeholder, which Compose
    // needs as `StringAnnotation.item` to do the chip lookup. The raw
    // code text is exposed via a separate annotation tag, so v3 can
    // implement a click-to-copy affordance without re-walking the AST.
    val render: InlineMarkdownRender = inlineRender("see `myFunc`")
    val codeAnnotations: List<AnnotatedString.Range<String>> =
      render.annotated.getStringAnnotations(tag = "INLINE_CODE_TEXT", start = 0, end = render.annotated.length)
    assertEquals(1, codeAnnotations.size)
    assertEquals("myFunc", codeAnnotations[0].item)
  }

  @Test
  fun `INLINE_CONTENT_TAG annotation item matches the inlineContent map key`() {
    // This pins the bug that originally caused every chip to fall back
    // to default Markdown rendering. Compose's `inlineContent` mechanism
    // scans for annotations with the FIXED internal tag
    // "androidx.compose.foundation.text.inlineContent" (a constant
    // Compose owns). It does NOT scan arbitrary user-defined tags —
    // the previous v1 approach used `pushStringAnnotation("INLINE_CODE", ...)`,
    // which Compose ignored, so the chip was silently dropped and the
    // segment was rendered with default `Markdown(...)`. We now use
    // `appendInlineContent(id, alternateText)` (which pushes the
    // correct tag for us) and use the PUA placeholder as both the
    // annotation item and the map key.
    val render: InlineMarkdownRender = inlineRender("use `foo()` here")
    val codeAnnotations: List<AnnotatedString.Range<String>> =
      render.annotated.getStringAnnotations(
        tag = "androidx.compose.foundation.text.inlineContent",
        start = 0,
        end = render.annotated.length,
      )
    assertEquals(1, codeAnnotations.size)
    val annotationItem: String = codeAnnotations[0].item
    // The annotation item must be one of the keys in `inlineContent`.
    // For a single chip, that's the only key.
    assertEquals(render.inlineContent.keys.single(), annotationItem)
  }

  // -----------------------------------------------------------------
  // Links
  // -----------------------------------------------------------------

  @Test
  fun `link renders with link color and underline span`() {
    val render: InlineMarkdownRender = inlineRender("see [the docs](https://example.com)")
    val linkSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter { it.item.textDecoration == TextDecoration.Underline }
    assertEquals(1, linkSpans.size)
    assertEquals(testLinkColor, linkSpans[0].item.color)
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
    // `LinkReferenceDefinition` block in the AST, which means the
    // document contains a non-`Paragraph` child and the parser bails
    // out (v2 scope: paragraph-only). Inline links like
    // `[text](https://...)` resolve inline with no extra block, so
    // they're always supported. This test pins the inline-link path.
    val render: InlineMarkdownRender = inlineRender("see [Example](https://example.com/page)")
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://example.com/page", render.urlAnnotations[0].url)
  }

  // -----------------------------------------------------------------
  // Mixed inline elements
  // -----------------------------------------------------------------

  @Test
  fun `bold with code in same paragraph — both render`() {
    val render: InlineMarkdownRender = inlineRender("**bold** and `code` together")
    assertTrue("expected bold span", render.annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    assertEquals(1, render.inlineContent.size)
  }

  @Test
  fun `italic with link in same paragraph — both render`() {
    val render: InlineMarkdownRender = inlineRender("*italic* and [link](https://x.test)")
    assertTrue("expected italic span", render.annotated.spanStyles.any { span -> span.item.fontStyle == FontStyle.Italic })
    assertEquals(1, render.urlAnnotations.size)
  }

  // -----------------------------------------------------------------
  // Multiple paragraphs
  // -----------------------------------------------------------------

  @Test
  fun `two paragraphs separated by blank line are rendered as two paragraphs`() {
    val render: InlineMarkdownRender = inlineRender("first.\n\nsecond.")
    assertEquals(2, render.paragraphCount)
    val visible: String = visibleText(render.annotated)
    assertEquals("first.\n\nsecond.", visible)
  }

  // -----------------------------------------------------------------
  // Escaped characters
  // -----------------------------------------------------------------

  @Test
  fun `escaped asterisks render as literal asterisks — no emphasis`() {
    val render: InlineMarkdownRender = inlineRender("not \\*italic\\* here")
    val visible: String = visibleText(render.annotated)
    assertEquals("not *italic* here", visible)
    assertTrue(render.annotated.spanStyles.none { span -> span.item.fontStyle == FontStyle.Italic })
  }

  // -----------------------------------------------------------------
  // Whitespace and special chars
  // -----------------------------------------------------------------

  @Test
  fun `soft line break becomes a single space`() {
    val render: InlineMarkdownRender = inlineRender("line one\nline two")
    val visible: String = visibleText(render.annotated)
    assertEquals("line one line two", visible)
  }

  @Test
  fun `paragraph with only inline code renders correctly`() {
    val render: InlineMarkdownRender = inlineRender("`only code`")
    // The chip is the source of truth for the code text — the
    // `InlineTextContent` lambda closes over the `code` String and
    // passes it to `InlineCodeChip`. We can't easily reach into the
    // chip from a unit test, so the chip-registered assertion below
    // is the meaningful one.
    assertEquals(1, render.inlineContent.size)
    val puaCount: Int = render.annotated.count { char -> char == '\uE000' }
    assertEquals(1, puaCount)
  }

  // -----------------------------------------------------------------
  // Null safety / defensiveness
  // -----------------------------------------------------------------

  @Test
  fun `parser does not throw on unclosed backtick`() {
    // CommonMark treats `foo` (no closing backtick) as literal text.
    // The parser should not throw — a defensive try/catch wraps the
    // walker and converts any throw into a bail with a descriptive
    // reason (logged to the IDE log).
    val result: InlineMarkdownRenderResult = render("a `foo with no closing")
    assertNotNull(result)
    // Specifically: a single paragraph with literal text containing
    // an unclosed backtick should still render successfully (the
    // backtick is just literal text in the AST).
    assertNotNull(result.render)
  }

  // -----------------------------------------------------------------
  // GFM Strikethrough (~~struck~~) — added 2026-07-14
  // -----------------------------------------------------------------

  @Test
  fun `strikethrough renders with LineThrough span`() {
    // GFM extension: `~~struck~~`. With the Strikethrough extension
    // enabled, the parser produces a [Strikethrough] inline node
    // that the renderer translates to a SpanStyle with
    // [TextDecoration.LineThrough]. The visible text is preserved.
    val render: InlineMarkdownRender = inlineRender("a ~~struck~~ word")
    val strikeSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter {
        it.item.textDecoration?.contains(TextDecoration.LineThrough) == true
      }
    assertEquals(1, strikeSpans.size)
    assertEquals(
      "struck",
      visibleText(render.annotated.subSequence(strikeSpans[0].start, strikeSpans[0].end))
    )
  }

  @Test
  fun `strikethrough accumulates with bold — both styles apply`() {
    // `~~**bold strike**~~` — the inner bold gets BOTH the bold
    // weight AND the line-through decoration.
    val render: InlineMarkdownRender = inlineRender("~~**bold strike**~~")
    val strikeSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter {
        it.item.textDecoration?.contains(TextDecoration.LineThrough) == true
      }
    assertEquals(1, strikeSpans.size)
    val strikeRange: AnnotatedString.Range<SpanStyle> = strikeSpans[0]
    assertEquals(FontWeight.Bold, strikeRange.item.fontWeight)
    assertEquals(
      "bold strike",
      visibleText(render.annotated.subSequence(strikeRange.start, strikeRange.end))
    )
  }

  @Test
  fun `strikethrough inside link — link underline plus strike`() {
    // `~~[struck link](https://x.test)~~` — the link's text is
    // struck-through, and the link's underline decoration coexists
    // with the strike (composed via [combineDecoration]).
    val render: InlineMarkdownRender =
      inlineRender("see ~~[struck link](https://x.test)~~ here")
    val strikeSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter {
        it.item.textDecoration?.contains(TextDecoration.LineThrough) == true
      }
    assertTrue("expected at least one strike span", strikeSpans.isNotEmpty())
    // The link's URL is still emitted (URL annotations are not
    // stripped by the strike wrapper).
    assertEquals(1, render.urlAnnotations.size)
    assertEquals("https://x.test", render.urlAnnotations[0].url)
  }

  @Test
  fun `strikethrough text and inline code in same paragraph both render`() {
    // Mix of formatting — the chip and the strike should be
    // independent (the strike only spans the tilded text, the
    // chip is a separate `inlineContent` entry).
    val render: InlineMarkdownRender = inlineRender("struck ~~here~~ with `code`")
    val strikeSpans: List<AnnotatedString.Range<SpanStyle>> =
      render.annotated.spanStyles.filter {
        it.item.textDecoration?.contains(TextDecoration.LineThrough) == true
      }
    assertEquals(1, strikeSpans.size)
    assertEquals(1, render.inlineContent.size)
  }

  // -----------------------------------------------------------------
  // CJK-aware inline code chip width — added 2026-07-14
  // -----------------------------------------------------------------

  @Test
  fun `cjkAwareWidthRatio for pure Latin uses latin ratio`() {
    // 5 ASCII chars × 0.6 = 3.0
    assertEquals(3.0f, cjkAwareWidthRatio("hello"), 0.0001f)
  }

  @Test
  fun `cjkAwareWidthRatio for pure CJK uses CJK ratio`() {
    // 3 Chinese ideographs × 1.0 = 3.0 — much wider than the
    // previous Latin-only `0.6f * 3 = 1.8` would have allocated.
    // This is the bug the user reported: chip clipped Chinese
    // code spans because the old computation under-allocated
    // width for full-width characters.
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
    assertEquals(1.8f, cjkAwareWidthRatio("a,b"), 0.0001f)   // 3 × 0.6
    assertEquals(2.2f, cjkAwareWidthRatio("a，b"), 0.0001f)   // 2 × 0.6 + 1 × 1.0
  }

  @Test
  fun `cjkAwareWidthRatio empty string returns zero`() {
    assertEquals(0f, cjkAwareWidthRatio(""), 0.0001f)
  }

  @Test
  fun `inline code with Chinese characters gets wider placeholder than Latin-only`() {
    // The fix for the "chip clips Chinese code" bug. We can't
    // easily reach into the chip's Placeholder from a unit test
    // (it's stored in a private map and computed inside a
    // closure), but the chip width is derived from the
    // `cjkAwareWidthRatio` value, so a larger code string of
    // CJK chars produces a wider chip than the same length of
    // Latin chars. We verify the helper directly above and
    // assert the public render still succeeds here (no
    // exception, one chip registered).
    val render: InlineMarkdownRender = inlineRender("use `中文测试` here")
    assertEquals(1, render.inlineContent.size)
    assertEquals(1, render.annotated.count { char -> char == '\uE000' })
  }
}
