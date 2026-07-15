/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumInlineSegmentTest.kt  2026-07-15 17:15:59 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [splitIntoInlineSegments] — the link-boundary slicer that
 * lets the renderer use `ExternalLink` for link ranges while keeping
 * `Text(annotated, inlineContent = ...)` for the prose path.
 *
 * The slicer is a pure function (no Compose, no theme), so these run
 * in the standard JUnit 4 sandbox against the `internal` helper
 * directly.
 */
class GradumInlineSegmentTest {

  @Test
  fun `no url annotations yields a single text segment`() {
    val annotated: AnnotatedString = buildAnnotatedString { append("plain prose with no links") }
    val segments: List<InlineSegment> = splitIntoInlineSegments(
      annotated = annotated,
      inlineContent = emptyMap(),
      urlAnnotations = emptyList(),
    )
    assertEquals(1, segments.size)
    assertTrue(segments[0] is InlineSegment.TextSegment)
    val textSeg: InlineSegment.TextSegment = segments[0] as InlineSegment.TextSegment
    assertEquals("plain prose with no links", textSeg.annotated.toString())
  }

  @Test
  fun `one link in the middle produces two text segments and one link segment`() {
    // Walker output for "see [the docs](https://example.com) please":
    //   "see " (text), "the docs" (link, 8 chars), " please" (text)
    // The `[]` brackets are part of the Markdown source consumed
    // by the parser — the walker only sees the Link's child Text
    // node "the docs", not the brackets.
    val annotated: AnnotatedString = buildAnnotatedString { append("see the docs please") }
    val urlAnnotations: List<UrlAnnotation> = listOf(
      UrlAnnotation(start = 4, end = 12, url = "https://example.com"),
    )
    val segments: List<InlineSegment> = splitIntoInlineSegments(
      annotated = annotated,
      inlineContent = emptyMap(),
      urlAnnotations = urlAnnotations,
    )
    assertEquals(3, segments.size)
    assertTrue(segments[0] is InlineSegment.TextSegment)
    assertEquals("see ", (segments[0] as InlineSegment.TextSegment).annotated.toString())
    assertTrue(segments[1] is InlineSegment.LinkSegment)
    val linkSeg: InlineSegment.LinkSegment = segments[1] as InlineSegment.LinkSegment
    assertEquals("the docs", linkSeg.text)
    assertEquals("https://example.com", linkSeg.url)
    assertTrue(segments[2] is InlineSegment.TextSegment)
    assertEquals(" please", (segments[2] as InlineSegment.TextSegment).annotated.toString())
  }

  @Test
  fun `link at the start omits the leading text segment`() {
    val annotated: AnnotatedString = buildAnnotatedString { append("docs and more") }
    val urlAnnotations: List<UrlAnnotation> = listOf(
      UrlAnnotation(start = 0, end = 4, url = "https://x.test"),
    )
    val segments: List<InlineSegment> = splitIntoInlineSegments(
      annotated = annotated,
      inlineContent = emptyMap(),
      urlAnnotations = urlAnnotations,
    )
    assertEquals(2, segments.size)
    assertTrue(segments[0] is InlineSegment.LinkSegment)
    assertEquals("docs", (segments[0] as InlineSegment.LinkSegment).text)
    assertTrue(segments[1] is InlineSegment.TextSegment)
    assertEquals(" and more", (segments[1] as InlineSegment.TextSegment).annotated.toString())
  }

  @Test
  fun `link at the end omits the trailing text segment`() {
    val annotated: AnnotatedString = buildAnnotatedString { append("check home") }
    val urlAnnotations: List<UrlAnnotation> = listOf(
      UrlAnnotation(start = 6, end = 10, url = "https://x.test"),
    )
    val segments: List<InlineSegment> = splitIntoInlineSegments(
      annotated = annotated,
      inlineContent = emptyMap(),
      urlAnnotations = urlAnnotations,
    )
    assertEquals(2, segments.size)
    assertTrue(segments[0] is InlineSegment.TextSegment)
    assertEquals("check ", (segments[0] as InlineSegment.TextSegment).annotated.toString())
    assertTrue(segments[1] is InlineSegment.LinkSegment)
    assertEquals("home", (segments[1] as InlineSegment.LinkSegment).text)
  }

  @Test
  fun `two links in the same line produce alternating text and link segments`() {
    val annotated: AnnotatedString = buildAnnotatedString { append("see A and B ok") }
    val urlAnnotations: List<UrlAnnotation> = listOf(
      UrlAnnotation(start = 4, end = 5, url = "https://a.test"),
      UrlAnnotation(start = 10, end = 11, url = "https://b.test"),
    )
    val segments: List<InlineSegment> = splitIntoInlineSegments(
      annotated = annotated,
      inlineContent = emptyMap(),
      urlAnnotations = urlAnnotations,
    )
    assertEquals(5, segments.size)
    assertEquals("see ", (segments[0] as InlineSegment.TextSegment).annotated.toString())
    assertEquals("A", (segments[1] as InlineSegment.LinkSegment).text)
    assertEquals(" and ", (segments[2] as InlineSegment.TextSegment).annotated.toString())
    assertEquals("B", (segments[3] as InlineSegment.LinkSegment).text)
    assertEquals(" ok", (segments[4] as InlineSegment.TextSegment).annotated.toString())
  }

  @Test
  fun `unsorted url annotations are sorted before slicing`() {
    val annotated: AnnotatedString = buildAnnotatedString { append("a B c A d") }
    val urlAnnotations: List<UrlAnnotation> = listOf(
      UrlAnnotation(start = 6, end = 7, url = "https://a.test"),
      UrlAnnotation(start = 2, end = 3, url = "https://b.test"),
    )
    val segments: List<InlineSegment> = splitIntoInlineSegments(
      annotated = annotated,
      inlineContent = emptyMap(),
      urlAnnotations = urlAnnotations,
    )
    assertEquals(5, segments.size)
    assertEquals("a ", (segments[0] as InlineSegment.TextSegment).annotated.toString())
    assertEquals("B", (segments[1] as InlineSegment.LinkSegment).text)
    assertEquals(" c ", (segments[2] as InlineSegment.TextSegment).annotated.toString())
    assertEquals("A", (segments[3] as InlineSegment.LinkSegment).text)
    assertEquals(" d", (segments[4] as InlineSegment.TextSegment).annotated.toString())
  }

  @Test
  fun `inline content map is propagated to every text segment`() {
    val annotated: AnnotatedString = buildAnnotatedString { append("foo bar baz") }
    val urlAnnotations: List<UrlAnnotation> = listOf(
      UrlAnnotation(start = 3, end = 6, url = "https://x.test"),
    )
    val placeholder: String = "PLACEHOLDER"
    val inlineContent: Map<String, InlineTextContent> = mapOf(
      placeholder to InlineTextContent(
        placeholder = Placeholder(
          width = 10.sp,
          height = 10.sp,
          placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
      ) {},
    )
    val segments: List<InlineSegment> = splitIntoInlineSegments(
      annotated = annotated,
      inlineContent = inlineContent,
      urlAnnotations = urlAnnotations,
    )
    val textSegments: List<InlineSegment.TextSegment> = segments
      .filterIsInstance<InlineSegment.TextSegment>()
    assertEquals(2, textSegments.size)
    for (seg in textSegments) {
      assertEquals(setOf(placeholder), seg.inlineContent.keys)
    }
  }
}
