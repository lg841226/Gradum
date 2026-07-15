/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumTaskListTest.kt  2026-07-15 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import org.commonmark.node.BulletList
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the GFM task-list item detection / strip helpers in
 * [BlockRenderer.kt]. These run as pure-data logic — no Compose UI,
 * no theme — so they live in the same package and call the
 * `internal` helpers directly. End-to-end rendering (Checkbox on the
 * left, inline text on the right) is verified by manual smoke-test
 * in the chat panel.
 */
class GradumTaskListTest {

  // ─── extractTaskListMarker ──────────────────────────────────────────

  @Test
  fun `unchecked marker (space bracket space) is detected`() {
    val paragraph: Paragraph = parseFirstParagraph("- [ ] todo item")
    val marker: TaskListMarker? = extractTaskListMarker(paragraph)
    assertNotNull(marker)
    assertEquals(false, marker!!.checked)
  }

  @Test
  fun `checked marker (lowercase x) is detected`() {
    val paragraph: Paragraph = parseFirstParagraph("- [x] done item")
    val marker: TaskListMarker? = extractTaskListMarker(paragraph)
    assertNotNull(marker)
    assertEquals(true, marker!!.checked)
  }

  @Test
  fun `checked marker (uppercase X) is detected`() {
    val paragraph: Paragraph = parseFirstParagraph("- [X] done item")
    val marker: TaskListMarker? = extractTaskListMarker(paragraph)
    assertNotNull(marker)
    assertEquals(true, marker!!.checked)
  }

  @Test
  fun `empty brackets (no space) are not a task-list marker`() {
    // Per the GFM spec: marker must be `[ ]` / `[x]` / `[X]` only —
    // `[]` (no space inside) is prose, not a task.
    val paragraph: Paragraph = parseFirstParagraph("- [] not a task")
    assertNull(extractTaskListMarker(paragraph))
  }

  @Test
  fun `missing space after the brackets is not a task-list marker`() {
    // The space between the closing bracket and the first content
    // character is required by the GFM spec.
    val paragraph: Paragraph = parseFirstParagraph("- [ ]no space")
    assertNull(extractTaskListMarker(paragraph))
  }

  @Test
  fun `plain list item is not a task-list marker`() {
    val paragraph: Paragraph = parseFirstParagraph("- plain item")
    assertNull(extractTaskListMarker(paragraph))
  }

  @Test
  fun `non-Text first child means no marker`() {
    // Synthesize a Paragraph whose first child is another Paragraph
    // (legal in the AST, unusual in real markdown). The helper
    // requires the first child to be a Text node.
    val paragraph: Paragraph = Paragraph()
    paragraph.appendChild(Paragraph().apply { appendChild(Text("nested")) })
    assertNull(extractTaskListMarker(paragraph))
  }

  // ─── stripTaskListMarker ────────────────────────────────────────────

  @Test
  fun `stripping unchecked marker yields the text after the marker`() {
    val paragraph: Paragraph = parseFirstParagraph("- [ ] todo item")
    val stripped: Paragraph = stripTaskListMarker(paragraph) ?: error("should strip")
    assertEquals("todo item", firstTextLiteral(stripped))
  }

  @Test
  fun `stripping checked marker (lowercase x) yields the text after the marker`() {
    val paragraph: Paragraph = parseFirstParagraph("- [x] done item")
    val stripped: Paragraph = stripTaskListMarker(paragraph) ?: error("should strip")
    assertEquals("done item", firstTextLiteral(stripped))
  }

  @Test
  fun `stripping checked marker (uppercase X) yields the text after the marker`() {
    val paragraph: Paragraph = parseFirstParagraph("- [X] done item")
    val stripped: Paragraph = stripTaskListMarker(paragraph) ?: error("should strip")
    assertEquals("done item", firstTextLiteral(stripped))
  }

  @Test
  fun `stripping a non-task-list paragraph returns null`() {
    val paragraph: Paragraph = parseFirstParagraph("- plain item")
    assertNull(stripTaskListMarker(paragraph))
  }

  @Test
  fun `stripping a task-list paragraph with empty content yields an empty Text`() {
    val paragraph: Paragraph = parseFirstParagraph("- [ ] ")
    val stripped: Paragraph = stripTaskListMarker(paragraph) ?: error("should strip")
    assertEquals("", firstTextLiteral(stripped))
  }

  @Test
  fun `stripping preserves any trailing inline children of the first Paragraph`() {
    // `- [ ] **bold** item` — commonmark parses this as:
    //   Paragraph
    //     Text "[ ] "
    //     StrongEmphasis { Text "bold" }
    //     Text " item"
    // After stripping the leading `[ ] ` from the first Text, the
    // first Text becomes empty AND the StrongEmphasis + trailing Text
    // remain as siblings.
    val paragraph: Paragraph = parseFirstParagraph("- [ ] **bold** item")
    val stripped: Paragraph = stripTaskListMarker(paragraph) ?: error("should strip")
    val firstText: Text = stripped.firstChild as Text
    assertEquals("", firstText.literal)
    val children: List<Node> = collectAllChildren(stripped)
    // 3 children: empty Text, StrongEmphasis, trailing Text
    assertEquals(3, children.size)
  }

  // ─── End-to-end AST shape ───────────────────────────────────────────

  @Test
  fun `task list inside a BulletList has marker only on the first item`() {
    val parsed: Node = Parser.builder().build().parse("- [ ] one\n- [x] two\n- three")
    val list: BulletList = parsed.firstChild as BulletList
    val items: List<ListItem> = collectListItems(list)
    assertEquals(3, items.size)
    val firstMarker: TaskListMarker? = (items[0].firstChild as? Paragraph)?.let(::extractTaskListMarker)
    val secondMarker: TaskListMarker? = (items[1].firstChild as? Paragraph)?.let(::extractTaskListMarker)
    val thirdMarker: TaskListMarker? = (items[2].firstChild as? Paragraph)?.let(::extractTaskListMarker)
    assertEquals(TaskListMarker(checked = false), firstMarker)
    assertEquals(TaskListMarker(checked = true), secondMarker)
    assertNull(thirdMarker)
  }

  // ─── Helpers ────────────────────────────────────────────────────────

  /** Parse a single line of markdown and return the first Paragraph found. */
  private fun parseFirstParagraph(line: String): Paragraph {
    val parser: Parser = Parser.builder().build()
    val bulletList: BulletList = parser.parse(line).firstChild as BulletList
    val listItem: ListItem = bulletList.firstChild as ListItem
    return listItem.firstChild as Paragraph
  }

  private fun firstTextLiteral(paragraph: Paragraph): String =
    (paragraph.firstChild as Text).literal.orEmpty()

  private fun collectAllChildren(parent: Node): List<Node> =
    generateSequence(parent.firstChild) { it.next }.toList()
}
