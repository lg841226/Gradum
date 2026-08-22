/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumFootnoteTest.kt  2026-08-22 15:14:53 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for inline footnote rendering in two flavors:
 * - Pandoc-style: `^[footnote text]`  (group 2)
 * - CommonMark-style: `[^reference]`  (group 3)
 *
 * The footnote syntax is detected in [renderTextInline] and rendered
 * as a blue marker via [FootnoteMark] (using JewelTheme.typography.small).
 * Empty footnotes `^[]` are skipped.
 */
class GradumFootnoteTest {
  private val testTint: Color = Color(0xFF60A5FA)
  private val testLinkColor: Color = Color(0xFF3B82F6)
  private val testImageAltColor: Color = Color(0xFF888888)
  private val testFontSizeSp: Float = 14f

  private fun render(text: String): InlineMarkdownRenderResult =
    parseInlineMarkdown(
      plainText = text,
      fontSizeSp = testFontSizeSp,
      chipTint = testTint,
      linkColor = testLinkColor,
      imageAltColor = testImageAltColor,
    )

  private fun inlineRender(text: String): InlineMarkdownRender =
    requireNotNull(render(text).render)

  /** Drop PUA placeholders so assertions can read the visible text. */
  private fun visibleText(annotated: AnnotatedString): String {
    val out: StringBuilder = StringBuilder()
    for (element in annotated) {
      val currentChar: Char = element
      if (currentChar != '\uE000' && currentChar != '\uE002') out.append(currentChar)
    }
    return out.toString()
  }

  /** Count PUA placeholders of a given base character. */
  private fun countPlaceholders(annotated: AnnotatedString, base: Char): Int {
    var count = 0
    for (element in annotated) {
      if (element == base) count++
    }
    return count
  }

  @Test
  fun `regex matches single pandoc footnote`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("hello^[world]").toList()
    assertEquals(1, matches.size)
    assertEquals("world", matches[0].groupValues[2])
  }

  @Test
  fun `regex matches multiple pandoc footnotes`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("a^[1] b^[2]").toList()
    assertEquals(2, matches.size)
    assertEquals("1", matches[0].groupValues[2])
    assertEquals("2", matches[1].groupValues[2])
  }

  @Test
  fun `regex matches empty pandoc footnote content`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("text^[] more").toList()
    assertEquals(1, matches.size)
    assertEquals("", matches[0].groupValues[2])
  }

  @Test
  fun `regex does not match standalone caret`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("hello ^world").toList()
    assertEquals(0, matches.size)
  }

  @Test
  fun `regex does not match unmatched bracket`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("hello ^[world").toList()
    assertEquals(0, matches.size)
  }

  @Test
  fun `regex does not match empty input`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("").toList()
    assertEquals(0, matches.size)
  }

  @Test
  fun `regex matches pandoc footnote with CJK text`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("text^[脚注内容]").toList()
    assertEquals(1, matches.size)
    assertEquals("脚注内容", matches[0].groupValues[2])
  }

  @Test
  fun `regex matches pandoc footnote with special characters`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("text^[hello world!@#]").toList()
    assertEquals(1, matches.size)
    assertEquals("hello world!@#", matches[0].groupValues[2])
  }


  @Test
  fun `regex matches commonmark footnote ref`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("text[^1] more").toList()
    assertEquals(1, matches.size)
    assertEquals("1", matches[0].groupValues[3])
  }

  @Test
  fun `regex matches multiple commonmark footnote refs`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("a[^1] b[^2] c[^3]").toList()
    assertEquals(3, matches.size)
    assertEquals("1", matches[0].groupValues[3])
    assertEquals("2", matches[1].groupValues[3])
    assertEquals("3", matches[2].groupValues[3])
  }

  @Test
  fun `regex matches commonmark footnote ref with text content`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("[^some-text]").toList()
    assertEquals(1, matches.size)
    assertEquals("some-text", matches[0].groupValues[3])
  }

  @Test
  fun `regex does not match unmatched commonmark ref`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("hello [^world").toList()
    assertEquals(0, matches.size)
  }

  @Test
  fun `regex matches both flavours in same text`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("a^[pandoc] b[^commonmark]").toList()
    assertEquals(2, matches.size)
    assertEquals("pandoc", matches[0].groupValues[2])
    assertEquals("commonmark", matches[1].groupValues[3])
  }

  @Test
  fun `regex commonmark ref does not consume non-bracket content`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("text[^1] and more").toList()
    assertEquals(1, matches.size)
    val fullMatch = matches[0].value
    assertEquals("[^1]", fullMatch)
  }

  @Test
  fun `regex matches plain bracket number`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("text[1] more").toList()
    assertEquals(1, matches.size)
    assertEquals("1", matches[0].groupValues[4])
  }

  @Test
  fun `regex matches multiple plain bracket numbers`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("[1] [2] [3]").toList()
    assertEquals(3, matches.size)
    assertEquals("1", matches[0].groupValues[4])
    assertEquals("2", matches[1].groupValues[4])
    assertEquals("3", matches[2].groupValues[4])
  }

  @Test
  fun `regex plain bracket number does not match non-digits`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("[abc]").toList()
    assertEquals(0, matches.size)
  }

  @Test
  fun `regex plain bracket number does not consume beyond bracket`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("text[1] and more").toList()
    assertEquals(1, matches.size)
    assertEquals("[1]", matches[0].value)
  }

  @Test
  fun `regex matches all three flavours in same text`() {
    val matches = INLINE_FOOTNOTE_REGEX.findAll("a^[pandoc] b[^commonmark] c[1]").toList()
    assertEquals(3, matches.size)
    assertEquals("pandoc", matches[0].groupValues[2])
    assertEquals("commonmark", matches[1].groupValues[3])
    assertEquals("1", matches[2].groupValues[4])
  }

  @Test
  fun `standalone footnote renders successfully`() {
    val result = render("^[脚注]")
    assertNotNull(result.render)
    assertNull(result.bailReason)
  }

  @Test
  fun `footnote text appears in visible output`() {
    val render = inlineRender("^[note]")
    val text = visibleText(render.annotated)
    // Footnote is rendered as InlineTextContent composable, not as plain text.
    // The PUA placeholder is in the annotated string; the ":note" text is in the composable.
    assertEquals("", text) // only PUA placeholder, which gets stripped
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    assertTrue("footnote placeholder in inlineContent", render.inlineContent.isNotEmpty())
    // Check the annotation tag holds the footnote text
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("note", footnoteAnnotations[0].item)
  }

  @Test
  fun `link with footnote renders both`() {
    val render = inlineRender("[MDN](https://example.com)^[documentation]")
    val text = visibleText(render.annotated)
    // Link text is visible, footnote is a PUA placeholder
    assertTrue("Expected link text, got: $text", text.contains("MDN"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("documentation", footnoteAnnotations[0].item)
  }

  @Test
  fun `text before and after footnote preserved`() {
    val render = inlineRender("hello^[note] world")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'hello', got: $text", text.contains("hello"))
    assertTrue("Expected 'world', got: $text", text.contains("world"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("note", footnoteAnnotations[0].item)
  }

  @Test
  fun `multiple footnotes in same paragraph`() {
    val render = inlineRender("a^[1] b^[2]")
    // allocateFootnote uses repeat(footnoteCounter+1): 1st=\uE002, 2nd=\uE002\uE002 → 3 chars total
    assertTrue("Expected at least 2 footnote placeholders", countPlaceholders(render.annotated, '\uE002') >= 2)
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(2, footnoteAnnotations.size)
    assertEquals("1", footnoteAnnotations[0].item)
    assertEquals("2", footnoteAnnotations[1].item)
  }

  @Test
  fun `empty footnote skipped`() {
    val render = inlineRender("text^[] more")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'text', got: $text", text.contains("text"))
    assertTrue("Expected 'more', got: $text", text.contains("more"))
    assertFalse("Should not contain ':', got: $text", text.contains(":"))
  }

  @Test
  fun `plain text without footnote unchanged`() {
    val render = inlineRender("just plain text")
    val text = visibleText(render.annotated)
    assertEquals("just plain text", text)
  }

  @Test
  fun `footnote placeholder registered in inlineContent`() {
    val render = inlineRender("text^[note]")
    val footnotePlaceholders = countPlaceholders(render.annotated, '\uE002')
    assertEquals("Expected 1 footnote placeholder", 1, footnotePlaceholders)
    assertTrue(
      "Expected footnote placeholder in inlineContent",
      render.inlineContent.values.any { it is androidx.compose.foundation.text.InlineTextContent }
    )
  }

  @Test
  fun `footnote with CJK text renders correctly`() {
    val render = inlineRender("文本^[脚注内容]")
    val text = visibleText(render.annotated)
    assertTrue("Expected '文本', got: $text", text.contains("文本"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("脚注内容", footnoteAnnotations[0].item)
  }

  @Test
  fun `footnote inside bold text`() {
    val render = inlineRender("**bold^[note] text**")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'bold' and 'text', got: $text", text.contains("bold") && text.contains("text"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("note", footnoteAnnotations[0].item)
  }

  // ── CommonMark [^ref] integration tests ──────────────────────────────

  @Test
  fun `commonmark footnote ref renders successfully`() {
    val result = render("text[^1] more")
    assertNotNull(result.render)
    assertNull(result.bailReason)
  }

  @Test
  fun `commonmark footnote ref text appears in visible output`() {
    val render = inlineRender("text[^1] more")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'text', got: $text", text.contains("text"))
    assertTrue("Expected 'more', got: $text", text.contains("more"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("1", footnoteAnnotations[0].item)
  }

  @Test
  fun `commonmark footnote ref inside paragraph`() {
    val render = inlineRender("Markdown footnotes allow you to add notes[^1]. They are useful[^2].")
    val text = visibleText(render.annotated)
    assertTrue("Expected prose text", text.contains("Markdown footnotes"))
    assertTrue("Expected second sentence", text.contains("They are useful"))
    assertTrue("Expected at least 2 footnote placeholders", countPlaceholders(render.annotated, '\uE002') >= 2)
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(2, footnoteAnnotations.size)
    assertEquals("1", footnoteAnnotations[0].item)
    assertEquals("2", footnoteAnnotations[1].item)
  }

  @Test
  fun `commonmark footnote ref with link renders both`() {
    val render = inlineRender("[MDN](https://example.com)[^1]")
    val text = visibleText(render.annotated)
    assertTrue("Expected link text, got: $text", text.contains("MDN"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("1", footnoteAnnotations[0].item)
  }

  @Test
  fun `commonmark footnote ref with CJK text`() {
    val render = inlineRender("文本[^1] 内容")
    val text = visibleText(render.annotated)
    assertTrue("Expected '文本', got: $text", text.contains("文本"))
    assertTrue("Expected '内容', got: $text", text.contains("内容"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("1", footnoteAnnotations[0].item)
  }

  @Test
  fun `commonmark footnote ref inside bold text`() {
    val render = inlineRender("**bold[^1] text**")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'bold' and 'text', got: $text", text.contains("bold") && text.contains("text"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("1", footnoteAnnotations[0].item)
  }

  @Test
  fun `mixed pandoc and commonmark footnotes`() {
    val render = inlineRender("a^[pandoc] b[^commonmark]")
    assertTrue("Expected at least 2 footnote placeholders", countPlaceholders(render.annotated, '\uE002') >= 2)
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(2, footnoteAnnotations.size)
    assertEquals("pandoc", footnoteAnnotations[0].item)
    assertEquals("commonmark", footnoteAnnotations[1].item)
  }

  // ── Plain bracket-number [1] integration tests ────────────────────────

  @Test
  fun `plain bracket number renders successfully`() {
    val result = render("text[1] more")
    assertNotNull(result.render)
    assertNull(result.bailReason)
  }

  @Test
  fun `plain bracket number text appears in visible output`() {
    val render = inlineRender("text[1] more")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'text', got: $text", text.contains("text"))
    assertTrue("Expected 'more', got: $text", text.contains("more"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(1, footnoteAnnotations.size)
    assertEquals("1", footnoteAnnotations[0].item)
  }

  @Test
  fun `multiple plain bracket numbers in paragraph`() {
    val render = inlineRender("References[1] and[2] and[3]")
    assertTrue("Expected at least 3 footnote placeholders", countPlaceholders(render.annotated, '\uE002') >= 3)
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(3, footnoteAnnotations.size)
    assertEquals("1", footnoteAnnotations[0].item)
    assertEquals("2", footnoteAnnotations[1].item)
    assertEquals("3", footnoteAnnotations[2].item)
  }

  @Test
  fun `mixed all three footnote flavours`() {
    val render = inlineRender("a^[pandoc] b[^commonmark] c[1]")
    assertTrue("Expected at least 3 footnote placeholders", countPlaceholders(render.annotated, '\uE002') >= 3)
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(3, footnoteAnnotations.size)
    assertEquals("pandoc", footnoteAnnotations[0].item)
    assertEquals("commonmark", footnoteAnnotations[1].item)
    assertEquals("1", footnoteAnnotations[2].item)
  }

  // ── Edge case tests ───────────────────────────────────────────────────

  @Test
  fun `footnote at start of text`() {
    val render = inlineRender("[1] at start")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'at start'", text.contains("at start"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals("1", footnoteAnnotations[0].item)
  }

  @Test
  fun `footnote at end of text`() {
    val render = inlineRender("at end [1]")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'at end'", text.contains("at end"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
  }

  @Test
  fun `footnote only text`() {
    val render = inlineRender("[1]")
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals("1", footnoteAnnotations[0].item)
  }

  @Test
  fun `adjacent footnotes no space`() {
    val render = inlineRender("[1][2][3]")
    assertTrue("Expected at least 3 footnote placeholders", countPlaceholders(render.annotated, '\uE002') >= 3)
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(3, footnoteAnnotations.size)
  }

  @Test
  fun `footnote inside italic text`() {
    val render = inlineRender("*italic[1] text*")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'italic' and 'text'", text.contains("italic") && text.contains("text"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals("1", footnoteAnnotations[0].item)
  }

  @Test
  fun `footnote with punctuation after`() {
    val render = inlineRender("text[1]. And more[2], here.")
    assertTrue("Expected at least 2 footnote placeholders", countPlaceholders(render.annotated, '\uE002') >= 2)
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(2, footnoteAnnotations.size)
    assertEquals("1", footnoteAnnotations[0].item)
    assertEquals("2", footnoteAnnotations[1].item)
  }

  @Test
  fun `pandoc footnote with long text`() {
    val render = inlineRender("text^[this is a very long footnote text that spans multiple words]")
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals("this is a very long footnote text that spans multiple words", footnoteAnnotations[0].item)
  }

  @Test
  fun `commonmark ref with multi-digit number`() {
    val render = inlineRender("text[^123] more")
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals("123", footnoteAnnotations[0].item)
  }

  @Test
  fun `plain bracket with multi-digit number`() {
    val render = inlineRender("text[99] more")
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals("99", footnoteAnnotations[0].item)
  }

  @Test
  fun `footnote inside strikethrough text`() {
    val render = inlineRender("~~deleted[1] text~~")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'deleted' and 'text'", text.contains("deleted") && text.contains("text"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
  }

  @Test
  fun `empty commonmark ref skipped`() {
    val render = inlineRender("text[^] more")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'text'", text.contains("text"))
    assertTrue("Expected 'more'", text.contains("more"))
    assertEquals(0, countPlaceholders(render.annotated, '\uE002'))
  }

  @Test
  fun `empty pandoc footnote skipped`() {
    val render = inlineRender("text^[] more")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'text'", text.contains("text"))
    assertTrue("Expected 'more'", text.contains("more"))
    assertEquals(0, countPlaceholders(render.annotated, '\uE002'))
  }

  @Test
  fun `footnote inside link text`() {
    val render = inlineRender("[click here[1]](https://example.com)")
    val text = visibleText(render.annotated)
    assertTrue("Expected link text", text.contains("click here"))
    assertEquals(1, countPlaceholders(render.annotated, '\uE002'))
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals("1", footnoteAnnotations[0].item)
  }

  @Test
  fun `multiple footnote types with surrounding prose`() {
    val render = inlineRender("According to studies^[first], research shows[^1], and data supports[2] this claim.")
    val text = visibleText(render.annotated)
    assertTrue("Expected 'According to studies'", text.contains("According to studies"))
    assertTrue("Expected 'research shows'", text.contains("research shows"))
    assertTrue("Expected 'data supports'", text.contains("data supports"))
    assertTrue("Expected 'this claim'", text.contains("this claim"))
    assertTrue("Expected at least 3 footnote placeholders", countPlaceholders(render.annotated, '\uE002') >= 3)
    val footnoteAnnotations = render.annotated.getStringAnnotations(FOOTNOTE_TEXT_TAG, 0, render.annotated.length)
    assertEquals(3, footnoteAnnotations.size)
    assertEquals("first", footnoteAnnotations[0].item)
    assertEquals("1", footnoteAnnotations[1].item)
    assertEquals("2", footnoteAnnotations[2].item)
  }
}
