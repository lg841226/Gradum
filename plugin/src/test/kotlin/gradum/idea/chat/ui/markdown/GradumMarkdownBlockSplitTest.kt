/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms, see the MIT LICENSE file.
 *
 * GradumMarkdownBlockSplitTest.kt  2026-07-14 Changed by gwy
 *
 * Tests for [splitPlainAtBlocks] (the block-boundary splitter that
 * turned an 804-char message into 20 chars of output in the
 * 2026-07-14 bug). The function splits a Plain segment on top-level
 * CommonMark block boundaries so the inline chip parser only ever
 * sees pure-prose segments, and every other top-level block is
 * routed to `Markdown(...)` via [MarkdownSegment.NonProseBlock].
 */

package gradum.idea.chat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradumMarkdownBlockSplitTest {

  @Test
  fun `single paragraph stays as a single Plain segment`() {
    val segments: List<MarkdownSegment> = splitPlainAtBlocks("just some prose with `code`")
    assertEquals(1, segments.size)
    val plain: MarkdownSegment.Plain = segments[0] as MarkdownSegment.Plain
    assertEquals("just some prose with `code`", plain.text)
  }

  @Test
  fun `paragraph followed by heading becomes Plain then NonProseBlock`() {
    // This is the bug-2026-07-14 case: a 20-char paragraph + a
    // heading used to render as 20 chars (the heading was dropped).
    // The new splitter must emit two segments so both render.
    val segments: List<MarkdownSegment> = splitPlainAtBlocks("some prose\n\n## heading after")
    assertEquals(2, segments.size)
    val plain: MarkdownSegment.Plain = segments[0] as MarkdownSegment.Plain
    val heading: MarkdownSegment.NonProseBlock = segments[1] as MarkdownSegment.NonProseBlock
    assertEquals("some prose", plain.text)
    assertEquals("## heading after", heading.text)
  }

  @Test
  fun `paragraph followed by fenced code block — the common chat case`() {
    // Most common in chat: an assistant explains something with
    // inline code in a paragraph, then provides a full code sample
    // in a fenced block. Both must render.
    val segments: List<MarkdownSegment> =
      splitPlainAtBlocks("here is `foo()`:\n\n```\nbar\n```\n")
    assertEquals(2, segments.size)
    val plain: MarkdownSegment.Plain = segments[0] as MarkdownSegment.Plain
    val code: MarkdownSegment.NonProseBlock = segments[1] as MarkdownSegment.NonProseBlock
    assertEquals("here is `foo()`:", plain.text)
    assertEquals("```\nbar\n```", code.text)
  }

  @Test
  fun `bullet list becomes a single NonProseBlock`() {
    val segments: List<MarkdownSegment> = splitPlainAtBlocks("- item 1\n- item 2\n- item 3")
    assertEquals(1, segments.size)
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    assertEquals("- item 1\n- item 2\n- item 3", list.text)
  }

  @Test
  fun `blockquote becomes a single NonProseBlock`() {
    val segments: List<MarkdownSegment> = splitPlainAtBlocks("> quoted line\n> more quote")
    assertEquals(1, segments.size)
    val quote: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    assertEquals("> quoted line\n> more quote", quote.text)
  }

  @Test
  fun `thematic break becomes a single NonProseBlock`() {
    val segments: List<MarkdownSegment> = splitPlainAtBlocks("---")
    assertEquals(1, segments.size)
    val horizontalRule: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    assertEquals("---", horizontalRule.text)
  }

  @Test
  fun `multiple blocks in any order split correctly`() {
    // The mixed content that triggered the bug — paragraph + list
    // + heading + thematic break + paragraph.
    val input: String =
      """
      |paragraph one with `code`
      |
      |- list item 1
      |- list item 2
      |
      |## heading
      |
      |---
      |
      |paragraph two
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(5, segments.size)
    assertTrue("segment 0 should be Plain", segments[0] is MarkdownSegment.Plain)
    assertTrue("segment 1 should be NonProseBlock (list)", segments[1] is MarkdownSegment.NonProseBlock)
    assertTrue("segment 2 should be NonProseBlock (heading)", segments[2] is MarkdownSegment.NonProseBlock)
    assertTrue("segment 3 should be NonProseBlock (thematic break)", segments[3] is MarkdownSegment.NonProseBlock)
    assertTrue("segment 4 should be Plain", segments[4] is MarkdownSegment.Plain)
    assertEquals("paragraph one with `code`", (segments[0] as MarkdownSegment.Plain).text)
    assertEquals("paragraph two", (segments[4] as MarkdownSegment.Plain).text)
  }

  @Test
  fun `LinkReferenceDefinition is dropped — reference link still resolves in paragraph`() {
    // The reference link `[text][ref]` resolves at parse time — the
    // `Link` node inside the `Paragraph` carries the resolved URL,
    // and the `LinkReferenceDefinition` block is dropped (not
    // visible body text).
    val segments: List<MarkdownSegment> =
      splitPlainAtBlocks("see [text][ref]\n\n[ref]: https://example.com")
    // Only the paragraph remains.
    assertEquals(1, segments.size)
    val plain: MarkdownSegment.Plain = segments[0] as MarkdownSegment.Plain
    // The reference link resolves — the re-serialized text contains
    // the inline link form, not the reference form.
    assertTrue(
      "expected inline link in serialized text: ${plain.text}",
      plain.text.contains("[text](https://example.com)"),
    )
  }

  @Test
  fun `blank input returns an empty list`() {
    assertEquals(0, splitPlainAtBlocks("").size)
    assertEquals(0, splitPlainAtBlocks("   \n  \t  ").size)
  }

  @Test
  fun `round-trip serializer preserves inline formatting`() {
    // A paragraph with bold + italic + inline code + link is
    // re-serialized back to valid markdown. The exact whitespace
    // may differ from the original (commonmark normalizes), but
    // the structure must survive.
    val input: String = "a **bold** *italic* `code` [link](https://x.test) end"
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(1, segments.size)
    val plain: MarkdownSegment.Plain = segments[0] as MarkdownSegment.Plain
    assertTrue(plain.text.contains("**bold**"))
    assertTrue(plain.text.contains("*italic*"))
    assertTrue(plain.text.contains("`code`"))
    assertTrue(plain.text.contains("[link](https://x.test)"))
  }

  @Test
  fun `nested ordered list with bullet child is round-tripped as a nested list`() {
    // Bug: 2026-07-14 — nested list with mixed children used to
    // serialize to "1. 有序1- 无序嵌套\n2. 有序2- 有序嵌套" (all on
    // one line, nested list unindented, Markdown(...) re-parses
    // it as a flat list of two items, losing the nesting). The
    // serializer must now (a) put a newline between the paragraph
    // and the nested list inside each list item, and (b) indent
    // the nested list by at least 4 columns so Markdown sees it
    // as a child of the outer list item.
    val input: String =
      """
      |1. 有序1
      |    - 无序嵌套
      |2. 有序2
      |    1. 有序嵌套
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(1, segments.size)
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    // Each outer list item's paragraph must be on the same line
    // as its "N. " marker.
    assertTrue(
      "outer list item 1 paragraph should follow the marker on the same line: $text",
      text.contains("1. 有序1\n"),
    )
    assertTrue(
      "outer list item 2 paragraph should follow the marker on the same line: $text",
      text.contains("2. 有序2\n"),
    )
    // The nested bullet list must be indented at least 4 columns
    // so Markdown recognizes it as a continuation of the outer
    // list item (and not a new top-level list).
    assertTrue(
      "nested bullet list should be indented 4 spaces: $text",
      text.contains("\n    - 无序嵌套"),
    )
    // The nested ordered list must also be indented 4 columns.
    assertTrue(
      "nested ordered list should be indented 4 spaces: $text",
      text.contains("\n    1. 有序嵌套"),
    )
  }

  @Test
  fun `nested list with paragraph and code block child preserves the code block`() {
    // A list item containing a paragraph + a fenced code block
    // must serialize with the code block indented 4 columns past
    // the marker (CommonMark rule for continuation blocks inside
    // list items). 2-space indent is NOT enough — CommonMark would
    // re-parse that as a top-level code block, not a list child.
    val input: String =
      """
      |- list item
      |
      |    ```
      |    inner code
      |    ```
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(1, segments.size)
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("list item should retain the paragraph: $text", text.contains("- list item"))
    assertTrue(
      "fenced code block should be indented 4 columns past the marker: $text",
      text.contains("\n    ```"),
    )
  }

  @Test
  fun `paragraph with strikethrough round-trips tildens`() {
    // The block splitter's serializer must emit `~~` for a
    // Strikethrough inline node so the inline parser can re-parse
    // the serialized source and re-detect the strike. Without the
    // serializer branch, the tildens would be silently dropped and
    // the inline parser would see plain text.
    val segments: List<MarkdownSegment> = splitPlainAtBlocks("a ~~struck~~ word")
    assertEquals(1, segments.size)
    val plain: MarkdownSegment.Plain = segments[0] as MarkdownSegment.Plain
    assertEquals("a ~~struck~~ word", plain.text)
  }
}
