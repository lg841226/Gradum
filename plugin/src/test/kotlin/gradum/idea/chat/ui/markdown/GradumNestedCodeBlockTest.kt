/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumNestedCodeBlockTest.kt  2026-07-17 23:16:24 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for fenced code blocks nested inside list items (including
 * task lists). The serializer must preserve the code block's fence
 * markers and indent them 4 columns past the list marker so
 * CommonMark reparses them as list-item children, not top-level blocks.
 */
class GradumNestedCodeBlockTest {

  @Test
  fun `task list item with nested code block preserves the fence`() {
    val input: String =
      """
      |- [ ] Configure settings:
      |    ```json
      |    { "theme": "monokai" }
      |    ```
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("task list marker should be preserved: $text", text.contains("- [ ] Configure settings:"))
    assertTrue(
      "fenced code block should be indented 4 columns: $text",
      text.contains("    ```json"),
    )
    assertTrue("code content should be preserved: $text", text.contains("""{ "theme": "monokai" }"""))
    assertTrue("closing fence should be preserved: $text", text.contains("    ```"))
  }

  @Test
  fun `task list item with code block followed by another task`() {
    val input: String =
      """
      |- [ ] First task with code:
      |    ```
      |    code here
      |    ```
      |- [ ] Second task
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("first task should be present: $text", text.contains("- [ ] First task with code:"))
    assertTrue("code block should be indented: $text", text.contains("    ```"))
    assertTrue("second task should be present: $text", text.contains("- [ ] Second task"))
  }


  @Test
  fun `bullet list item with nested JSON code block`() {
    val input: String =
      """
      |- Configure syntax highlighting:
      |    ```json
      |    {
      |      "theme": "monokai",
      |      "languages": ["javascript", "python"]
      |    }
      |    ```
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("list item should be preserved: $text", text.contains("- Configure syntax highlighting:"))
    assertTrue("fenced block should be indented 4 columns: $text", text.contains("    ```json"))
    assertTrue("JSON content should be preserved: $text", text.contains(""""theme": "monokai""""))
    assertTrue("nested array should be preserved: $text", text.contains(""""languages": ["javascript", "python"]"""))
  }

  @Test
  fun `bullet list item with code block and trailing text item`() {
    val input: String =
      """
      |- Task with code block:
      |    ```
      |    code here
      |    ```
      |- Test code block rendering
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("first task should be present: $text", text.contains("- Task with code block:"))
    assertTrue("code block should be indented: $text", text.contains("    ```"))
    assertTrue(
      "second task should NOT be inside the code block: $text",
      text.contains("- Test code block rendering"),
    )
    val codeEnd: Int = text.indexOf("    ```", text.indexOf("    ```") + 1)
    val secondItem: Int = text.indexOf("- Test code block rendering")
    assertTrue(
      "second task must appear after closing fence: codeEnd=$codeEnd, secondItem=$secondItem",
      secondItem > codeEnd,
    )
  }


  @Test
  fun `ordered list item with nested code block preserves indent`() {
    val input: String =
      """
      |1. Setup environment:
      |    ```
      |    npm install
      |    ```
      |2. Run tests
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("ordered marker should be preserved: $text", text.contains("1. Setup environment:"))
    assertTrue("code block should be indented 4 columns: $text", text.contains("    ```"))
    assertTrue("second item should be present: $text", text.contains("2. Run tests"))
  }

  @Test
  fun `deeply nested code block inside two-level list`() {
    val input: String =
      """
      |- Parent task
      |    - [ ] Child task:
      |        ```
      |        nested code
      |        ```
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("parent task should be present: $text", text.contains("- Parent task"))
    assertTrue("child task should be present: $text", text.contains("- [ ] Child task:"))
    assertTrue(
      "deeply nested code block should be indented 8 columns: $text",
      text.contains("        ```"),
    )
    assertTrue("code content should be preserved: $text", text.contains("        nested code"))
  }

  // ── Code block + blockquote in same list item ───────────────────────

  @Test
  fun `list item with both code block and blockquote`() {
    val input: String =
      """
      |- [ ] Task with code and quote:
      |    ```
      |    code here
      |    ```
      |    > quoted text
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("task marker should be preserved: $text", text.contains("- [ ] Task with code and quote:"))
    assertTrue("code block should be present: $text", text.contains("    ```"))
    assertTrue("blockquote should be present: $text", text.contains("    > quoted text"))
  }

  @Test
  fun `list with multiple code blocks in different items`() {
    val input: String =
      """
      |- First task:
      |    ```
      |    code one
      |    ```
      |- Second task:
      |    ```
      |    code two
      |    ```
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("first task should be present: $text", text.contains("- First task:"))
    assertTrue("second task should be present: $text", text.contains("- Second task:"))
    assertTrue("first code block should be present: $text", text.contains("    code one"))
    assertTrue("second code block should be present: $text", text.contains("    code two"))
  }

  @Test
  fun `code block with language tag preserves the info string`() {
    val input: String =
      """
      |- [ ] Install dependencies:
      |    ```bash
      |    npm install
      |    ```
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("language tag should be preserved: $text", text.contains("    ```bash"))
  }


  @Test
  fun `empty code block inside list item is preserved`() {
    val input: String =
      """
      |- [ ] Task with empty code:
      |    ```
      |    ```
      """.trimMargin()
    val segments: List<MarkdownSegment> = splitPlainAtBlocks(input)
    assertEquals(
      1,
      segments.size
    )
    val list: MarkdownSegment.NonProseBlock = segments[0] as MarkdownSegment.NonProseBlock
    val text: String = list.text
    assertTrue("task should be present: $text", text.contains("- [ ] Task with empty code:"))
    assertTrue("opening fence should be present: $text", text.contains("    ```"))
  }
}
